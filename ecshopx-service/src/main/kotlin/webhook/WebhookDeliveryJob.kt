package com.ecshopx.kotlin.service.webhook

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Webhook 投递兜底作业（M2-1 §25.4 的"事务外"那半条腿）。
 *
 * 它只有一件事要做：**把到期没投的投掉**。即时性由 `EventPublisher` 的 `afterCommit` 唤醒负责，
 * 可靠性由这一条负责 —— 两者不能互换：
 * - 只有唤醒：进程在提交后、发出前被杀，那一行就永远躺在 `pending` 里，没人再来捞；
 * - 只有作业：对接方的接收端等的是"下一个 30 秒轮次"，一次下单要等半分钟才收到推送，
 *   这在联调时会被当成"推送没做"。
 *
 * 没有 ShedLock（与 [com.ecshopx.kotlin.service.order.OrderTimeoutJob] 同一现状：M0/M1/M2 都是单实例）。
 * 多实例在这里**不需要**锁：抢占靠 `WebhookDeliveryMapper.claim` 那句
 * `WHERE id = ? AND status = 'pending'`，两个实例扫到同一行只有一个受影响行数为 1，
 * 语义上就是"至多一次投递"。锁在这里能省的只是重复的扫描 SQL，省不掉重复的投递。
 *
 * 测试里不靠它：`ecx.schedule.webhook-deliver-*` 的缺省值是 30s/60s，
 * 定时作业在测试上下文里的真实行为就是"永远不跑"，断它等于不断（§25.5 末条）——
 * e2e 直接调 [WebhookDeliveryService.deliverDue]，这里只做**参数装配**的验证。
 */
@Component
class WebhookDeliveryJob(
    private val sender: WebhookDeliveryService,

    /** 一轮取多少条到期记录（配置写大了也不会一次投完积压，`WebhookDeliveryService` 内有硬上限） */
    @Value("\${ecx.webhook.batch-limit:20}") private val batchLimit: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${ecx.schedule.webhook-deliver-initial-delay-ms:60000}",
        fixedDelayString = "\${ecx.schedule.webhook-deliver-ms:30000}",
    )
    fun scan() {
        // runCatching：作业里抛出去的东西会终结这条 scheduling 线程上的后续触发，
        // 而投递失败是**常态**（对端挂了就等下一次退避），不该让兜底本身先倒下
        val sent = runCatching { sender.deliverDue(batchLimit) }
            .onFailure { log.error("webhook 投递作业异常", it) }
            .getOrDefault(0)
        if (sent > 0) log.info("webhook 投递 {} 条", sent)
    }
}
