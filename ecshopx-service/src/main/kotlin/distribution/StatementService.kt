package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.distribution.StatementDetailsMapper
import com.ecshopx.kotlin.dao.distribution.StatementFactsMapper
import com.ecshopx.kotlin.dao.distribution.StatementPeriodSettingMapper
import com.ecshopx.kotlin.dao.distribution.StatementsMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 结算对账单（docs/04 §29.6，批F；M0 建表、M2-4 补服务与任务腿）。
 *
 * **事实源只认一条**：`trade.is_settled=TRUE AND trade_state='SUCCESS'` 的行，
 * 落账期窗口按**结清时刻**（`trade.updated`，回调 CAS 结清那一笔写的那个时间）。
 * 为什么不用订单的 `created`：那是下单时刻，钱还没进来；按它归期会把"月初下单、
 * 月中付款"的单算进错的期，月初对账的人会跟财务对不上。
 *
 * **一张订单一生只进一张单**：已铺过单的 order_id 先捞出来在服务层过滤。
 * 这一步放 Kotlin 而不是 SQL——`trade.order_id` 是 VARCHAR、`statement_details.order_id`
 * 是 BIGINT，SQL 里 CAST 在 H2 与 MySQL 两方言写法不同，判据放在一处才不漂（C241）。
 *
 * **算式**（C241，与源系统的口径差异写在这里而不是口头说）：
 *  - `total_fee` = Σ 实付（trade.pay_fee）
 *  - `refund_fee` = Σ 已退（trade.refunded_fee，生成那一刻的累计）
 *  - `freight_fee` = Σ 订单运费（trade 不记，回 orders 补）
 *  - `rebate_fee` = Σ 推广佣金（orders.total_rebate，**单列供核对，不再从应结里扣一次**）
 *  - `commission_fee` = Σ 平台佣金（orders.commission_fee）
 *  - `statement_fee`（应结）= total_fee - refund_fee - commission_fee
 *
 * 账期窗口 `[start, end)` 半开，任务按"上一个已闭合的自然日"跑；同一个窗口重复跑**幂等**
 * （statement_no 由 类型+店铺+窗口日 决定，撞号就不重铺）。
 */
@Service
class StatementService(
    private val settings: StatementPeriodSettingMapper,
    private val statements: StatementsMapper,
    private val details: StatementDetailsMapper,
    private val facts: StatementFactsMapper,
    private val orders: OrdersMapper,
) {

    // ===================== 账期设置 =====================

    /** ST-A0 账期设置读：每种结算对象一行（没有的补默认 daily 回显，不落库） */
    fun settingsOf(companyId: Long): Map<String, Any?> {
        val rows = settings.listByCompany(companyId)
        val byType = rows.associateBy { it.merchantType }
        return linkedMapOf(
            "distributor" to periodView(byType["distributor"]),
            "supplier" to periodView(byType["supplier"]),
        )
    }

    /** ST-A0 账期设置写（按 merchant_type upsert；period_type 只认 daily/weekly/monthly） */
    fun saveSetting(companyId: Long, merchantType: String, periodType: String): Map<String, Any?> {
        if (merchantType !in setOf("distributor", "supplier")) {
            throw BizException(ErrorCode.VALIDATION, "结算对象不合法", mapOf("merchant_type" to listOf("只支持 distributor/supplier")))
        }
        if (periodType !in setOf("daily", "weekly", "monthly")) {
            throw BizException(ErrorCode.VALIDATION, "账期不合法", mapOf("period_type" to listOf("只支持 daily/weekly/monthly")))
        }
        val now = TimeSec.now()
        val cur = settings.find(companyId, merchantType)
        if (cur == null) {
            settings.insert(
                com.ecshopx.kotlin.dao.distribution.StatementPeriodSettingDO().apply {
                    this.companyId = companyId
                    this.merchantType = merchantType
                    this.periodType = periodType
                    this.created = now
                    this.updated = now
                },
            )
        } else {
            settings.update(cur.id, companyId, periodType, now)
        }
        return linkedMapOf("merchant_type" to merchantType, "period_type" to periodType)
    }

    private fun periodView(row: com.ecshopx.kotlin.dao.distribution.StatementPeriodSettingDO?): Map<String, Any?> =
        linkedMapOf(
            "period_type" to (row?.periodType ?: "daily"),
            "configured" to (row != null),
        )

    // ===================== 生成账期单（任务腿） =====================

    /**
     * 生成一个窗口的账期单；返回新建的账期单数（已存在的窗口重复跑返回 0，不重复计账）。
     * 任务腿与 e2e 直调走的是同一条（不另开"测试专用"口）。
     */
    fun generate(companyId: Long, start: Long, end: Long, now: Long): Int {
        if (end <= start) return 0
        val trades = facts.listSettledTrades(companyId, start, end)
        if (trades.isEmpty()) return 0
        val counted = facts.listCountedOrderIds(companyId).toSet()
        // 一单一生：已铺过单号的这一期不重铺（退款把 updated 推到下一期也不会重复计）
        val fresh = trades.filter { t ->
            val oid = t.orderId.toLongOrNull()
            oid != null && oid !in counted
        }
        if (fresh.isEmpty()) return 0
        val groups = fresh.groupBy { it.distributorId }
        var made = 0
        for ((distributorId, rows) in groups) {
            // 平台自营（distributor_id=0）不结给任何店铺，不出账期单
            if (distributorId <= 0) continue
            val orderIds = rows.mapNotNull { it.orderId.toLongOrNull() }.distinct()
            val fees = if (orderIds.isEmpty()) emptyList() else orders.listStatementFees(orderIds)
            val feeBy = fees.associateBy { it.orderId }
            val totalFee = rows.sumOf { it.payFee }
            val refundFee = rows.sumOf { it.refundedFee }
            val freightFee = fees.sumOf { it.freightFee }
            val rebateFee = fees.sumOf { it.totalRebate }
            val commissionFee = fees.sumOf { it.commissionFee }
            val statementFee = totalFee - refundFee - commissionFee
            val no = statementNo("distributor", distributorId, start)
            if (statements.idByNo(no) != null) continue
            val stmt = com.ecshopx.kotlin.dao.distribution.StatementDO().apply {
                statementNo = no
                this.companyId = companyId
                this.distributorId = distributorId
                merchantType = "distributor"
                orderNum = rows.size
                this.totalFee = totalFee
                this.freightFee = freightFee
                this.rebateFee = rebateFee
                this.refundFee = refundFee
                this.statementFee = statementFee
                this.commissionFee = commissionFee
                startTime = start
                endTime = end
                statementStatus = "ready"
                created = now
                updated = now
            }
            statements.insert(stmt)
            for (t in rows) {
                val oid = t.orderId.toLongOrNull() ?: continue
                val f = feeBy[oid]
                val d = com.ecshopx.kotlin.dao.distribution.StatementDetailDO().apply {
                    this.statementId = stmt.id          // useGeneratedKeys 回填在 DO 的 id 上
                    orderId = oid
                    num = 1
                    this.totalFee = t.payFee
                    this.rebateFee = f?.totalRebate ?: 0L
                    this.refundFee = t.refundedFee
                    refundNum = if (t.refundedFee > 0) 1 else 0
                    created = now
                    updated = now
                }
                details.insert(d)
            }
            made++
        }
        return made
    }

    /** 账期单号：`ST-{类型首字母}-{店铺id}-{窗口日}`，撞号即"这一期已经出过了"（幂等判据） */
    private fun statementNo(merchantType: String, distributorId: Long, start: Long): String =
        "ST-" + merchantType.take(1).uppercase() + "-" + distributorId + "-" + ymd(start)

    // ===================== 对账单读面 =====================

    /** ST-A1 账期单翻页（merchant_type/distributor_id/status 可选筛） */
    fun summarized(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["page_size"]?.toIntOrNull() ?: raw["pageSize"]?.toIntOrNull())
        val f = StatementsMapper.Filter().apply {
            this.companyId = companyId
            merchantType = raw["merchant_type"]?.takeIf { it.isNotBlank() }
            distributorId = raw["distributor_id"]?.takeIf { it.isNotBlank() }?.toLongOrNull()
            status = raw["statement_status"]?.takeIf { it.isNotBlank() }
        }
        if (f.merchantType != null && f.merchantType !in setOf("distributor", "supplier")) {
            throw BizException(ErrorCode.VALIDATION, "结算对象不合法", mapOf("merchant_type" to listOf("只支持 distributor/supplier")))
        }
        if (f.status != null && f.status !in setOf("ready", "confirmed", "paid")) {
            throw BizException(ErrorCode.VALIDATION, "状态不合法", mapOf("statement_status" to listOf("只支持 ready/confirmed/paid")))
        }
        return PageHelper.paginate(
            pq,
            count = { statements.count(f) },
            query = { offset, limit -> statements.page(f, offset, limit) },
            map = { rows -> rows.map { rowView(it) } },
        )
    }

    /** ST-A2 账期单详情（头 + 明细行） */
    fun detail(companyId: Long, id: Long): Map<String, Any?> {
        val s = statements.find(companyId, id) ?: throw BizException(ErrorCode.NOT_FOUND, "结算单不存在")
        val rows = details.listByStatement(id).map {
            linkedMapOf<String, Any?>(
                "order_id" to it.orderId,
                "num" to it.num,
                "total_fee" to it.totalFee,
                "rebate_fee" to it.rebateFee,
                "refund_fee" to it.refundFee,
                "refund_num" to it.refundNum,
            )
        }
        val view = rowView(s)
        view["details"] = rows
        return view
    }

    /** ST-A3 确认结算（ready→confirmed；已确认的重复确认按幂等回当前态，不报错也不改账） */
    fun confirm(companyId: Long, id: Long, operatorId: Long?, operator: String): Map<String, Any?> {
        val s = statements.find(companyId, id) ?: throw BizException(ErrorCode.NOT_FOUND, "结算单不存在")
        if (s.statementStatus == "ready") {
            val n = statements.casStatus(id, companyId, "ready", "confirmed", TimeSec.now())
            if (n == 0) throw BizException(ErrorCode.BIZ, "结算单状态已变更，请刷新重试")
        }
        val after = statements.find(companyId, id)!!
        return rowView(after).apply { put("operator", operator); put("operator_id", operatorId) }
    }

    private fun rowView(s: com.ecshopx.kotlin.dao.distribution.StatementDO): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "statement_id" to s.id,
            "statement_no" to s.statementNo,
            "company_id" to s.companyId,
            "merchant_type" to s.merchantType,
            "distributor_id" to s.distributorId,
            "order_num" to s.orderNum,
            "total_fee" to s.totalFee,
            "freight_fee" to s.freightFee,
            "rebate_fee" to s.rebateFee,
            "refund_fee" to s.refundFee,
            "statement_fee" to s.statementFee,
            "commission_fee" to s.commissionFee,
            "start_time" to s.startTime,
            "end_time" to s.endTime,
            "statement_status" to s.statementStatus,
            "created" to s.created,
        )

    // ===================== 窗口与日界 =====================

    /** yyyymmdd 整数（Asia/Shanghai 业务日界，与结算窗口同一时区） */
    private fun ymd(epochSec: Long): Int =
        LocalDateTime.ofInstant(TimeSec.of(epochSec), TimeSec.TZ).toLocalDate().let { d ->
            d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
        }

    /**
     * 上一段已闭合窗口（按周期）：daily=昨天 00:00..今天 00:00；
     * weekly=上周一到今天 00:00；monthly=上月 1 号 00:00..今天 00:00。
     */
    fun previousWindow(periodType: String, now: Long): Pair<Long, Long> {
        val today = LocalDateTime.ofInstant(TimeSec.of(now), TimeSec.TZ).toLocalDate()
        val todayStart = today.atStartOfDay(TimeSec.TZ).toEpochSecond()
        return when (periodType) {
            "weekly" -> {
                val monday = today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong())
                monday.atStartOfDay(TimeSec.TZ).toEpochSecond() to todayStart
            }
            "monthly" -> today.withDayOfMonth(1).atStartOfDay(TimeSec.TZ).toEpochSecond() to todayStart
            else -> today.minusDays(1).atStartOfDay(TimeSec.TZ).toEpochSecond() to todayStart
        }
    }

    /** 供任务腿用：窗口的自然日（yyyyMMdd）便于落日志 */
    fun dayOf(epochSec: Long): Int = ymd(epochSec)

    /** 本地日期转 epoch 秒（测试与运维算窗口用） */
    fun epochOf(date: LocalDate): Long = date.atStartOfDay(TimeSec.TZ).toEpochSecond()
}
