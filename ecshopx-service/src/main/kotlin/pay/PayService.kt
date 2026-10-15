package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.pay.DepositMapper
import com.ecshopx.kotlin.dao.pay.DepositTradeDO
import com.ecshopx.kotlin.dao.pay.PointMapper
import com.ecshopx.kotlin.dao.pay.PointLogDO
import com.ecshopx.kotlin.dao.pay.TradeDO
import com.ecshopx.kotlin.dao.pay.TradeMapper
import com.ecshopx.kotlin.dao.pay.TradeState
import com.ecshopx.kotlin.service.order.OrderFlowService
import com.ecshopx.kotlin.service.order.OrderQueryService
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * M0-7 支付闭环服务（PAY-1..5 + ORDER-3 的预支付部分）。
 *
 * 三条硬口径：
 *  1. **应付只认订单表**：`trade.total_fee` 取自 `orders_normal_orders.total_fee`，回调里的金额只做**校验**，不参与计算；
 *  2. **结清幂等**：`trade` 的 NOTPAY→SUCCESS 是 CAS，只有抢到那一行的调用才会触发订单结清与积分入账，
 *     渠道重复通知（PAY-5）与并发双击支付（PAY-2）都只结清一次；
 *  3. **动账与状态同事务**：扣积分/扣余额、写支付单、迁移订单状态、入账留痕在一个 `db.transactional` 里，
 *     任一步抛错整单回滚（余额不会被吃掉而订单仍待付款）。
 */
@Service
class PayService(
    private val orders: OrdersMapper,
    private val trades: TradeMapper,
    private val points: PointMapper,
    private val deposits: DepositMapper,
    private val flow: OrderFlowService,
    private val query: OrderQueryService,
    private val registry: PayChannelRegistry,
    private val seq: SeqGenerator,
    private val db: Db,
    private val mapper: ObjectMapper,
) {

    /** PAY-1 取既有订单的支付参数（前端进支付页先拉一次） */
    fun config(orderId: String, userId: Long, companyId: Long): Map<String, Any?> {
        val o = loadOrder(orderId, userId)
        val acc = deposits.find(companyId, userId)
        val pt = points.find(companyId, userId)
        val last = trades.listByOrder(o.orderId.toString()).firstOrNull()
        return linkedMapOf(
            "order_id" to o.orderId.toString(),
            "order_type" to o.orderType,
            "title" to o.title,
            "total_fee" to o.totalFee,
            "pay_fee" to o.payFee,
            "order_status" to o.orderStatus,
            "pay_status" to o.payStatus,
            "trade_state" to (last?.tradeState ?: TradeState.NOTPAY),
            "trade_id" to last?.tradeId,
            "pay_types" to registry.supported,
            "deposit" to linkedMapOf(
                "deposit_amount" to (acc?.depositAmount ?: 0L),
                "gift_amount" to (acc?.giftAmount ?: 0L),
                "balance" to ((acc?.depositAmount ?: 0L) + (acc?.giftAmount ?: 0L)),
            ),
            "point" to linkedMapOf(
                "point" to (pt?.point ?: 0L),
                "can_pay" to ((pt?.point ?: 0L) >= PointRate.pointOf(o.totalFee)),
            ),
            "auto_cancel_time" to o.autoCancelTime,
            "receipt_type" to o.receiptType,
            "shop_id" to o.shopId,
            "distributor_id" to o.distributorId,
        )
    }

    /**
     * PAY-2 支付订单。`order_id` 以 `CZ` 开头视为储值充值单（PAY-4 产生的那笔），
     * 其余走订单支付：point/deposit 即时结清，wxpay 出参数等回调，offline_pay 落凭证。
     */
    fun pay(userId: Long, companyId: Long, form: PayForm): Map<String, Any?> {
        val orderId = form.orderId?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "order_id 不能为空", mapOf("order_id" to listOf("缺少订单号")))
        if (orderId.startsWith(RECHARGE_PREFIX)) return settleRecharge(userId, companyId, orderId)

        val channel = registry.get(form.payType)
        val o = loadOrder(orderId, userId)
        if (o.payStatus == "PAYED" || o.orderStatus != com.ecshopx.kotlin.common.domain.OrderStatus.NOTPAY) {
            throw BizException(ErrorCode.BIZ, if (o.payStatus == "PAYED") "订单已支付" else "订单当前状态不可支付")
        }
        val trade = ensureTrade(o, channel.payType)
        val outcome = db.transactional {
            val out = channel.pay(o, trade)
            if (out.settled) {
                if (trades.settle(trade.tradeId, out.payFee, channel.payType, out.transactionId, TimeSec.now()) == 0) {
                    throw BizException(ErrorCode.BIZ, "支付单已结清，请勿重复支付")
                }
                if (!flow.markPaid(o.orderId, channel.payType, channel.payType, payFee = out.payFee)) {
                    throw BizException(ErrorCode.BIZ, "订单状态已变更，请刷新后重试")
                }
                accruePoints(o)
            }
            out
        }
        val detail = query.detail(o.orderId, userId)
        @Suppress("UNCHECKED_CAST")
        return linkedMapOf<String, Any?>(
            "order_id" to o.orderId.toString(),
            "trade_id" to trade.tradeId,
            "trade_state" to (if (outcome.settled) TradeState.SUCCESS else TradeState.NOTPAY),
            "pay_type" to channel.payType,
            "total_fee" to o.totalFee,
            "pay_fee" to if (outcome.settled) outcome.payFee else o.totalFee,
            "trade_info" to linkedMapOf("order_id" to o.orderId.toString(), "trade_id" to trade.tradeId),
            "order_created" to true,
        ).apply {
            putAll(outcome.params)
            putAll(detail)
        }
    }

    /** PAY-3 轮询支付结果：只读 trade，前端据此决定跳成功页还是继续等回调 */
    fun tradeState(tradeId: String, userId: Long, companyId: Long): Map<String, Any?> {
        if (tradeId.startsWith(RECHARGE_PREFIX)) {
            val t = deposits.findTrade(tradeId) ?: throw BizException(ErrorCode.NOT_FOUND, "充值单不存在")
            if (t.userId != userId || t.companyId != companyId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
            return linkedMapOf(
                "trade_id" to t.tradeId, "order_id" to t.tradeId,
                "trade_state" to t.status, "pay_fee" to t.amount, "pay_type" to "deposit",
                // 充值单没有渠道流水号，键位仍给出以保持 PAY-3 出参形状一致
                "transaction_id" to "",
            )
        }
        val t = trades.findById(tradeId) ?: throw BizException(ErrorCode.NOT_FOUND, "支付单不存在")
        if (t.userId.toLongOrNull() != userId || t.companyId != companyId) {
            throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        }
        return linkedMapOf(
            "trade_id" to t.tradeId, "order_id" to t.orderId, "trade_state" to t.tradeState,
            "pay_fee" to t.payFee, "pay_type" to t.payType, "transaction_id" to t.transactionId,
        )
    }

    /** PAY-4 储值充值下单：只生成 CZ 单与档位赠金，真正到账在 PAY-2（order_id=CZ…）里完成 */
    fun recharge(userId: Long, companyId: Long, form: RechargeForm): Map<String, Any?> {
        val amount = form.amount ?: 0L
        if (amount <= 0) throw BizException(ErrorCode.VALIDATION, "充值金额必须大于 0", mapOf("amount" to listOf("金额非法")))
        val rule = deposits.listRules(companyId).firstOrNull { it.rechargeAmount == amount }
        val gift = rule?.giftAmount ?: 0L
        val acc = deposits.find(companyId, userId)
        val before = (acc?.depositAmount ?: 0L) + (acc?.giftAmount ?: 0L)
        val tradeId = seq.nextBn(SeqName.RECHARGE_NO)
        db.transactional {
            deposits.insertTrade(DepositTradeDO().apply {
                this.tradeId = tradeId
                this.companyId = companyId
                this.userId = userId
                tradeType = "recharge"
                relId = tradeId
                this.amount = amount
                giftAmount = gift
                beforeBalance = before
                afterBalance = before + amount + gift
                status = TradeState.NOTPAY
                created = TimeSec.now()
                updated = TimeSec.now()
            })
        }
        return linkedMapOf(
            "order_id" to tradeId, "trade_id" to tradeId, "amount" to amount, "gift_amount" to gift,
            "rule_name" to rule?.ruleName, "trade_state" to TradeState.NOTPAY,
            "pay_types" to listOf("wxpay", "deposit"),
        )
    }

    /** 充值单结清：金额进本金、档位进赠金，同时把 deposit_trade 状态 CAS 成 SUCCESS */
    private fun settleRecharge(userId: Long, companyId: Long, tradeId: String): Map<String, Any?> {
        val t = deposits.findTrade(tradeId) ?: throw BizException(ErrorCode.NOT_FOUND, "充值单不存在")
        if (t.userId != userId || t.companyId != companyId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        if (t.status == TradeState.SUCCESS) throw BizException(ErrorCode.BIZ, "充值单已结清")
        val acc = deposits.find(companyId, userId) ?: run {
            deposits.insertEmpty(companyId, userId, TimeSec.now())
            deposits.find(companyId, userId)!!
        }
        db.transactional {
            if (deposits.updateTradeStatus(tradeId, TradeState.SUCCESS, TimeSec.now()) == 0) {
                throw BizException(ErrorCode.BIZ, "充值单状态已变更")
            }
            deposits.recharge(acc.id, t.amount, t.giftAmount, TimeSec.now())
        }
        val after = deposits.find(companyId, userId)!!
        return linkedMapOf(
            "order_id" to tradeId, "trade_id" to tradeId, "trade_state" to TradeState.SUCCESS,
            "amount" to t.amount, "gift_amount" to t.giftAmount, "pay_type" to "recharge",
            "deposit" to linkedMapOf(
                "deposit_amount" to after.depositAmount, "gift_amount" to after.giftAmount,
                "balance" to after.depositAmount + after.giftAmount,
            ),
        )
    }

    /**
     * ORDER-3 创建订单 + 预支付：下单落库后直接按渠道出参数（与 PAY-2 同一套渠道实现）。
     * 复用 M0-6 的 `OrderCreateService` 由 Web 层先建单再调本方法，避免两处拼参数。
     */
    fun prepay(userId: Long, companyId: Long, orderId: Long, payType: String?): Map<String, Any?> =
        pay(userId, companyId, PayForm(orderId = orderId.toString(), payType = payType))

    /**
     * PAY-5 渠道异步回调（wxpay mock）。金额只做校验、结清走 CAS，重复通知一律回成功应答。
     * 返回应答体原文（非 data 包络），Web 层直接吐 XML。
     */
    fun notify(params: Map<String, String>): NotifyResult {
        val tradeId = params["out_trade_no"] ?: params["trade_id"]
            ?: return NotifyResult(false, "缺少 out_trade_no")
        val t = trades.findById(tradeId) ?: return NotifyResult(false, "支付单不存在")
        if (t.tradeState == TradeState.SUCCESS) return NotifyResult(true, "OK（已结清，幂等应答）")
        if (t.tradeState == TradeState.CLOSED) {
            // 订单在回调前已关闭：M0 不自动退款（退款/售后属 M1），但应答成功以免渠道无限重试
            return NotifyResult(true, "OK（支付单已关闭，款项需人工退回）")
        }
        val amount = (params["total_fee"] ?: params["amount"])?.toLongOrNull()
            ?: return NotifyResult(false, "金额缺失")
        if (amount != t.totalFee) return NotifyResult(false, "金额不符：期望 ${t.totalFee} 分，收到 $amount 分")
        val state = params["trade_state"] ?: "SUCCESS"
        if (!state.equals("SUCCESS", ignoreCase = true)) return NotifyResult(false, "渠道回报未成功：$state")

        val orderId = t.orderId.toLongOrNull() ?: return NotifyResult(false, "订单号非法")
        val o = orders.findById(orderId) ?: return NotifyResult(false, "订单不存在")
        val transactionId = params["transaction_id"] ?: "MOCK${TimeSec.now()}"
        return try {
            db.transactional {
                if (trades.settle(tradeId, t.totalFee, t.payType ?: "wxpay", transactionId, TimeSec.now()) == 0) {
                    return@transactional NotifyResult(true, "OK（并发已结清）")
                }
                if (!flow.markPaid(orderId, t.payType ?: "wxpay", params["channel"] ?: "mock",
                        logText = "渠道回调结清（${params["channel"] ?: "mock"}），实付 ${t.totalFee} 分", payFee = t.totalFee)) {
                    throw BizException(ErrorCode.BIZ, "订单状态已变更，回调稍后重试")
                }
                accruePoints(o)
                trades.saveResponse(tradeId, mapper.writeValueAsString(params), TimeSec.now())
                NotifyResult(true, "OK")
            } ?: NotifyResult(false, "内部错误")
        } catch (e: BizException) {
            NotifyResult(false, e.message ?: "结清失败")
        }
    }

    class NotifyResult(val success: Boolean, val message: String)

    /** 复用同一订单的未付支付单，换渠道时也只保留一笔 NOTPAY（历史笔由 closeByOrder 收掉） */
    private fun ensureTrade(o: com.ecshopx.kotlin.dao.order.NormalOrderDO, payType: String): TradeDO {
        val orderId = o.orderId.toString()
        trades.findActiveByOrder(orderId)?.let { exist ->
            if (exist.payType != null && exist.payType != payType) trades.closeByOrder(orderId, TimeSec.now())
            else return exist
        }
        val t = TradeDO().apply {
            tradeId = seq.nextBn(SeqName.TRADE_NO)
            this.orderId = o.orderId.toString()
            companyId = o.companyId
            userId = o.userId.toString()
            shopId = o.shopId
            distributorId = o.distributorId
            totalFee = o.totalFee
            discountFee = o.discountFee
            this.payType = payType
            body = o.title ?: "订单${this.orderId}"
            timeStart = TimeSec.now().toString()
            created = TimeSec.now()
            updated = TimeSec.now()
        }
        trades.insert(t)
        return t
    }

    /** 支付成功才入账：赠送分（get_points）进积分账户并留痕，0 分不入账 */
    private fun accruePoints(o: com.ecshopx.kotlin.dao.order.NormalOrderDO) {
        val gain = o.getPoints
        if (gain <= 0L) return
        val acc = points.find(o.companyId, o.userId) ?: run {
            points.insertEmpty(o.companyId, o.userId, TimeSec.now())
            points.find(o.companyId, o.userId)!!
        }
        if (points.add(o.companyId, o.userId, gain, TimeSec.now()) == 0) {
            throw BizException(ErrorCode.BIZ, "积分入账失败，请重试")
        }
        points.insertLog(PointLogDO().apply {
            companyId = o.companyId
            userId = o.userId
            orderId = o.orderId.toString()
            changePoint = gain
            beforePoint = acc.point
            afterPoint = acc.point + gain
            remark = "订单支付赠送积分"
            created = TimeSec.now()
        })
    }

    private fun loadOrder(orderId: String, userId: Long): com.ecshopx.kotlin.dao.order.NormalOrderDO {
        val id = orderId.toLongOrNull() ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        val o = orders.findById(id) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.userId != userId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        return o
    }

    companion object {
        const val RECHARGE_PREFIX = "CZ"
    }
}

/** PAY-2 入参（member_card_code/coupon_code 是券卡活动的开关位，M0 不产生金额，与 C25 同口径） */
@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PayForm(
    val orderId: String? = null,
    val payType: String? = null,
    val orderType: String? = null,
    val memberCardCode: String? = null,
    val couponCode: String? = null,
    val returnUrl: String? = null,
)

/** PAY-4 入参 */
@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RechargeForm(
    @JsonProperty("amount") val amount: Long? = null,
    val payType: String? = null,
)
