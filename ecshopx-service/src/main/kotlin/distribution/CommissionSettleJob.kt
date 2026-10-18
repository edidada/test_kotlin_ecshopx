package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.domain.TimeSec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 佣金到期结算（docs/04 §18.5 / DL-A3 的自动那条腿）。
 *
 * 入账时按 `sys_config.commission.close_delay`（默认 7 天）写了一个 `plan_close_time`，
 * 把"在途佣金"变成"可提现余额"这个动作必须由时间来完成 —— 没有这个 Job，
 * delay>0 的租户里佣金永远停在未结算，`balance` 恒为 0，店铺提不出钱；
 * 反过来，光有定时又不够：运营会想在活动收尾时**提前结一刀**，那才是 DL-A3 显式结算。
 * 两个入口共用 DAO 里同一条判据（`is_close=FALSE AND plan_close_time<=now`），
 * 于是不管谁先跑，账只结算一次（条件 UPDATE，第二次 0 行）。
 *
 * 结算**不动金额**：`rebate` 一个不改，只把 `is_close` 置位。
 * 退款冲销走的是另一条腿（未结算直接减、已结算插负向行，见 [CommissionService.reverse]），
 * 所以"结算完又来了退款"也有去处，不会因为已结算就冲不掉。
 *
 * 测试直接调 [runNow]，不等调度（与 M1-3 三个 Job 同一口径，D30）。
 */
@Component
class CommissionSettleJob(
    private val commissions: CommissionService,
    @Value("\${ecx.schedule.commission-settle-batch:500}") private val batch: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelayString = "\${ecx.schedule.commission-settle-initial-delay-ms:50000}",
        fixedDelayString = "\${ecx.schedule.commission-settle-ms:300000}")
    fun scan() {
        val n = runNow(TimeSec.now())
        if (n > 0) log.info("佣金到期结算 {} 行", n)
    }

    /** @return 本次置为已结算的行数 */
    fun runNow(now: Long): Int = commissions.settleDueAll(now)

    /** 还剩多少到期未结（巡检用，不参与判定） */
    fun pending(now: Long): Long = commissions.dueCount(now)

    /** 一次最多扫多少行：批量是 SQL 层的，这里只是把配置项暴露出来 */
    fun batchSize(): Int = batch
}
