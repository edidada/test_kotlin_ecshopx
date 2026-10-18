package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.distribution.CashWithdrawalDO
import com.ecshopx.kotlin.dao.distribution.CashWithdrawalMapper
import com.ecshopx.kotlin.dao.distribution.CommissionSum
import com.ecshopx.kotlin.dao.distribution.DistributeLogDO
import com.ecshopx.kotlin.dao.distribution.DistributeLogMapper
import com.ecshopx.kotlin.dao.distribution.DistributeLogRow
import com.ecshopx.kotlin.dao.distribution.DistributorMapper
import com.ecshopx.kotlin.dao.distribution.DistributorValid
import com.ecshopx.kotlin.dao.distribution.SalesmanLinkMapper
import com.ecshopx.kotlin.dao.distribution.SalesmanMapper
import com.ecshopx.kotlin.dao.distribution.WithdrawalStatus
import com.ecshopx.kotlin.dao.distribution.WithdrawalSum
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.stereotype.Service

/**
 * M1-4 佣金入账/冲销/结算（docs/04 §18.5，DL-A1..A3）。
 *
 * 三条口径决定了这个类为什么存在，而不是把 SQL 散进 `PayService`：
 *  1. **入账只发生在"钱真的进来了"之后** —— 三个结清点（`PAY-2` 即时结清 / 回调结清 /
 *     后台转账审核通过）都在各自的事务里调 [accrue]，与积分入账 `accruePoints` 同位。
 *     放在建单侧等于"没付钱的单也给店铺记了佣金"，退款时又要反向抹一遍；
 *  2. **幂等判据是"这一单有没有过流水"**（§18.5 第③条）。回调会重放、转账会重复点通过，
 *     用 `COUNT(order_id)` 挡住比用订单状态挡可靠：状态在别处也会被改；
 *  3. **冲销分两段**（D52）：未结算的行直接减（钱还没变成可提现余额，减了就是减了）；
 *     已结算的行**不回改**，改成插一条负向流水 —— 那笔钱早进过 `balance`，
 *     回改历史行会让"页面上看到过的余额"和账本对不上，负向行走同一张表、
 *     同一组聚合 SQL（`SUM(CASE WHEN is_close …)`），聚合式不用改就把它抵掉了。
 */
@Service
class CommissionService(
    private val logs: DistributeLogMapper,
    private val withdrawals: CashWithdrawalMapper,
    private val distributors: DistributorMapper,
    private val orderItems: OrderItemsMapper,
    private val configs: SysConfigMapper,
    private val salesmen: SalesmanLinkMapper,
    private val salesmanRows: SalesmanMapper,
    private val db: Db,
) {

    /**
     * 结清后入账（`PayService` 三个结清点在各自事务内调用）。
     *
     * 只给**分销单**记佣金（§18.5 第①条）：自营单 `distributor_id` 指向商城总部那条主店铺行，
     * 平台给自己抽佣没有意义，源系统同样是 `order_holder='distributor'` 才走这条链。
     * 佣金数取订单明细行的 `total_rebate` —— 下单时已按 `items.rebate × num` 落定（D46），
     * 这里**不重算费率**，改费率只影响之后的新单。
     */
    fun accrue(
        o: NormalOrderDO,
        now: Long,
    ): Int {
        if (o.distributorId <= 0L || o.orderHolder != "distributor") return 0
        if (logs.countByOrder(o.orderId) > 0) return 0
        val delay = closeDelayDays(o.companyId)
        val plan = if (delay > 0) now + delay * DAY else null
        var fee = 0L
        orderItems.listByOrder(o.orderId).forEach { row ->
            val rebate = row.totalRebate
            if (rebate <= 0L) return@forEach
            logs.insert(
                DistributeLogDO().apply {
                    companyId = o.companyId; distributorId = o.distributorId; orderId = o.orderId
                    itemId = row.itemId; userId = o.userId; shopId = row.shopId
                    // 导购归属跟着单走（3B）：代客下单由会话注入、会员自单由 `members.salesman_id` 回落，
                    // 到这里已经是"这单当时算谁的"，入账只是把它抄进流水行，不重新判断（D46 同族）
                    salesmanId = o.salesmanId
                    num = row.num; this.rebate = row.totalRebate; totalRebate = row.totalRebate
                    isClose = plan == null; planCloseTime = plan
                },
            )
            fee += rebate
        }
        return fee.toInt()
    }

    /**
     * 退款冲销（`RefundService` 在退款成功的事务里调用）。
     *
     * `delta` 由调用方按**行**的退款占比算好（与订单头 `total_rebate` 的冲销同一个分子分母，
     * 两处不同判据就会漂）。这里做的是"先减没结的、剩下才动已结的"：
     *  - 未结算行：`reduce` 一条 UPDATE 带 `GREATEST(…, 0)`，减到 0 为止；
     *  - 还剩的额度：只在**已结算的正向行**里找（`closedRebate`），插一条等额负向行，
     *    已结算总额因此回落到正确位置，而那条正向行原样留着 —— 对账时两行都在，能讲清发生了什么；
     *  - 都找不到的部分**不记**：这单的佣金从没进过账，冲一笔凭空出来的负数会让全场
     *    `commission_total` 变负，那是更难解释的账。
     */
    fun reverse(
        orderId: Long,
        itemId: Long,
        delta: Long,
        now: Long,
    ) {
        if (delta <= 0L) return
        var left = delta
        val open = logs.openRebate(orderId, itemId)
        if (open > 0L) {
            val take = minOf(left, open)
            logs.reduce(orderId, itemId, take, now)
            left -= take
        }
        if (left <= 0L) return
        val closed = logs.closedRebate(orderId, itemId)
        if (closed <= 0L) return
        val src = logs.firstClosed(orderId, itemId) ?: return
        val back = minOf(left, closed)
        logs.insert(
            DistributeLogDO().apply {
                companyId = src.companyId; distributorId = src.distributorId; this.orderId = src.orderId
                this.itemId = src.itemId; userId = src.userId; shopId = src.shopId
                // 三个归属（店/买家/岗）一律抄来源行：负向行要冲的就是那条已结算行所在的账，
                // 现在这个人是不是还在这个店里，跟这笔冲销该记在谁头上无关
                salesmanId = src.salesmanId
                // 负向行不重复记件数：件数是成交事实，退款改的是钱不是销量
                num = 0; rebate = -back; totalRebate = -back
                // 已结算的钱要立刻从 balance 里回去，所以这条负向行天生是 is_close=TRUE
                isClose = true; planCloseTime = null
            },
        )
    }

    /**
     * DL-A3 显式结算。`to_time` 缺省=此刻；同一时间窗第二次调用 `closed_count=0`（幂等）。
     *
     * 先取数再更新、**两条语句同一条判据**（`is_close=FALSE AND plan_close_time<=to_time`），
     * 出参的 `closed_fee` 才不会和真正被置位的行不是同一批。
     */
    fun close(
        companyId: Long,
        distributorId: Long?,
        toTime: Long?,
        scope: DistributorScope,
    ): Map<String, Any?> {
        if (distributorId != null) scope.require(distributorId)
        val to = toTime ?: TimeSec.now()
        val ids = if (distributorId != null || scope.unrestricted) null else scope.narrow(null)
        val now = TimeSec.now()
        return db.transactional {
            val prev = logs.closePreview(companyId, distributorId, ids, to) ?: emptyMap()
            val cnt = (prev["c"] as? Number)?.toLong() ?: 0L
            val fee = (prev["s"] as? Number)?.toLong() ?: 0L
            val hit = logs.closeBatch(companyId, distributorId, ids, to, now)
            linkedMapOf<String, Any?>(
                // 报**实际改到的行数**：并发下第二次调用预览有数、更新 0 行，取 0 才是真话
                "closed_count" to hit,
                "closed_fee" to if (hit.toLong() == cnt) fee else 0L,
                "to_time" to to,
                "distributor_id" to (distributorId ?: 0),
            )
        }
    }

    /** 定时结算用：全场到期行一把结掉（`CommissionSettleJob`），判据与 [close] 完全同一条 */
    fun settleDue(
        companyId: Long,
        now: Long,
    ): Int = logs.closeBatch(companyId, null, null, now, now)

    /**
     * Job 入口：无租户上下文，所以走 DAO 那条不带 `company_id` 的同判据 UPDATE。
     * 条件更新 ⇒ 多实例同时扫也只有一份副作用落地（与 M0-6 关单、M1-3 拼团结算同一口径）。
     */
    fun settleDueAll(now: Long): Int = logs.closeBatchAll(now, now)

    /** 还剩多少行到期未结（巡检/日志用，不参与判定） */
    fun dueCount(now: Long): Long = logs.countDue(now)

    /** DL-A1 佣金记录列表（行带商品名与掩码后的买家） */
    fun list(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
        val f = logFilters(companyId, raw, scope)
        return PageHelper.paginate(
            pq,
            count = { logs.count(f) },
            query = { offset, limit -> logs.page(f, offset, limit) },
            map = { rows -> rows.map { logRow(it) } },
        )
    }

    /**
     * DL-A2 分销统计：六数 + 余额。
     *
     * `balance` 与 DS-A8 走的是同一条公式（§18.5），只是聚合面从"按店 GROUP BY"换成"按筛选条件求和"；
     * 两处共用 [OccupyingSum] 这个口径常量，避免"店铺列表里的余额"和"统计卡的余额"两套算法。
     */
    fun stats(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val distributorId = raw["distributor_id"]?.toLongOrNull()?.takeIf { it > 0 }
        if (distributorId != null) scope.require(distributorId)
        val ids = if (distributorId != null || scope.unrestricted) null else scope.narrow(null)
        val c = logs.sum(companyId, distributorId, ids)
        val w = withdrawals.sum(companyId, distributorId, ids)
        val occupying = OccupyingSum.of(w)
        return linkedMapOf(
            "commission_total" to c.commissionTotal,
            "commission_open" to c.commissionOpen,
            "commission_closed" to c.commissionClosed,
            "withdraw_applied" to w.withdrawApplied,
            "withdraw_paid" to w.withdrawPaid,
            "withdraw_refused" to w.withdrawRefused,
            // 占用额单列给前端看公式，balance 仍 = 已结算 − 占用（非负）
            "withdraw_occupying" to occupying,
            "balance" to (c.commissionClosed - occupying).coerceAtLeast(0L),
        )
    }

    // ===================== 提现四态（CW-A1/A2/A3 + CW-F1/F2） =====================

    /**
     * CW-F1 店铺提现申请（会员面，申请人=该店的在册负责人）。
     *
     * 四道闸门全是 422（§18.5）：金额、余额、在途唯一、店铺营业态。
     * **余额判据读的是聚合式而不是任何缓存列** —— 结清与结算都在别的流程里改这些行，
     * 用现算的数就不会出现"页面说能提 100、提交时账上只有 80"。
     */
    fun apply(
        companyId: Long,
        userId: Long,
        form: WithdrawalApplyForm,
    ): Map<String, Any?> {
        val distributorId = form.distributorId ?: 0
        val money = form.money ?: 0
        val e = linkedMapOf<String, List<String>>()
        if (distributorId <= 0) e["distributor_id"] = listOf("必填")
        if (money <= 0) e["money"] = listOf("提现金额必须大于 0")
        if (e.isNotEmpty()) throw BizException(ErrorCode.BIZ, "提现申请不合法", e)
        // 归属判据：本项目 distributor 表没有"老板"列，在册员工行（salesman.user_id）就是唯一可信归属
        if (salesmen.countActive(companyId, userId, distributorId) == 0L) {
            throw BizException(ErrorCode.NO_PERM, "无权为该店铺提现", mapOf("distributor_id" to listOf("不是您的店铺")))
        }
        val d = distributors.findById(distributorId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
        if (d.isValid != DistributorValid.TRUE || !d.reviewStatus) {
            throw BizException(ErrorCode.BIZ, "店铺未启用，不能提现")
        }
        // 在途唯一排在余额之前：这是"资格"判据，余额是"额度"判据。
        // 反过来的话，一家已经把额度提完的店再点一次提现，会看到"余额不足"这种误导人的原因
        // —— 他真正该看到的是"上一笔还没处理完"。
        if (withdrawals.countInFlight(companyId, distributorId, WithdrawalStatus.IN_FLIGHT) > 0) {
            throw BizException(ErrorCode.BIZ, "已有提现申请待处理，请等财务处理完再提")
        }
        val st = balanceOf(companyId, distributorId)
        if (money > st.balance) {
            throw BizException(
                ErrorCode.BIZ,
                "可提现余额不足",
                mapOf("money" to listOf("可提现 ${st.balance} 分，申请 ${money} 分")),
            )
        }
        val w = CashWithdrawalDO().apply {
            this.companyId = companyId; this.distributorId = distributorId; this.userId = userId
            shopId = d.shopId; this.money = money; status = WithdrawalStatus.APPLIED
        }
        withdrawals.insert(w)
        return withdrawalView(w, d.name)
    }

    /** CW-A2 处理提现：`applied → confirmed/refused`，`confirmed → paid/refused`（§18.5 状态机） */
    fun check(
        companyId: Long,
        id: Long,
        form: WithdrawalCheckForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val to = form.status
            ?: throw BizException(ErrorCode.VALIDATION, "status 必填", mapOf("status" to listOf("只支持 confirmed/paid/refused")))
        val w = withdrawals.findById(id, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "提现申请不存在")
        scope.require(w.distributorId)
        val from = w.status
        if (to !in ALLOWED_TARGETS) {
            throw BizException(
                ErrorCode.VALIDATION,
                "status 非法",
                mapOf("status" to listOf("只支持 ${ALLOWED_TARGETS.joinToString("/")}，applied 由申请方产生")),
            )
        }
        val ok = when {
            // applied 只能被"确认"或"拒绝"，不能一步跳到打款 —— 申请方与打款方必须分人（§18.7）
            from == WithdrawalStatus.APPLIED -> to == WithdrawalStatus.CONFIRMED || to == WithdrawalStatus.REFUSED
            // confirmed 之后才是打款或反悔；paid/refused 是终态，什么都不许迁
            from == WithdrawalStatus.CONFIRMED -> to == WithdrawalStatus.PAID || to == WithdrawalStatus.REFUSED
            else -> false
        }
        if (!ok) {
            throw BizException(
                ErrorCode.BIZ,
                "当前状态 $from 不能迁到 $to",
                mapOf("status" to listOf("${from} → ${to} 不在状态机上")),
            )
        }
        if (withdrawals.transit(id, companyId, from, to, form.remarks, TimeSec.now()) == 0) {
            throw BizException(ErrorCode.BIZ, "该申请已被处理，请刷新后重试")
        }
        val row = withdrawals.findById(id, companyId)!!
        val name = distributors.findById(row.distributorId, companyId)?.name
        return withdrawalView(row, name)
    }

    /** CW-A1 后台提现列表（按数据权限收窄） */
    fun adminList(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
        val f = withdrawalFilters(companyId, raw, scope)
        val names = linkedMapOf<Long, String>()
        return PageHelper.paginate(
            pq,
            count = { withdrawals.count(f) },
            query = { offset, limit -> withdrawals.page(f, offset, limit) },
            map = { rows ->
                rows.map {
                    val n = names.getOrPut(it.distributorId) {
                        distributors.findById(it.distributorId, companyId)?.name ?: ""
                    }
                    withdrawalView(it, n)
                }
            },
        )
    }

    /** CW-F2 我的提现记录（店铺视角：只出本人申请过的行） */
    fun myList(
        companyId: Long,
        userId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "userId" to userId,
            "status" to raw["status"]?.takeIf { it.isNotBlank() },
            "distributorId" to null,
            "salesmanId" to null,
            "salesmanIds" to null,
            // CW-F2 是店铺那侧的账（C176）：同一个会员如果也以导购身份提过钱（CW-F3），
            // 那几行只进他自己的 CW-F4，不混进这份"店里的提现记录"
            "onlyShop" to true,
            "ids" to null,
        )
        return PageHelper.paginate(
            pq,
            count = { withdrawals.count(f) },
            query = { offset, limit -> withdrawals.page(f, offset, limit) },
            map = { rows -> rows.map { withdrawalView(it, distributors.findById(it.distributorId, companyId)?.name) } },
        )
    }

    // ===================== 导购侧（3B：ST-E4 聚合 / CW-F3 申请 / CW-F4 记录） =====================

    /**
     * ST-E4 与 SM-A1 的岗侧佣金聚合。
     *
     * 口径与店侧 [stats] 完全一致，只是聚合键换成 `salesman_id` —— 一个人的账不因为他换过店而断开，
     * 也不与所在店的账合并（§22 C176）：店拿店的佣金、岗拿岗的提成，两张提现单各自排队。
     */
    fun salesmanCommission(
        companyId: Long,
        salesmanId: Long,
    ): CommissionSum = logs.sumSalesman(companyId, salesmanId)

    /** 岗侧占用提现额：与店侧共用 [OccupyingSum] 那一条公式（`applied+confirmed+paid` 占、`refused` 不占） */
    fun salesmanWithdraw(
        companyId: Long,
        salesmanId: Long,
    ): Long = OccupyingSum.of(withdrawals.sumSalesman(companyId, salesmanId))

    /**
     * 岗的可提额 = **min(他自己那些行的余额, 店铺那口池的余额)**。
     *
     * 这一条是本案最容易想歪的地方，值得把推理写全：
     * 佣金表里只有**一口池**（按 `distributor_id` 聚合，§18.5 D46：每行只有一个 `rebate` 数），
     * `salesman_id` 切的是"这一份钱属于哪个岗"，**不是"另开一份钱"**。
     * 如果两边各自按自己的聚合独立限额（岗看 `salesman_id`、店看 `distributor_id` 且都含这批行），
     * 就会重复出金：店先把 3000 提空，岗仍能在同一批行上再提 2500 —— 池子里没有这笔钱了。
     * 取 min 之后两边加起来永远 ≤ 池子：先提的一方把池子扣下去（店的聚合含所有提现行，
     * 岗的提现也按 `distributor_id` 记），后提的一方自然受限。
     * 而**列表**仍然分两栏（CW-F2 只出 `salesman_id=0`、CW-F4 只出 `salesman_id>0`），
     * 所以"两套账互不合并"这句口径成立的是账本分列与限额分列，不是两份钱（§22 C176 记准确表述）。
     */
    fun salesmanWithdrawable(
        companyId: Long,
        salesmanId: Long,
        distributorId: Long,
    ): Long {
        val own = salesmanCommission(companyId, salesmanId).commissionClosed - salesmanWithdraw(companyId, salesmanId)
        return own.coerceAtMost(balanceOf(companyId, distributorId).balance).coerceAtLeast(0L)
    }

    /**
     * CW-F3 导购提现申请（会员面，申请人=当前会员）。
     *
     * 归属认的是**在册导购行**（`salesman.user_id = 会员`），不是 `members.salesman_id` 那个"当前绑定"：
     * 前者是"我在这个店有这个岗"，后者是"我买东西时算谁的"，拿后者发钱会把钱发给一个早就离岗的人。
     * 非导购 403（§18.5 CW-F3 那行的判据），同一个会员在多家店各有岗时必须说清提到哪家。
     */
    fun salesmanApply(
        companyId: Long,
        userId: Long,
        form: SalesmanWithdrawalApplyForm,
    ): Map<String, Any?> {
        val money = form.money ?: 0
        val e = linkedMapOf<String, List<String>>()
        if (money <= 0) e["money"] = listOf("提现金额必须大于 0")
        if (e.isNotEmpty()) throw BizException(ErrorCode.BIZ, "提现申请不合法", e)
        val mine = salesmanRows.listActiveByUser(companyId, userId)
        if (mine.isEmpty()) throw BizException(ErrorCode.NO_PERM, "您不是任何店铺的在册导购，不能以导购身份提现")
        val want = form.distributorId
        val s = when {
            want != null && want > 0 -> mine.firstOrNull { it.distributorId == want }
                ?: throw BizException(ErrorCode.BIZ, "您在该店铺没有在册导购岗", mapOf("distributor_id" to listOf("无此岗")))
            mine.size > 1 -> throw BizException(
                ErrorCode.BIZ,
                "您在 ${mine.size} 家店铺各有导购岗，请指明提现到哪家",
                mapOf("distributor_id" to listOf("必填")),
            )
            else -> mine.first()
        }
        val d = distributors.findById(s.distributorId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
        if (d.isValid != DistributorValid.TRUE || !d.reviewStatus) {
            throw BizException(ErrorCode.BIZ, "店铺未启用，不能提现")
        }
        // 在途唯一按**岗**挡（不是按店）：同店两个导购各提各的互不影响，同一个人不能同时挂两笔
        if (withdrawals.countInFlightBySalesman(companyId, s.salesmanId, WithdrawalStatus.IN_FLIGHT) > 0) {
            throw BizException(ErrorCode.BIZ, "已有提现申请待处理，请等财务处理完再提")
        }
        val closed = salesmanCommission(companyId, s.salesmanId).commissionClosed
        val balance = salesmanWithdrawable(companyId, s.salesmanId, s.distributorId)
        if (money > balance) {
            throw BizException(
                ErrorCode.BIZ,
                "可提现余额不足",
                mapOf("money" to listOf("可提现 $balance 分，申请 $money 分（本岗已结算 $closed 分）")),
            )
        }
        val w = CashWithdrawalDO().apply {
            this.companyId = companyId
            distributorId = s.distributorId
            this.userId = userId
            shopId = s.shopId
            salesmanId = s.salesmanId
            this.money = money
            status = WithdrawalStatus.APPLIED
        }
        withdrawals.insert(w)
        return withdrawalView(w, d.name)
    }

    /**
     * CW-F4 导购提现记录（会员面）。
     *
     * 这里按 **`user_id`** 认行，而不是按 `salesman_id IN (我的岗)`：提现单的申请人就是本人，
     * 万一某条岗被停用了（`is_valid` 置 false），历史申请也该还在他自己的列表里 ——
     * 记录面少一行，就是把他提过的钱从他的账上抹掉。
     */
    fun salesmanList(
        companyId: Long,
        userId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "userId" to userId,
            "status" to raw["status"]?.takeIf { it.isNotBlank() },
            "salesmanId" to null,
            "salesmanIds" to null,
            // 只出岗那侧的行：同一个会员可能既替店提过钱（CW-F1，`salesman_id=0`）又替自己提过，
            // 两套账各自成列表（C176），混在一份里就会变成"他好像提了两倍"
            "onlySalesman" to true,
            "distributorId" to raw["distributor_id"]?.toLongOrNull()?.takeIf { it > 0 },
            "ids" to null,
        )
        return PageHelper.paginate(
            pq,
            count = { withdrawals.count(f) },
            query = { offset, limit -> withdrawals.page(f, offset, limit) },
            map = { rows -> rows.map { withdrawalView(it, distributors.findById(it.distributorId, companyId)?.name) } },
        )
    }

    /**
     * CW-A3 打款信息（mock 回执，D47：不接渠道代付）。
     *
     * 真实代付（bady/adapay/hfpay）属 M2-2 的 `PayChannel` 实现位，这里只把
     * "财务照着打款要看的那几栏"给全：`paid` 之前 `paid_time=0` 且账户栏写明"待财务打款"。
     */
    fun payinfo(
        companyId: Long,
        id: Long,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val w = withdrawals.findById(id, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "提现申请不存在")
        scope.require(w.distributorId)
        val d = distributors.findById(w.distributorId, companyId)
        val paid = w.status == WithdrawalStatus.PAID
        return linkedMapOf(
            "id" to w.id,
            "distributor_id" to w.distributorId,
            "money" to w.money,
            "status" to w.status,
            "pay_type" to "offline",
            "paid_time" to (if (paid) w.updated else 0L),
            "account" to linkedMapOf(
                "name" to (d?.name ?: ""),
                "mobile" to Mask.mobile(d?.mobile),
                "bank_name" to "",
                "bank_account" to "",
                "tip" to if (paid) "已打款" else "待财务打款",
            ),
        )
    }

    /** §18.5 余额公式的唯一实现处（DS-A8/DL-A2/申请闸门三处共用） */
    private fun balanceOf(
        companyId: Long,
        distributorId: Long,
    ): Balance {
        val c = logs.sum(companyId, distributorId, null)
        val w = withdrawals.sum(companyId, distributorId, null)
        val occupying = OccupyingSum.of(w)
        return Balance(c.commissionClosed, occupying, (c.commissionClosed - occupying).coerceAtLeast(0L))
    }

    private data class Balance(val closed: Long, val occupying: Long, val balance: Long)

    /** 占用额 = 在途 + 已打款（C149：`paid` 也占，`refused` 不占） */
    private object OccupyingSum {
        fun of(w: WithdrawalSum): Long = w.withdrawApplied + w.withdrawPaid
    }

    /** 佣金结算延时（天）：租户行 → 全局行 → 缺省 7（与 `order.auto_cancel_minutes` 同一条读法） */
    fun closeDelayDays(companyId: Long): Long =
        configs.getValue(companyId, KEY_DELAY)?.toLongOrNull()
            ?: configs.getValue(0L, KEY_DELAY)?.toLongOrNull()
            ?: DEFAULT_DELAY

    private fun logFilters(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val close = raw["is_close"]?.takeIf { it.isNotBlank() }
        return linkedMapOf(
            "companyId" to companyId,
            "distributorId" to raw["distributor_id"]?.toLongOrNull()?.takeIf { it > 0 },
            "orderId" to raw["order_id"]?.toLongOrNull()?.takeIf { it > 0 },
            // ST-E5「我的佣金」与 DL-A1 共用一条列表口径，只是这一键由员工端会话强制填进去（§18.3）
            "salesmanId" to raw["salesman_id"]?.toLongOrNull()?.takeIf { it > 0 },
            "isClose" to if (close == null) null else close == "true" || close == "1",
            "beginTime" to raw["begin_time"]?.toLongOrNull()?.takeIf { it > 0 },
            "endTime" to raw["end_time"]?.toLongOrNull()?.takeIf { it > 0 },
            "ids" to scope.narrow(null),
            "itemName" to raw["keyword"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun withdrawalFilters(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): Map<String, Any?> = linkedMapOf(
        "companyId" to companyId,
        "distributorId" to raw["distributor_id"]?.toLongOrNull()?.takeIf { it > 0 },
        "status" to raw["status"]?.takeIf { it.isNotBlank() },
        "userId" to null,
        // 后台按岗筛提现（CW-A1 追加键）；`salesmanIds` 留给"这批岗的钱"那种批量口径，目前只有服务层会填
        "salesmanId" to raw["salesman_id"]?.toLongOrNull()?.takeIf { it > 0 },
        "salesmanIds" to null,
        "ids" to scope.narrow(null),
    )

    private fun logRow(r: DistributeLogRow): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        // 本仓 `order_id` 就是号段发出来的单号（schema 无 order_no 列），出参两栏都给、同为字符串：
        // 后台列表拿它显示，前端拿它跳详情，不需要知道"其实是一个数"
        "order_id" to r.orderId.toString(),
        "order_no" to r.orderId.toString(),
        "distributor_id" to r.distributorId,
        "item_id" to r.itemId,
        "item_name" to r.itemName,
        "user_id" to r.userId,
        "shop_id" to r.shopId,
        // 归属岗（3B）：0 = 这单不是导购开的。ST-E5 按这一列强制收窄，后台列表则把它当一栏展示
        "salesman_id" to r.salesmanId,
        "num" to r.num,
        // 掩码优先级：昵称有就用昵称，没有退到手机号 —— 与售后/拼团那两处同一个口径
        "nickname" to (r.nickname?.takeIf { it.isNotBlank() } ?: Mask.mobile(r.mobile)),
        "rebate" to r.rebate,
        "total_rebate" to r.totalRebate,
        "is_close" to r.isClose,
        "plan_close_time" to (r.planCloseTime ?: 0),
        "created" to r.created,
        "updated" to r.updated,
    )

    private fun withdrawalView(
        w: CashWithdrawalDO,
        shopName: String?,
    ): Map<String, Any?> = linkedMapOf(
        "id" to w.id,
        "distributor_id" to w.distributorId,
        "shop_name" to (shopName ?: ""),
        "user_id" to w.userId,
        "shop_id" to w.shopId,
        // 岗与店两套余额互不合并（C176）：这一列 0 = 店铺提现、>0 = 导购提现，后台据此分栏处理
        "salesman_id" to w.salesmanId,
        "money" to w.money,
        "status" to w.status,
        "status_text" to statusText(w.status),
        "remarks" to w.remarks,
        "created" to w.created,
        "updated" to w.updated,
        // 与 CW-A3 同一句判据：只有 paid 才有打款时刻
        "paid_time" to (if (w.status == WithdrawalStatus.PAID) w.updated else 0L),
    )

    private fun statusText(s: String): String = when (s) {
        WithdrawalStatus.APPLIED -> "待处理"
        WithdrawalStatus.CONFIRMED -> "已确认待打款"
        WithdrawalStatus.PAID -> "已打款"
        else -> "已拒绝"
    }


    companion object {
        const val KEY_DELAY = "commission.close_delay"
        const val DEFAULT_DELAY = 7L
        private const val DAY = 86400L

        /** 后台只能往这三个态迁（`applied` 是申请方产生的，运营不能"倒着置回待处理"） */
        val ALLOWED_TARGETS = setOf(WithdrawalStatus.CONFIRMED, WithdrawalStatus.PAID, WithdrawalStatus.REFUSED)
    }
}

/** CW-F1 提现申请 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WithdrawalApplyForm(
    val distributorId: Long? = null,
    val money: Long? = null,
)

/** CW-F3 导购提现申请（`distributor_id` 只在"同一个人在多家店各有岗"时才必填） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SalesmanWithdrawalApplyForm(
    val money: Long? = null,
    val distributorId: Long? = null,
)

/** CW-A2 处理提现 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WithdrawalCheckForm(
    val status: String? = null,
    val remarks: String? = null,
)

/** DL-A3 显式结算 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CommissionCloseForm(
    val distributorId: Long? = null,
    val toTime: Long? = null,
)
