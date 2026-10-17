package com.ecshopx.kotlin.service.pay

import com.alipay.api.AlipayClient
import com.alipay.api.DefaultAlipayClient
import com.alipay.api.internal.util.AlipaySignature
import com.alipay.api.request.AlipayTradeCloseRequest
import com.alipay.api.request.AlipayTradeCreateRequest
import com.alipay.api.request.AlipayTradeQueryRequest
import com.alipay.api.request.AlipayTradeRefundRequest
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.dao.auth.MembersAssociationsMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.pay.TradeDO
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * 支付宝渠道（docs/05 §M1-2 的 "Alipay 沙箱 profile"），`ecx.pay.active-channel-profile=alipay` 时启用。
 *
 * 沙箱与线上**同一套代码**，只差三个配置值：`server-url`（沙箱网关）、`app-id`、两把密钥，
 * 所以这里不出现"沙箱分支"——分支一旦进代码，切线上就要改代码，那是 M0 假渠道才需要的东西。
 *
 * 与微信渠道的三处实质差别（都在渠道内部消化，业务层看不出来）：
 *  1. **金额单位**：支付宝用「元、两位小数」字符串，本系统全程「分」，进出各换算一次
 *    （[fenToYuan] / [yuanToFen]），换算只在渠道出现；
 *  2. **异步通知是 form-urlencoded + `sign`**，不是 v3 的 JSON + `Wechatpay-*` 头，
 *    所以它走 [parseFormNotification]（RSA2 验签）而不是 [parseNotification]；
 *  3. **退款同步返回**：`alipay.trade.refund` 当场给结果，没有"等回执"这一态，
 *    因此只产出 [RefundOutcome.settled]，不会碰 `pendingCallback`。
 */
@Component
@PayChannelProfile("alipay")
class AliPayChannel(
    private val assoc: MembersAssociationsMapper,
    @Value("\${ecx.pay.alipay.server-url:}") serverUrl: String,
    @Value("\${ecx.pay.alipay.app-id:}") private val appIdConfig: String,
    @Value("\${ecx.pay.alipay.private-key:}") privateKeyConfig: String,
    @Value("\${ecx.pay.alipay.alipay-public-key:}") alipayPublicKeyConfig: String,
    @Value("\${ecx.pay.alipay.notify-url:}") private val notifyUrl: String,
    @Value("\${ecx.pay.alipay.charset:UTF-8}") private val charsetName: String,
    @Value("\${ecx.pay.alipay.sign-type:RSA2}") private val signType: String,
    @Value("\${ecx.pay.alipay.fallback-buyer-id:}") private val fallbackBuyerId: String,
) : PayChannel {

    override val payType = "alipay"

    private val server = serverUrl.ifBlank { DEFAULT_GATEWAY }
    private val privateKey = privateKeyConfig
    private val publicKey = alipayPublicKeyConfig

    /** 懒建：缺配置时不影响应用启动，第一次支付才报"配置不全" */
    @Volatile
    private var cached: AlipayClient? = null

    private fun client(): AlipayClient {
        if (listOf(server, appIdConfig, privateKey, publicKey).any { it.isBlank() }) {
            throw BizException(
                ErrorCode.BIZ,
                "支付宝配置不全：需要 server-url / app-id / private-key / alipay-public-key",
            )
        }
        return cached ?: synchronized(this) {
            DefaultAlipayClient(server, appIdConfig, privateKey, FORMAT, charsetName, publicKey, signType)
                .also { cached = it }
        }
    }

    /**
     * `alipay.trade.create`：只创建交易并拿 `order_id`，前端用 `my.tradePay({tradeNO})` 调起收银台。
     * 因此 `settled=false`，钱要等异步通知（或查单）才落地——与 wxpay 的 `settled=false` 同一含义。
     *
     * `buyer_id` 与微信的 `payer.openid` 是同一件事（这笔钱从谁的账户出），所以按同一套规矩取：
     * 先反查会员的支付宝绑定，查不到才用应用级兜底值；两个都没有就失败，
     * **绝不退化成"无主支付"**——支付宝的当面付/网页支付可以不带 buyer_id，但带不回的绑定关系
     * 意味着对账时没人能说清这笔钱是谁付的。
     */
    override fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome {
        val buyer = assoc.findAlipayUserIdByUser(order.companyId, order.userId)?.takeIf { it.isNotBlank() }
            ?: fallbackBuyerId.takeIf { it.isNotBlank() }
            ?: throw BizException(
                ErrorCode.BIZ, "该会员未绑定支付宝，无法发起支付宝支付",
                mapOf("pay_type" to listOf("缺少 buyer_id，请先用支付宝登录绑定会员")),
            )
        val request = AlipayTradeCreateRequest().apply {
            setNotifyUrl(notifyUrl)
            bizContent = """{"out_trade_no":"${trade.tradeId}","total_amount":"${fenToYuan(trade.totalFee)}",""" +
                """"subject":"${(order.title ?: "订单${order.orderId}").jsonEscape()}","buyer_id":"$buyer"}"""
        }
        val response = try {
            client().execute(request)
        } catch (e: Exception) {
            throw BizException(ErrorCode.BIZ, "支付宝下单失败：${e.message}", mapOf("pay_type" to listOf("渠道拒绝")))
        }
        if (!response.isSuccess) {
            throw BizException(
                ErrorCode.BIZ, "支付宝下单失败：${response.subMsg ?: response.subCode ?: response.code}",
                mapOf("pay_type" to listOf(response.subCode ?: response.code ?: "-")),
            )
        }
        return PayOutcome(
            settled = false,
            payFee = 0L,
            params = linkedMapOf(
                // 前端契约键位与 wxpay 不同（支付宝调起只要一个交易号），这里如实给出，不做假统一
                "trade_no" to response.tradeNo,
                "out_trade_no" to response.outTradeNo,
                "total_amount" to fenToYuan(trade.totalFee),
            ),
            transactionId = response.tradeNo,
        )
    }

    /** 原路退：`alipay.trade.refund` 同步给结果；`out_request_no` 用退款单号，渠道按它幂等 */
    override fun refund(req: RefundRequest): RefundOutcome {
        val request = AlipayTradeRefundRequest().apply {
            bizContent = """{"out_trade_no":"${req.trade.tradeId}","out_request_no":"${req.refundBn}",""" +
                """"refund_amount":"${fenToYuan(req.fee)}","refund_reason":"售后退款"}"""
        }
        val response = try {
            client().execute(request)
        } catch (e: Exception) {
            return RefundOutcome(settled = false, message = "支付宝退款失败：${e.message}")
        }
        return if (response.isSuccess) {
            RefundOutcome(settled = true, refundId = response.tradeNo ?: req.trade.tradeId)
        } else {
            RefundOutcome(
                settled = false,
                message = "支付宝退款未完成：${response.subMsg ?: response.subCode ?: response.code}",
            )
        }
    }

    /**
     * 查单：`alipay.trade.query`，补单作业用（同一套金额换算，回来仍是分）。
     *
     * **状态必须归一化**：支付宝的 `trade_status` 是 `TRADE_SUCCESS`/`TRADE_FINISHED`，
     * 而补单作业与回调共用同一个判据 `trade_state == "SUCCESS"`（见 PayReconcileJob）。
     * 直接把渠道原话传回去，掉单就永远补不上——报文看起来完全正常，这是最难查的一种漏。
     */
    override fun queryTrade(tradeId: String): Map<String, Any?>? {
        val request = AlipayTradeQueryRequest().apply {
            bizContent = """{"out_trade_no":"$tradeId"}"""
        }
        val r = client().execute(request)
        return linkedMapOf(
            "trade_state" to (if (r.isSuccess) r.tradeStatus?.let(::normalizeStatus) else null),
            "transaction_id" to r.tradeNo,
            "total_fee" to r.totalAmount?.let { yuanToFen(it) },
            "buyer_logon_id" to r.buyerLogonId,
        )
    }

    /**
     * 关单（补单作业第二条腿）：`alipay.trade.close`。
     * 语义与微信一致 —— 返回的是"还要不要重试"：交易已完结（`ACQ.TRADE_HAS_SUCCESS`）、
     * 查无此单（`TRADE_NOT_EXIST`）都是终局结论，重试只会撞同一个答复；
     * 网络/网关 5xx 才返回 false 留到下轮。
     */
    override fun closeTrade(tradeId: String): Boolean {
        val response = try {
            val request = AlipayTradeCloseRequest().apply {
                bizContent = """{"out_trade_no":"$tradeId"}"""
            }
            client().execute(request)
        } catch (e: Exception) {
            return false
        }
        return response.isSuccess || response.subCode in CLOSE_TERMINAL_SUB_CODES
    }

    /**
     * 支付宝异步通知（form-urlencoded）验签 + 归一化。
     *
     * `AlipaySignature.rsaCheckV1` 的待签串是"除 sign/sign_type 外全部参数按 key 升序拼接"，
     * 由 SDK 自己算——我们不手搓，避免"参数顺序/编码"这种最容易踩的坑。
     * 验不过返回 null，控制器回 FAIL 让支付宝重试；验过了才把「元」换成「分」交给 PayService 做金额核对。
     */
    override fun parseFormNotification(params: Map<String, String>): Map<String, String>? {
        // app_id 不符 = 这封通知不是发给本应用的（同一域名下多应用复用回调地址时会发生）
        if (params["app_id"] != appIdConfig) return null
        val verified = runCatching {
            AlipaySignature.rsaCheckV1(params, publicKey, charsetName, signType)
        }.getOrDefault(false)
        if (!verified) return null
        val status = params["trade_status"] ?: return null
        return linkedMapOf(
            "out_trade_no" to (params["out_trade_no"] ?: ""),
            "transaction_id" to (params["trade_no"] ?: ""),
            "total_fee" to (params["total_amount"]?.let { yuanToFen(it).toString() } ?: ""),
            "trade_state" to normalizeStatus(status),
            "channel" to payType,
            "resource_type" to "notification",
        )
    }

    /** 渠道状态 → 本系统支付单状态（与 [queryTrade] 同一口径，回调与补单不会两套判据） */
    private fun normalizeStatus(raw: String): String = when (raw) {
        "TRADE_SUCCESS", "TRADE_FINISHED" -> "SUCCESS"
        "TRADE_CLOSED" -> "CLOSED"
        "WAIT_BUYER_PAY" -> "NOTPAY"
        else -> raw
    }

    /**
     * 支付宝只判断响应文本是否等于 `success`，其它一律按"没处理完"重推
     * （重试节奏 4m/10m/…），所以我们不能回微信那段 XML——内容再对，格式不对就是无限重试。
     */
    override fun formNotificationAck(success: Boolean, message: String): String = if (success) "success" else "failure"

    companion object {
        const val DEFAULT_GATEWAY = "https://openapi.alipay.com/gateway.do"
        const val FORMAT = "json"

        /** 关单的终局 sub_code：交易已完结 / 查无此单，重发只是撞同一个答复 */
        val CLOSE_TERMINAL_SUB_CODES = setOf("ACQ.TRADE_HAS_SUCCESS", "TRADE_NOT_EXIST", "ACQ.TRADE_NOT_EXIST")

        /** 分 → 元（两位小数，支付宝侧的金额格式） */
        fun fenToYuan(fen: Long): String = BigDecimal(fen).movePointLeft(2).setScale(2).toPlainString()

        /** 元 → 分：通知与查询回来的金额都按这个换，四舍五入NotAllowed——渠道不会给超过两位小数 */
        fun yuanToFen(yuan: String): Long = try {
            BigDecimal(yuan.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact()
        } catch (e: ArithmeticException) {
            BigDecimal(yuan.trim()).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
        }

        private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
