package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.promotion.SeckillGoodsMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 秒杀池子对账（docs/04 §14.6 / D30）。
 *
 * 释放超时未付的 `hold_store` 这件事**主要由 `OrderFlowService.runClose` 做**（关单必放占用）。
 * 本 Job 只兜"关单与释放之间断过"的残余：进程被杀、人工改库、老数据 ——
 * 判据是「`hold_store` 大于该场次该商品所有未付款秒杀单的件数合计」，差额就是悬空占用。
 *
 * 与订单超时关单同一扫描节拍（60s），但**不重复扣**：`trimHold` 带 `hold_store >= orphan` 守卫，
 * 差额按 SQL 现算，并发下单刚抢走的量不会被回收。
 */
@Component
class SeckillSettleJob(
    private val seckillGoods: SeckillGoodsMapper,
    @Value("\${ecx.schedule.seckill-orphan-batch:200}") private val batch: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelayString = "\${ecx.schedule.seckill-settle-initial-delay-ms:50000}",
        fixedDelayString = "\${ecx.schedule.seckill-settle-ms:60000}")
    fun scan() {
        val trimmed = runNow()
        if (trimmed > 0) log.info("回收秒杀悬空占用 {} 行", trimmed)
    }

    /** 单测与运维手动收口都用这一入口（D30：Job 各暴露 runNow） */
    fun runNow(): Int = seckillGoods.listOrphanHold().take(batch).count { seckillGoods.trimHold(it.id, it.orphan) > 0 }

    /** 还剩多少悬空占用（巡检看，不参与判定） */
    fun pending(): Int = seckillGoods.listOrphanHold().size

    fun batchSize(): Int = batch
}
