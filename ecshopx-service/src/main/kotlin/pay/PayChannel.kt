package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.pay.DepositMapper
import com.ecshopx.kotlin.dao.pay.OfflinePaymentDO
import com.ecshopx.kotlin.dao.pay.OfflinePaymentMapper
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

interface PayChannel {
    /** 入参 pay_type 取值（docs/04 §2.4：point/deposit/offline_pay/wxpay） */
    val payType: String

    fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome
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
        return PayOutcome(
            settled = true,
            payFee = need,
            params = mapOf("deposit_used" to need, "deposit_left" to balance - need, "gift_used" to useGift),
            transactionId = "DEPOSIT${trade.tradeId}",
        )
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
