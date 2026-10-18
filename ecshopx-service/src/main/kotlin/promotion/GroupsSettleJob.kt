package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.domain.TimeSec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 拼团结算（docs/04 §14.6 / D26 / D30）——**到期判定与机器人凑团两条腿**。
 *
 * 为什么必须有这个 Job：成团是"凑够人"这件事的结果，而凑人靠别人下单付款，
 * 只有时间能给出终局。两条腿的顺序也重要 ——
 *  1. [GroupsService.settleDue]：`end_time` 已过的团，够人就判成团（兜住结清回调丢了的情况），
 *     不够人判失败并走 M1-1 退款链全额退 + 回补库存与名额；
 *  2. [GroupsService.fillRobots]：还在窗内、但离结束只剩 `robot-horizon`（默认 600s）且活动开了 `robot`
 *     的团，补虚拟成员直接成团 —— 先判到期再补机器人，否则一个已经失败的团会被机器人"救活"。
 *
 * 全部动作都是条件更新（CAS），所以多实例同时扫也只有一份副作用落地（与 M0-6 的关单任务同一口径）。
 * 测试直接调 [runNow]，不等调度。
 */
@Component
class GroupsSettleJob(
    private val groups: GroupsService,
    @Value("\${ecx.schedule.groups-robot-horizon:600}") private val robotHorizon: Long,
    @Value("\${ecx.schedule.groups-settle-batch:50}") private val batch: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelayString = "\${ecx.schedule.groups-settle-initial-delay-ms:60000}",
        fixedDelayString = "\${ecx.schedule.groups-settle-ms:60000}")
    fun scan() {
        val r = runNow(TimeSec.now())
        if (r > 0) log.info("拼团结算处理 {} 个团", r)
    }

    /** @return 本次真正发生状态变化（成团/失败/补机器人）的团数 */
    fun runNow(now: Long): Int = groups.settleDue(now, batch) + groups.fillRobots(now, robotHorizon, batch)

    /** 机器人补位的提前量（秒）：运营口径要能看见，不然"为什么突然成团"没法解释 */
    fun robotHorizon(): Long = robotHorizon

    fun batchSize(): Int = batch
}
