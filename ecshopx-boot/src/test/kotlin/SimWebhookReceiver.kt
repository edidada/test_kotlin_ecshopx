package com.ecshopx.kotlin.boot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 假接收端的应答形态（§25.4 状态机的三条入路都靠它造出来） */
enum class SimReceiveMode {
    /** 200：投递成功 */
    OK,

    /** 下一次 500、之后恢复 200 —— 造"先失败一次再成功、`attempts=2`"那一条 */
    FAIL_ONCE,

    /** 一直 500 —— 造退避与终态 `fail` */
    FAIL_ALWAYS,

    /** 连应答都不给（直接关连接）：等价于对端挂了，我方必须按"可重试失败 + `last_http_status=0`"处理 */
    DROP,
}

/**
 * 假 Webhook 接收端（docs/04 §25.6 门槛③要求的 `SimWebhookReceiver`）。
 *
 * 与 `SimAliPayServer` / `SimWxPayServer` 同一夹具惯例：**唯一拿不到的东西是对端的视角**，
 * 那就把对端造出来。这里刻意**不复用产品代码里的 `OpenapiSign`**：
 * HMAC 与 `assemble`/md5 两支都在这里各自重写一遍（[ownHmac] / [ownAssemble]），
 * 理由是"发送侧用自己要验的函数验自己"证不了任何事 —— 两边实现同一个 PHP 语义、
 * 互不知情才算对上（§25.6 门槛①那句"跨语言一致性"的 JUnit 侧等价物；
 * 另一份独立实现是 `tools/m21_openapi_smoke.py`，那份连语言都不同）。
 *
 * 三个记录点各自服务一条断言：
 *  - `hits` 存**原始字节**：HMAC 覆盖的是收到的那串字节，不是重新序列化后的 JSON，
 *    这里若先 parse 再 `toString()` 回来验，就等于替产品代码掩盖了"先序列化后签名"的顺序错；
 *  - 头名一律转小写存：`X-Ecx-Signature` 与 `x-ecx-signature` 在 HTTP/1.1 里是同一个头，
 *    夹具按大小写敏感存会让断言写成玄学；
 *  - `mode` 是 `@Volatile`：投递发生在业务线程（`afterCommit` 唤醒），改模式的是测试线程。
 */
class SimWebhookReceiver(private val mapper: ObjectMapper) {

    /**
     * 一次收到的推送：头（小写名）+ 原始字节。
     *
     * `mapper` 由外层注入而不是让 [Hit] 直接闭包外层的构造参数 —— 嵌套类看不见外围类的属性，
     * 而这里必须让 `json()` 无参可调用（测试里读载荷字段全靠它，`hit.json().path("data")` 才是人话）。
     */
    data class Hit(
        val headers: Map<String, String>,
        val raw: ByteArray,
        private val mapper: ObjectMapper,
    ) {
        val body: String get() = String(raw, Charsets.UTF_8)

        fun header(name: String): String? = headers[name.lowercase()]

        /** 信封（含 `sign`）；解析不出来时返回缺失节点而不是抛，让断言说"第 3 封不是 JSON" */
        fun json(): JsonNode = mapper.readTree(raw)

        override fun equals(other: Any?): Boolean =
            other is Hit && other.headers == headers && other.raw.contentEquals(raw)

        override fun hashCode(): Int = headers.hashCode() * 31 + raw.contentHashCode()
    }

    val hits = CopyOnWriteArrayList<Hit>()

    @Volatile
    var mode: SimReceiveMode = SimReceiveMode.OK

    val url: String get() = "http://127.0.0.1:${server.address.port}/hook"

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    fun start(): SimWebhookReceiver {
        server.createContext("/hook") { ex -> handle(ex) }
        server.executor = null
        server.start()
        return this
    }

    fun stop() {
        server.stop(0)
    }

    /** 只留 `topic` 那几封（订阅面同时挂着多个 topic 时用它把噪声去掉） */
    fun hitsOf(topic: String): List<Hit> = hits.filter { it.json().path("topic").asText() == topic }

    /** 某个订单的某类事件（载荷里的 `order_id` 是判据，不用数总数） */
    fun hitsFor(topic: String, orderId: Any): List<Hit> = hitsBy(topic, "order_id", orderId)

    /** 按载荷字段筛（商品事件用的是 `item_id`，关单用的是 `reason`，同一支函数够用） */
    fun hitsBy(topic: String, field: String, value: Any): List<Hit> = hitsOf(topic).filter {
        it.json().path("data").path(field).asText() == value.toString()
    }

    /** 某一封的载荷字段名清单（门槛③"载荷字段逐个对"就是拿它和 `WebhookTopics` 的目录对） */
    fun dataFields(hit: Hit): Set<String> = hit.json().path("data").fieldNames().asSequence().toSet()

    fun clear() {
        hits.clear()
    }

    /** 第一封的验签结果（门槛③"两支都验通"就是断这两句） */
    fun firstHmacOk(secret: String): Boolean = hits.firstOrNull()?.let { hmacOk(secret, it) } == true

    fun firstBodySignOk(secret: String): Boolean = hits.firstOrNull()?.let { bodySignOk(secret, it) } == true

    /** 头里的 HMAC 与本夹具按**原始字节**重算的值是否一致 */
    fun hmacOk(secret: String, hit: Hit): Boolean {
        val header = hit.header("x-ecx-signature") ?: return false
        val ts = hit.header("x-ecx-timestamp") ?: return false
        return header == "sha256=" + ownHmac(secret, ts + "\n" + hit.body)
    }

    /**
     * 体内 `sign` 与"去掉 `sign` 后按 PHP 语义重组再 md5"的结果是否一致。
     *
     * `skip = "sign"` 是这一支的命门：发送侧是**先算签、后把 `sign` 追加进信封**
     * （`WebhookDeliveryService.buildBody`），所以对端重组时必须把这一位摘掉；
     * 留着它就是把"签覆盖了自己"当成契约，那既不是 §25.4 写的规则，也永远对不上。
     */
    fun bodySignOk(secret: String, hit: Hit): Boolean {
        val node = runCatching { hit.json() }.getOrNull() ?: return false
        val sign = node.path("sign").asText("")
        if (sign.isBlank()) return false
        return sign == md5Upper(secret + ownAssemble(node, "sign") + secret)
    }

    /** 四个头都到了吗（`X-Ecx-Msg-Id` 是对端判重用的，缺它整条幂等设计就断了） */
    fun hasAllHeaders(hit: Hit): Boolean =
        listOf("x-ecx-signature", "x-ecx-timestamp", "x-ecx-msg-id", "x-ecx-event")
            .all { !hit.header(it).isNullOrBlank() }

    private fun handle(ex: HttpExchange) {
        try {
            val raw = ex.requestBody.use { it.readBytes() }
            val headers = LinkedHashMap<String, String>()
            ex.requestHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.lastOrNull().orEmpty() }
            hits.add(Hit(headers, raw, mapper))
            when (takeMode()) {
                SimReceiveMode.DROP -> {
                    // 不发应答头、直接关掉：客户端看到的是"连接被重置/没有应答"，
                    // 这正是 §25.4 里"连接失败也算可重试失败"那一条的真实入路
                    return
                }
                SimReceiveMode.OK -> write(ex, 200, """{"ok":true}""")
                else -> write(ex, 500, """{"ok":false,"error":"simulated receiver failure"}""")
            }
        } catch (e: Exception) {
            runCatching { write(ex, 500, """{"error":"${e::class.simpleName}"}""") }
        } finally {
            runCatching { ex.close() }
        }
    }

    /** `FAIL_ONCE` 是"消耗一次"的模式：读过就自己回落成 `OK`，测试不必记得去改回来 */
    private fun takeMode(): SimReceiveMode = when (mode) {
        SimReceiveMode.FAIL_ONCE -> {
            mode = SimReceiveMode.OK
            SimReceiveMode.FAIL_ONCE
        }
        else -> mode
    }

    private fun write(ex: HttpExchange, status: Int, body: String): Unit {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // ---------- 独立实现的签名侧（刻意不调用产品代码） ----------

    private fun ownHmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun md5Upper(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).toHex().uppercase()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /**
     * 参与重组的「键 → 子节点」清单。
     *
     * 对象取真实键名，数组取下标字符串 —— PHP `json_decode($body, true)` 之后两者本来就是同一个
     * 关联数组，`ksort` 排的也就是这些下标（所以 `10` 排在 `2` 前面）。
     */
    private fun ownFields(node: JsonNode): List<Pair<String, JsonNode?>> =
        if (node.isArray) node.toList().mapIndexed { i: Int, e: JsonNode -> Pair(i.toString(), e) }
        else node.fieldNames().asSequence().map { Pair(it, node.get(it)) }.toList()

    /**
     * PHP `ksort(..., SORT_STRING)` + 逐项拼 "键+值" 的独立实现。
     * 规则与 §25.1 逐条对：null 整项跳过（键也不出现）、布尔 1/0、对象与数组递归、
     * 数组下标按**字符串序**参与（所以 10 排在 2 之前）、数字取报文里的字面量文本。
     *
     * [skip] 只在最外层用（摘掉 `sign` 自己），递归进去的子结构没有"跳过某一位"这回事。
     */
    private fun ownAssemble(node: JsonNode, skip: String? = null): String {
        val sb = StringBuilder()
        ownFields(node)
            .sortedWith { a, b -> BYTE_ORDER.compare(a.first, b.first) }
            .forEach { (k, v) ->
                if (k == skip || v == null || v.isNull) return@forEach
                sb.append(k).append(ownLeaf(v))
            }
        return sb.toString()
    }

    /** UTF-8 字节序（等价 PHP `SORT_STRING`）—— 夹具自己写一遍，不在这里调产品代码的比较器 */
    private val BYTE_ORDER = Comparator<String> { a, b ->
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }

    private fun ownLeaf(v: JsonNode): String = when {
        // 对象与数组走同一条assemble：数组只是"键为下标字符串的对象"（PHP `json_decode(..., true)`
        // 出来就是这个形状）。这里刻意不先 `createObjectNode()` 再 `set()` 转一遍 ——
        // Jackson 的 `ObjectNode.set` 是泛型方法，Kotlin 侧推不出 `T`，为了过编译去写显式类型参数
        // 只是给夹具添噪声；直接在 [ownAssemble] 里按下标取子节点才是这份语义本来的样子。
        v.isObject || v.isArray -> ownAssemble(v)
        v.isBoolean -> if (v.booleanValue()) "1" else "0"
        else -> v.asText()
    }
}
