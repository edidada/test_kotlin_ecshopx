package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MembersAssociationsMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.pay.TradeDO
import com.fasterxml.jackson.databind.ObjectMapper
import com.wechat.pay.java.core.RSAConfig
import com.wechat.pay.java.core.exception.ServiceException
import com.wechat.pay.java.core.http.DefaultHttpClientBuilder
import com.wechat.pay.java.core.http.HttpClient
import com.wechat.pay.java.core.notification.NotificationParser
import com.wechat.pay.java.core.notification.RSANotificationConfig
import com.wechat.pay.java.core.notification.RequestParam
import com.wechat.pay.java.service.payments.jsapi.JsapiServiceExtension
import com.wechat.pay.java.service.payments.jsapi.model.Amount
import com.wechat.pay.java.service.payments.jsapi.model.CloseOrderRequest
import com.wechat.pay.java.service.payments.jsapi.model.Payer
import com.wechat.pay.java.service.payments.jsapi.model.PrepayRequest
import com.wechat.pay.java.service.payments.jsapi.model.QueryOrderByOutTradeNoRequest
import com.wechat.pay.java.service.refund.RefundService
import com.wechat.pay.java.service.refund.model.AmountReq
import com.wechat.pay.java.service.refund.model.CreateRequest
import com.wechat.pay.java.service.refund.model.Status
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * 微信支付 v3 真实渠道（docs/05 §M1-2），`ecx.pay.active-channel-profile=wechatpay` 时替换 M0 的 mock 渠道。
 *
 * 只做"渠道侧动作"：JSAPI 下单出调起参数、查单、关单、原路退款、回调验签解密；
 * 结清订单、写支付单、退款单推进全部留在 PayService / RefundService，与 mock 渠道同一套编排
 * ——**换 profile 不换业务**，这是 M0-7 就把渠道抽象成 `PayChannel` 的原因。
 *
 * 三处与 mock 的实质差别：
 *  1. 出参仍是 `appId/timeStamp/nonceStr/package/signType/paySign` 六键（PAY-2 契约不变，前端零改动）；
 *  2. `paySign` 由 SDK 用**商户私钥**真签，mock 的 `MOCK-` 假签名在这个 profile 下不可能出现；
 *  3. 回调走 v3 的 JSON + `Wechatpay-*` 头，验签（平台证书）与 AES-256-GCM 解密都由 SDK 完成，
 *     任一步失败就返回 null，控制器一律回 FAIL，**绝不拿没验过签的报文结清**。
 *
 * 密钥不进代码、不进库：`ecx.pay.wxpay.*` 只接部署环境的文件路径或配置中心下发值。
 * M1-2 的口径是**一套商户号服务一个租户**（多商户映射属 M2 开放能力），所以配置全走应用级而非 `sys_config`。
 */
@Component
@PayChannelProfile("wechatpay")
class WxPayV3Channel(
    private val assoc: MembersAssociationsMapper,
    private val mapper: ObjectMapper,
    @Value("\${ecx.pay.wxpay.mch-id:}") mchIdConfig: String,
    @Value("\${ecx.pay.wxpay.app-id:}") appIdConfig: String,
    @Value("\${ecx.pay.wxpay.api-v3-key:}") apiV3KeyConfig: String,
    @Value("\${ecx.pay.wxpay.private-key:}") privateKeyConfig: String,
    @Value("\${ecx.pay.wxpay.private-key-path:}") privateKeyPathConfig: String,
    @Value("\${ecx.pay.wxpay.certificate-serial:}") certSerialConfig: String,
    @Value("\${ecx.pay.wxpay.platform-certificate:}") platformCertConfig: String,
    @Value("\${ecx.pay.wxpay.platform-certificate-path:}") platformCertPathConfig: String,
    @Value("\${ecx.pay.wxpay.notify-url:}") private val notifyUrl: String,
    @Value("\${ecx.pay.wxpay.refund-notify-url:}") private val refundNotifyUrl: String,
    @Value("\${ecx.pay.wxpay.base-url:}") baseUrlConfig: String,
    @Value("\${ecx.pay.wxpay.fallback-open-id:}") private val fallbackOpenId: String,
) : PayChannel {

    override val payType = "wxpay"

    /** 懒建：应用启动不去读密钥文件；配置缺失只让第一次真实支付报错，不拖垮整个上下文 */
    private val client = WxPayV3Client(
        mchId = mchIdConfig,
        appId = appIdConfig,
        apiV3Key = apiV3KeyConfig,
        keyMaterial = privateKeyConfig.ifBlank { privateKeyPathConfig },
        certificateSerial = certSerialConfig,
        certificateMaterial = platformCertConfig.ifBlank { platformCertPathConfig },
        baseUrl = baseUrlConfig,
    )

    /**
     * JSAPI 下单：`out_trade_no` 用支付单号 `trade.trade_id`（回调据此定位支付单），
     * 金额取 `trade.total_fee`（分），openid 取会员微信绑定；未绑定且没配兜底 openid 就直接失败，
     * 绝不退化成"无主支付"。
     */
    override fun pay(order: NormalOrderDO, trade: TradeDO): PayOutcome {
        val c = client.ensure()
        val openid = assoc.findOpenIdByUser(order.companyId, order.userId)?.takeIf { it.isNotBlank() }
            ?: fallbackOpenId.takeIf { it.isNotBlank() }
            ?: throw BizException(
                ErrorCode.BIZ, "该会员未绑定微信，无法发起微信支付",
                mapOf("pay_type" to listOf("缺少 openid，请先用微信登录绑定会员")),
            )
        val request = PrepayRequest().apply {
            appid = c.appId
            mchid = c.mchId
            description = (order.title ?: "订单${order.orderId}").take(MAX_DESCRIPTION)
            outTradeNo = trade.tradeId
            notifyUrl = this@WxPayV3Channel.notifyUrl
            amount = Amount().apply {
                total = trade.totalFee.toInt()
                currency = CURRENCY
            }
            payer = Payer().apply { this.openid = openid }
        }
        val p = try {
            c.jsapi.prepayWithRequestPayment(request)
        } catch (e: Exception) {
            throw BizException(ErrorCode.BIZ, "微信下单失败：${e.message}", mapOf("pay_type" to listOf("渠道拒绝")))
        }
        return PayOutcome(
            settled = false,
            payFee = 0L,
            params = linkedMapOf(
                "appId" to p.appId,
                "timeStamp" to p.timeStamp,
                "nonceStr" to p.nonceStr,
                "package" to p.packageVal,
                "signType" to p.signType,
                "paySign" to p.paySign,
            ),
        )
    }

    /**
     * 原路退：`out_refund_no = 退款单号`，微信按它幂等——同一笔重复提交返回同一退款单，
     * 所以 RefundService 的 CAS 重放不会退两次。
     * `PROCESSING` 是"渠道已受理、结果待回执"，映射成 [RefundOutcome.pendingCallback]：
     * 退款单原地停在 AUDIT_SUCCESS，由 REFUND.SUCCESS 回调把它推到 SUCCESS（不是人工登记）。
     */
    override fun refund(req: RefundRequest): RefundOutcome {
        val c = client.ensure()
        val request = CreateRequest().apply {
            outTradeNo = req.trade.tradeId
            outRefundNo = req.refundBn.toString()
            reason = "售后退款"
            if (refundNotifyUrl.isNotBlank()) notifyUrl = refundNotifyUrl
            amount = AmountReq().apply {
                refund = req.fee
                total = req.trade.payFee
                currency = CURRENCY
            }
        }
        val r = try {
            c.refundService.create(request)
        } catch (e: Exception) {
            return RefundOutcome(settled = false, message = "微信退款失败：${e.message}")
        }
        return when (r.status) {
            Status.SUCCESS -> RefundOutcome(settled = true, refundId = r.refundId)
            Status.PROCESSING -> RefundOutcome(
                settled = false,
                refundId = r.refundId,
                pendingCallback = true,
                message = "微信已受理退款（${r.refundId}），到账以渠道回执为准",
            )
            else -> RefundOutcome(settled = false, refundId = r.refundId, message = "微信退款未完成：${r.status}")
        }
    }

    /** 查单：补单作业用（回调丢失时唯一能把钱和订单对上的动作） */
    override fun queryTrade(tradeId: String): Map<String, Any?> {
        val c = client.ensure()
        val t = c.jsapi.queryOrderByOutTradeNo(
            QueryOrderByOutTradeNoRequest().apply {
                outTradeNo = tradeId
                mchid = c.mchId
            },
        )
        return linkedMapOf(
            "trade_state" to t.tradeState?.name,
            "transaction_id" to t.transactionId,
            "total_fee" to t.amount?.total?.toLong(),
            "success_time" to t.successTime,
        )
    }

    /**
     * 关单（M1-2 补单作业第二条腿）：把本地已 `CLOSED` 的支付单在渠道侧也收掉。
     *
     * 返回值在这里不是"关成功了没"，而是**"还要不要重试"**：
     *  - 200 = 关掉；
     *  - 渠道答"已支付/已关闭/订单不存在"= 这笔在渠道侧已经定性，重试只会一直撞同一个结论，
     *    所以照样返回 true 让 `channel_closed` 置位；"已支付"的那笔钱不会丢，
     *    它下一轮以 `SUCCESS` 落进补单分支（或直接等回调），按回调口径处理；
     *  - 其余（网络挂了、限流、5xx）返回 false，下一轮继续发。
     */
    override fun closeTrade(tradeId: String): Boolean {
        val c = client.ensure()
        return try {
            c.jsapi.closeOrder(
                CloseOrderRequest().apply {
                    outTradeNo = tradeId
                    mchid = c.mchId
                },
            )
            true
        } catch (e: ServiceException) {
            terminalCloseFailure(e.errorCode, e.errorMessage)
        } catch (e: Exception) {
            false
        }
    }

    /** 渠道给出的"不用再试"结论（错误码随 SDK 版本/文档措辞有别名，故码与文案都判一次） */
    private fun terminalCloseFailure(code: String?, message: String?): Boolean =
        code?.uppercase() in CLOSE_TERMINAL_CODES ||
            listOf("已支付", "已关闭", "不存在", "订单已退款").any { message?.contains(it) == true }

    /**
     * v3 回调解析（PAY-5）。信封字段先自行读一遍（只为分流"交易回执/退款回执"），
     * **权威动作仍由 [NotificationParser.parse] 完成**：它对同一段 body 原文验签再解密，
     * 签名不符、serial 对不上平台证书、时间戳过期一律抛错 → 返回 null。
     */
    override fun parseNotification(headers: Map<String, String>, body: String): Map<String, String>? {
        val h = headers.mapKeys { it.key.lowercase() }
        val serial = h["wechatpay-serial"] ?: return null
        val timestamp = h["wechatpay-timestamp"] ?: return null
        val nonce = h["wechatpay-nonce"] ?: return null
        val signature = h["wechatpay-signature"] ?: return null
        // 时间戳新鲜期由**我们**校验：SDK 的证书验签只保证"这段签名确实是平台私钥签的"，
        // 不比较时间戳——原样重放一封旧报文（签名依旧有效）就能重放通知。
        // 微信文档把 5 分钟窗口写成商户侧义务，所以这一行不是可选加固，是验签链路的一部分。
        val ts = timestamp.toLongOrNull() ?: return null
        if (kotlin.math.abs(TimeSec.now() - ts) > NOTIFY_WINDOW_SECONDS) return null
        val envelope = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
        val requestParam = RequestParam.Builder()
            .serialNumber(serial)
            .timestamp(timestamp)
            .nonce(nonce)
            .signature(signature)
            .signType(h["wechatpay-signature-type"] ?: SIGN_TYPE)
            .body(body)
            .build()
        @Suppress("UNCHECKED_CAST")
        val plain = runCatching {
            client.ensure().parser.parse(requestParam, MutableMap::class.java) as Map<String, Any?>
        }.getOrNull() ?: return null

        val eventType = envelope.path("event_type").asText("")
        // v3 的信封里 resource_type 恒为 encrypt-resource（密文资源的意思），**分流靠 event_type**；
        // 再加一道"解密后有没有 out_refund_no"的兜底，防止渠道改了事件名却没改载荷
        val isRefund = eventType.startsWith("REFUND") || plain.containsKey("out_refund_no")
        val out = linkedMapOf(
            "resource_type" to if (isRefund) "refund" else "transaction",
            "event_type" to eventType,
            "channel" to payType,
        )
        plain.forEach { (k, v) -> if (v is String) out[k] = v }
        val amount = plain["amount"] as? Map<*, *>
        if (isRefund) {
            out["out_trade_no"] = plain["out_trade_no"]?.toString().orEmpty()
            out["out_refund_no"] = plain["out_refund_no"]?.toString().orEmpty()
            out["trade_state"] = when (plain["refund_status"]?.toString()) {
                "SUCCESS" -> "REFUND"
                "ABNORMAL", "CLOSED" -> "PAYERROR"
                else -> "REFUND_PROCESSING"
            }
            out["total_fee"] = money(amount?.get("total"))
            out["refund_fee"] = money(amount?.get("refund"))
            out["transaction_id"] = plain["refund_id"]?.toString().orEmpty()
        } else {
            out["out_trade_no"] = plain["out_trade_no"]?.toString().orEmpty()
            out["trade_state"] = plain["trade_state"]?.toString() ?: "SUCCESS"
            // 金额只做校验：以渠道实际收款额 total_paid 对齐本地应付（口径同 M0-7 C 系）
            out["total_fee"] = money(amount?.get("total_paid") ?: amount?.get("total"))
            out["transaction_id"] = plain["transaction_id"]?.toString().orEmpty()
        }
        return out
    }

    /**
     * 报文金额 → 「分」整数字符串。
     *
     * SDK 的 `NotificationParser` 用 Gson 把资源体解成 `Map`，**JSON 数字一律变成 Double**，
     * 直接 `toString()` 会拿到 `100.0`，下游 `toLongOrNull()` 得 null → 明明是正常回执却报"金额缺失"
     * （M1-2 仿真跑出来的真问题）。渠道侧金额本来就是整数分，这里只做取整还原，不做任何换算。
     */
    private fun money(v: Any?): String = when (v) {
        null -> ""
        is Number -> v.toLong().toString()
        else -> v.toString()
    }

    /**
     * **v3 没有表单回调**：微信支付 APIv3 只发 `application/json` + `Wechatpay-*` 头。
     *
     * 这里必须显式拒绝，不能沿用接口默认的"原样返回"——`/api/notify/` 下的回调是匿名放行的，
     * 如果 profile=wechatpay 时还接受表单，任何人 `curl` 一个 `out_trade_no + total_fee`
     * 就能把订单标成已付，整条验签链路等于被绕过。mock 渠道保留默认实现是 M0-7 冒烟的既定口径
     * （假渠道由我们自己 curl），真渠道没有这个特权。
     */
    override fun parseFormNotification(params: Map<String, String>): Map<String, String>? = null

    companion object {
        const val SIGN_TYPE = "WECHATPAY2-SHA256-RSA2048"
        const val CURRENCY = "CNY"
        const val MAX_DESCRIPTION = 127

        /** 回调时间戳新鲜期（秒）：微信文档要求的商户侧校验，超窗一律当伪造处理 */
        const val NOTIFY_WINDOW_SECONDS = 300L

        /**
         * 关单的终局错误码：钱已在渠道侧定性（已支付 / 已关闭 / 查无此单），重试没有意义。
         * 同一件事在各版本文档里的措辞不一致（`ORDERPAID` vs `ORDER_PAID`，"不存在"既可能是专码
         * 也可能被包进 `INVALID_REQUEST`），所以码与文案各判一次；
         * 网络/限流/5xx 一律不在这里，交给下一轮重发。
         */
        val CLOSE_TERMINAL_CODES = setOf(
            "ORDERPAID", "ORDER_PAID", "TRADE_EXIST", "ORDER_CLOSED",
            "ORDER_NOT_EXIST", "TRADE_NOT_EXIST", "INVALID_REQUEST",
        )
    }
}

/**
 * 一次构建、全程复用：`RSAConfig` 内含商户私钥与平台证书，`AbstractHttpClient` 内含 OkHttpClient 连接池，
 * 每次支付都重建既浪费也拿不到连接复用。
 *
 * `base-url` 是给**仿真与企业网关**用的路由覆盖：SDK 的请求地址由 `HostName` 枚举固定生成（改不了），
 * 只能在 OkHttp 层换 scheme+host+port——路径与查询串原样保留，而 SDK 的 `Authorization` 签的正是
 * "方法 + 路径 + 查询串 + body"，所以覆盖后签名依旧成立，验签链路一步没少。
 * 这也是本轮"本地仿真代替真单"的唯一接缝（docs/05 §M1-2 门槛）。
 */
class WxPayV3Client(
    val mchId: String,
    val appId: String,
    private val apiV3Key: String,
    private val keyMaterial: String,
    private val certificateSerial: String,
    private val certificateMaterial: String,
    val baseUrl: String,
) {
    /** 三件套 + 商户身份：一次构建后的稳定视图，调用方拿到的一定非空 */
    class Ready(
        val jsapi: JsapiServiceExtension,
        val refundService: RefundService,
        val parser: NotificationParser,
        val mchId: String,
        val appId: String,
    )

    @Volatile
    private var ready: Ready? = null

    fun requireReady() {
        if (listOf(mchId, appId, apiV3Key, keyMaterial, certificateSerial, certificateMaterial).any { it.isBlank() }) {
            throw BizException(
                ErrorCode.BIZ,
                "微信支付 v3 配置不全：需要 mch-id / app-id / api-v3-key / private-key(-path) / " +
                    "certificate-serial / platform-certificate(-path)",
            )
        }
    }

    /** 双检锁：并发首笔支付只构建一次（RSAConfig 里含私钥解析与证书解析，重复构建既慢又没意义） */
    fun ensure(): Ready = ready ?: synchronized(this) {
        ready ?: build().also { ready = it }
    }

    private fun build(): Ready {
        requireReady()
        val privateKeyPem = readPem(keyMaterial, "PRIVATE KEY")
        val certificatePem = readPem(certificateMaterial, "CERTIFICATE")
        val config = RSAConfig.Builder()
            .merchantId(mchId)
            .privateKey(com.wechat.pay.java.core.util.PemUtil.loadPrivateKeyFromString(privateKeyPem))
            .merchantSerialNumber(certificateSerial)
            .wechatPayCertificates(certificatePem)
            .build()
        val http: HttpClient? = if (baseUrl.isBlank()) {
            null
        } else {
            DefaultHttpClientBuilder().config(config).okHttpClient(rewritingClient(baseUrl)).build()
        }
        return Ready(
            jsapi = JsapiServiceExtension.Builder().config(config).apply { http?.let { httpClient(it) } }.build(),
            refundService = RefundService.Builder().config(config).apply { http?.let { httpClient(it) } }.build(),
            parser = NotificationParser(
                RSANotificationConfig.Builder().certificates(certificatePem).apiV3Key(apiV3Key).build(),
            ),
            mchId = mchId,
            appId = appId,
        )
    }
}

/** PEM 既支持内联（配置中心下发），也支持文件路径（PHP 侧 `wexinpay/cert/` 的老习惯） */
internal fun readPem(material: String, header: String): String {
    val marker = "-----BEGIN $header-----"
    val text = if (material.contains(marker)) material else File(material).readText()
    if (!text.contains(marker)) throw BizException(ErrorCode.BIZ, "密钥内容缺少 $marker")
    return text
}

/** 把 SDK 发出的 `https://api.mch.weixin.qq.com/...` 换成 `base-url` 指向的地址，路径与签名不受影响 */
internal fun rewritingClient(baseUrl: String): OkHttpClient {
    val target = baseUrl.toHttpUrlOrNull()
        ?: throw BizException(ErrorCode.BIZ, "非法的 ecx.pay.wxpay.base-url：$baseUrl")
    val rewrite = Interceptor { chain ->
        val original = chain.request()
        val rewritten = original.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        chain.proceed(original.newBuilder().url(rewritten).build())
    }
    return OkHttpClient.Builder().addInterceptor(rewrite).build()
}
