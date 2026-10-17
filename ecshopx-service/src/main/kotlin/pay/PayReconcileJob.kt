package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.pay.TradeMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 支付补单作业（docs/05 §M1-2 的"查单 + 关单"）。
 *
 * 解决的是真实渠道特有的两个**单边状态**：
 *  - **掉单**：钱在渠道侧扣了，回调却因为网络/我方发布窗口没进来 → 订单永远停在待付款；
 *  - **关单不彻底**：本地超时关单/取消把 `trade_state` 收掉了，但渠道侧那张预支付单还活着，
 *    超时窗口内用户扫码照样能付出去，钱收了订单却已关。
 * M0-7 的 mock 渠道不存在这两个问题（回调与关单都由我们自己 curl 出来），真渠道必须有不依赖回调的收敛路径。
 *
 * 四条口径：
 *  1. **补单复用回调的同一段代码**：查到 `SUCCESS` 后调 `PayService.notify(...)`，
 *     于是 CAS 幂等、金额核对、积分入账、状态迁移全部与回调一致——两套结清逻辑迟早会漂，这里只有一套；
 *  2. **本地渠道不参与**：只扫 `pay_type in (wxpay, alipay)`（可配），积分/余额/线下没有渠道可查；
 *  3. **宽限期**：两条扫描都带 `<= now - grace`，给正常回调留到达时间，避免和回调抢同一行做无用功；
 *  4. **关单靠 `channel_closed` 收敛**：本地 `CLOSED` 只代表我方状态，向渠道发过 close（成功，
 *     或渠道答"已支付/不存在"）才置 1 停止重试。答"已支付"的那笔不会丢钱——
 *     它下一轮以 `SUCCESS` 落进第一条腿，订单侧按回调口径处理。
 */
@Component
class PayReconcileJob(
    private val trades: TradeMapper,
    private val registry: PayChannelRegistry,
    private val pay: PayService,
    @Value("\${ecx.schedule.reconcile-enabled:true}") private val enabled: Boolean,
    @Value("\${ecx.schedule.reconcile-pay-types:wxpay,alipay}") payTypesCsv: String,
    @Value("\${ecx.schedule.reconcile-batch:100}") private val batch: Int,
    @Value("\${ecx.schedule.reconcile-grace-seconds:120}") private val graceSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val payTypes = payTypesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 默认 5 分钟一轮；initialDelay 60s 让启动期的建表/seed 先完成（与 OrderTimeoutJob 同一手法） */
    @Scheduled(initialDelayString = "\${ecx.schedule.reconcile-initial-delay-ms:60000}",
        fixedDelayString = "\${ecx.schedule.reconcile-ms:300000}")
    fun scan() {
        if (!enabled || payTypes.isEmpty()) return
        val r = reconcileNow(TimeSec.now())
        val settled = r["settled"] ?: 0
        val closed = r["closed"] ?: 0
        if (settled > 0 || closed > 0) log.info("支付补单：结清 $settled 笔，渠道关单 $closed 笔")
    }

    /** 一轮扫描（测试直接调这个方法，不等定时器） */
    fun reconcileNow(now: Long): Map<String, Int> {
        var settled = 0
        var closed = 0
        trades.listStaleNotpay(now - graceSeconds, payTypes, batch).forEach { t ->
            val channel = runCatching { registry.get(t.payType) }.getOrNull() ?: return@forEach
            val q = runCatching { channel.queryTrade(t.tradeId) }.getOrNull() ?: return@forEach
            if (q?.get("trade_state") == "SUCCESS") {
                val res = pay.notify(
                    mapOf(
                        "out_trade_no" to t.tradeId,
                        "total_fee" to (q["total_fee"]?.toString() ?: ""),
                        "trade_state" to "SUCCESS",
                        "transaction_id" to (q["transaction_id"]?.toString() ?: "RECON$t.tradeId"),
                        "channel" to "${t.payType}-reconcile",
                    ),
                )
                // 金额不符/订单已关都会得到 success=false：不重试到死，交给后台对账页看
                if (res.success) settled++
            }
        }
        trades.listPendingChannelClose(now - graceSeconds, payTypes, batch).forEach { t ->
            val channel = runCatching { registry.get(t.payType) }.getOrNull() ?: return@forEach
            // 渠道接口挂了/超时 → false，本轮不置位，下轮重试；一直失败也只是多打几次 close，钱不会因此错位
            if (runCatching { channel.closeTrade(t.tradeId) }.getOrDefault(false) && trades.markChannelClosed(t.tradeId, now) > 0) {
                closed++
            }
        }
        return mapOf("settled" to settled, "closed" to closed)
    }
}
