package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.OpenapiContract
import com.ecshopx.kotlin.boot.contract.WebhookContract
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.openapi.OpenapiDeveloperMapper
import com.ecshopx.kotlin.service.openapi.OpenapiGatewayService
import com.ecshopx.kotlin.service.openapi.OpenapiMethodRegistry
import com.ecshopx.kotlin.service.openapi.OpenapiMetrics
import com.ecshopx.kotlin.service.openapi.OpenapiNonceStore
import com.ecshopx.kotlin.service.openapi.OpenapiSign
import com.ecshopx.kotlin.service.webhook.WebhookDeliveryService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M2-1 DoD（docs/04 §25.6 前三道门槛）：openapi 网关 + Webhook 出站端到端。
 *
 * 三条门槛各自有一个"只能这样证"的动作，本类围绕它们排：
 *  - **门槛①（签名链）**：闸门顺序就是错误码顺序，所以位次用例成组出现——
 *    "不带 sign 的未知 app_key 必须回 `E4004` 而不是 `E4003`"这一条单独成立，
 *    否则网关就在向没验过签的人回答"这个 app_key 存不存在"。
 *    时间窗**两个方向**都测（±601 拒、±599 过），只测超窗等于没测窗口。
 *    重放那条断的是**业务侧没有副作用**（积分一位没动），证明第 8 道闸门挡在委托之前。
 *    `assemble` 的 PHP 语义用**手写的期望串**钉住（含十一元素列表的 `"0","1","10","2"` 下标序）——
 *    拿实现算一遍再和实现比是自证，写死一串人算得出的期望才算对得上。
 *  - **门槛②（分发与租户边界）**：`ecx.order.get` 的出参与 `ORDER-9` 同一条单**逐层字段相同**，
 *    且只在手机号那几层不同（不同到 `Mask.mobile(后台值)` 这个程度）——
 *    这是"网关只是同一张脸的另一侧"的唯一机械化证明。
 *    第二个租户的密钥读业务数据得 **0 行**而不是报错，它写 `company_id=1` 则 `E4001`（fail-closed）。
 *  - **门槛③（出站链）**：假接收端 [SimWebhookReceiver] 自己实现一遍 HMAC 与 `assemble`（不调产品代码），
 *    两支签都验通；退避、终态 `fail`、手动重推同 `msg_id`、停用连 `pending` 都不生成、
 *    CAS 未命中不生成第二条事件。
 *
 * 演示数据沿用全仓那批（会员 13800000001、商品 3 = 8900 分、地址 1、`admin/admin123`、
 * `distributor01/shop123`），密钥是 seed 里的**假密钥**（只用于本地自签自验）。
 *
 * 一处测试配置值得说明：`ecx.webhook.max-attempts=2`、`backoff-base-seconds=1`。
 * 生产缺省是 5 次与 30 秒基数，但 e2e 不能为此睡三分钟；把基数压到 1 秒之后，
 * `2^1×1=2` 这一位反而**更容易钉住退避公式的形状**（断 `next_try_at` 落在 now+2 附近），
 * 真实基数下的"排到 60 秒之后"由 `tools/m21_openapi_smoke.py` 在 18080 上实测覆盖。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccm21;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.webhook-deliver-initial-delay-ms=3600000",
        "ecx.schedule.webhook-deliver-ms=3600000",
        "ecx.webhook.max-attempts=2",
        "ecx.webhook.backoff-base-seconds=1",
        "ecx.webhook.timeout-ms=1500",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class M21EndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var sender: WebhookDeliveryService
    @Autowired lateinit var developers: OpenapiDeveloperMapper
    @Autowired lateinit var nonceStore: OpenapiNonceStore
    @Autowired lateinit var registry: OpenapiMethodRegistry
    @Autowired lateinit var metrics: OpenapiMetrics

    /** 默认必须是 `false`：这一位为 true 时网关跳过第 3..8 道闸门，生产形态等于把开放面敞开 */
    @Value("\${ecx.openapi.debug:false}") private var openapiDebug: Boolean = false

    private var tok = ""
    private var admin = ""
    private var shop = ""
    private lateinit var receiver: SimWebhookReceiver

    /** OP-A2 建出来的第二个开发者（出站密钥缺失的 422 用例 + 新密钥即刻可用都靠它） */
    private var devNew = 0L
    private var akNew = ""
    private var skNew = ""
    private var subCreated = 0L
    private var subPaid = 0L
    private var oidSample = ""

    // ================= 调用件 =================

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "DELETE" -> delete(url)
            else -> put(url)
        }
        token?.let { req.header("Authorization", "Bearer $it") }
        body?.let { req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)) }
        return mvc.perform(req).andReturn()
    }

    private fun data(res: MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun ok(url: String, body: Map<String, Any?>? = null, asMember: String = tok): JsonNode =
        data(call(if (body == null) "GET" else "POST", url, asMember, body), url)

    private fun okAdmin(m: String, url: String, body: Map<String, Any?>? = null): JsonNode =
        data(call(m, url, admin, body), "admin $m $url")

    private fun expect(m: String, url: String, body: Map<String, Any?>?, code: Int, token: String = admin): JsonNode {
        val res = call(m, url, token, body)
        assertEquals(code, res.response.status, "$m $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    /**
     * 任何读面都不许回显密钥（§25.3 判据 1/2 的机械化断言）。
     *
     * 整棵树逐层查、不看值：`ContractDiff` 的"多出只回显"在这里正好反过来用 ——
     * 这一族的"多一个键"不是显示问题而是泄露，所以它得由一张黑名单判，而不是由期望键集判。
     */
    private fun assertNoSecret(node: JsonNode, label: String) {
        when {
            node.isObject -> {
                OpenapiContract.FORBIDDEN.forEach { k -> assertFalse(node.has(k), "$label 的某一层出现了键 $k") }
                node.forEach { assertNoSecret(it, label) }
            }
            node.isArray -> node.forEach { assertNoSecret(it, label) }
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun ts(offset: Long = 0): String = (now() + offset).toString()

    private fun nonce(): String = "N21" + System.nanoTime()

    /**
     * query 与 body 的分工：**只有业务参数进 body，公共位进 query**。
     * 这不是随意选择——对端最常见的形态就是这样（`?app_key=&timestamp=&sign=` 挂串、业务参数打 JSON），
     * 而网关签的是"合并后的全集"，所以这一份实现同时把 §25.1 那条合并语义也证了。
     */
    private val common = setOf("app_key", "timestamp", "version", "nonce", "request_id", "sign", "method", "debug", "company_id")

    private fun wire(v: Any?): String = when (v) {
        is Boolean -> if (v) "1" else "0"
        else -> v.toString()
    }

    private fun split(verb: String, params: Map<String, Any?>): Pair<Map<String, String>, Map<String, Any?>> {
        val q = LinkedHashMap<String, String>()
        val b = LinkedHashMap<String, Any?>()
        params.forEach { (k, v) ->
            val business = verb != "GET" && k !in common
            when {
                v == null -> if (business) b[k] = null      // 值为 null 只能表达在 body 里（query 没有"空值键"这种写法）
                business -> b[k] = v
                else -> q[k] = wire(v)
            }
        }
        return q to b
    }

    /**
     * 发一次网关调用，返回**信封**（HTTP 恒 200，所以这里先断状态码再断 `code`）。
     *
     * query 侧一律走 [MockHttpServletRequestBuilder.param] 而不是手拼百分号编码的查询串。
     * 这不是图省事：MockMvc **不对 URL 做百分号解码**（一次性夹具实测：`get("/e?t=2026-10-19%2023%3A12%3A41")`
     * 里 `@RequestParam` 拿到的是字面量 `2026-10-19%2023%3A12%3A41`，而 `.param("t", "2026-10-19 23:12:41")`
     * 拿到的是真串），所以手拼那份会让第 3 道闸门把日期时间戳判成"不合法"回 `E4002` ——
     * 症状看着像产品错，实际是测试框架的取值语义。那是框架的事，不是被测物的契约，不该由断言去迁就。
     * 真实 HTTP 的 query 编码由 `tools/m21_openapi_smoke.py` 覆盖，那一份是经 `requests` 真发出去的。
     */
    private fun openapi(
        params: Map<String, Any?>,
        secret: String = SK1,
        verb: String = "GET",
        pathStyle: Boolean = false,
        /** 在**签好之后**改包：值、布尔、嵌套数组、多塞一键，四种篡改都从这里进 */
        tamper: (MutableMap<String, Any?>) -> Unit = {},
    ): JsonNode {
        val toSign = LinkedHashMap(params)
        toSign.remove("sign")
        if (pathStyle) toSign.remove("method")               // OA-G2：method 走路径位就不参与签名
        val (q0, b0) = split(verb, toSign)
        val merged = LinkedHashMap<String, Any?>()
        q0.forEach { (k, v) -> merged[k] = v }
        b0.forEach { (k, v) -> merged[k] = v }
        val wire = LinkedHashMap(toSign)
        wire["sign"] = OpenapiSign.genSign(merged, secret)
        tamper(wire)
        val (q, b) = split(verb, wire)
        val base = if (pathStyle) "/api/openapi/${params["method"]}" else "/api/openapi"
        val req = when (verb) {
            "GET" -> get(base)
            "PUT" -> put(base)
            "DELETE" -> delete(base)
            else -> post(base).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(b))
        }
        q.forEach { (k, v) -> req.param(k, v) }
        val res = mvc.perform(req).andReturn()
        assertEquals(
            200, res.response.status,
            "开放面 HTTP 必须恒 200（§25.1）：$base ${params["method"]} -> " + utf8(res.response.contentAsByteArray),
        )
        val node = mapper.readTree(utf8(res.response.contentAsByteArray))
        assertContract(node, OpenapiContract.GATEWAY_ENVELOPE, "网关信封：$base ${params["method"]}")
        return node
    }

    private fun gw(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        version: String = "2.0",
        secret: String = SK1,
        verb: String = "GET",
        offset: Long = 0,
        nonceValue: String? = null,
        pathStyle: Boolean = false,
        tamper: (MutableMap<String, Any?>) -> Unit = {},
    ): JsonNode {
        val p = LinkedHashMap<String, Any?>()
        p["app_key"] = secretKeyOf(secret)
        p["method"] = method
        p["version"] = version
        p["timestamp"] = ts(offset)
        p["nonce"] = nonceValue ?: nonce()
        p.putAll(params)
        return openapi(p, secret = secret, verb = verb, pathStyle = pathStyle, tamper = tamper)
    }

    /** 演示库的三把假密钥按 secret 值反查 app_key（省掉每个用例都写一遍 app_key） */
    private fun secretKeyOf(secret: String): String = when (secret) {
        SK1 -> AK1
        SK2 -> AK2
        else -> akNew
    }

    private fun code(node: JsonNode): String = node.path("code").asText()

    private fun msg(node: JsonNode): String = node.path("message").asText()

    // ================= 库侧读口径 =================

    private fun pointOf(uid: Long): Long =
        jdbc.queryForObject("SELECT point FROM point_member WHERE company_id = 1 AND user_id = ?", Long::class.java, uid)!!

    /** 事件行数按"topic + 载荷里的业务主键"数，不看总数（订阅面同时挂着多个 topic） */
    private fun eventCount(topic: String, field: String, value: Any): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webhook_delivery_log WHERE topic = ? AND payload LIKE ?",
        Int::class.java, topic, "%\"$field\":$value%",
    )!!

    private fun rowOf(deliveryId: Long): Map<String, Any> = jdbc.queryForMap(
        "SELECT id, msg_id, status, attempts, next_try_at, last_http_status, url, subscription_id, developer_id " +
            "FROM webhook_delivery_log WHERE id = ?", deliveryId,
    )

    private fun createOrder(num: Int = 1): String = ok(
        "/api/h5app/wxapp/order_new",
        linkedMapOf(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to num)),
        ),
    ).path("order_id").asText()

    /** 微信渠道结清：`notify` 走的是真回调链，`markPaid` 的 CAS 也就是真那一次 */
    private fun wxPay(oid: String): String {
        val trade = ok("/api/h5app/wxapp/payment", mapOf("order_id" to oid, "pay_type" to "wxpay")).path("trade_id").asText()
        val body = utf8(
            mvc.perform(
                post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("out_trade_no", trade).param("total_fee", "8900")
                    .param("transaction_id", "WX-$trade").param("trade_state", "SUCCESS"),
            ).andReturn().response.contentAsByteArray,
        )
        assertTrue(body.contains("SUCCESS"), "回调应答：$body")
        return trade
    }

    private fun sub(topic: String): Long = okAdmin("POST", "/api/webhook/subscription", mapOf(
        "developer_id" to 1, "topic" to topic, "url" to receiver.url,
    )).path("id").asLong()

    private fun waitHits(topic: String, field: String, value: Any, timeoutMs: Long = 4000): List<SimWebhookReceiver.Hit> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hits = receiver.hitsBy(topic, field, value)
            if (hits.isNotEmpty()) return hits
            Thread.sleep(50)
        }
        return emptyList()
    }

    // ================= 门槛① 签名链 =================

    @Test @Order(1) fun `令牌 假接收端与 debug 默认值`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null, mapOf("username" to "admin", "password" to "admin123")), "admin")
            .path("token").asText()
        shop = data(call("POST", "/api/operator/login", null, mapOf("username" to "distributor01", "password" to "shop123")), "shop")
            .path("token").asText()
        assertTrue(tok.isNotBlank() && admin.isNotBlank() && shop.isNotBlank())

        receiver = SimWebhookReceiver(mapper).start()
        assertTrue(receiver.url.startsWith("http://127.0.0.1:"), "假接收端起来了：${receiver.url}")

        // §25.5：`ecx.openapi.debug` 的缺省值是 false。这一句不是走过场——它是"跳过 3..8"的总闸
        assertFalse(openapiDebug, "ecx.openapi.debug 默认必须是 false，否则开放面没有验签")

        // 出站密钥从库里读一遍再钉住夹具用的那个值：seed 的密钥一改，本类所有 HMAC/`sign` 断言
        // 必须跟着改，否则"验通了"验的是上一批密钥（这类夹具与种子漂移最难查）
        assertEquals(OUT_SK1, jdbc.queryForObject(
            "SELECT external_app_secret FROM openapi_developer WHERE developer_id = 1", String::class.java,
        ), "developer 1 的出站密钥就是本类写死的那把假密钥")
        assertEquals(SK1, jdbc.queryForObject(
            "SELECT app_secret FROM openapi_developer WHERE app_key = ?", String::class.java, AK1,
        ), "入向密钥同上（§25.5：演示密钥只用于本地自签自验）")
        assertTrue(registry.versions == setOf("1.0", "2.0"), "注册表版本集合：${registry.versions}")
    }

    @Test @Order(2) fun `assemble 与 PHP 语义逐字对（手写期望串）`() {
        val nested = linkedMapOf<String, Any?>(
            "version" to "2.0", "timestamp" to "1700000000", "app_key" to "ak",
            "method" to "ecx.order.deliver", "nonce" to "n1", "debug" to false, "order_id" to 1001,
            "items" to listOf(
                linkedMapOf("order_items_id" to 1, "num" to 2),
                linkedMapOf("order_items_id" to 2, "num" to 3),
            ),
            "note" to null,
        )
        // 期望串是人按 §25.1 规则排出来的：键字节序 = app_key debug items method nonce(注:note=null 整项跳过) order_id timestamp version；
        // 内层 {"num","order_items_id"} 也是字节序，列表下标 0/1 参与排序
        assertEquals(
            "app_keyakdebug0items0num2order_items_id11num3order_items_id2methodecx.order.delivernoncen1order_id1001timestamp1700000000version2.0",
            OpenapiSign.assemble(nested), "嵌套 + 布尔 + null 整项跳过",
        )

        // 十一元素列表：下标按**字符串序**排，所以 10 在 2 之前（这条最反直觉，也最容易在换实现时丢掉）
        assertEquals(
            "l0v1v10v2v3v4v5v6v7v8v9v",
            OpenapiSign.assemble(linkedMapOf("l" to List(11) { "v" })), "列表下标的字符串序",
        )

        // 布尔折成 1/0、null 整项（键也不出）跳过
        assertEquals("b1c0z9", OpenapiSign.assemble(linkedMapOf("b" to true, "c" to false, "n" to null, "z" to "9")))

        // 数字用**字面量文本**：1 与 1.0 是两个串（这里按请求里的写法原样进表）
        assertEquals("a1.0", OpenapiSign.assemble(linkedMapOf("a" to "1.0")))
        assertEquals("a1", OpenapiSign.assemble(linkedMapOf("a" to 1)))

        // genSign = strtoupper(md5(secret + assemble(params) + secret))：期望串是人按规则手拼出来的，
        // 不是拿实现算一遍再和实现比（那种自证什么也证不了）
        assertEquals(md5Upper("sk_aa1sk_a"), OpenapiSign.genSign(linkedMapOf("a" to "1"), "sk_a"), "签的形状 = 大写 md5")
        assertTrue(Regex("^[0-9A-F]{32}$").matches(OpenapiSign.genSign(mapOf("x" to "y"), "sk")), "32 位大写十六进制")

        // 出向那两层签的**编码形状**各与一份独立实现逐个对（HMAC 小写十六进制、体内 sign 大写 md5）：
        // 接收端 [SimWebhookReceiver] 验的就是这一层口径，这里先把它钉死，省得线上验不过时两头找
        val body = """{"a":1}"""
        val stamp = ts()
        assertEquals(
            "sha256=" + receiverOwnHmac(OUT_SK1, stamp + "\n" + body),
            "sha256=" + OpenapiSign.hmacSha256Hex(OUT_SK1, stamp + "\n" + body),
            "HMAC 覆盖 ts + 换行 + 原始字节：产品侧与本类各算一遍必须同值",
        )
        assertEquals(
            OpenapiSign.genSign(linkedMapOf("a" to 1), OUT_SK1),
            md5Upper(OUT_SK1 + "a1" + OUT_SK1),
            "体内 sign 的 md5 口径（大写、前后各夹一次密钥）",
        )
    }

    @Test @Order(3) fun `正例出数据 五种改包一律 E4004`() {
        val p = linkedMapOf<String, Any?>(
            "app_key" to AK1, "method" to "ecx.items.entity.get", "version" to "2.0",
            "timestamp" to ts(), "nonce" to nonce(), "page" to "1",
        )
        // **只发一次**：nonce 是防重放位，同一份参数打第二遍就是 `E4002`，那不属于这一条用例要证的事
        val good = openapi(p)
        assertEquals(OpenapiCode0, code(good), "签对就该出数据：" + msg(good))
        assertContract(good.path("data"), WebhookContract.LIST_ENVELOPE, "OA-M7 出的是分页信封")
        assertTrue(good.path("data").path("total_count").asLong() >= 1, "第一租户有演示商品")

        val base = linkedMapOf<String, Any?>(
            "app_key" to AK1, "method" to "ecx.order.deliver", "version" to "2.0",
            "timestamp" to ts(), "nonce" to nonce(), "order_id" to 999999,
            "items" to listOf(linkedMapOf("order_items_id" to 1, "num" to 1)),
            "flag" to false, "note" to null,
        )
        // 五条一律 `E4004`：嵌套结构要真的进报文，所以这一组走 POST（业务参数进 body、公共位进 query，
        // 而签名覆盖的是两边合并后的全集 —— §25.1 那条合并语义在这里同时被证了一次）
        // 1) 改值（签的是 999999，发出去的是 999998）
        assertEquals("E4004", code(openapi(base, verb = "POST", tamper = { it["order_id"] = 999998 })), "改一位值")
        // 2) 翻转布尔（`false → "0"` 与 `true → "1"` 是两个串）
        assertEquals("E4004", code(openapi(base, verb = "POST", tamper = { it["flag"] = true })), "翻转布尔")
        // 3) 改嵌套数组里的一个元素（这一条是"递归有没有真的进签"的判据：不递归时改里面动不到签）
        assertEquals("E4004", code(openapi(base, verb = "POST", tamper = {
            @Suppress("UNCHECKED_CAST")
            val list = it["items"] as List<Map<String, Any?>>
            it["items"] = listOf((list[0] + ("num" to 7)))
        })), "改嵌套数组元素")
        // 4) 多塞一个没参与签名的键（键集合变了就是签变了）
        assertEquals("E4004", code(openapi(base, verb = "POST", tamper = { it["extra"] = "1" })), "多一个未签的键")
        // 5) 想靠入参 debug=true 绕过验签：那一位只是**参与签名的数据**，不是开关
        assertEquals("E4004", code(openapi(base, verb = "POST", tamper = { it["debug"] = "1" })), "debug 入参不能当开关用")
        // 6) 抓到一份合法报文之后改它的 method / 补它的新参数（"整包重放到别的接口"）：
        //    签覆盖的是**这一次调用的全部参数**，换接口就是换参数集，签必然对不上
        assertEquals(
            "E4004",
            code(openapi(base, verb = "POST", tamper = { it["method"] = "ecx.order.writeoff"; it["code"] = "X" })),
            "换 method 不换签",
        )
    }

    @Test @Order(4) fun `闸门位次 时间窗两个方向 与 debug 旁路`() {
        val good = linkedMapOf<String, Any?>(
            "app_key" to AK1, "method" to "ecx.items.entity.get", "version" to "2.0",
            "timestamp" to ts(), "nonce" to nonce(),
        )
        // 位次 1：三个必传位。`version` 有默认，所以缺 version 不报（这条也照抄源系统）
        assertEquals("E4001", code(openapi(good - "timestamp")), "缺 timestamp")
        assertEquals("E4001", code(openapi(good - "app_key")), "缺 app_key")
        // 位次 4 先于位次 5：**未知 app_key 且不带 sign → E4004**，
        // 反过来（先查密钥）就等于向没验过签的人回答"这个 app_key 存不存在"
        // （`sign` 由 [openapi] 现算，所以"不发签"这件事得用 tamper 在签完之后抹掉）
        assertEquals(
            "E4004",
            code(openapi(good + ("app_key" to "ak_ffffffffffffffff"), tamper = { it.remove("sign") })),
            "缺 sign 先于查密钥",
        )
        assertEquals("E4003", code(openapi(good + ("app_key" to "ak_ffffffffffffffff"))), "未知 app_key（有 sign）")
        // 位次 3：边界两个方向都要测
        assertEquals("E4002", code(gw("ecx.items.entity.get", offset = 601)), "+601 超窗")
        assertEquals("E4002", code(gw("ecx.items.entity.get", offset = -601)), "-601 超窗")
        assertEquals(OpenapiCode0, code(gw("ecx.items.entity.get", offset = 599)), "+599 在窗内")
        assertEquals(OpenapiCode0, code(gw("ecx.items.entity.get", offset = -599)), "-599 在窗内")
        // 时间戳认 strtotime 那两族写法：unix 秒之外还认 `yyyy-MM-dd HH:mm:ss`
        val fmt = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val dtNode = openapi(good + ("timestamp" to fmt) + ("nonce" to nonce()))
        assertEquals(OpenapiCode0, code(dtNode), "日期时间串同样在窗内（送出的是 $fmt）：" + msg(dtNode))
        val badNode = openapi(good + ("timestamp" to "not-a-time") + ("nonce" to nonce()))
        assertEquals("E4002", code(badNode), "认不出来按不合法处理：" + msg(badNode))
        // 位次 7/8：nonce 与 request_id 其一必填
        assertEquals("E4001", code(openapi(good - "nonce")), "缺 nonce 且缺 request_id")
        assertEquals(OpenapiCode0, code(openapi((good - "nonce") + ("request_id" to nonce()))), "request_id 顶上")
        // OA-G2 路径式入口：method 不参与签名，其余同一套闸门
        assertEquals(OpenapiCode0, code(gw("ecx.items.entity.get", pathStyle = true)), "路径式 method")

        // debug=true 时不验签（§25.1 位次 2）。这里现造一个 debug 开的网关实例：
        // 换测试上下文要整个类重来一遍，而这一条判据的载体是**构造参数**，直接构造最省
        val dbg = OpenapiGatewayService(
            developers, nonceStore, registry, mapper, metrics,
            enabled = true, debug = true, windowSeconds = 600, nonceTtl = 600, wrapResponse = true,
        )
        val env = dbg.handle(
            null, "GET",
            mapOf("app_key" to "ak_whatever", "method" to "ecx.items.entity.get", "version" to "2.0", "timestamp" to "1"),
            null,
        ) as Map<*, *>
        assertEquals(OpenapiCode0, env["code"], "debug 跳过 3..8：密钥不存在、时间戳是 1、还没 sign")
        // 但 debug 不越过位次 9（租户仍取系统默认租户 1，写 company_id=2 照样 fail-closed）
        val denied = dbg.handle(
            null, "GET",
            mapOf(
                "app_key" to "ak_whatever", "method" to "ecx.items.entity.get", "version" to "2.0",
                "timestamp" to ts(), "company_id" to "2",
            ),
            null,
        ) as Map<*, *>
        assertEquals("E4001", denied["code"], "debug 也要过第 9 道闸门")
        // 文案要落在"企业不一致"上，不能是"timestamp必填"—— 两个 E4001 不是一回事
        assertEquals("company_id 与密钥所属企业不一致", denied["message"], "第 9 道闸门自己报的错")
    }

    @Test @Order(5) fun `重放挡在委托之前 业务侧一位没动`() {
        val before = pointOf(1)
        val n = "M21-REPLAY-" + System.nanoTime()
        val params = linkedMapOf<String, Any?>(
            "user_id" to 1, "change_point" to 7, "remark" to "网关调账",
        )
        val first = gw("ecx.member_point.update", params, verb = "POST", nonceValue = n)
        assertEquals(OpenapiCode0, code(first), "第一次动账生效：" + msg(first))
        assertEquals(before + 7, pointOf(1), "第一次真的加了 7 分")

        val second = gw("ecx.member_point.update", params, verb = "POST", nonceValue = n)
        assertEquals("E4002", code(second), "同一个 nonce 第二次")
        assertEquals("nonce 已使用", msg(second))
        // 这一句才是门槛①要的**副作用**判据：不是"委托自己幂等"，是它根本没被执行
        assertEquals(before + 7, pointOf(1), "重放必须挡在委托之前：积分一位不能再动")

        // 换 nonce 就回冲回去，别把后面的积分用例的坐标系搞乱
        assertEquals(OpenapiCode0, code(gw("ecx.member_point.update", params + ("change_point" to -7), verb = "POST")))
        assertEquals(before, pointOf(1), "回冲后与进场时刻一致")
    }

    // ================= 门槛② 分发与租户边界 =================

    @Test @Order(6) fun `未知版本 未知method verb 不符 与必填位`() {
        assertEquals("E4006", code(gw("ecx.items.entity.get", version = "9.9")), "该版本号不存在API")
        assertEquals("E4005", code(gw("ecx.nope.nope")), "找不到API")
        // verb 与注册不符在源系统就是 API_NOT_FOUND（不是 405）：改成 405 就漂了，且带的是空体
        assertEquals("E4005", code(gw("ecx.order.deliver", mapOf("order_id" to 1), verb = "GET")), "GET 打 POST 面")
        assertEquals("E4001", code(gw("ecx.order.list", version = "1.0")), "缺 user_id")
        assertEquals("E4001", code(gw("ecx.order.get")), "order_id/order_bn 至少一个")
        // 未带令牌的裸调用：进闸门而不是被安全链拦成 401（§25.3 那条"链的位次"）
        val raw = mvc.perform(get("/api/openapi")).andReturn()
        assertEquals(200, raw.response.status, "匿名入口不能被 JWT 链接管")
        assertEquals("E4001", code(mapper.readTree(utf8(raw.response.contentAsByteArray))), "裸调用回缺参而不是 401")
    }

    @Test @Order(7) fun `租户取自密钥 第二个租户读零行 写别人租户 E4001`() {
        val d2 = gw("ecx.items.entity.get", secret = SK2)
        assertEquals(OpenapiCode0, code(d2), "第二个租户读商品是**空集合**而不是报错：" + msg(d2))
        assertEquals(0L, d2.path("data").path("total_count").asLong())
        assertEquals(0, d2.path("data").path("list").size())
        // 它想把租户写成 1 —— fail-closed，第 9 道闸门
        val forged = gw("ecx.items.entity.get", mapOf("company_id" to 1), secret = SK2)
        assertEquals("E4001", code(forged))
        assertEquals("company_id 与密钥所属企业不一致", msg(forged))
    }

    @Test @Order(8) fun `ecx_order_get 与后台 ORDER-9 逐字段相同`() {
        oidSample = createOrder()
        val adminView = okAdmin("GET", "/api/orders/$oidSample")
        val gwView = gw("ecx.order.get", mapOf("order_id" to oidSample.toLong())).path("data")
        assertEquals(OpenapiCode0, code(gw("ecx.order.get", mapOf("order_id" to oidSample.toLong()))))

        val a = LinkedHashMap<String, String>(); flatten(adminView, "", a)
        val g = LinkedHashMap<String, String>(); flatten(gwView, "", g)
        assertEquals(a.keys.sorted(), g.keys.sorted(), "字段集必须逐层相同（同一委托同一装配函数）")
        assertTrue(a.size > 30, "详情面比的是几十个叶子字段，不是三个壳键：${a.size}")
        a.forEach { (k, v) ->
            val leaf = k.substringAfterLast('.').substringBeforeLast('[')
            if (leaf in MOBILE_KEYS) {
                assertEquals(Mask.mobile(v), g[k], "$k 出境必须掩码")
                if (v.length >= 7) assertTrue(g[k]!!.contains("****"), "$k 出境后还带全值就是泄露")
            } else {
                assertEquals(v, g[k], "$k 必须与后台面逐字相同")
            }
        }
        // 收件人手机号在后台面是全值、在开放面是掩码 —— 这一条单独钉一次，别让上面那个循环被读成"约等于"
        val receiverMobile = adminView.path("orderInfo").path("receiver_mobile").asText()
        assertEquals(Mask.mobile(receiverMobile), gwView.path("orderInfo").path("receiver_mobile").asText())
    }

    @Test @Order(9) fun `跨租户与不存在同答 会员手机号恒掩码`() {
        val mine = oidSample.toLong()
        val cross = gw("ecx.order.get", mapOf("order_id" to mine), secret = SK2)
        val ghost = gw("ecx.order.get", mapOf("order_id" to 987654321L))
        assertEquals("E5301", code(cross), "别的租户的单：不回答「在不在」")
        assertEquals("E5301", code(ghost))
        assertEquals(msg(ghost), msg(cross), "跨租户与不存在必须**同码同文案**，否则开放面就是单号探测器")
        // 写面同理：拿第二个租户的密钥去发第一租户的货，同样是"订单不存在"
        assertEquals("E5301", code(gw("ecx.order.deliver", mapOf("order_id" to mine), secret = SK2, verb = "POST")), "跨租户发货同样被拒")

        val m = gw("ecx.member.query", mapOf("mobile" to "13800000001"), version = "1.0")
        assertEquals(OpenapiCode0, code(m))
        assertEquals(1L, m.path("data").path("user_id").asLong())
        assertEquals("138****0001", m.path("data").path("mobile").asText(), "出境面的手机号没有「看全值」这一档")
        // 别的租户同号的人不能被这一条答出来（读口按密钥租户筛）
        assertEquals("E5501", code(gw("ecx.member.query", mapOf("mobile" to "13800000001"), version = "1.0", secret = SK2)))
    }

    // ================= 后台面 OP-A1..A6 =================

    @Test @Order(10) fun `开发者密钥面 出参契约 与密钥只进不出`() {
        val list = okAdmin("GET", "/api/setting/openapi/developers")
        assertContract(list, WebhookContract.LIST_ENVELOPE, "OP-A1 信封")
        assertNoSecret(list, "OP-A1 整页（含每一行）")
        val seeded = list.path("list").firstOrNull { it.path("app_key").asText() == AK1 }
        assertTrue(seeded != null, "seed 的演示密钥在列表里")
        assertContract(seeded!!, OpenapiContract.DEVELOPER_ROW, "OP-A1 行")
        assertFalse(seeded.has("app_secret"), "列表行不许出现入向密钥")
        assertFalse(seeded.has("external_app_secret"), "列表行不许出现出站密钥")

        // OP-A2：服务端发 keypair，**只有这一条响应带 secret**
        val created = okAdmin("POST", "/api/setting/openapi/developer", mapOf(
            "name" to "e2e 新建对接方", "remark" to "契约与即刻可用用例",
            "external_base_uri" to "https://erp.invalid/openapi/receive?k=1",
        ))
        assertContract(created, OpenapiContract.DEVELOPER_CREATE, "OP-A2 响应")
        devNew = created.path("developer_id").asLong()
        akNew = created.path("app_key").asText()
        skNew = created.path("app_secret").asText()
        assertTrue(Regex("^ak_[0-9a-f]{16}$").matches(akNew), "app_key 形状：$akNew")
        assertTrue(Regex("^sk_[0-9a-f]{32}$").matches(skNew), "app_secret 形状：$skNew")

        // 新密钥**即刻可用**（不需要"等缓存过期"这种玄学）
        assertEquals(OpenapiCode0, code(gw("ecx.items.entity.get", secret = skNew)), "新密钥签的请求直接通")

        // 出站地址在后台面只出到 host（这一列常常是对端内网地址，进日志的人不该看见它）
        val upd = okAdmin("PUT", "/api/setting/openapi/developer/$devNew", mapOf("remark" to "改一笔"))
        assertContract(upd, OpenapiContract.DEVELOPER_UPDATE, "OP-A3 响应")
        assertEquals("erp.invalid", upd.path("external_base_uri").asText(), "external_base_uri 脱敏到 host")
        assertFalse(upd.has("app_secret") || upd.has("external_app_secret"), "改档案不回显任何密钥")
        assertEquals(1, upd.path("status").asInt(), "没传 status 就不改（三态，C194 同一条纪律）")

        // OP-A4 重置入向密钥：旧值**下一个请求即 E4004**，新值当场生效，没有宽限期
        val reset = okAdmin("POST", "/api/setting/openapi/developer/$devNew/reset_secret")
        assertContract(reset, OpenapiContract.RESET_SECRET, "OP-A4 响应")
        val fresh = reset.path("app_secret").asText()
        assertTrue(fresh != skNew && Regex("^sk_[0-9a-f]{32}$").matches(fresh))
        assertEquals("E4004", code(gw("ecx.items.entity.get", secret = skNew)), "旧值必须立刻签不过")
        skNew = fresh
        assertEquals(OpenapiCode0, code(gw("ecx.items.entity.get", secret = skNew)), "新值立刻生效")

        // 吊销 = status=0（本项目补的那一位：源系统只能删行，删行会连历史一起抹）
        okAdmin("PUT", "/api/setting/openapi/developer/$devNew", mapOf("status" to 0))
        assertEquals("E4003", code(gw("ecx.items.entity.get", secret = skNew)), "停用即刻生效")
        okAdmin("PUT", "/api/setting/openapi/developer/$devNew", mapOf("status" to 1))
        assertEquals(OpenapiCode0, code(gw("ecx.items.entity.get", secret = skNew)), "再启用又回来了")

        // OP-A5 目录来自注册表：19 条 method、两个版本、行形状固定
        val catalog = okAdmin("GET", "/api/setting/openapi/methods")
        assertContract(catalog, OpenapiContract.METHODS, "OP-A5 信封")
        assertEquals(listOf("1.0", "2.0"), catalog.path("versions").map { it.asText() })
        assertEquals(29, catalog.path("list").size(), "24 条 method，M2-1 首批 19 条 + 批E 五族，多版本条目各注册一次")
        catalog.path("list").forEach { assertContract(it, OpenapiContract.METHOD_ROW, "OP-A5 行") }
        assertEquals(
            19, catalog.path("list").map { it.path("method").asText() }.toSet().size,
            "§25.2 那张表的规模就是 19 条",
        )
    }

    @Test @Order(11) fun `开放面的权限分档 店铺运营一条都不该有`() {
        // §25.5：role 2 不配这一族的任何码（密钥与推送地址是租户对外的口子，不是某家店的资产）
        expect("GET", "/api/setting/openapi/developers", null, 403, token = shop)
        expect("GET", "/api/webhook/subscriptions", null, 403, token = shop)
        expect("GET", "/api/webhook/topics", null, 403, token = shop)
        expect("POST", "/api/webhook/test", mapOf("url" to "http://127.0.0.1:1/x", "developer_id" to 1), 403, token = shop)
        // 读口没令牌也一样进不来
        expect("GET", "/api/setting/openapi/developers", null, 401, token = "")
    }

    // ================= 订阅面 WH-A1..A9 =================

    @Test @Order(12) fun `订阅面的 422 家族 每条都是一句人能懂的话`() {
        expect("POST", "/api/webhook/subscription", mapOf("developer_id" to 1, "topic" to "nope.nope", "url" to receiver.url), 422)
        expect("POST", "/api/webhook/subscription", mapOf("developer_id" to 1, "topic" to "order.created", "url" to "http://erp.invalid/hook"), 422)
        expect("POST", "/api/webhook/subscription", mapOf("developer_id" to 1, "topic" to "order.created", "url" to "ftp://127.0.0.1/hook"), 422)
        // 别的租户的开发者：404（不回答"这个 developer_id 存不存在于别家"）
        expect("POST", "/api/webhook/subscription", mapOf("developer_id" to 2, "topic" to "order.created", "url" to receiver.url), 404)
        // 本租户但没配出站密钥的开发者：422 —— 没出站密钥就没有能验得通的推送
        expect("POST", "/api/webhook/subscription", mapOf("developer_id" to devNew, "topic" to "order.created", "url" to receiver.url), 422)

        subCreated = sub("order.created")
        expect("POST", "/api/webhook/subscription", mapOf("developer_id" to 1, "topic" to "order.created", "url" to receiver.url), 422)

        val list = okAdmin("GET", "/api/webhook/subscriptions?developer_id=1")
        assertContract(list, WebhookContract.LIST_ENVELOPE, "WH-A1 信封")
        val row = list.path("list").first { it.path("id").asLong() == subCreated }
        assertContract(row, WebhookContract.SUB_ROW, "WH-A1 行")
        assertEquals(AK1, row.path("app_key").asText(), "行里带 app_key，运营不用再去别处查这个 developer_id 是谁")
        assertEquals("演示对接方", row.path("developer_name").asText())

        // 三态：只改 status，url 与 topic 原样留着
        val off = okAdmin("PUT", "/api/webhook/subscription/$subCreated", mapOf("status" to 0))
        assertContract(off, WebhookContract.SUB_CREATE, "WH-A3 响应")
        assertEquals(0, off.path("status").asInt())
        assertEquals(receiver.url, off.path("url").asText())
        okAdmin("PUT", "/api/webhook/subscription/$subCreated", mapOf("status" to 1))

        // WH-A8 事件目录来自代码（与 OP-A5 同一个纪律：另维护一份清单必然漂）
        val topics = okAdmin("GET", "/api/webhook/topics")
        assertEquals(10, topics.size(), "§25.4 那张表的十条事件")
        topics.forEach { assertContract(it, WebhookContract.TOPIC_ROW, "WH-A8 行") }
        assertTrue(topics.all { it.path("data_fields").size() > 0 }, "每条事件都得说清载荷字段")
    }

    @Test @Order(13) fun `WH-A9 测试事件 与正式推送同一套签名 且落一条证据`() {
        val res = okAdmin("POST", "/api/webhook/test", mapOf(
            "url" to receiver.url, "developer_id" to 1, "topic" to "webhook.test", "ping" to "m21",
        ))
        assertContract(res, WebhookContract.TEST_PUSH, "WH-A9 出参")
        assertTrue(res.path("dispatched").asBoolean(), "测试事件当场就发：" + res)
        assertEquals("success", res.path("status").asText())
        val id = res.path("id").asLong()
        val hit = receiver.hits.first()
        assertTrue(receiver.hmacOk(OUT_SK1, hit), "测试事件也过 HMAC 那一层")
        assertTrue(receiver.bodySignOk(OUT_SK1, hit), "体内 sign 同样验通")
        assertEquals(hit.header("x-ecx-msg-id"), res.path("msg_id").asText())
        // 入参原样进 echo：typed DTO 会把没声明的键丢掉，那样就不叫原样
        assertEquals("m21", hit.json().path("data").path("echo").path("ping").asText())
        // 证据本身在表里（对接方拿"测试通了"当验收依据时，这一行就是依据）
        assertEquals(1, eventCountQuery("webhook.test", id))
        val detail = okAdmin("GET", "/api/webhook/delivery/$id")
        assertEquals(hit.header("x-ecx-signature"), detail.path("signature").asText(), "留痕必须是**实发**的那个头值")
    }

    private fun eventCountQuery(topic: String, id: Long): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM webhook_delivery_log WHERE topic = ? AND id = ?", Int::class.java, topic, id,
    )!!

    // ================= 门槛③ 出站链 =================

    @Test @Order(14) fun `建单结清发货 事件真的推出去了`() {
        subPaid = sub("order.paid")
        // 三条订阅对应三段动作：outbox 是「事件 × 订阅」的笛卡尔积，少订阅一个 topic 就少一封，
        // 所以上面那三句 `assertEquals(1, …)` 只有在三个 topic 都挂上时才不是空跑
        sub("order.shipped")
        receiver.clear()
        val oid = createOrder()
        wxPay(oid)
        okAdmin("PUT", "/api/orders/delivery", mapOf("order_id" to oid.toLong(), "delivery_corp" to "zto", "delivery_code" to "E2E-M21"))

        val created = waitHits("order.created", "order_id", oid)
        assertEquals(1, created.size, "建单事件一封不多不少")
        val paid = waitHits("order.paid", "order_id", oid)
        assertEquals(1, paid.size)
        val shipped = waitHits("order.shipped", "order_id", oid)
        assertEquals(1, shipped.size)

        val hit = created.first()
        assertTrue(receiver.hasAllHeaders(hit), "四个头都到位（缺 msg-id 就断了判重）")
        assertEquals("order.created", hit.header("x-ecx-event"))
        assertTrue(receiver.hmacOk(OUT_SK1, hit), "HMAC 头按收到的**原始字节**重算必须一致")
        assertTrue(receiver.bodySignOk(OUT_SK1, hit), "体内 sign 按「去掉 sign 后重组」必须一致")
        val env = hit.json()
        assertContract(env, WebhookContract.ENVELOPE_ON_WIRE, "出站信封（线格式多一个 sign）")
        assertEquals(hit.header("x-ecx-msg-id"), env.path("msg_id").asText(), "头里的 msg_id 与体内一致")
        assertEquals(1L, env.path("company_id").asLong())
        assertEquals(1L, env.path("developer_id").asLong())
        assertEquals(
            setOf("order_id", "user_id", "distributor_id", "shop_id", "pay_fee", "status", "created"),
            receiver.dataFields(hit), "order.created 的载荷字段逐个对（与 WH-A8 目录同一份来源）",
        )
        assertEquals(8900L, env.path("data").path("pay_fee").asLong())
        // 第二支事件的载荷形状
        assertEquals(setOf("order_id", "pay_fee", "pay_type", "pay_time"), receiver.dataFields(paid.first()))
        assertEquals(setOf("order_id", "orders_delivery_id", "delivery_corp", "delivery_code", "delivered"), receiver.dataFields(shipped.first()))
    }

    @Test @Order(15) fun `重复回调不生成第二条事件 CAS 未命中那一支不发`() {
        val oid = createOrder()
        val trade = ok("/api/h5app/wxapp/payment", mapOf("order_id" to oid, "pay_type" to "wxpay")).path("trade_id").asText()
        fun notify(): String = utf8(
            mvc.perform(
                post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("out_trade_no", trade).param("total_fee", "8900")
                    .param("transaction_id", "WX-$trade").param("trade_state", "SUCCESS"),
            ).andReturn().response.contentAsByteArray,
        )
        assertTrue(notify().contains("SUCCESS"))
        val after1 = eventCount("order.paid", "order_id", oid.toLong())
        assertEquals(1, after1)
        // 渠道重试同一笔通知：订单状态没迁移，所以**一行 outbox 都不许多**
        notify()
        notify()
        assertEquals(after1, eventCount("order.paid", "order_id", oid.toLong()), "重复回调不生成第二条事件（§25.6 门槛③）")
        assertEquals(1, receiver.hitsBy("order.paid", "order_id", oid.toLong()).size, "对端也只收到一封")
    }

    @Test @Order(16) fun `先500后200 走到 success 且 attempts 是 2`() {
        // 先把在途的行排空：`FAIL_ONCE` 只吃一次应答，若留着上一轮的 pending，
        // 被吃掉的就是那一条，本条会一次成功、attempts=1 —— 用例假绿得毫无痕迹
        drain()
        receiver.mode = SimReceiveMode.FAIL_ONCE
        val oid = createOrder()
        val id = deliveryIdOf("order.created", oid.toLong())
        val r = rowOf(id)
        assertEquals("pending", r["status"], "第一次 500 → 回 pending 排下一次：" + r)
        assertEquals(1, (r["attempts"] as Number).toInt())
        assertEquals(500, (r["last_http_status"] as Number).toInt(), "留痕要写清是渠道给的 500，不是连不上")
        // 退避排在 now + 2^1 × 1 秒：基数压成 1 正是为了钉住**公式的形状**（真实基数 30 秒由冒烟脚本实测）
        val due = (r["next_try_at"] as Number).toLong()
        assertTrue(due > now() - 1 && due <= now() + 3, "退避没排出去：next_try_at=$due now=${now()}")

        // 正在排队（pending、还没到点）的那条不能手动重推：那等于让同一条排两次队、投两次
        expect("POST", "/api/webhook/delivery/$id/retry", null, 422)
        assertEquals("pending", rowOf(id)["status"], "422 那一下不能把状态改掉")
        assertEquals(1, (rowOf(id)["attempts"] as Number).toInt(), "被拒的那一次也不能占掉一个尝试")

        Thread.sleep((due - now() + 2) * 1000L)
        sender.deliverDue()
        val after = rowOf(id)
        assertEquals("success", after["status"], "第二次就成功：" + after)
        assertEquals(2, (after["attempts"] as Number).toInt())
        assertEquals(200, (after["last_http_status"] as Number).toInt())
        assertEquals(
            2, receiver.hitsBy("order.created", "order_id", oid.toLong()).size,
            "对端收到两封：第一封是它自己回的 500（重试不是我方漏发，就是它那一封没接住）",
        )
    }

    @Test @Order(17) fun `一直500 走到终态 fail 手动重推同 msg_id`() {
        drain()
        receiver.mode = SimReceiveMode.FAIL_ALWAYS
        val oid = createOrder()
        val id = deliveryIdOf("order.created", oid.toLong())
        var r = rowOf(id)
        assertEquals("pending", r["status"], "还有额度就回 pending")
        assertEquals(1, (r["attempts"] as Number).toInt())
        val msgId = r["msg_id"]

        // 退避到期之后第二次仍失败：attempts 到 max-attempts（本上下文=2）→ 终态 fail
        Thread.sleep(((r["next_try_at"] as Number).toLong() - now() + 2) * 1000L)
        sender.deliverDue()
        r = rowOf(id)
        assertEquals("fail", r["status"], "额度用尽必须是终态：" + r)
        assertEquals(2, (r["attempts"] as Number).toInt())
        assertEquals(0L, (r["next_try_at"] as Number).toLong(), "终态不再自动排（next_try_at 写 0）")

        // 终态不在扫描条件里：再跑一轮也不动它，也不会替人多试一次
        sender.deliverDue()
        assertEquals("fail", rowOf(id)["status"], "fail 不该被作业自动捞起来")
        assertEquals(2, (rowOf(id)["attempts"] as Number).toInt(), "自动作业不能悄悄把额度再加一次")

        // 换个好端点重推：**同一个 msg_id**、attempts 不清零
        receiver.mode = SimReceiveMode.OK
        val retry = okAdmin("POST", "/api/webhook/delivery/$id/retry")
        assertContract(retry, WebhookContract.RETRY, "WH-A7 出参")
        assertEquals(msgId, retry.path("msg_id").asText(), "重推不换号：对端靠它判重")
        assertTrue(retry.path("dispatched").asBoolean(), "点了就该真发出去：" + retry)
        assertEquals("success", retry.path("status").asText())
        assertEquals(3, (rowOf(id)["attempts"] as Number).toInt(), "attempts 不清零：这一条一共试过三次")
        assertEquals(msgId, rowOf(id)["msg_id"])
        assertEquals(200, (rowOf(id)["last_http_status"] as Number).toInt())

        // 已经成功的不能再重推（422，而不是"提交了但什么都没发生"）
        val again = expect("POST", "/api/webhook/delivery/$id/retry", null, 422)
        assertTrue(again.path("message").asText().contains("重推"), again.toString())

        // 按 msg_id 精确回捞（列表不含正文）
        val list = okAdmin("GET", "/api/webhook/deliveries?msg_id=$msgId")
        assertEquals(1, list.path("list").size())
        val row = list.path("list")[0]
        assertContract(row, WebhookContract.DELIVERY_ROW, "WH-A5 行")
        assertFalse(row.has("payload"), "列表不含 CLOB 正文")
        val detail = okAdmin("GET", "/api/webhook/delivery/$id")
        assertContract(detail, WebhookContract.DELIVERY_DETAIL, "WH-A6 详情")
        assertFalse(detail.path("payload").has("sign"), "库里那份是**不含 sign** 的信封（签要发送时刻现算）")
        assertTrue(detail.path("signature").asText().startsWith("sha256="), "留痕是实发的那个头值")
        assertEquals(
            3, receiver.hitsBy("order.created", "order_id", oid.toLong()).size,
            "对端一共收到三封：两封它自己回了 500，第三封是手动重推的那一封",
        )
    }

    @Test @Order(18) fun `停用即刻断推 连 pending 都不生成`() {
        okAdmin("PUT", "/api/webhook/subscription/$subCreated", mapOf("status" to 0))
        val oid = createOrder()
        assertEquals(0, eventCount("order.created", "order_id", oid.toLong()), "停用之后连一行都不落（不是生成后再跳过）")
        // 商品事件同理：这一族此刻**根本没有订阅**，`publish` 一条 SQL 都不写
        val ghostItem = okAdmin(
            "POST", "/api/goods/items", mapOf("item_name" to "无订阅的商品", "price" to 100, "store" to 1),
        ).path("item_id").asLong()
        assertEquals(0, eventCount("goods.created", "item_id", ghostItem), "没有订阅 = 不落 outbox 行，不是落了再跳过")
        okAdmin("PUT", "/api/webhook/subscription/$subCreated", mapOf("status" to 1))
        val oid2 = createOrder()
        assertTrue(eventCount("order.created", "order_id", oid2.toLong()) >= 1, "再启用就回来了")
    }

    @Test @Order(19) fun `收货 关单 与商品三事件`() {
        listOf("order.completed", "order.closed", "goods.created", "goods.updated", "goods.deleted").forEach { sub(it) }

        val done = createOrder()
        wxPay(done)
        okAdmin("PUT", "/api/orders/delivery", mapOf("order_id" to done.toLong(), "delivery_corp" to "sf", "delivery_code" to "E2E-M21-B"))
        ok("/api/h5app/wxapp/order/confirmReceipt", mapOf("order_id" to done))
        val c = waitHits("order.completed", "order_id", done)
        assertEquals(1, c.size)
        assertEquals(setOf("order_id", "finish_time"), receiver.dataFields(c.first()))

        val cancelled = createOrder()
        ok("/api/h5app/wxapp/order/cancel", mapOf("order_id" to cancelled, "cancel_reason" to "下错单了"))
        val cl = waitHits("order.closed", "order_id", cancelled)
        assertEquals(1, cl.size)
        assertEquals(setOf("order_id", "reason", "closed_at"), receiver.dataFields(cl.first()))
        assertEquals("下错单了", cl.first().json().path("data").path("reason").asText())

        val itemId = okAdmin("POST", "/api/goods/items", mapOf("item_name" to "e2e 事件商品", "price" to 1234, "store" to 10)).path("item_id").asLong()
        val gc = waitHits("goods.created", "item_id", itemId)
        assertEquals(1, gc.size)
        assertEquals(setOf("item_id", "item_bn", "item_name", "price", "store", "approve_status"), receiver.dataFields(gc.first()))
        okAdmin("PUT", "/api/goods/items/$itemId", mapOf("item_name" to "e2e 事件商品改", "price" to 2222, "store" to 8))
        assertEquals(1, waitHits("goods.updated", "item_id", itemId).size)
        okAdmin("DELETE", "/api/goods/items/$itemId", null)
        val gd = waitHits("goods.deleted", "item_id", itemId)
        assertEquals(1, gd.size, "删除事件只带两个键（当时的价格库存没有意义）")
        assertEquals(setOf("item_id", "item_bn"), receiver.dataFields(gd.first()))
    }

    @Test @Order(20) fun `概况与收尾 计数不同源就分开出`() {
        metrics.reset()
        gw("ecx.items.entity.get")
        gw("ecx.items.entity.get")
        // 闸门就失败的调用**没有开发者身份**：它进 UNKNOWN 桶，不该算在这个开发者头上
        assertEquals("E4005", code(gw("ecx.nope.nope")), "method 不存在 → 还没认出是谁")
        // 认出身份之后失败的那一次才算在他头上（委托抛 E5301）
        assertEquals("E5301", code(gw("ecx.order.get", mapOf("order_id" to 987654321L))))
        val stats = okAdmin("GET", "/api/setting/openapi/developer/1/stats")
        assertContract(stats, OpenapiContract.STATS, "OP-A6 信封")
        assertContract(stats.path("gateway"), OpenapiContract.STATS_GATEWAY, "OP-A6 网关三位")
        assertContract(stats.path("delivery"), OpenapiContract.STATS_DELIVERY, "OP-A6 出站四位")
        assertNoSecret(stats, "OP-A6 概况")
        assertEquals(3L, stats.path("gateway").path("total").asLong(), "本进程内数得清：绑过身份的三次")
        assertEquals(2L, stats.path("gateway").path("success").asLong())
        assertEquals(1L, stats.path("gateway").path("fail").asLong(), "失败那一位只算认出身份之后的")
        assertTrue(stats.path("note").asText().isNotBlank(), "两半不同源这件事写在出参里，不靠人记")
        assertTrue(stats.path("delivery").path("success").asLong() > 0, "出站那四位来自表，与网关计数无关")

        // WH-A4 删订阅：配置删掉，投递记录一条都不动（那是对接方"我收到了"的证据）
        val kept = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ?", Int::class.java, subCreated)!!
        assertTrue(kept > 0, "删之前先确认名下有记录")
        val del = okAdmin("DELETE", "/api/webhook/subscription/$subCreated")
        assertTrue(del.path("deleted").asBoolean())
        assertEquals(kept, jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery_log WHERE subscription_id = ?", Int::class.java, subCreated), "删订阅不删证据")
        expect("DELETE", "/api/webhook/subscription/$subCreated", null, 404)
    }

    @Test @Order(21) fun `批E 遗留五族方法 会员券 小程序码 地区 图片库 口令重置`() {
        // M20 会员维度券列表（与 CP-F3 同一装配；本库没有券域铺数，只证形状与总账为 0）
        val mine = gw("ecx.userdiscount.list", params = mapOf("user_id" to 1))
        assertEquals("E0000", code(mine))
        assertEquals(0L, mine.path("data").path("total_count").asLong(), "没发过券就是 0")

        // M21 小程序码：scene 形态，出参 base64 载体（差异 C236），解码必须还原 PNG 签名
        val q = gw("ecx.wxapp.qrcode", verb = "POST", version = "1.0", params = mapOf("scene" to "a=1", "type" to "unlimit"))
        assertEquals("E0000", code(q))
        assertEquals("image/png", q.path("data").path("content_type").asText())
        val bytes = java.util.Base64.getDecoder().decode(q.path("data").path("image").asText())
        assertTrue(bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte(), "解码即 PNG 字节头：${bytes.take(4)}")

        // M22 地区树（精简 seed，C237）：浙江省 → 下挂城市 → 城市再挂区县
        val tree = gw("ecx.jurisdiction.get")
        assertEquals("E0000", code(tree))
        assertTrue(tree.path("data").size() > 0, "seed 至少有省级行")
        assertEquals("浙江省", tree.path("data").get(0).path("name").asText())
        assertEquals(2, tree.path("data").get(0).path("children").get(0).path("level").asInt(), "第二级是市")

        // M23 图片上传（base64 载体，C238）→ 静态面可访问 → M24 图片库翻页含该行
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        val up = gw("ecx.image.upload", verb = "POST", version = "2.0", params = mapOf(
            "file_base64" to java.util.Base64.getEncoder().encodeToString(png), "file_name" to "e2e.png", "content_type" to "image/png",
        ))
        assertEquals("E0000", code(up))
        val url = up.path("data").path("url").asText()
        assertTrue(url.startsWith("/storage/"), "本地上传的 url 形状：$url")
        val static = mvc.perform(get(url)).andReturn()
        assertEquals(200, static.response.status, "上传后立即可访问：$url")
        val list = gw("ecx.image.list", version = "2.0")
        assertEquals("E0000", code(list))
        assertTrue(list.path("data").path("list").any { it.path("url").asText() == url }, "图片库台账含刚传的行")

        // M25 口令重置（源内部面）：临时口令当场出一次，用它重新登录即成功（真写库，不是摆设）
        val rp = gw("exc.operator.resetpwd", verb = "POST", version = "1.0", params = mapOf("username" to "admin"))
        assertEquals("E0000", code(rp))
        val temp = rp.path("data").path("temp_password").asText()
        assertTrue(temp.isNotBlank(), "临时口令当场出一次")
        assertEquals("admin", rp.path("data").path("username").asText())
        assertEquals(200, call("POST", "/api/operator/login", null, mapOf(
            "username" to "admin", "password" to temp,
        )).response.status, "拿到临时口令即刻可登录（本轮说明的是真改密而非摆设）")
        // 越租户/停用账号同回"账号不存在"（E5000 统一形状）——不存在也同码，不当探测面
        assertEquals("E5000", code(gw("exc.operator.resetpwd", verb = "POST", version = "1.0", params = mapOf("username" to "no-such-user"))))
    }

    @AfterAll fun shutdown() {
        runCatching { receiver.stop() }
    }

    // ================= 小工具 =================

    private fun deliveryIdOf(topic: String, orderId: Long): Long = jdbc.queryForObject(
        "SELECT id FROM webhook_delivery_log WHERE topic = ? AND payload LIKE ? ORDER BY id DESC LIMIT 1",
        Long::class.java, topic, "%\"order_id\":$orderId%",
    )!!

    /**
     * 把在途（`pending`/`running`）的 outbox 行排空。
     *
     * 投递类用例（[Order] 16/17）的前置：夹具的 `FAIL_ONCE` 只吃一次应答，
     * 留着上一轮的 pending 就会让"失败的第一次"落到别的行上，本条反而一次成功 —— 那种假绿看不出来。
     * 一轮 `deliverDue()` 只投到点的行，所以带退避的那条要等它到点，这里按小睡循环收敛。
     */
    private fun drain(timeoutMs: Long = 15000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            sender.deliverDue()
            val left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_delivery_log WHERE status IN ('pending', 'running')", Int::class.java,
            )!!
            if (left == 0) return
            assertTrue(System.currentTimeMillis() < deadline, "在途记录排不空（剩 $left 条）")
            Thread.sleep(200)
        }
    }

    /** 把一棵 JSON 摊平成"路径 → 叶子值"，用来做逐字段比对（嵌套层不比键名就等于没比） */
    private fun flatten(node: JsonNode, path: String, out: LinkedHashMap<String, String>) {
        when {
            node.isObject -> node.fields().forEach { (k, v) -> flatten(v, if (path.isEmpty()) k else "$path.$k", out) }
            node.isArray -> node.forEachIndexed { i, v -> flatten(v, "$path[$i]", out) }
            else -> out[path] = node.asText()
        }
    }

    /** 夹具侧的 HMAC（与 [SimWebhookReceiver] 同一实现路径，这里只在本类里当期望值用） */
    private fun receiverOwnHmac(secret: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun md5Upper(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.uppercase()

    private companion object {
        // seed 里的**假密钥**：只用于本地自签自验，与那批 bcrypt 演示口令同一惯例（docs/04 §25.5）
        const val AK1 = "ak_1a2b3c4d5e6f7081"
        const val SK1 = "sk_1a2b3c4d5e6f708192a3b4c5d6e7f809"
        const val AK2 = "ak_2b3c4d5e6f708192"
        const val SK2 = "sk_2b3c4d5e6f708192a3b4c5d6e7f8091a"
        const val OUT_SK1 = "esk_2b3c4d5e6f708192a3b4c5d6e7f809"

        const val OpenapiCode0 = "E0000"

        /** 出境面必须掩码的列名（与网关 `MOBILE_KEYS` 同一份清单） */
        val MOBILE_KEYS = setOf("mobile", "phone", "receiver_phone", "receiver_mobile", "contact_mobile")
    }
}
