package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.domain.TimeSec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 结算账期任务（docs/04 §29.6，批F）。
 *
 * 每天在"上一个自然日闭合之后"跑一次，把窗口内结清的钱按店铺聚成账期单。
 * 幂等由服务层保证（同一窗口的 statement_no 撞号就不重铺），所以**重复触发不重复计账**——
 * 这条是"账"的底线：宁可漏跑（补跑一次窗口就行），不可重跑（重跑一次就是重复付）。
 *
 * 跑哪些租户：**显式配置白名单** `ecx.schedule.statement-settle-company-ids`（缺省 1）。
 * 为什么是白名单而不是按 `statement_period_setting` 有行就跑：全租户扫账期意味着所有店铺的
 * 应结金额一起动，判错窗口就是批量错账；宁少不滥（C244：本批只跑 distributor 账位，
 * supplier 的设置行/位次留着，订单链上 supplier_id 恒 0 没有可对账事实源）。
 *
 * 测试直接调 [runNow]，不等调度（与 M1-3 三个 Job 同一口径，D30）。
 */
@Component
class StatementSettleJob(
    private val statements: StatementService,
    @Value("\${ecx.schedule.statement-settle-company-ids:1}") private val companyIds: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        initialDelayString = "\${ecx.schedule.statement-settle-initial-delay-ms:90000}",
        fixedDelayString = "\${ecx.schedule.statement-settle-ms:86400000}",
    )
    fun scan() {
        val n = runNow(TimeSec.now())
        if (n > 0) log.info("结算账期单 {} 张", n)
    }

    /** 跑一轮（所有白名单租户 × 各自的账期设置）；返回新建账期单数 */
    fun runNow(now: Long): Int {
        var made = 0
        for (cid in companyIds.split(",").mapNotNull { it.trim().toLongOrNull() }) {
            val rows = statements.settingsOf(cid)
            val period = (rows["distributor"] as? Map<*, *>)?.get("period_type") as? String ?: "daily"
            val (start, end) = statements.previousWindow(period, now)
            made += statements.generate(cid, start, end, now)
        }
        return made
    }
}
