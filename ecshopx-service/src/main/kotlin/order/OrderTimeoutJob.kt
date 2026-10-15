package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.domain.TimeSec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 超时未付款自动关单（docs/05 M0-6「@Scheduled + ShedLock-JDBC」）。
 *
 * 实测偏差并记录：本仓库没有引 ShedLock（M0 单实例 + H2 单文件），
 * 用「扫描 → 单单 CAS（WHERE order_status='NOTPAY' AND pay_status='NOTPAY'）」代替分布式锁——
 * 多实例同时扫到同一单时只有一个能拿到受影响行数 1，库存不会重复回补，语义等价于幂等锁。
 * M1 引入多实例部署时再补 ShedLock-JDBC（表已可由 schema 建）。
 */
@Component
class OrderTimeoutJob(
    private val query: OrderQueryService,
    private val flow: OrderFlowService,
    @Value("\${ecx.schedule.order-timeout-batch:200}") private val batch: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 默认每 60s 扫一批；initialDelay 30s 让启动期的建表/seed 先完成 */
    @Scheduled(initialDelayString = "\${ecx.schedule.order-timeout-initial-delay-ms:30000}",
        fixedDelayString = "\${ecx.schedule.order-timeout-ms:60000}")
    fun scan() {
        val closed = closeExpired(TimeSec.now(), batch)
        if (closed > 0) log.info("超时关单 {} 笔", closed)
    }

    /** 到点单逐个关（单单独立事务，一笔失败不影响整批） */
    fun closeExpired(now: Long, limit: Int): Int =
        query.timeoutIds(now, limit).count { flow.timeoutCancel(it) }
}

/** @EnableScheduling 挂在服务层，boot 与测试上下文都能扫到 */
@org.springframework.context.annotation.Configuration
@org.springframework.scheduling.annotation.EnableScheduling
class ScheduleConfig
