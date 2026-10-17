package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.pay.DepositMapper
import com.ecshopx.kotlin.dao.pay.DepositTradeDO
import com.ecshopx.kotlin.dao.pay.OfflinePaymentDO
import com.ecshopx.kotlin.dao.pay.OfflinePaymentMapper
import com.ecshopx.kotlin.dao.pay.PointLogDO
import com.ecshopx.kotlin.dao.pay.PointMapper
import com.ecshopx.kotlin.dao.pay.TradeDO
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * 支付渠道抽象（docs/05 §M0-7）。
 *
 * 一个渠道只做两件事：**动账**（余额/积分，内部 CAS，失败即抛）或**产出渠道参数包**（外部渠道），
 * 结清订单、写支付单状态、积分入账、留痕都归 PayService，渠道不得触碰订单表。
 *
 * `settled=true` 表示本次调用即完成收款（point/deposit 这类本地渠道），
 * `settled=false` 表示钱还在渠道侧路上（wxpay mock 等异步回调、offline_pay 等后台审核）。
 */
class PayOutcome(
    val settled: Boolean,
    val payFee: Long,
    val params: Map<String, Any?>,
    val transactionId: String? = null,
)

/**
 * 退款请求（M1-1）。渠道只负责"把钱/积分/余额退回原池子"，
 * 累计 refunded_fee、状态迁移、佣金冲销都在 RefundService 里，渠道不得碰售后表与订单表。
 */
class RefundRequest(
    val order: NormalOrderDO,
    val trade: TradeDO,
    val refundBn: Long,
    /** 现金退款额（分）；积分单为 0 */
    val fee: Long,
    /** 积分退款额（分等值）；只有 pay_type=point 的单才非 0 */
    val point: Long,
)

/**
 * 退款结果。三态而不是布尔：
 *  - `settled` 渠道当场退成功；
 *  - `deferred` 渠道退不了但也不算失败（线下转账要人工打款），退款单停在 AUDIT_SUCCESS 等登记；
 *  - 两者皆假 = 渠道失败，退款单落 CHANGE 留痕，**不抛错**（源系统一致，避免"售后页面 500 但钱已退"）。
 */
class RefundOutcome(
    val settled: Boolean,
    val refundId: String? = null,
    val deferred: Boolean = false,
    val message: String? = null,
)

interface PayChannel {
    /** 入参 pay_type 取值（docs/04 §2.4：point/deposit/offline_pay/wxpay） */
    val payType: String

    fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome

    /** 原路退（docs/05 §M1-1 "退款执行接 PayChannel.refund"） */
    fun refund(req: RefundRequest): RefundOutcome
}

/** 积分汇率：M0 固定 1 积分 = 1 分（PHP `score_for_money` 的比例换算属 M1 营销配置） */
object PointRate {
    const val POINT_PER_FEN: Long = 1L
    fun pointOf(fen: Long): Long = fen / POINT_PER_FEN
}

/** 假 JSAPI 参数包：形状与 ORDER-3 出参完全一致，前端可直接走既有调起逻辑 */
object MockJsApiParams {
    private val rnd = SecureRandom()

    fun of(trade: TradeDO, appId: String): Map<String, Any?> = mapOf(
        "appId" to appId,
        "timeStamp" to TimeSec.now().toString(),
        "nonceStr" to (0 until 16).joinToString("") { "%02x".format(rnd.nextInt(256)) },
        "package" to "prepay_id=${trade.tradeId}",
        "signType" to "RSA",
        // mock 签名：真实实现是 SHA256withRSA(参数拼接 + 商户密钥)，这里只保证"非空且每次一致于单号"
        "paySign" to "MOCK-" + trade.tradeId.hashCode().toUInt().toString(16),
    )
}

/** wxpay（mock 渠道）：只出参数，等 PAY-5 回调结清 */
@Component
class WxPayMockChannel(private val configs: SysConfigMapper) : PayChannel {
    override val payType = "wxpay"

    override fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome {
        val appId = configs.getValue(order.companyId, "pay.wxpay.appid") ?: "wxmock0000000000"
        return PayOutcome(settled = false, payFee = 0L, params = MockJsApiParams.of(trade, appId))
    }

    /**
     * 原路退（mock）：真实实现是 wxpay refunds API，幂等键 out_refund_no=退款单号。
     * mock 一律当场成功并回一个稳定退款号——同一 refund_bn 重放得到同一个 id，
     * 所以 RefundService 的 CAS 重复执行不会造出两笔渠道退款。
     */
    override fun refund(req: RefundRequest): RefundOutcome =
        RefundOutcome(settled = true, refundId = "MOCKREF${req.refundBn}")
}

/**
 * 积分支付：纯积分兑换结清，需要 `可用积分 >= 应付分数`（1 积分 = 1 分）。
 * `sys_config.point.deduct.max_ratio` 是"积分+现金混合抵扣"的上限，混合支付属 M1，这里不受其约束。
 */
@Component
class PointChannel(private val points: PointMapper) : PayChannel {
    override val payType = "point"

    override fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome {
        val need = PointRate.pointOf(order.totalFee)
        val acc = points.find(order.companyId, order.userId)
            ?: throw BizException(ErrorCode.BIZ, "积分账户不存在", mapOf("point" to listOf("当前会员无积分账户")))
        if (acc.point < need) {
            throw BizException(ErrorCode.BIZ, "积分不足，还需 $need 积分", mapOf("point" to listOf("可用积分 ${acc.point}")))
        }
        if (points.deduct(order.companyId, order.userId, need, TimeSec.now()) == 0) {
            throw BizException(ErrorCode.BIZ, "积分已变动，请重试", mapOf("point" to listOf("并发扣减失败")))
        }
        return PayOutcome(
            settled = true,
            payFee = order.totalFee,
            params = mapOf("point_used" to need, "point_left" to acc.point - need),
            transactionId = "POINT${trade.tradeId}",
        )
    }

    /**
     * 积分原路退：退回余额但**不动 total_point**（累计获得口径），并留一条退款流水。
     * 账户行缺失时补空行再退——积分单能付成功说明账户一定存在，缺失只可能是脏数据，
     * 补行比重载错误更安全（钱已经要退给用户了，不能因为留痕表缺行而退不进去）。
     */
    override fun refund(req: RefundRequest): RefundOutcome {
        val back = if (req.point > 0) req.point else req.fee
        if (back <= 0) return RefundOutcome(settled = true, refundId = "POINTREF${req.refundBn}")
        val acc = points.find(req.order.companyId, req.order.userId) ?: run {
            points.insertEmpty(req.order.companyId, req.order.userId, TimeSec.now())
            points.find(req.order.companyId, req.order.userId)!!
        }
        if (points.refund(req.order.companyId, req.order.userId, back, TimeSec.now()) == 0) {
            return RefundOutcome(settled = false, message = "积分账户动账失败")
        }
        points.insertLog(
            PointLogDO().apply {
                companyId = req.order.companyId
                userId = req.order.userId
                orderId = req.order.orderId.toString()
                changePoint = back
                beforePoint = acc.point
                afterPoint = acc.point + back
                remark = "售后退款回补积分（退款单 ${req.refundBn}）"
                created = TimeSec.now()
            },
        )
        return RefundOutcome(settled = true, refundId = "POINTREF${req.refundBn}")
    }
}

/**
 * 余额（储值）支付：赠金优先、本金兜底，需要 `本金+赠金 >= 应付`。
 * 整行动账走乐观条件更新（swap），0 行 = 期间余额被改，直接报"请重试"。
 */
@Component
class DepositChannel(private val deposits: DepositMapper) : PayChannel {
    override val payType = "deposit"

    override fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome {
        val need = order.totalFee
        val acc = deposits.find(order.companyId, order.userId)
            ?: throw BizException(ErrorCode.BIZ, "余额账户不存在", mapOf("deposit" to listOf("当前会员无余额账户")))
        val balance = acc.depositAmount + acc.giftAmount
        if (balance < need) {
            throw BizException(ErrorCode.BIZ, "余额不足，当前 $balance 分", mapOf("deposit" to listOf("还差 ${need - balance} 分")))
        }
        val useGift = minOf(acc.giftAmount, need)
        val useCash = need - useGift
        if (deposits.swap(acc.id, acc.depositAmount - useCash, acc.giftAmount - useGift,
                acc.depositAmount, acc.giftAmount, TimeSec.now()) == 0) {
            throw BizException(ErrorCode.BIZ, "余额已变动，请重试", mapOf("deposit" to listOf("并发扣减失败")))
        }
        // 消费也要留痕：退款时要按"本金/赠金各扣了多少"原比例回补，没有这一行就只能全退本金（会白送赠金）
        deposits.insertTrade(
            DepositTradeDO().apply {
                // 一笔支付单一条消费留痕，主键天然唯一（重付会先 closeByOrder 再生成新单号）
                tradeId = "C${trade.tradeId}"
                companyId = order.companyId
                userId = order.userId
                tradeType = "consume"
                relId = order.orderId.toString()
                amount = useCash
                giftAmount = useGift
                beforeBalance = balance
                afterBalance = balance - need
                status = "SUCCESS"
                created = TimeSec.now()
                updated = TimeSec.now()
            },
        )
        return PayOutcome(
            settled = true,
            payFee = need,
            params = mapOf("deposit_used" to need, "deposit_left" to balance - need, "gift_used" to useGift),
            transactionId = "DEPOSIT${trade.tradeId}",
        )
    }

    /**
     * 余额原路退：按这笔订单消费时留下的"本金/赠金"比例回补，
     * 找不到消费留痕（历史数据）时全部回本金——宁可让赠金变本金，也不能少退钱。
     */
    override fun refund(req: RefundRequest): RefundOutcome {
        if (req.fee <= 0) return RefundOutcome(settled = true, refundId = "DEPOSITREF${req.refundBn}")
        val acc = deposits.find(req.order.companyId, req.order.userId) ?: run {
            deposits.insertEmpty(req.order.companyId, req.order.userId, TimeSec.now())
            deposits.find(req.order.companyId, req.order.userId)!!
        }
        val consume = deposits.findConsumeByOrder(req.order.orderId.toString())
        val gift = if (consume == null || consume.amount + consume.giftAmount <= 0) {
            0L
        } else {
            (req.fee * consume.giftAmount / (consume.amount + consume.giftAmount)).coerceAtMost(req.fee)
        }
        if (deposits.refund(acc.id, req.fee - gift, gift, TimeSec.now()) == 0) {
            return RefundOutcome(settled = false, message = "余额账户动账失败")
        }
        deposits.insertTrade(
            DepositTradeDO().apply {
                tradeId = "RD${req.refundBn}"
                companyId = req.order.companyId
                userId = req.order.userId
                tradeType = "refund"
                relId = req.order.orderId.toString()
                amount = req.fee - gift
                giftAmount = gift
                beforeBalance = acc.depositAmount + acc.giftAmount
                afterBalance = acc.depositAmount + acc.giftAmount + req.fee
                status = "SUCCESS"
                created = TimeSec.now()
                updated = TimeSec.now()
            },
        )
        return RefundOutcome(settled = true, refundId = "DEPOSITREF${req.refundBn}")
    }
}

/**
 * 线下转账：只落凭证，订单保持待付款，等后台审核打款（审核动作属 M1-4）。
 * 因此 `settled=false` 且不产生渠道参数包。
 */
@Component
class OfflineChannel(private val offs: OfflinePaymentMapper) : PayChannel {
    override val payType = "offline_pay"

    override fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome {
        val p = OfflinePaymentDO().apply {
            orderId = order.orderId
            companyId = order.companyId
            totalFee = order.totalFee
            payFee = 0L
            checkStatus = 0
            paySn = trade.tradeId
            transferRemark = "线下转账待审核"
            created = TimeSec.now()
            updated = TimeSec.now()
        }
        offs.insert(p)
        return PayOutcome(
            settled = false,
            payFee = 0L,
            params = mapOf("offline_payment_id" to p.id, "check_status" to 0, "pay_sn" to trade.tradeId),
        )
    }

    /**
     * 线下转账没有"原路"可退：钱是商家柜台/对公收的，只能人工打款。
     * 所以这里一律 deferred —— 退款单停在 AUDIT_SUCCESS，由 AF-A10 登记银行七字段后才落 SUCCESS。
     */
    override fun refund(req: RefundRequest): RefundOutcome = RefundOutcome(
        settled = false,
        deferred = true,
        message = "线下转账订单需人工打款，请在退款单 ${req.refundBn} 登记银行信息",
    )
}

/** 渠道注册表：pay_type → 实现。未实现的类型（alipaymini/adapay/paypal…）一律 501，与 D2/D5 口径一致 */
@Component
class PayChannelRegistry(channels: List<PayChannel>) {
    private val byType = channels.associateBy { it.payType }

    fun get(payType: String?): PayChannel = payType?.let { byType[it] }
        ?: throw BizException(
            ErrorCode.NOT_IMPLEMENTED,
            "暂不支持该支付方式：${payType ?: "(空)"}，可选 ${supported.joinToString("/")}",
            mapOf("pay_type" to listOf("未实现的渠道")),
        )

    val supported: List<String> get() = listOf("wxpay", "point", "deposit", "offline_pay")
}
