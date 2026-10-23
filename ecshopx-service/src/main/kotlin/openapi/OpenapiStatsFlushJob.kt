package com.ecshopx.kotlin.service.openapi

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.openapi.OpenapiCallStatsMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 网关调用量聚合任务（docs/04 §29.6，批F；D76 的收口条）。
 *
 * M2-1 留下的话是这样说的：进程内计数器是**读数不是账**——它会重启归零、多实例各算各的；
 * M2-4 要"每租户每天调用量"的聚合，落的是这张表。所以这条任务的语义是**并入**：
 * 把进程计数器里的差量加进 `openapi_call_stats(company_id, day)`，多实例各自并、并发也不重不漏
 * （UPDATE 累加语义两实例同时跑也只是都加上了各自差量）。
 *
 * 为什么不做成逐次写：D76 的原文反对的是"网关每次调用插一行日志"的写放大——
 * 聚合表一分钟并入一次，开放面的每调用成本回到 O(表的并发度)，这才是计量该有的样子。
 *
 * 读口（OP-A6）的 `gateway` 三位仍然是进程内计数器：两个数字**不同源**这件事写在
 * note 里不混成一个总调用数（混了就没人知道哪一半是估的）。
 *
 * 测试直接调 [runNow]，不等调度（D30 同一口径）。
 */
@Component
class OpenapiStatsFlushJob(
    private val metrics: OpenapiMetrics,
    private val stats: OpenapiCallStatsMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${ecx.schedule.openapi-stats-flush-initial-delay-ms:70000}",
        fixedDelayString = "\${ecx.schedule.openapi-stats-flush-ms:60000}",
    )
    fun scan() {
        val n = runNow()
        if (n > 0) log.info("openapi 调用量并入 {} 行", n)
    }

    /** 并入一轮；返回涉及到的租户-日行数 */
    fun runNow(now: Long = TimeSec.now()): Int {
        val day = TimeSec.TZ.let { z ->
            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(now), z).let { d ->
                d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
            }
        }
        val touched = HashMap<Long, IntArray>()  // companyId -> [calls, success, fails]
        metrics.drainTo { companyId, calls, success, fails ->
            val t = touched.getOrPut(companyId) { intArrayOf(0, 0, 0) }
            t[0] += calls.toInt(); t[1] += success.toInt(); t[2] += fails.toInt()
        }
        for ((companyId, t) in touched) {
            val n = stats.accumulate(companyId, day, t[0].toLong(), t[1].toLong(), t[2].toLong(), now)
            if (n == 0) {
                // 当天还没有这一租户的行：INSERT 一行整期基线（后续差量继续 UPDATE 并入）。
                // 多实例同时"首行入账"会撞 UNIQUE——落不进去的那份差量改走 UPDATE 并入一次，
                // 丢账比晚账严重，宁可这里多打一行。
                runCatching {
                    stats.insert(
                        com.ecshopx.kotlin.dao.openapi.OpenapiCallStatsDO().apply {
                            this.companyId = companyId
                            this.day = day
                            calls = t[0].toLong()
                            success = t[1].toLong()
                            fails = t[2].toLong()
                            created = now
                            updated = now
                        },
                    )
                }.onFailure {
                    stats.accumulate(companyId, day, t[0].toLong(), t[1].toLong(), t[2].toLong(), now)
                }
            }
        }
        return touched.size
    }
}
