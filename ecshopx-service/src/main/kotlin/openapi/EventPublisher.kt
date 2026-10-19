package com.ecshopx.kotlin.service.openapi

import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryDO
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryMapper
import com.ecshopx.kotlin.dao.webhook.WebhookSubscriptionMapper
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * 事件发布 seam（M2-1 §25.4）。
 *
 * **outbox 落在事务内、投递落在事务外** —— 这一句是这个类存在的理由，也是它唯一的难点：
 * - 如果像源系统 `MarketingCenter\Request::call()` 那样在业务代码里同步发 HTTP
 *   （`timeout=>2`、`catch{ return [] }`），那么进程在提交后、发出前被杀，事件就永远丢了；
 * - 如果用 `Db.afterCommit` 里直接发，同样会丢 —— 提交之后已经没有事务可以回滚，但也没有任何东西留下"该发没发"。
 * outbox 的意义就是让"事件"和"业务变更"同生死：同一个事务里插一行，回滚就一起没有，提交就一定有人投。
 *
 * `afterCommit` 里那一次唤醒只是**加速**（对接方不必等 30 秒的作业轮次）；
 * 真正的兜底是 `WebhookDeliveryJob` —— 唤醒失败（进程正好在这之间被杀）不丢事件，只是慢一点。
 *
 * 挂点只在**状态迁移命中**的那一支调用（§25.6 门槛③：`markPaid` 的 CAS 未命中时不生成第二条事件），
 * 这件事由调用方保证：本类不做任何幂等判断，因为"这一次是不是命中那一支"只有调用方知道。
 */
@Component
class EventPublisher(
    private val subs: WebhookSubscriptionMapper,
    private val deliveries: WebhookDeliveryMapper,
    private val seq: SeqGenerator,
    private val db: Db,
    private val mapper: ObjectMapper,
    private val sender: com.ecshopx.kotlin.service.webhook.WebhookDeliveryService,
) {

    /**
     * 发一个事件。没有订阅（或订阅者已停用）时**一条 SQL 都不写**，直接返回空列表 ——
     * 这是"订阅 `status=0` 时事件不投"那条判据的实现处：连 `pending` 都不生成，
     * 不是生成之后再靠投递侧跳过（后者会让 outbox 堆满没人要的行，运维看"待投数"永远看不出真事）。
     *
     * @return 生成的 outbox 行 id（调用方一般不关心；测试与 WH-A9 要用它立刻催投）
     */
    fun publish(companyId: Long, topic: String, data: Map<String, Any?>): List<Long> {
        val targets = subs.listTargetsByTopic(companyId, topic)
        if (targets.isEmpty()) return emptyList()
        val ids = ArrayList<Long>(targets.size)
        targets.forEach { t ->
            // 址为空的订阅**一行都不落**：它投不出去，落进 outbox 只是多一条必然 `fail` 的记录，
            // 而那一行还会占着"待投数"这个指标（列上 NOT NULL，真出现空值说明这行是被绕过接口写坏的）
            val targetUrl = t.url?.takeIf { it.isNotBlank() } ?: return@forEach
            // 一个"事件 × 订阅"一行、各发各的号（§25.4：同一事件的多次重试与手动重推复用同一个 msg_id，
            // 但**不同订阅各自独立**——两家接收端的判重互不相干）
            val row = newDeliveryRow(t.companyId, t.developerId, t.subscriptionId, topic, targetUrl, data)
            deliveries.insert(row)
            ids += row.id
        }
        // 提交后催一次。注册点在这里（事务内）而不是调用方：调用方有 9 个挂点，
        // 少写一处就是一个"事件生成了但三十秒才动"的不一致，而这种漏只有在生产上才看得见
        db.afterCommit { sender.wakeNow() }
        return ids
    }

    /**
     * 落一行**不经订阅**的 outbox（WH-A9 的测试事件：目标地址是入参给的，不等任何订阅存在）。
     *
     * 它故意**不注册 `afterCommit` 唤醒**：这一条的投递由调用方在同一轮里显式发起（要立刻把结果还给点按钮的人），
     * 唤醒反而会让作业和这次调用抢同一个 CAS 位。信封形状、发号、`sign` 缺失这些事都跟 `publish` 共用
     * [newDeliveryRow] —— 测试事件与正式事件必须是同一形状，否则"测试通了"证明不了正式的那条能通。
     */
    fun publishDirect(
        companyId: Long,
        developerId: Long,
        subscriptionId: Long,
        topic: String,
        url: String,
        data: Map<String, Any?>,
    ): Long {
        val row = newDeliveryRow(companyId, developerId, subscriptionId, topic, url, data)
        deliveries.insert(row)
        return row.id
    }

    private fun newDeliveryRow(
        companyId: Long,
        developerId: Long,
        subscriptionId: Long,
        topic: String,
        url: String,
        data: Map<String, Any?>,
    ): WebhookDeliveryDO {
        val now = TimeSec.now()
        val msgId = seq.nextBn(SeqName.WEBHOOK_MSG)
        val envelope = linkedMapOf<String, Any?>(
            "msg_id" to msgId,
            "topic" to topic,
            "company_id" to companyId,
            "developer_id" to developerId,
            "occurred_at" to now,
            "data" to data,
        )
        return WebhookDeliveryDO().apply {
            this.companyId = companyId
            this.developerId = developerId
            this.subscriptionId = subscriptionId
            this.msgId = msgId
            this.topic = topic
            // 址在这一刻**抄进 outbox**：WH-A3 改订阅地址不追改历史记录，
            // 已经在路上的那一条仍打到旧 url（对端"我收到了"的证据不能随着配置改动而改变含义）
            this.url = url
            // 存的是**不含 `sign` 的信封**：签名要用发送时刻的时间戳现算，
            // 落库时算好的签到了重试时已经过期，且换密钥后必然对不上
            this.payload = mapper.writeValueAsString(envelope)
            status = STATUS_PENDING
            attempts = 0
            // 0 = 立即到期：唤醒与作业都按 `next_try_at <= now` 取，不需要特判"新建的那条"
            nextTryAt = 0
        }
    }

    private companion object {
        const val STATUS_PENDING = "pending"
    }
}
