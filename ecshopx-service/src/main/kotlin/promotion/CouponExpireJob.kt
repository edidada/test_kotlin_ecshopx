package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.domain.TimeSec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 券过期回收（docs/04 §14.6 / D30）。
 *
 * 只在**已领的实例**上改状态：`UNUSED` 且 `expire_time<=now` → `EXPIRED`，一条 UPDATE 全扫。
 * 不做"过期就退回库存"——源系统与本项目一致：`send_num` 是发放事实，撤不回。
 * 算价侧本来就用 `expire_time` 判可用，这个 Job 只是把状态字段对齐成人能看懂的样子，
 * 所以它跑不跑都不影响金额正确性（测试里靠 `runNow` 主动触发，不等调度）。
 */
@Component
class CouponExpireJob(
    private val coupons: CouponService,
    @Value("\${ecx.schedule.coupon-expire-batch:500}") private val batch: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelayString = "\${ecx.schedule.coupon-expire-initial-delay-ms:45000}",
        fixedDelayString = "\${ecx.schedule.coupon-expire-ms:300000}")
    fun scan() = runNow(TimeSec.now())

    /** 单测与运维手动收口都用这一入口（D30：三个 Job 各暴露 runNow(now)） */
    fun runNow(now: Long): Int {
        val n = coupons.expireBatch(now)
        if (n > 0) log.info("过期回收优惠券 {} 张", n)
        return n
    }

    /** 给后台/巡检看还剩多少（不参与判定） */
    fun pending(now: Long): Long = coupons.expirableCount(now)

    /** 一次最多扫多少行：批量是 SQL 层的，这里只是把配置项暴露出来 */
    fun batchSize(): Int = batch
}
