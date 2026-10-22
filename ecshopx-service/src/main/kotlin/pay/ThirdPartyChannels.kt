package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.pay.PayChannelConfigDO
import com.ecshopx.kotlin.dao.pay.PayChannelConfigMapper
import com.ecshopx.kotlin.dao.pay.TradeDO
import com.ecshopx.kotlin.dao.pay.TradeMapper
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 三家聚合支付渠道的实现位（M2-2 §27.4，"不逐家联调"）。
 *
 * 三家的共同约定（§27.4 末段，逐条写进实现）：
 *  1. [PayChannel.pay] **只出参数包不结清**（`settled=false`），结清只认回调；
 *  2. [PayChannel.refund] 返回 `pendingCallback`（三家退款都是异步受理）；
 *  3. [PayChannel.parseNotification] **必须验签**，验不过返回 null（控制器回 FAIL，不动账）。
 *
 * 签名约定（三家共用，写在这里避免每家各写一套）：`sign = hex(HMAC-SHA256(api_key, canonical))`，
 * `canonical` = 除 `sign` 外全部参数按 key 字节序排 `k=v&` 连拼。真实接入时按各家 SDK 换——
 * 但"验签挡在动账之前"这个位次是三家共用的约定，换签名公式不许把这一位换掉。
 *
 * 与 mock 渠道的差别只有一条：**没有配置行就当场 422**（"该渠道未配置"，不是静默走兜底）——
 * 配置是从 `pay_channel_config` 表读的（M1-2 那批真渠道读 yml，因为它们是平台级的单实例渠道；
 * 这三家是**按租户开户**的聚合支付，一个租户一套商户号，所以读表不读环境变量）。
 */
object ChannelSign {

    /** hex(HMAC-SHA256(key, canonical))；canonical 除 sign 外按 key 字节序 `k=v&` 连拼 */
    fun hmacSha256Hex(key: String, params: Map<String, Any?>): String {
        if (key.isBlank()) return ""
        val canonical = canonical(params)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun canonical(params: Map<String, Any?>): String {
        val data = if (params is LinkedHashMap<String, Any?>) params else LinkedHashMap(params)
        data.remove("sign")
        return data.keys.sorted().joinToString("&") { "${it}=${data[it] ?: ""}" }
    }
}

/**
 * 三家共用的一条基类：配置读取、参数包、签名、回调验签与状态归一都在这一层；
 * 子类只声明 `payType` 与"参数包的形状键"（四件事里唯一逐家不同的地方）。
 */
sealed class JsonTradeChannel(
    protected val configs: PayChannelConfigMapper,
    protected val trades: TradeMapper,
    protected val mapper: ObjectMapper,
) : PayChannel {

    /** 配置是**契约的第一个门**：没有行、行停用、没有 api_key，三态同答 422——不区分是哪种没配好 */
    protected fun requireConfig(companyId: Long, channel: String): PayChannelConfigDO {
        val c = configs.find(companyId, channel)
        if (c == null || c.status != 1 || c.apiKey?.isNotBlank() != true) {
            throw BizException(
                ErrorCode.BIZ, "支付渠道 ${channelName()} 未配置或不可用",
                mapOf("pay_type" to listOf("pay_channel_config 缺配置行")),
            )
        }
        return c
    }

    /** 子类的渠道名（= payType），报错文案里用它，避免每家自己拼 */
    protected abstract fun channelName(): String

    /** `pay()` 的参数包：只描述"这笔交易给谁收多少"，平台侧开户的信息（merchant/app）由它自己带 */
    protected fun envelope(order: NormalOrderDO, trade: TradeDO, c: PayChannelConfigDO): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "out_trade_no" to trade.tradeId,
            "amount" to trade.totalFee.toString(),
            "subject" to (order.title ?: "订单${order.orderId}"),
            notifyKey() to (c.notifyUrl ?: ""),
            "merchant_no" to (c.merchantNo ?: ""),
            "app_id" to (c.appId ?: ""),
        )

    /** 应答 Y 键的差异位（bs/hf/adapay 各自叫什么，真联调是 M2-4 的事，本批键位与各家前端约定对上） */
    protected open fun notifyKey(): String = "notify_url"

    override fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome {
        val c = requireConfig(order.companyId, channelName())
        val params = envelope(order, trade, c)
        params["sign"] = ChannelSign.hmacSha256Hex(c.apiKey!!, params)
        return PayOutcome(
            settled = false,                       // §27.4 约定 1：只出参数包不结清
            payFee = 0L,
            params = params,
            transactionId = null,
        )
    }

    override fun refund(req: RefundRequest): RefundOutcome {
        val c = requireConfig(req.order.companyId, channelName())
        val params = linkedMapOf<String, Any?>(
            "out_trade_no" to req.trade.tradeId,
            "out_refund_no" to req.refundBn.toString(),
            "amount" to req.fee.toString(),
            "merchant_no" to (c.merchantNo ?: ""),
        )
        params["sign"] = ChannelSign.hmacSha256Hex(c.apiKey!!, params)
        return RefundOutcome(
            settled = false,
            pendingCallback = true,                // §27.4 约定 2：退款是异步受理，回执推进者走回调
            refundId = null,
            message = "退款已受理，等待渠道回执",
        )
    }

    /**
     * v3（JSON）回调：报文是 `{"out_trade_no":..,"status":..,"amount":..,"transaction_id":..,"sign":..}`。
     * 验签失败（sign 缺失/不等/api_key 不配）→ null，控制器回 FAIL、**不动账**（约定 3）。
     * 状态归一：三家原话各不相同（`success`/`paid`/`succeeded`），这里一次收口成 `SUCCESS`，
     * 与 AliPayChannel 里的状态归一是同一纪律——渠道原话不能进订单表。
     */
    override fun parseNotification(headers: Map<String, String>, body: String): Map<String, String>? {
        val node = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
        val params = LinkedHashMap<String, Any?>()
        node.fields().forEach { (k, v) -> params[k] = if (v.isValueNode) v.asText() else v.toString() }
        val outTradeNo = params["out_trade_no"] as? String ?: return null
        val t = trades.findById(outTradeNo) ?: return null          // 不认识的支付单：不结清也不报"配置错"
        val c = configs.find(t.companyId, channelName()) ?: return null
        val expected = ChannelSign.hmacSha256Hex(c.apiKey ?: "", params)
        if (expected.isBlank() || !expected.equals(params["sign"] as? String ?: "", ignoreCase = true)) return null
        val status = (params["status"] as? String) ?: return null
        val flat = LinkedHashMap<String, String>()
        flat["out_trade_no"] = outTradeNo
        flat["transaction_id"] = (params["transaction_id"] as? String) ?: outTradeNo
        flat["total_fee"] = (params["amount"] as? String) ?: t.totalFee.toString()
        flat["channel"] = channelName()
        if (params["out_refund_no"] != null) {
            // 退款回执（PayService.notifyRefund 用的形态）
            flat["out_refund_no"] = params["out_refund_no"] as String
            flat["trade_state"] = when (status) {
                "success", "refund_success" -> "REFUND"
                "processing", "refund_processing" -> "REFUND_PROCESSING"
                else -> "PAYERROR"
            }
            flat["resource_type"] = "refund"
        } else {
            flat["trade_state"] = normalizeTradeState(status)
        }
        return flat
    }

    private fun normalizeTradeState(raw: String): String = when (raw.lowercase()) {
        "success", "paid", "succeeded", "payment.succeeded" -> "SUCCESS"
        "closed", "failed", "expired" -> "CLOSED"
        else -> raw.uppercase()
    }

    /**
     * 查单（补单作业用）：只认配置里给了 `query_url` 的形态（e2e 假服务器/仿真网关）。
     * 真链的每家 SDK 与证书体系是逐家联调 M2-4 的事，本批"查不起"就返回 null 退出补单轮询。
     */
    override fun queryTrade(tradeId: String): Map<String, Any?>? {
        val t = trades.findById(tradeId) ?: return null
        val c = configs.find(t.companyId, channelName()) ?: return null
        val extra = c.extra?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() }
            ?: return null
        val queryUrl = extra.path("query_url").asText(null) ?: return null
        return runCatching {
            val bodyBody = linkedMapOf<String, Any?>(
                "out_trade_no" to tradeId,
                "merchant_no" to (c.merchantNo ?: ""),
            )
            bodyBody["sign"] = ChannelSign.hmacSha256Hex(c.apiKey ?: "", bodyBody)
            val client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(3)).build()
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(queryUrl))
                .timeout(java.time.Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(bodyBody)))
                .build()
            val res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val node = mapper.readTree(res.body())
            linkedMapOf(
                "trade_state" to normalizeTradeState(node.path("status").asText("")),
                "transaction_id" to node.path("transaction_id").asText(tradeId),
                "total_fee" to node.path("amount").asText(t.totalFee.toString()).toLongOrNull() ?: t.totalFee,
            )
        }.getOrNull()
    }

    override fun closeTrade(tradeId: String): Boolean = true // 生效位：本批三家都接受"关单代表本地放弃"
}

@PayChannelProfile("adapay")
class AdaPayChannel(
    configs: PayChannelConfigMapper,
    trades: TradeMapper,
    mapper: ObjectMapper,
) : JsonTradeChannel(configs, trades, mapper) {
    override val payType = "adapay"
    override fun channelName() = "adapay"
}

@PayChannelProfile("bspay")
class BsPayChannel(
    configs: PayChannelConfigMapper,
    trades: TradeMapper,
    mapper: ObjectMapper,
) : JsonTradeChannel(configs, trades, mapper) {
    override val payType = "bspay"
    override fun channelName() = "bspay"
}

@PayChannelProfile("hfpay")
class HfPayChannel(
    configs: PayChannelConfigMapper,
    trades: TradeMapper,
    mapper: ObjectMapper,
) : JsonTradeChannel(configs, trades, mapper) {
    override val payType = "hfpay"
    override fun channelName() = "hfpay"
}
