package com.ecshopx.kotlin.boot

import com.wechat.pay.java.core.util.NonceUtil
import com.wechat.pay.java.core.util.PemUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * M1-2 本地仿真夹具（docs/05 §M1-2 门槛的等价实现）。
 *
 * 门槛原文是「沙箱真单全链路 + 回调乱序/重复/伪造签名用例」。真沙箱要商户号、appid、两把密钥和公网回调地址，
 * 这些都不是仓库里能放的东西，所以本夹具把**唯一拿不到的东西（渠道的私钥与签名权）在本地造出来**，
 * 其余每一步都走真实代码路径：
 *
 *  - 密钥与证书：`src/test/resources/sim/mockwx.p12` 里两把自签 RSA-2048（`merchant` 与 `platform`），
 *    由 keytool 生成，纯仿真、无任何生产价值，仓库里可以放；
 *  - 请求侧：SDK 用商户私钥真签 `Authorization`，假服务器用商户证书**真验签**（验不过就记 false，测试断言它）；
 *  - 应答侧：假服务器用平台私钥按 `timestamp\nnonce\nbody\n` 真签 `Wechatpay-*` 头，
 *    SDK 的 `WechatPay2Validator` 用平台证书真验签（`AbstractHttpClient.validateResponse` 对每个响应都验，
 *    包括 204 空应答，所以这里一个都不能漏签）；
 *  - 回调侧：夹具按 AEAD_AES_256_GCM 真加密资源、真签信封，应用用 SDK `NotificationParser` 验签+解密。
 *
 * 唯一的接缝是 `ecx.pay.wxpay.base-url`：SDK 的域名来自不可改的 `HostName` 枚举，所以请求在 OkHttp 层
 * 只换 scheme+host+port，**路径与查询串原样保留**——签名覆盖的正是"方法+路径+查询串+body"，
 * 于是验签链路一步没少。真接沙箱时把这一项换成网关地址即可，代码零改动。
 */
object SimWx {
    const val KEYSTORE = "/sim/mockwx.p12"
    const val PASSWORD = "changeit"
    const val MCH_ID = "1900000109"
    const val APP_ID = "wxecxsim0000000001"

    /** APIv3 密钥必须 32 字节；这是仿真值，不是任何环境的凭据 */
    const val API_V3_KEY = "ECX-SIM-WXPAY-AES256GCM-KEY-0001"

    const val SIGN_TYPE = "WECHATPAY2-SHA256-RSA2048"

    private fun keystore(): KeyStore = KeyStore.getInstance("PKCS12").apply {
        val stream = SimWx::class.java.getResourceAsStream(KEYSTORE)
            ?: throw IllegalStateException("缺少仿真密钥库 $KEYSTORE")
        stream.use { load(it, PASSWORD.toCharArray()) }
    }

    private fun certOf(alias: String): X509Certificate {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(
            ByteArrayInputStream(keystore().getCertificate(alias)!!.encoded),
        ) as X509Certificate
    }

    private fun keyOf(alias: String): PrivateKey {
        val raw = keystore().getKey(alias, PASSWORD.toCharArray()) ?: throw IllegalStateException("密钥库缺 $alias 私钥")
        // JCA 从 PKCS12 取出的 RSA 私钥编码就是 PKCS#8 DER，正是 SDK `loadPrivateKeyFromString` 期望的内容
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(raw.encoded))
    }

    val merchantKey: PrivateKey by lazy { keyOf("merchant") }
    val platformKey: PrivateKey by lazy { keyOf("platform") }
    val merchantCert: X509Certificate by lazy { certOf("merchant") }
    val platformCert: X509Certificate by lazy { certOf("platform") }

    /** 与 SDK 完全同一套 PEM/serial 口径：配置里给什么，测试就用什么 */
    val merchantPrivateKeyPem: String by lazy { pem("PRIVATE KEY", merchantKey.encoded) }
    val platformCertPem: String by lazy { pem("CERTIFICATE", platformCert.encoded) }
    val merchantSerial: String by lazy { PemUtil.getSerialNumber(merchantCert) }
    val platformSerial: String by lazy { PemUtil.getSerialNumber(platformCert) }

    private fun pem(header: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $header-----\n$body\n-----END $header-----\n"
    }

    /**
     * 支付宝侧要的是**裸 base64**（不带 PEM 头、不折行）：私钥给 PKCS#8 DER，公钥给 X.509 SPKI DER，
     * 与微信用的其实是 `mockwx.p12` 里同样那两把密钥、同两个身份——只是序列化口径不同。
     * 商户身份与平台身份必须各用各的：SDK 的 `checkAlipayPublicKey` 一旦发现"商户私钥能对上所配公钥"
     * 就断定密钥配错并直接抛异常，这正是把两把公钥填反的经典形态。
     */
    private fun b64(der: ByteArray): String = Base64.getEncoder().encodeToString(der)

    val merchantPrivateKeyB64: String by lazy { b64(merchantKey.encoded) }
    val merchantPublicKeyB64: String by lazy { b64(merchantCert.publicKey.encoded) }
    val platformPrivateKeyB64: String by lazy { b64(platformKey.encoded) }
    val platformPublicKeyB64: String by lazy { b64(platformCert.publicKey.encoded) }

    fun sign(key: PrivateKey, message: String): String {
        val s = Signature.getInstance("SHA256withRSA")
        s.initSign(key)
        s.update(message.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(s.sign())
    }

    fun verify(cert: X509Certificate, message: String, signatureB64: String): Boolean = try {
        val v = Signature.getInstance("SHA256withRSA")
        v.initVerify(cert)
        v.update(message.toByteArray(Charsets.UTF_8))
        v.verify(Base64.getDecoder().decode(signatureB64))
    } catch (e: Exception) {
        false
    }

    /** 回调用加密：与 SDK 的 AeadAesCipher 同参数（128 位 tag、nonce 与 AAD 都做 GCM 输入） */
    fun encrypt(plain: String, nonce: String, associatedData: String): String {
        require(API_V3_KEY.toByteArray(Charsets.UTF_8).size == 32) { "APIv3 密钥必须 32 字节" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(API_V3_KEY.toByteArray(Charsets.UTF_8), "AES"),
            GCMParameterSpec(128, nonce.toByteArray(Charsets.UTF_8)),
        )
        if (associatedData.isNotEmpty()) c.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
}

/**
 * 假渠道服务器：把 JSAPI 下单、查单、关单、退款四个 v3 接口在本地实现一遍，
 * 状态由测试驱动（[markPaid] / [setState] / [refundStatus]），因此"回调丢失""渠道还没付"这些
 * 真实世界才有的时序都能在测试里定点复现。
 *
 * **为什么是裸 [ServerSocket] 而不是 `com.sun.net.httpserver.HttpServer`**：
 * SDK 的 `HttpHeaders` 是普通 `HashMap`，`WechatPay2Validator` 按**原样大小写**取
 * `Wechatpay-Timestamp` / `Wechatpay-Signature` / `Wechatpay-Serial`；而 JDK 自带的 HttpServer 写响应时
 * 会把响应头名规范化成"首字母大写、其余全小写"（实测 `Wechatpay-Timestamp` 上线变成 `Wechatpay-timestamp`），
 * 于是 SDK 取不到时间戳，直接报 `timestamp[null] of httpResponse is invalid`。
 * 真实渠道不会改头名，夹具也就不能改——只能自己按字节写响应头。
 *
 * 每个应答都带 `Connection: close` 并关掉连接：不做 keep-alive，测试里请求量小，
 * 这样省掉粘包/半包的连接管理，出问题时也不会因为复用连接看到上一笔的残留。
 */
class SimWxPayServer {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    /** out_trade_no -> 渠道侧状态（NOTPAY/SUCCESS/CLOSED/REFUND） */
    private val state = ConcurrentHashMap<String, String>()

    /** out_trade_no -> 渠道实际收款额（分），查单应答与回调金额都取自这里 */
    private val paid = ConcurrentHashMap<String, Int>()
    val paths = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** 最近一次请求的 Authorization 是否通过商户证书验签 —— 测试据此断言"真签真验"确实发生了 */
    @Volatile
    var lastAuthVerified: Boolean? = null

    @Volatile
    var authChecks = 0

    /** 最近一次应答（码与体）：退款"等回执"这个中间态要由渠道应答体来证明，不是应用自己编的 */
    @Volatile
    var lastRes: Pair<Int, String> = 0 to ""

    /** 退款回执状态：默认 PROCESSING（真实退款多数先受理再回执），测试可改成 SUCCESS 走同步分支 */
    @Volatile
    var refundStatus = "PROCESSING"

    val prepayIds = ConcurrentHashMap<String, String>()
    var baseUrl: String = "http://127.0.0.1:${socket.localPort}"
        private set

    @Volatile
    private var running = false

    fun start(): SimWxPayServer {
        running = true
        Thread {
            while (running) {
                val conn = try {
                    socket.accept()
                } catch (e: Exception) {
                    continue
                }
                try {
                    serve(conn.getInputStream(), conn.getOutputStream())
                } catch (_: Exception) {
                    // 半包 / 客户端提前断开：渠道不需要错误页，丢掉这一笔即可；
                    // 真写错了测试会因为断言不过而暴露，不靠这里兜住
                } finally {
                    runCatching { conn.close() }
                }
            }
        }.apply { isDaemon = true; name = "sim-wxpay" }.start()
        return this
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    fun markPaid(outTradeNo: String, total: Int) {
        state[outTradeNo] = "SUCCESS"
        paid[outTradeNo] = total
    }

    fun setState(outTradeNo: String, value: String) {
        state[outTradeNo] = value
    }

    fun stateOf(outTradeNo: String): String? = state[outTradeNo]

    /** 读一次请求（请求行 + 头 + 按 Content-Length 取体），按路由算出应答并写出去 */
    private fun serve(inp: InputStream, out: OutputStream) {
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return
        val method = requestLine[0]
        // 请求行里的 target 就是"原始路径+查询串"，正是 SDK 签名覆盖的那段，一个字都不解码
        val target = requestLine[1]
        val headers = LinkedHashMap<String, String>()
        lines.drop(1).forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val body = readBody(inp, headers["content-length"]?.toIntOrNull() ?: 0)
        val path = target.substringBefore("?")
        paths.add("$method $path")
        lastAuthVerified = verifyAuth(headers, method, target, body)
        authChecks++
        val res = when {
            method == "POST" && path == "/v3/pay/transactions/jsapi" -> prepay(body)
            method == "GET" && path.startsWith("/v3/pay/transactions/out-trade-no/") -> query(path)
            method == "POST" && path.endsWith("/close") -> close(path)
            method == "POST" && path == "/v3/refund/domestic/refunds" -> refund(body)
            else -> Res(404, """{"code":"NOT_FOUND","message":"$path"}""")
        }
        lastRes = res.code to res.body
        respond(out, res.code, res.body)
    }

    private class Res(val code: Int, val body: String)

    /**
     * 逐字节读到空行为止。**不能用 BufferedReader/readLine**：它会预读，把 body 的前一段吞进缓冲区，
     * 于是 `total` / `out_trade_no` 就取不到了——渠道验签也必然失败。
     */
    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = inp.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray(), Charsets.UTF_8)
            buf.write(b)
            tail = (tail shl 8) or (b and 0xFF)
            if (tail == 0x0D0A0D0A) return String(buf.toByteArray(), 0, buf.size() - 4, Charsets.UTF_8)
        }
    }

    private fun readBody(inp: InputStream, len: Int): String {
        if (len <= 0) return ""
        val bytes = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(bytes, off, len - off)
            if (n < 0) break
            off += n
        }
        return String(bytes, 0, off, Charsets.UTF_8)
    }

    /**
     * SDK 的待签串是 `method\nrawPath[?query]\ntimestamp\nnonce\nbody\n`
     * （`WechatPay2Credential.buildMessage` 用 `URI.getRawPath()` + `getQuery()`），照抄一遍才能验得过。
     */
    private fun verifyAuth(headers: Map<String, String>, method: String, target: String, body: String): Boolean {
        val auth = headers["authorization"] ?: return false
        val parts = Regex("""(mchid|nonce_str|timestamp|serial_no|signature)="([^"]*)"""")
            .findAll(auth).associate { it.groupValues[1] to it.groupValues[2] }
        val signature = parts["signature"] ?: return false
        val message = "$method\n$target\n${parts["timestamp"]}\n${parts["nonce_str"]}\n$body\n"
        // serial_no 必须指向商户证书：拿平台证书来签商户请求 = 身份不对
        if (parts["serial_no"] != SimWx.merchantSerial) return false
        return SimWx.verify(SimWx.merchantCert, message, signature)
    }

    private fun jsonOf(body: String, key: String): String? =
        Regex(""""$key"\s*:\s*"?([\w-]+)"?""").find(body)?.groupValues?.get(1)

    private fun prepay(body: String): Res {
        val outTradeNo = jsonOf(body, "out_trade_no") ?: return Res(400, """{"code":"INVALID_REQUEST"}""")
        val total = jsonOf(body, "total")?.toIntOrNull() ?: 0
        prepayIds[outTradeNo] = "SIMWXP$outTradeNo"
        // 下单成功 ≠ 付了钱：渠道侧留 NOTPAY，等回调或查单才变 SUCCESS
        state.putIfAbsent(outTradeNo, "NOTPAY")
        paid[outTradeNo] = total
        return Res(200, """{"prepay_id":"${prepayIds[outTradeNo]}"}""")
    }

    private fun query(path: String): Res {
        val outTradeNo = path.removePrefix("/v3/pay/transactions/out-trade-no/").substringBefore("/")
        val status = state[outTradeNo] ?: "NOTPAY"
        val total = paid[outTradeNo] ?: 0
        if (status != "SUCCESS") {
            return Res(
                200,
                """{"mchid":"${SimWx.MCH_ID}","appid":"${SimWx.APP_ID}","out_trade_no":"$outTradeNo",""" +
                    """"trade_state":"$status","trade_state_desc":"未支付","amount":{"total":0,"payer_total":0,"currency":"CNY"}}""",
            )
        }
        return Res(
            200,
            """{"mchid":"${SimWx.MCH_ID}","appid":"${SimWx.APP_ID}","out_trade_no":"$outTradeNo",""" +
                """"transaction_id":"SIMTX$outTradeNo","trade_state":"SUCCESS","trade_state_desc":"支付成功",""" +
                """"success_time":"${Instant.now()}","payer":{"openid":"sim-openid-0001"},""" +
                """"amount":{"total":$total,"payer_total":$total,"currency":"CNY"}}""",
        )
    }

    private fun close(path: String): Res {
        val outTradeNo = path.removePrefix("/v3/pay/transactions/out-trade-no/").substringBeforeLast("/close")
        // 渠道已付的单关不掉：这正是补单作业把"已支付"当终局结论、停止重发的依据（口径见 WxPayV3Channel.closeTrade）
        if (state[outTradeNo] == "SUCCESS") {
            return Res(400, """{"code":"ORDERPAID","message":"订单已支付，不可关闭"}""")
        }
        state[outTradeNo] = "CLOSED"
        return Res(204, "")
    }

    private fun refund(body: String): Res {
        val outRefundNo = jsonOf(body, "out_refund_no") ?: return Res(400, """{"code":"INVALID_REQUEST"}""")
        val outTradeNo = jsonOf(body, "out_trade_no") ?: ""
        val total = paid[outTradeNo] ?: 0
        val refundFee = jsonOf(body, "refund")?.toIntOrNull() ?: total
        // PROCESSING 与 SUCCESS 都回同一个 refund_id：微信按 out_refund_no 幂等，重复申请不会多退一笔
        val id = "SIMREF$outRefundNo"
        if (refundStatus == "SUCCESS") state[outTradeNo] = "REFUND"
        // 分段拼接的原始字符串必须在「`",`」处断开、下一段以 `"` 开头；
        // 写成 `,"` 断开就会多出一对引号，SDK 报 `Invalid json response body[..."refund":8900,""payer_total"...]`
        // ——这是 M1-2 实跑出来的真错，字段名也必须照 v3 文档用 transaction_id（不是 trade_id）
        return Res(
            200,
            """{"refund_id":"$id","out_refund_no":"$outRefundNo","out_trade_no":"$outTradeNo",""" +
                """"transaction_id":"SIMTX$outTradeNo","status":"$refundStatus","channel":"ORIGINAL",""" +
                """"user_received_account":"微信支付原路退回",""" +
                """"amount":{"total":$total,"refund":$refundFee,"payer_total":$total,"payer_refund":$refundFee,"currency":"CNY"}}""",
        )
    }

    /**
     * 每个应答都签：SDK 对 2xx（含 204 空 body）一律验签，漏一个就是 ValidationException。
     * 头名一律按 SDK 读取的原样大小写写出（见类注释），空应答只给状态行 + 签名头 + `Connection: close`。
     */
    private fun respond(out: OutputStream, code: Int, body: String) {
        val ts = Instant.now().epochSecond.toString()
        val nonce = NonceUtil.createNonce(32)
        val message = "$ts\n$nonce\n$body\n"
        val status = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            else -> "Not Found"
        }
        val head = StringBuilder("HTTP/1.1 $code $status\r\n")
            .append("Server: ECX-SimWxPay\r\n")
            .append("Date: ").append(
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)),
            ).append("\r\n")
            .append("Wechatpay-Timestamp: ").append(ts).append("\r\n")
            .append("Wechatpay-Nonce: ").append(nonce).append("\r\n")
            .append("Wechatpay-Signature: ").append(SimWx.sign(SimWx.platformKey, message)).append("\r\n")
            .append("Wechatpay-Serial: ").append(SimWx.platformSerial).append("\r\n")
            .append("Wechatpay-Signature-Type: ").append(SimWx.SIGN_TYPE).append("\r\n")
        if (code == 204 || body.isEmpty()) {
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            return
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        head.append("Content-Type: application/json;charset=utf-8\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}

/**
 * 仿真回调报文构造：资源体按明文 JSON 组好 → AES-GCM 加密进 `resource.ciphertext`，
 * 信封再按 `timestamp\nnonce\nbody\n` 用平台私钥签名，产出的头与体直接 POST 给 `/api/notify/wxpay`。
 * 与真实 v3 报文的差别只剩"证书是自签的"，验签、解密、金额核对全部按真渠道的规矩走。
 */
class SimWxNotification(val body: String, val headers: Map<String, String>) {

    companion object {
        /** 正常报文：真加密 + 真签名 */
        fun signed(plain: String, eventType: String, associatedData: String): SimWxNotification {
            val nonce = NonceUtil.createNonce(16)
            val cipher = SimWx.encrypt(plain, nonce, associatedData)
            val body = """{"id":"SIMN${System.nanoTime()}","create_time":"${Instant.now()}",""" +
                """"resource_type":"encrypt-resource","event_type":"$eventType","summary":"仿真回执",""" +
                """"resource":{"original_type":"${if (eventType.startsWith("REFUND")) "refund" else "transaction"}",""" +
                """"algorithm":"AEAD_AES_256_GCM","ciphertext":"$cipher",""" +
                """"associated_data":"$associatedData","nonce":"$nonce"}}"""
            val ts = Instant.now().epochSecond.toString()
            val signNonce = NonceUtil.createNonce(32)
            return SimWxNotification(
                body,
                linkedMapOf(
                    "Wechatpay-Timestamp" to ts,
                    "Wechatpay-Nonce" to signNonce,
                    // 待签串与 SDK 校验口径一致：timestamp\nnonce\nbody\n
                    "Wechatpay-Signature" to SimWx.sign(SimWx.platformKey, "$ts\n$signNonce\n$body\n"),
                    "Wechatpay-Serial" to SimWx.platformSerial,
                    "Wechatpay-Signature-Type" to SimWx.SIGN_TYPE,
                ),
            )
        }

        /** 改包：报文体换了、签名还是原来那段（等价于中间人改了金额） */
        fun tampered(src: SimWxNotification, mutate: (String) -> String): SimWxNotification =
            SimWxNotification(mutate(src.body), src.headers)

        /** 假签名：攻击者拿不到平台私钥，只能随手填一段 base64 */
        fun forgedSignature(src: SimWxNotification): SimWxNotification =
            SimWxNotification(src.body, src.headers + ("Wechatpay-Signature" to "U0lNLWZvcmdlZC1zaWduYXR1cmU="))

        /** 假证书号：用商户证书号来"签"平台报文，serial 查不到平台证书 */
        fun forgedSerial(src: SimWxNotification): SimWxNotification =
            SimWxNotification(src.body, src.headers + ("Wechatpay-Serial" to SimWx.merchantSerial))

        /** 过期时间戳：验签本身能过，但超过 5 分钟新鲜期 */
        fun expired(src: SimWxNotification): SimWxNotification {
            val ts = (Instant.now().epochSecond - 600).toString()
            val nonce = NonceUtil.createNonce(32)
            return SimWxNotification(
                src.body,
                src.headers + mapOf(
                    "Wechatpay-Timestamp" to ts,
                    "Wechatpay-Nonce" to nonce,
                    "Wechatpay-Signature" to SimWx.sign(SimWx.platformKey, "$ts\n$nonce\n${src.body}\n"),
                ),
            )
        }

        /**
         * 交易回执（明文资源体按 v3 的字段写，金额用渠道实收 total_paid）。
         * `tradeState` 可给 `NOTPAY`/`PAYERROR`——乱序用例要的就是"钱还没到就先来的那封"，
         * 它同样经过真签真解密，只有结论是负的。
         */
        fun paid(
            outTradeNo: String,
            total: Int,
            transactionId: String,
            tradeState: String = "SUCCESS",
        ): SimWxNotification = signed(
            """{"mchid":"${SimWx.MCH_ID}","appid":"${SimWx.APP_ID}","out_trade_no":"$outTradeNo",""" +
                """"transaction_id":"$transactionId","trade_type":"JSAPI","trade_state":"$tradeState",""" +
                """"trade_state_desc":"${if (tradeState == "SUCCESS") "支付成功" else "未支付"}",""" +
                """"bank_type":"OTHERS","success_time":"${Instant.now()}",""" +
                """"payer":{"openid":"sim-openid-0001"},""" +
                """"amount":{"total":$total,"total_paid":$total,"payer_total":$total,"currency":"CNY"}}""",
            if (tradeState == "SUCCESS") "TRANSACTION.SUCCESS" else "TRANSACTION.PENDING",
            "transaction",
        )

        /** 退款回执：`refund_status` 决定应用把退款单推到 SUCCESS 还是 CHANGE */
        fun refund(outTradeNo: String, outRefundNo: String, refundId: String, refundStatus: String, total: Int): SimWxNotification =
            signed(
                """{"mchid":"${SimWx.MCH_ID}","out_trade_no":"$outTradeNo","out_refund_no":"$outRefundNo",""" +
                    """"refund_id":"$refundId","refund_status":"$refundStatus",""" +
                    """"success_time":"${Instant.now()}","user_received_account":"微信支付原路退回",""" +
                    """"amount":{"total":$total,"refund":$total,"payer_total":$total,"payer_refund":$total,"currency":"CNY"}}""",
                if (refundStatus == "SUCCESS") "REFUND.SUCCESS" else "REFUND.ABNORMAL",
                "refund",
            )
    }
}
