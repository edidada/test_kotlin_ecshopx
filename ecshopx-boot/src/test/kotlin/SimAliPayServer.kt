package com.ecshopx.kotlin.boot

import com.alipay.api.internal.util.AlipaySignature
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** 支付宝侧的仿真身份（沙箱 app_id/uid 都是同类形状，值本身无任何生产意义） */
object SimAli {
    const val APP_ID = "2021000123456789"
    const val OTHER_APP_ID = "2021000999999999"
    const val SELLER_ID = "2088301111111111"
    const val BUYER_ID = "2088101234567890"
    const val BUYER_LOGON = "sim***@alitest.com"
    const val CHARSET = "UTF-8"
    const val SIGN_TYPE = "RSA2"

    private val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    fun now(): String = java.time.LocalDateTime.now().format(fmt)
}

/**
 * 假支付宝网关（docs/05 §M1-2 的 "Alipay 沙箱 profile" 门槛等价实现）。
 *
 * 与微信夹具同一个思路：**唯一拿不到的东西是渠道的私钥与签名权，那就 locally 造出来**，
 * 其余每一步都走真实代码路径——SDK 用商户私钥真签请求，这里用商户公钥**真验签**；
 * 这里用平台私钥真签应答节点，SDK 的 `checkResponseSign` 用支付宝公钥**真验签**，
 * 验不过直接抛 `AlipayApiException`，我们的 `pay()` 就会看到"支付宝下单失败"。
 *
 * 三处与微信夹具不同的实现选择，都不是随手挑的：
 *  1. **这次可以用 JDK 自带 [HttpServer]**：支付宝只验应答**正文里那段节点子串**，不看响应头，
 *     所以头名大小写被规范化也无所谓（微信夹具必须裸 socket，原因写在那份文件里）；
 *     但要**同时读 URL query 与 POST body**——本版本 SDK 把公共参数（含 `method`、`sign`）挂在 query 上，
 *     body 里只有 `biz_content`，签名串却是两边合并后的全参数；
 *  2. **应答的签名对象是 `{"api_name_response":{…}}` 里那对花括号本身**：SDK 的
 *     `JsonConverter.parseSignSourceData` 用 `indexOf(apiKey)+长度+2` 定位节点起点，再按花括号配对截到
 *     节点末尾，把这段**原样子串**交给 `SignChecker.check`。所以夹具必须先把节点文本拼好、
 *     再对这段文本签名，最后拼外壳——顺序反了就必然验签失败；
 *  3. **`isSuccess()` 只看 `sub_code` 是否为空**（本版本 SDK 的实测口径，不看 `code`），
 *     所以业务失败要落 `sub_code`，成功节点里一个 `sub_` 字段都不能有。
 */
class SimAliPayServer {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** out_trade_no -> 渠道侧交易状态（WAIT_BUYER_PAY / TRADE_SUCCESS / TRADE_CLOSED） */
    private val state = ConcurrentHashMap<String, String>()

    /** out_trade_no -> 渠道记账金额（元，两位小数——支付宝全程用元） */
    private val amounts = ConcurrentHashMap<String, String>()
    private val tradeNos = ConcurrentHashMap<String, String>()

    /** 收到过的 `method`（含 out_trade_no），测试据此断言"确实打到渠道了" */
    val calls = CopyOnWriteArrayList<String>()

    /** 最近一次请求签名的验签结果与 app_id：证明"真签真验"发生在渠道侧，不是应用自说自话 */
    @Volatile
    var lastSignVerified: Boolean? = null

    @Volatile
    var lastAppId: String = ""

    /** 最近一次请求的 biz_content 原文：金额换算、buyer_id、notify_url 都从这里核对 */
    @Volatile
    var lastBiz: String = ""

    /** 最近一次应答的节点文本与 HTTP 形态，供"渠道原话"断言用 */
    @Volatile
    var lastNode: String = ""

    /** 最近一次写回给 SDK 的完整应答报文（排障用：SDK 看到的 body 与这里必须逐字节一致） */
    @Volatile
    var lastBody: String = ""

    val signChecks = AtomicInteger(0)

    val gatewayUrl: String get() = "http://127.0.0.1:${server.address.port}/gateway.do"

    fun start(): SimAliPayServer {
        server.createContext("/gateway.do") { ex -> handle(ex) }
        server.executor = null
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
    }

    /** 测试驱动渠道状态：等价于"用户在支付宝那边把钱付了" */
    fun markPaid(outTradeNo: String) {
        state[outTradeNo] = "TRADE_SUCCESS"
    }

    fun stateOf(outTradeNo: String): String? = state[outTradeNo]

    fun tradeNoOf(outTradeNo: String): String = tradeNos.getOrPut(outTradeNo) { "SIMALI$outTradeNo" }

    private fun handle(ex: HttpExchange) {
        try {
            val raw = ex.requestBody.use { it.readBytes() }
            // 4.39.x 的 SDK 把**公共参数放在 URL query，业务参数放在 POST body**（实测：
            // `gateway.do?method=…&sign=…&app_id=…` + body `biz_content=…`），
            // 而它签的是"合并后的全参数升序串"。网关只看 body 就会既取不到 method、也验不过签名。
            val params = LinkedHashMap(parseForm(ex.requestURI.rawQuery ?: ""))
            params.putAll(parseForm(String(raw, Charsets.UTF_8)))
            val method = params["method"].orEmpty()
            val biz = params["biz_content"].orEmpty()
            val out = bizOf(biz, "out_trade_no")
            calls.add("$method $out")
            lastBiz = biz
            lastAppId = params["app_id"].orEmpty()
            lastSignVerified = verifyRequest(params)
            signChecks.incrementAndGet()
            val node = route(method, biz, out)
            lastNode = node
            // 外壳按 SDK 的定位规则拼：`{` + `"xxx_response":` + 节点 + `,"sign":"…"}` + `}`
            val key = method.replace('.', '_') + "_response"
            val sign = AlipaySignature.rsa256Sign(node, SimWx.platformPrivateKeyB64, SimAli.CHARSET)
            write(ex, """{"$key":$node,"sign":"$sign"}""")
        } catch (e: Exception) {
            runCatching {
                write(ex, """{"error_response":{"code":"40002","msg":"Invalid Args","sub_code":"SYSTEM_ERROR",""" +
                    """"sub_msg":"夹具异常：${e::class.simpleName}"}}""")
            }
        } finally {
            ex.close()
        }
    }

    /**
     * 网关验签：`rsaCheckV2` = "除 sign 外全部参数按 key 升序拼 k=v&"，正是 SDK 给请求签名时用的那段
     * （`getSignatureContent` → `getSignContent`，同一套排序与空值过滤）。
     * 该方法会**就地删掉** map 里的 sign，所以传副本进去——否则后面再也读不到签名。
     */
    private fun verifyRequest(params: Map<String, String>): Boolean {
        val sign = params["sign"] ?: return false
        val copy = LinkedHashMap(params)
        return runCatching {
            AlipaySignature.rsaCheckV2(copy, SimWx.merchantPublicKeyB64, SimAli.CHARSET, params["sign_type"] ?: SimAli.SIGN_TYPE)
        }.getOrDefault(false) && sign.isNotBlank()
    }

    private fun route(method: String, biz: String, out: String): String = when {
        method == "alipay.trade.create" -> create(biz, out)
        method == "alipay.trade.query" -> query(out)
        method == "alipay.trade.close" -> close(out)
        method == "alipay.trade.refund" -> refund(biz, out)
        else -> """{"code":"40002","msg":"Invalid Args","sub_code":"ISV.API_NOT_EXIST","sub_msg":"没有这个接口：$method"}"""
    }

    /**
     * 下单：真网关会在这里校验 buyer_id 与金额，夹具只把这笔交易挂成"等买家付款"。
     * `total_amount` 原样记下（元），后面查单应答与退款都用它，金额换算就有第二条独立证据。
     */
    private fun create(biz: String, out: String): String {
        if (out.isBlank()) {
            return """{"code":"40004","msg":"Business Failed","sub_code":"INVALID_PARAMETER","sub_msg":"biz_content 缺 out_trade_no"}"""
        }
        amounts.putIfAbsent(out, bizOf(biz, "total_amount"))
        tradeNos.getOrPut(out) { "SIMALI$out" }
        state.putIfAbsent(out, "WAIT_BUYER_PAY")
        return """{"code":"10000","msg":"Success","out_trade_no":"$out","trade_no":"${tradeNos[out]}"}"""
    }

    /**
     * 查单：状态用支付宝原话（`TRADE_SUCCESS` / `WAIT_BUYER_PAY`），**不归一化**。
     * 归一化是 [com.ecshopx.kotlin.service.pay.AliPayChannel] 的职责，这一层越是原样照抄，
     * 漏归一化那种"报文完全正常、掉单永远补不上"的错就越藏不住。
     */
    private fun query(out: String): String {
        val st = state[out] ?: return """{"code":"40004","msg":"Business Failed","sub_code":"ACQ.TRADE_NOT_EXIST","sub_msg":"交易不存在"}"""
        val amt = amounts[out].orEmpty()
        val tn = tradeNos[out] ?: ""
        val base = """"code":"10000","msg":"Success","out_trade_no":"$out","trade_no":"$tn","total_amount":"$amt","""
        return if (st == "TRADE_SUCCESS") {
            """{$base"trade_status":"TRADE_SUCCESS","buyer_logon_id":"${SimAli.BUYER_LOGON}",""" +
                """"buyer_user_id":"${SimAli.BUYER_ID}","receipt_amount":"$amt","buyer_pay_amount":"$amt",""" +
                """"invoice_amount":"$amt","point_amount":"0.00"}"""
        } else {
            """{$base"trade_status":"$st","buyer_user_id":"${SimAli.BUYER_ID}"}"""
        }
    }

    /** 关单：已支付的交易关不掉，回 `ACQ.TRADE_HAS_SUCCESS` —— 渠道给了终局结论，重试只是撞同一句 */
    private fun close(out: String): String {
        val tn = tradeNos[out] ?: ""
        if (state[out] == "TRADE_SUCCESS") {
            return """{"code":"40004","msg":"Business Failed","sub_code":"ACQ.TRADE_HAS_SUCCESS",""" +
                """"sub_msg":"交易已经支付，不允许撤销","out_trade_no":"$out","trade_no":"$tn"}"""
        }
        state[out] = "TRADE_CLOSED"
        return """{"code":"10000","msg":"Success","out_trade_no":"$out","trade_no":"$tn"}"""
    }

    /**
     * 退款：支付宝的 `trade.refund` 是**同步**给结果的（没有"等回执"这一态），
     * 所以渠道侧只需把 `fund_change=Y` 写进应答，退款单当场就能落终态。
     */
    private fun refund(biz: String, out: String): String {
        val tn = tradeNos[out] ?: ""
        if (state[out] != "TRADE_SUCCESS") {
            return """{"code":"40004","msg":"Business Failed","sub_code":"ACQ.TRADE_NOT_SUCCESS",""" +
                """"sub_msg":"交易未支付，无法退款","out_trade_no":"$out","trade_no":"$tn"}"""
        }
        val refundYuan = bizOf(biz, "refund_amount")
        val outRequestNo = bizOf(biz, "out_request_no")
        lastRefundRequestNo = outRequestNo
        return """{"code":"10000","msg":"Success","out_trade_no":"$out","trade_no":"$tn",""" +
            """"buyer_logon_id":"${SimAli.BUYER_LOGON}","fund_change":"Y","refund_fee":"$refundYuan",""" +
            """"send_back_fee":"$refundYuan","has_deposit_back":"false"}"""
    }

    /** 最近一次退款请求的 `out_request_no`（幂等键 = 退款单号） */
    @Volatile
    var lastRefundRequestNo: String = ""

    private fun parseForm(raw: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        raw.split('&').forEach { kv ->
            if (kv.isNotBlank()) {
                val i = kv.indexOf('=')
                val k = if (i < 0) kv else kv.substring(0, i)
                val v = if (i < 0) "" else kv.substring(i + 1)
                m[decode(k)] = decode(v)
            }
        }
        return m
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, SimAli.CHARSET)
    } catch (e: Exception) {
        s
    }

    /** biz_content 就是一段 JSON 文本，按字段取值即可（夹具不需要完整解析器） */
    private fun bizOf(biz: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(biz)?.groupValues?.get(1) ?: ""

    private fun write(ex: HttpExchange, body: String) {
        lastBody = body
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json;charset=${SimAli.CHARSET}")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}

/**
 * 仿真异步通知：form-urlencoded + 全参签名，字段与真通知同构。
 *
 * 待签串用 SDK 自己的 `getSignCheckContentV1`（去掉 `sign`/`sign_type` 后按 key 升序拼），
 * 与 [com.ecshopx.kotlin.service.pay.AliPayChannel.parseFormNotification] 里的 `rsaCheckV1` 是同一口径——
 * 这正是真支付宝通知的验签方式（`sign_type` 是否参与签名是 V1/V2 的唯一区别，配错必验不过）。
 */
class SimAliNotification(val params: Map<String, String>) {

    companion object {
        /** 正常报文：真签。`status` 给 `WAIT_BUYER_PAY` 就是乱序用例要的"钱还没到的那一封" */
        fun of(
            outTradeNo: String,
            tradeNo: String,
            totalYuan: String,
            status: String = "TRADE_SUCCESS",
            appId: String = SimAli.APP_ID,
        ): SimAliNotification = signed(
            linkedMapOf(
                "notify_time" to SimAli.now(),
                "notify_id" to "SIMN${System.nanoTime()}",
                "notify_type" to "trade_status_sync",
                "trade_status" to status,
                "trade_no" to tradeNo,
                "out_trade_no" to outTradeNo,
                "app_id" to appId,
                "charset" to SimAli.CHARSET,
                "version" to "1.0",
                "buyer_id" to SimAli.BUYER_ID,
                "buyer_logon_id" to SimAli.BUYER_LOGON,
                "seller_id" to SimAli.SELLER_ID,
                "seller_email" to "sim-seller@alitest.com",
                "total_amount" to totalYuan,
                "receipt_amount" to totalYuan,
                "buyer_pay_amount" to totalYuan,
                "invoice_amount" to totalYuan,
                "point_amount" to "0.00",
                "discount_amount" to "0.00",
                "subject" to "仿真订单",
                "body" to "仿真订单",
            ),
        )

        /** 用平台私钥按 V1 口径签名（该方法会删掉传入 map 的 sign/sign_type，所以先复制） */
        fun signed(params: Map<String, String>): SimAliNotification {
            val copy = LinkedHashMap(params)
            val content = AlipaySignature.getSignCheckContentV1(copy)
            val sign = AlipaySignature.rsa256Sign(content, SimWx.platformPrivateKeyB64, SimAli.CHARSET)
            return SimAliNotification(LinkedHashMap(params).apply { put("sign", sign); put("sign_type", SimAli.SIGN_TYPE) })
        }

        /** 假签名：拿不到平台私钥的人只能随手填一段 base64 */
        fun forgedSignature(src: SimAliNotification): SimAliNotification =
            SimAliNotification(src.params + ("sign" to "U0lNLWFsaWZvcmdlZA=="))

        /** 改包保留原签名：中间人把金额改了、签名没动（金额对不上之外，验签这一关也过不去） */
        fun tampered(src: SimAliNotification, mutate: (Map<String, String>) -> Map<String, String>): SimAliNotification =
            SimAliNotification(mutate(LinkedHashMap(src.params)))

        /**
         * 签对了但身份不对：用**商户自己的私钥**签通知。
         * 真实故障形态是密钥填反（把商户公钥配成支付宝公钥），这里用同一招反证验签确实在按平台密钥判。
         */
        fun signedByWrongKey(params: Map<String, String>): SimAliNotification {
            val copy = LinkedHashMap(params)
            val content = AlipaySignature.getSignCheckContentV1(copy)
            val sign = AlipaySignature.rsa256Sign(content, SimWx.merchantPrivateKeyB64, SimAli.CHARSET)
            return SimAliNotification(LinkedHashMap(params).apply { put("sign", sign); put("sign_type", SimAli.SIGN_TYPE) })
        }
    }
}
