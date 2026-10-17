package com.ecshopx.kotlin.service.aftersales

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.aftersales.AftersalesDO
import com.ecshopx.kotlin.dao.aftersales.AftersalesDetailMapper
import com.ecshopx.kotlin.dao.aftersales.AftersalesMapper
import com.ecshopx.kotlin.dao.aftersales.AftersalesProgress
import com.ecshopx.kotlin.dao.aftersales.AftersalesRefundDO
import com.ecshopx.kotlin.dao.aftersales.AftersalesRefundMapper
import com.ecshopx.kotlin.dao.aftersales.AftersalesStatus
import com.ecshopx.kotlin.dao.aftersales.ItemAftersalesStatus
import com.ecshopx.kotlin.dao.aftersales.RefundChannel
import com.ecshopx.kotlin.dao.aftersales.RefundStatus
import com.ecshopx.kotlin.dao.aftersales.RefundType
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderItemDO
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrderOperateLogDO
import com.ecshopx.kotlin.dao.order.OrderOperateLogMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.order.TransitPatch
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.pay.TradeDO
import com.ecshopx.kotlin.dao.pay.TradeMapper
import com.ecshopx.kotlin.service.pay.PayChannelRegistry
import com.ecshopx.kotlin.service.pay.RefundRequest
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Service

/**
 * M1-1 退款执行链（docs/04 §11.2 AF-A4/A8/A9/A10）。
 *
 * 一张退款单的完整生命周期只有两个闸门，顺序固定：
 *
 * ```text
 * READY --AF-A4 同意--> AUDIT_SUCCESS --渠道退款成功/线下登记--> SUCCESS
 *   |                        |
 *   +--AF-A4 拒绝--> REFUSE  +--渠道当场失败--> CHANGE（额度已回滚，可重新确认）
 *   +--撤销/驳回--> CANCEL
 * ```
 *
 * 三条口径：
 *  1. **退款单只在进入「待退款(8)」时生成**（由 `AftersalesService.review` 在同一事务里调 [createReady]），
 *     审核之前后台退款列表看不到任何"未审先退"的单；
 *  2. **额度在 AF-A4 同意时就占用**（`trade.refunded_fee` / 订单行 `refunded_fee` 两道条件更新），
 *     之后无论渠道当场成功还是等线下打款，都不会重复占用——所以 [offline] 只推状态、不再加额度；
 *  3. **渠道失败不留半成品**：渠道动账与额度占用同事务，失败时整个事务回滚（额度回到原位），
 *     再单独把退款单标成 `CHANGE` 留痕并返回 200，不抛错（§11.4 与源系统一致，避免"页面 500 但钱已退"）。
 */
@Service
class RefundService(
    private val refunds: AftersalesRefundMapper,
    private val aftersales: AftersalesMapper,
    private val details: AftersalesDetailMapper,
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val orderLogs: OrderOperateLogMapper,
    private val trades: TradeMapper,
    private val registry: PayChannelRegistry,
    private val seq: SeqGenerator,
    private val db: Db,
) {

    // ===================== 生成 / 作废（由售后状态机在同事务内调用） =====================

    /**
     * 进入「待退款」时生成退款单。金额与积分分别记账：积分单 `refund_fee=0`、`refund_point=申请额`，
     * 现金单反之——两个池子分开守恒（见 [AftersalesRefundDO] 与 `AftersalesRefundMapper.sumRefunded`）。
     */
    fun createReady(a: AftersalesDO, fee: Long, point: Long, now: Long): AftersalesRefundDO {
        val orderNo = a.orderId.toString()
        val trade = trades.findSuccessByOrder(orderNo)
            ?: throw BizException(ErrorCode.BIZ, "订单没有已成功的支付单，无法生成退款单")
        val o = orders.findById(a.orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        val payTypeText = trade.payType ?: o.payType
        val r = AftersalesRefundDO().apply {
            refundBn = seq.next(SeqName.REFUND_NO)
            aftersalesBn = a.aftersalesBn
            orderId = orderNo
            tradeId = trade.tradeId
            itemId = a.itemId
            companyId = a.companyId
            refundType = RefundType.AFTERSALES
            // 线下转账没有"原路"，只能人工打款 → 通道记 offline，AF-A10 登记后落 SUCCESS
            refundChannel = if (payTypeText == OFFLINE_PAY) RefundChannel.OFFLINE else RefundChannel.ORIGINAL
            refundStatus = RefundStatus.READY
            orderFee = o.totalFee
            refundFee = fee
            refundedFee = 0L
            refundPoint = point
            refundedPoint = 0L
            returnFreight = a.freight
            payType = payTypeText
            created = now
            updated = now
        }
        refunds.insert(r)
        return r
    }

    /**
     * 作废在途退款单（售后驳回 / 买家撤销）。已经是终态的行直接跳过——
     * 这一步在循环里跑，绝不能因为"其中一笔早就退完了"而把整个撤销动作打回。
     */
    fun cancel(refundBn: Long, companyId: Long, memoText: String, now: Long): Boolean {
        val from = refunds.findByBn(refundBn, companyId)?.refundStatus ?: return false
        if (from != RefundStatus.READY && from != RefundStatus.AUDIT_SUCCESS) return false
        val p = AftersalesRefundMapper.RefundTransitParam().apply {
            bn = refundBn
            this.companyId = companyId
            fromStatus = from
            toStatus = RefundStatus.CANCEL
            memo = memoText
            updated = now
        }
        return refunds.transit(p) > 0
    }

    /** §11.3 `refund_info` 出参行：单号一律串，金额分 */
    fun row(r: AftersalesRefundDO): Map<String, Any?> = linkedMapOf(
        "refund_bn" to r.refundBn.toString(),
        "aftersales_bn" to r.aftersalesBn.toString(),
        "order_id" to r.orderId,
        "trade_id" to r.tradeId,
        "item_id" to r.itemId,
        "company_id" to r.companyId,
        "refund_type" to r.refundType,
        "refund_channel" to r.refundChannel,
        "refund_status" to r.refundStatus,
        "status_msg" to statusMsg(r.refundStatus),
        "order_fee" to r.orderFee,
        "refund_fee" to r.refundFee,
        "refunded_fee" to r.refundedFee,
        "refund_point" to r.refundPoint,
        "refunded_point" to r.refundedPoint,
        "return_freight" to r.returnFreight,
        "pay_type" to r.payType,
        "refund_id" to r.refundId,
        "refund_time" to r.refundTime,
        "refund_success_time" to r.refundSuccessTime,
        "payee_name" to r.payeeName,
        "bank_name" to r.bankName,
        "bank_account" to r.bankAccount,
        "bank_branch" to r.bankBranch,
        "bank_province" to r.bankProvince,
        "bank_city" to r.bankCity,
        "memo" to r.memo,
        "created" to r.created,
        "updated" to r.updated,
    )

    // ===================== AF-A4 退款确认（终局） =====================

    /**
     * 商家点"确认退款"。这是唯一会**动钱**的售后端点，所以两道守恒闸门都在写库上：
     *  - `trade.refunded_fee + ? <= pay_fee`：整单不超实付；
     *  - `行 refunded_fee + ? <= 行 total_fee`：单行不超货款（运费不进这个池子）。
     *
     * 拒绝退款（`check_refund=0`）走另一条腿：售后转终态 3/5、退款单 REFUSE、件数镜像重算，
     * 额度回到"其他在途申请"可用范围内（`aftersales_status=3` 不再占用）。
     */
    fun checkRefund(companyId: Long, form: AftersalesRefundCheckForm): Map<String, Any?> {
        val bn = form.aftersalesBn?.toLongOrNull()
            ?: throw BizException(ErrorCode.VALIDATION, "aftersales_bn 必须是售后单号串", mapOf("aftersales_bn" to listOf("格式非法")))
        val a = aftersales.findByBn(bn, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "售后单不存在")
        if (a.aftersalesStatus != AftersalesStatus.PROCESSING || a.progress != AftersalesProgress.SELLER_CONFIRMED) {
            throw BizException(ErrorCode.BIZ, "当前状态不可确认退款：${AftersalesDict.progressMsg(a.progress)}")
        }
        val ready = refunds.listByAftersales(bn).firstOrNull { it.refundStatus == RefundStatus.READY }
            ?: throw BizException(ErrorCode.BIZ, "没有待确认的退款单：本单可能已确认或在人工打款中")
        val now = TimeSec.now()

        if (form.checkRefund != 1) {
            val refuseMemo = form.refundsMemo?.takeIf { it.isNotBlank() }
                ?: throw BizException(ErrorCode.VALIDATION, "拒绝退款必须填写理由", mapOf("refunds_memo" to listOf("不能为空")))
            return reject(a, ready, refuseMemo, now)
        }

        val fee = when {
            form.refundFee == null -> ready.refundFee
            form.refundFee!! < 0L -> throw BizException(ErrorCode.VALIDATION, "refund_fee 不能为负", mapOf("refund_fee" to listOf("不能为负")))
            form.refundFee!! > ready.refundFee -> throw BizException(
                ErrorCode.BIZ, "退款金额不能超过申请额",
                mapOf("refund_fee" to listOf("申请 ${ready.refundFee} 分，最多退 ${ready.refundFee} 分")),
            )
            else -> form.refundFee!!
        }
        val point = ready.refundPoint
        val o = orders.findById(a.orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        val trade = trades.findById(ready.tradeId)
            ?: throw BizException(ErrorCode.BIZ, "原支付单不存在：${ready.tradeId}")
        val itemRow = orderItems.listByOrder(a.orderId).firstOrNull { it.itemId == a.itemId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "订单明细不存在")

        return try {
            db.transactional { execute(a, ready, o, trade, itemRow, fee, point, form.refundsMemo, now) }
        } catch (e: ChannelFailure) {
            // 上面的事务整体回滚 ⇒ 额度一分没占；这里只留一行 CHANGE 状态给后台看（§11.4：不抛错）
            db.transactional {
                val p = AftersalesRefundMapper.RefundTransitParam().apply {
                    this.bn = ready.refundBn
                    this.companyId = companyId
                    fromStatus = RefundStatus.READY
                    toStatus = RefundStatus.CHANGE
                    memo = "渠道退款失败：${e.message}（额度未占用，可重新确认退款）"
                    updated = TimeSec.now()
                }
                refunds.transit(p)
                log(a.orderId, "seller", "refund_change", "退款单 ${ready.refundBn} 渠道失败：${e.message}")
                true
            }
            linkedMapOf(
                "refund_bn" to ready.refundBn.toString(),
                "refund_status" to RefundStatus.CHANGE,
                "settled" to false,
                "message" to (e.message ?: "渠道退款失败，请人工处理"),
            )
        }
    }

    // ===================== AF-A10 线下打款登记 =====================

    /**
     * 线下转账订单的人工打款：补齐银行七字段并把退款单从 `AUDIT_SUCCESS` 推到 `SUCCESS`。
     * 额度已在 AF-A4 同意时占用（见类注释口径 2），这里**只推状态**，不再重复累加。
     */
    fun offline(companyId: Long, form: RefundOfflineForm): Map<String, Any?> {
        val bn = form.refundBn?.toLongOrNull()
            ?: throw BizException(ErrorCode.VALIDATION, "refund_bn 必须是退款单号串", mapOf("refund_bn" to listOf("格式非法")))
        val r = refunds.findByBn(bn, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "退款单不存在")
        if (r.refundStatus != RefundStatus.AUDIT_SUCCESS) {
            throw BizException(ErrorCode.BIZ, "只有待打款的退款单能登记银行信息：${statusMsg(r.refundStatus)}")
        }
        // M1-2：真实渠道的"已受理待回执"同样停在 AUDIT_SUCCESS，但它等的是渠道回调，不是人工打款。
        // 只认原支付渠道是 offline_pay 的单——否则一笔微信退款会被登记成"线下已打款"，钱退两次。
        val originTrade = trades.findById(r.tradeId)
        if (originTrade?.payType != "offline_pay") {
            throw BizException(
                ErrorCode.BIZ,
                "本单不是线下转账退款，无需人工登记：渠道 ${originTrade?.payType ?: "(未知支付单)"} 的退款由渠道回执推进",
            )
        }
        val payee = form.payeeName?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "payee_name 不能为空", mapOf("payee_name" to listOf("不能为空")))
        val account = form.bankAccount?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "bank_account 不能为空", mapOf("bank_account" to listOf("不能为空")))
        val a = aftersales.findByBn(r.aftersalesBn, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "售后单不存在")
        val now = TimeSec.now()

        db.transactional {
            val bank = AftersalesRefundDO().apply {
                refundBn = bn
                this.companyId = companyId
                payeeName = payee
                bankName = form.bankName?.takeIf { it.isNotBlank() }
                bankAccount = account
                bankBranch = form.bankBranch?.takeIf { it.isNotBlank() }
                bankProvince = form.bankProvince?.takeIf { it.isNotBlank() }
                bankCity = form.bankCity?.takeIf { it.isNotBlank() }
                memo = form.memo?.takeIf { it.isNotBlank() } ?: "线下打款登记"
            }
            if (refunds.fillOfflineBank(bank) == 0) {
                throw BizException(ErrorCode.BIZ, "退款单状态已变更，请刷新后重试")
            }
            finalizeSuccess(a, bn, r.refundFee, r.refundPoint, "OFFLINE$bn", now,
                memo = "线下打款完成", refundMemo = null)
            log(a.orderId, "seller", "refund_offline", "退款单 $bn 线下打款登记：$payee / $account")
            true
        }
        val done = refunds.findByBn(bn, companyId) ?: r
        return row(done)
    }

    // ===================== PAY-5 渠道退款回执（docs/05 §M1-2） =====================

    /**
     * 真实渠道异步退款的收尾：`PROCESSING` 的单由 `REFUND.SUCCESS` 回执推进到 `SUCCESS`。
     *
     * 三条口径：
     *  1. **回执只认渠道验签后的报文**（调用方是 PayService.notifyV3，验签不过根本到不了这里）；
     *  2. **幂等靠同一张 CAS**：从 `AUDIT_SUCCESS` 出发推 `SUCCESS`，重复回执 0 行 → 报"状态已变更"，
     *     整体回滚，所以佣金不会二次冲销、订单 pay_status 不会二次重算；
     *  3. **额度不在这里动**（AF-A4 已占），回执失败（ABNORMAL/CLOSED）只把退款单标 `CHANGE` 留痕、
     *     **额度保持占用**——钱在渠道侧已经挂账，回收额度等于允许买家重复申请，宁可少退不可多退；
     *     微信侧的异常退款 API 属 M2，这里交人工跟进。
     */
    fun confirmByCallback(
        companyId: Long,
        outRefundNo: String,
        refundId: String?,
        succeeded: Boolean,
    ): Map<String, Any?> {
        val bn = outRefundNo.toLongOrNull()
            ?: return linkedMapOf("settled" to false, "message" to "退款单号非法：$outRefundNo")
        val r = refunds.findByBn(bn, companyId)
            ?: return linkedMapOf("settled" to false, "message" to "退款单不存在：$bn")
        if (r.refundStatus == RefundStatus.SUCCESS) {
            return linkedMapOf("settled" to true, "idempotent" to true, "refund_bn" to bn.toString())
        }
        if (r.refundStatus != RefundStatus.AUDIT_SUCCESS) {
            return linkedMapOf(
                "settled" to false,
                "refund_bn" to bn.toString(),
                "message" to "退款单不在待回执状态：${statusMsg(r.refundStatus)}",
            )
        }
        val a = aftersales.findByBn(r.aftersalesBn, companyId)
            ?: return linkedMapOf("settled" to false, "message" to "售后单不存在：${r.aftersalesBn}")
        val now = TimeSec.now()

        if (!succeeded) {
            db.transactional {
                val p = AftersalesRefundMapper.RefundTransitParam().apply {
                    this.bn = bn
                    this.companyId = r.companyId
                    fromStatus = RefundStatus.AUDIT_SUCCESS
                    toStatus = RefundStatus.CHANGE
                    this.refundId = refundId
                    memo = "渠道回执退款失败（$refundId），额度仍占用，需人工用异常退款处理"
                    updated = now
                }
                refunds.transit(p)
                transitAftersales(a, AftersalesStatus.PROCESSING, AftersalesProgress.REFUND_PROCESSING, now,
                    memo = "渠道回执退款失败，需人工处理")
                log(a.orderId, "system", "refund_change", "退款单 $bn 渠道回执失败：退款号 $refundId")
                true
            }
            return linkedMapOf(
                "settled" to false,
                "refund_bn" to bn.toString(),
                "refund_status" to RefundStatus.CHANGE,
                "message" to "渠道回执退款失败，需人工处理",
            )
        }

        val o = orders.findById(a.orderId)
            ?: return linkedMapOf("settled" to false, "message" to "订单不存在：${a.orderId}")
        val itemRow = orderItems.listByOrder(a.orderId).firstOrNull { it.itemId == a.itemId }
            ?: return linkedMapOf("settled" to false, "message" to "订单明细不存在")
        db.transactional {
            finalizeSuccess(a, bn, r.refundFee, r.refundPoint, refundId ?: r.refundId, now,
                memo = "渠道退款回执：退款已原路返回")
            reverseCommission(o, itemRow, r.refundFee, r.refundPoint, now)
            log(a.orderId, "system", "refund_success",
                "退款单 $bn 收到渠道退款回执：现金 ${r.refundFee} 分 / 积分 ${r.refundPoint} 分")
            true
        }
        return linkedMapOf(
            "settled" to true,
            "refund_bn" to bn.toString(),
            "refund_status" to RefundStatus.SUCCESS,
            "refunded_fee" to r.refundFee,
            "refunded_point" to r.refundPoint,
        )
    }

    // ===================== AF-A8 / AF-A9 查询 =====================

    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "refundBn" to raw["refund_bn"]?.toLongOrNull(),
            "orderId" to raw["order_id"]?.takeIf { it.isNotBlank() },
            "aftersalesBn" to raw["aftersales_bn"]?.toLongOrNull(),
            "status" to raw["refund_status"]?.takeIf { it.isNotBlank() },
            "type" to raw["refund_type"]?.takeIf { it.isNotBlank() },
            "from" to raw["start_time"]?.toLongOrNull(),
            "to" to raw["end_time"]?.toLongOrNull(),
        )
        return PageHelper.paginate(
            pq,
            count = { refunds.count(f) },
            query = { offset, limit -> refunds.page(f, offset, limit) },
            map = { rows -> rows.map { row(it) } },
        )
    }

    /** AF-A9：详情额外给"本支付单还能退多少"，守恒口径直接可见 */
    fun detail(refundBn: Long, companyId: Long): Map<String, Any?> {
        val r = refunds.findByBn(refundBn, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "退款单不存在")
        val trade = trades.findById(r.tradeId)
        val sum = refunds.sumRefunded(r.orderId, r.companyId)
        return linkedMapOf<String, Any?>().apply {
            putAll(row(r))
            put("trade_can_refund", ((trade?.payFee ?: 0L) - (trade?.refundedFee ?: 0L)).coerceAtLeast(0L))
            put("order_refunded_fee", sum?.refundedFee ?: 0L)
            put("order_refunded_point", sum?.refundedPoint ?: 0L)
            put("aftersales", linkedMapOf<String, Any?>(
                "aftersales_bn" to r.aftersalesBn.toString(),
                "aftersales_status" to (aftersales.findByBn(r.aftersalesBn, companyId)?.aftersalesStatus ?: -1),
                "progress" to (aftersales.findByBn(r.aftersalesBn, companyId)?.progress ?: -1),
            ))
        }
    }

    // ===================== 内部执行件 =====================

    /**
     * 同意退款的执行体（一个事务内）：退款单转审核通过 → 两道额度闸门 → 渠道原路退 → 按结果落终态。
     * @throws ChannelFailure 渠道当场失败，调用方据此回滚并留 CHANGE
     */
    private fun execute(
        a: AftersalesDO,
        r: AftersalesRefundDO,
        o: NormalOrderDO,
        trade: TradeDO,
        itemRow: OrderItemDO,
        fee: Long,
        point: Long,
        memoText: String?,
        now: Long,
    ): Map<String, Any?> {
        val p = AftersalesRefundMapper.RefundTransitParam().apply {
            bn = r.refundBn
            companyId = r.companyId
            fromStatus = RefundStatus.READY
            toStatus = RefundStatus.AUDIT_SUCCESS
            refundTime = now
            if (!memoText.isNullOrBlank()) memo = memoText
            updated = now
        }
        if (refunds.transit(p) == 0) throw BizException(ErrorCode.BIZ, "退款单状态已变更，请刷新后重试")

        // 闸门一：整单实付。0 行 = 这笔试图退超 pay_fee（并发/多次部分退款都靠它兜底）
        if (trades.addRefundedFee(trade.tradeId, fee, now) == 0) {
            throw BizException(
                ErrorCode.BIZ, "超出本单剩余可退额",
                mapOf("refund_fee" to listOf("实付 ${trade.payFee} 分，已退 ${trade.refundedFee} 分，还能退 ${(trade.payFee - trade.refundedFee).coerceAtLeast(0L)} 分")),
            )
        }
        // 闸门二：订单行货款。积分单 fee=0 天然通过，行状态仍写 REFUND_SUCCESS
        if (orderItems.addRefundedFee(itemRow.id, fee, ItemAftersalesStatus.REFUND_SUCCESS, now) == 0) {
            throw BizException(
                ErrorCode.BIZ, "超出本行剩余可退额",
                mapOf("refund_fee" to listOf("行货款 ${itemRow.totalFee} 分，已退 ${itemRow.refundedFee} 分")),
            )
        }

        val outcome = registry.get(trade.payType ?: o.payType).refund(RefundRequest(o, trade, r.refundBn, fee, point))
        return when {
            outcome.settled -> {
                finalizeSuccess(a, r.refundBn, fee, point, outcome.refundId, now, "退款已原路返回")
                reverseCommission(o, itemRow, fee, point, now)
                log(o.orderId, "seller", "refund_success", "退款单 ${r.refundBn} 退款成功：现金 $fee 分 / 积分 $point 分")
                linkedMapOf(
                    "refund_bn" to r.refundBn.toString(),
                    "refund_status" to RefundStatus.SUCCESS,
                    "settled" to true,
                    "refund_id" to outcome.refundId,
                    "refunded_fee" to fee,
                    "refunded_point" to point,
                )
            }
            outcome.pendingCallback -> {
                // 真实微信退款常见的中间态：渠道已受理、钱还没到，退款单原地停在 AUDIT_SUCCESS。
                // 与 deferred 的差别只有一条：**推进者是渠道回执（PAY-5 REFUND.SUCCESS），不是 AF-A10 的人工登记**，
                // 所以这里把 channel 留在 original，offline() 据此拒绝人工打款登记（防止双退）。
                val t = AftersalesRefundMapper.RefundTransitParam().apply {
                    bn = r.refundBn
                    companyId = r.companyId
                    fromStatus = RefundStatus.AUDIT_SUCCESS
                    toStatus = RefundStatus.AUDIT_SUCCESS
                    channel = RefundChannel.ORIGINAL
                    refundId = outcome.refundId
                    memo = outcome.message ?: "渠道已受理，等待退款回执"
                    updated = now
                }
                refunds.transit(t)
                transitAftersales(a, AftersalesStatus.PROCESSING, AftersalesProgress.REFUND_PROCESSING, now,
                    memo = outcome.message ?: "渠道已受理，等待退款回执")
                log(o.orderId, "seller", "refund_pending", "退款单 ${r.refundBn} 渠道已受理：${outcome.message}")
                linkedMapOf(
                    "refund_bn" to r.refundBn.toString(),
                    "refund_status" to RefundStatus.AUDIT_SUCCESS,
                    "settled" to false,
                    "deferred" to true,
                    "pending_callback" to true,
                    "refund_id" to outcome.refundId,
                    "message" to (outcome.message ?: "渠道已受理，等待退款回执"),
                )
            }
            outcome.deferred -> {
                // 线下转账：钱要走柜台，退款单停在 AUDIT_SUCCESS 等 AF-A10 登记
                val t = AftersalesRefundMapper.RefundTransitParam().apply {
                    bn = r.refundBn
                    companyId = r.companyId
                    fromStatus = RefundStatus.AUDIT_SUCCESS
                    toStatus = RefundStatus.AUDIT_SUCCESS
                    channel = RefundChannel.OFFLINE
                    memo = outcome.message ?: "待人工打款"
                    updated = now
                }
                refunds.transit(t)
                transitAftersales(a, AftersalesStatus.PROCESSING, AftersalesProgress.REFUND_PROCESSING, now,
                    memo = outcome.message ?: "线下打款待登记")
                log(o.orderId, "seller", "refund_pending", "退款单 ${r.refundBn} 需人工打款：${outcome.message}")
                linkedMapOf(
                    "refund_bn" to r.refundBn.toString(),
                    "refund_status" to RefundStatus.AUDIT_SUCCESS,
                    "settled" to false,
                    "deferred" to true,
                    "message" to (outcome.message ?: "需人工打款"),
                )
            }
            else -> throw ChannelFailure(outcome.message ?: "渠道退款失败")
        }
    }

    /** 拒绝退款（AF-A4 check_refund=0）：售后终态 3/5，退款单 REFUSE，件数镜像重算 */
    private fun reject(a: AftersalesDO, r: AftersalesRefundDO, memo: String, now: Long): Map<String, Any?> {
        db.transactional {
            val p = AftersalesRefundMapper.RefundTransitParam().apply {
                bn = r.refundBn
                companyId = r.companyId
                fromStatus = RefundStatus.READY
                toStatus = RefundStatus.REFUSE
                this.memo = memo
                updated = now
            }
            if (refunds.transit(p) == 0) throw BizException(ErrorCode.BIZ, "退款单状态已变更，请刷新后重试")
            transitAftersales(a, AftersalesStatus.REFUSED, AftersalesProgress.REFUND_REFUSED, now,
                refuseReason = memo, memo = memo)
            orderItems.listByOrder(a.orderId).firstOrNull { it.itemId == a.itemId }?.let {
                orderItems.setAftersalesStatus(it.id, ItemAftersalesStatus.SELLER_REFUSE_BUYER, now)
            }
            recomputeLeftNum(a.orderId, a.companyId, now)
            log(a.orderId, "seller", "refund_refuse", "退款单 ${r.refundBn} 被拒绝：$memo")
            true
        }
        return linkedMapOf(
            "refund_bn" to r.refundBn.toString(),
            "aftersales_bn" to a.aftersalesBn.toString(),
            "refund_status" to RefundStatus.REFUSE,
            "aftersales_status" to AftersalesStatus.REFUSED,
            "progress" to AftersalesProgress.REFUND_REFUSED,
        )
    }

    /**
     * 退款到账后的收尾：退款单 AUDIT_SUCCESS→SUCCESS（带 refunded_*）、售后终态 2/4、
     * 订单支付态按金额守恒重算（见 [syncPayStatus]）。两个调用点（渠道即时成功 / 线下登记）共用，
     * CAS 从 AUDIT_SUCCESS 出发，重复调用得到 0 行而不会二次推进。
     * @param refundMemo 退款单备注；传 null 表示**保留原备注**（AF-A10 已把银行登记原文写在上面）
     */
    private fun finalizeSuccess(
        a: AftersalesDO,
        refundBn: Long,
        fee: Long,
        point: Long,
        refundId: String?,
        now: Long,
        memo: String?,
        refundMemo: String? = memo,
    ) {
        val p = AftersalesRefundMapper.RefundTransitParam().apply {
            bn = refundBn
            companyId = a.companyId
            fromStatus = RefundStatus.AUDIT_SUCCESS
            toStatus = RefundStatus.SUCCESS
            refundedFee = fee
            refundedPoint = point
            this.refundId = refundId
            refundSuccessTime = now
            if (refundMemo != null) this.memo = refundMemo
            updated = now
        }
        if (refunds.transit(p) == 0) throw BizException(ErrorCode.BIZ, "退款单状态已变更，请勿重复退款")
        // 终态 4=已处理：源系统另有 6=退款完成，是"退款到账但售后未结案"的展示中间态；
        // 本项目退款即结案，直接写 4，6 只在字典里保留（docs/04 §11.4）
        transitAftersales(a, AftersalesStatus.DONE, AftersalesProgress.FINISHED, now, memo = memo)
        syncPayStatus(a.orderId, now)
    }

    /** 售后主表 CAS + 明细双写（与 AftersalesService 同一套口径） */
    private fun transitAftersales(
        a: AftersalesDO,
        toStatus: Int,
        toProgress: Int,
        now: Long,
        refuseReason: String? = null,
        memo: String? = null,
    ) {
        val p = AftersalesMapper.TransitParam().apply {
            bn = a.aftersalesBn
            companyId = a.companyId
            fromStatus = a.aftersalesStatus
            fromProgress = a.progress
            this.toStatus = toStatus
            this.toProgress = toProgress
            this.refuseReason = refuseReason
            this.memo = memo
            updated = now
        }
        if (aftersales.transit(p) == 0) throw BizException(ErrorCode.BIZ, "售后单状态已变更，请刷新后重试")
        details.transit(a.aftersalesBn, a.aftersalesStatus, a.progress, toStatus, toProgress, now)
    }

    /**
     * 订单支付态回写：**按金额守恒判定**，不是按行状态。
     * 部分退款时订单行的 `aftersales_status` 已经是 REFUND_SUCCESS（行级状态只描述"最近一次售后"），
     * 用它判"全退完"会把部分退款误判成整单退完，进而让后续申请被"订单未支付"挡掉（M1-1 门槛用例就断在这）。
     * 所以这里比对「已成功退的现金 + 积分」与订单实付；只在已付款/已部分退的单据上写。
     */
    private fun syncPayStatus(orderId: Long, now: Long) {
        val o = orders.findById(orderId) ?: return
        if (o.payStatus == PayStatus.NOTPAY) return
        val sum = refunds.sumRefundedSuccess(orderId.toString(), o.companyId)
        val refunded = (sum?.refundedFee ?: 0L) + (sum?.refundedPoint ?: 0L)
        val allRefunded = o.payFee > 0L && refunded >= o.payFee
        val patch = TransitPatch().apply { payStatus = if (allRefunded) PayStatus.REFUND else PayStatus.REFUND_PART }
        orders.transit(orderId, patch, null, o.payStatus, null, now)
    }

    /**
     * 佣金/返利按退款占比冲销（docs/05 M1-1「佣金冲销事件」）：
     * 以**订单行**的成交额为分母，避免用整单金额摊到别家货上；GREATEST 由 SQL 兜住不为负。
     */
    private fun reverseCommission(o: NormalOrderDO, itemRow: OrderItemDO, fee: Long, point: Long, now: Long) {
        val amount = fee + point
        if (amount <= 0L || itemRow.totalFee <= 0L) return
        val commission = itemRow.commissionFee * amount / itemRow.totalFee
        val rebate = itemRow.totalRebate * amount / itemRow.totalFee
        if (commission > 0L || rebate > 0L) orders.reverseCommission(o.orderId, commission, rebate, now)
    }

    /** 件数镜像重算：总件数 - 仍占额度的申请件数（退款拒绝/撤销后把额度还回去） */
    private fun recomputeLeftNum(orderId: Long, companyId: Long, now: Long) {
        val o = orders.findById(orderId) ?: return
        val total = orderItems.listByOrder(orderId).sumOf { it.num }.toInt()
        val occupied = aftersales.occupiedNum(companyId, orderId).toInt()
        val to = (total - occupied).coerceAtLeast(0)
        if (o.leftAftersalesNum == to) return
        if (orders.casLeftAftersalesNum(orderId, o.leftAftersalesNum, to, now) == 0 && o.leftAftersalesNum == 0) {
            orders.backfillLeftAftersalesNum(orderId, to, now)
        }
    }

    private fun log(orderId: Long, role: String, behavior: String, text: String) {
        orderLogs.insert(
            OrderOperateLogDO().apply {
                this.orderId = orderId
                operator = role
                operatorRole = role
                this.behavior = behavior
                logText = text
            },
        )
    }

    private fun statusMsg(s: String?): String = when (s) {
        RefundStatus.READY -> "待财务确认"
        RefundStatus.AUDIT_SUCCESS -> "审核通过，待打款"
        RefundStatus.SUCCESS -> "已退款"
        RefundStatus.REFUSE -> "已拒绝退款"
        RefundStatus.CANCEL -> "已作废"
        RefundStatus.CHANGE -> "渠道退款失败，待人工处理"
        else -> s ?: "-"
    }

    /** 渠道当场失败：靠它把额度占用整体回滚，再由调用方单独留 CHANGE */
    private class ChannelFailure(message: String?) : RuntimeException(message)

    companion object {
        private const val OFFLINE_PAY = "offline_pay"
    }
}
