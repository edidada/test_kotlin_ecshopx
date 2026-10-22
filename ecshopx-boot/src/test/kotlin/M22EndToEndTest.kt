package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.dao.pay.PayChannelConfigDO
import com.ecshopx.kotlin.dao.pay.PayChannelConfigMapper
import com.ecshopx.kotlin.service.pay.ChannelSign
import com.ecshopx.kotlin.service.pay.AdaPayChannel
import com.ecshopx.kotlin.service.systemlink.SystemLinkSign
import com.ecshopx.kotlin.service.systemlink.SystemLinkSignMode
import com.ecshopx.kotlin.service.wx.WxComponentState
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

/**
 * M2-2 对接族 e2e（docs/04 §27.6 门槛①..⑥，docs/05 §M2-2）。
 *
 * 六道门槛在这一份里一次钉死，全部离线（H2 + mock profile，不连任何外网）：
 *
 *  - **门槛①（两支签名）**：SystemLink 的 `assemble` **逐串**对上手写期望
 *    （含 `null`/布尔差异与 PHP `SORT_STRING` 的 "10" < "2" 字节序），同一参数两支签**必须不同**；
 *  - **门槛②（白名单位次）**：IP 白名单**在签名之后**——签对但 IP 不在名单里拿 `ip access denied`，
 *    签错拿 `sign error`，缺 `sign` 也拿 `sign error`；三种错误码不混答；
 *  - **门槛③（OME 留痕）**：一次调用落 `request` + `response` **两行同 msg_id**，
 *    SL-A3 列表不出 `params`/`result`，SL-A4 详情带原文；`items.setStore` 绝对回写 + 不认识的 bn 进 `no_update`；
 *  - **门槛④（site 四支）**：`ecapi.site` 的 create→checkisopen→renew（从原到期时间加）→close 全链 +
 *    重复 eid / 不续不合法两支 422 语义（fail 而不 500）；
 *  - **门槛⑤（WX mock 全不出网）**：直连绑定→模板→发送（成功行 `mock=1`、未配模板 40037 fail 行）→
 *    未绑定租户 422 且**不落行**→A9 PNG 字节头与 link JSON→G2 验签（ticket 进 [WxComponentState]、
 *    authorized/unauthorized 翻 `wx_app.status`）；
 *  - **门槛⑥（PAY 三渠道）**：pay() 只出参数包 `settled=false`→回调验签通过才结清（订单 PAYED）、
 *    错签 `FAIL` 不动账、`refund()` 回 `pendingCallback`。
 *
 * key 点：`ecx.pay.active-channel-profile=mock,adapay,bspay,hfpay`——三家聚合渠道按 pay_type
 * 隔离注册，mock 与它们不抢同一个键；`ecx.wx.component-token` 与门槛⑤验签用的 token 是同一把。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccm22;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.webhook-deliver-initial-delay-ms=3600000",
        "ecx.schedule.webhook-deliver-ms=3600000",
        "ecx.pay.active-channel-profile=mock,adapay,bspay,hfpay",
        "ecx.wx.component-token=wxcomp_token_22",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class M22EndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var state: WxComponentState
    @Autowired lateinit var payConfigs: PayChannelConfigMapper
    @Autowired lateinit var adaPay: AdaPayChannel

    private var tok = ""
    private var admin = ""
    private var op22 = ""

    // ================= 调用件 =================

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>? = null): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
            else -> delete(url)
        }
        token?.let { req.header("Authorization", "Bearer $it") }
        body?.let { req.contentType(MediaType.APPLICATION_JSON).content(toJson(it)) }
        return mvc.perform(req).andReturn()
    }

    private fun toJson(v: Any?): String = mapper.writeValueAsString(v)

    private fun bodyOf(res: MvcResult): JsonNode = mapper.readTree(utf8(res.response.contentAsByteArray))

    private fun data(res: MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label -> " + utf8(res.response.contentAsByteArray))
        return bodyOf(res).path("data")
    }

    /** SL-G1/G2 的应答不是 ApiResponse 包络，是 `{rsp, code, err_msg, data(串)}` —— 单独一副眼睛 */
    private fun envelope(res: MvcResult): JsonNode = bodyOf(res)

    /** e2e 惯用的直接 DB 眼睛（config 只插不读，渠道的读路径在产品代码里） */
    private fun tradeState(tradeId: String): String =
        jdbc.queryForObject("SELECT trade_state FROM trade WHERE trade_id = ?", String::class.java, tradeId)!!

    private fun orderCol(orderId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM orders_normal_orders WHERE order_id = ?", Any::class.java, orderId.toLong())
            .toString()

    /** 门槛④⑥的建单口：`pay_type` 给了就是 ORDER-3（下单+预支付），不给是 ORDER-2 只建单 */
    private fun createOrder(payType: String? = null): JsonNode {
        val body = linkedMapOf<String, Any?>(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to 1)),
        )
        payType?.let { body["pay_type"] = it }
        return ok("POST", "/api/h5app/wxapp/order", body)
    }

    private fun ok(verb: String, url: String, body: Map<String, Any?>? = null, asWho: String = tok): JsonNode =
        data(call(verb, url, asWho, body), url)

    private fun expect(m: String, url: String, body: Map<String, Any?>?, code: Int, token: String = admin): JsonNode {
        val res = call(m, url, token, body)
        assertEquals(code, res.response.status, "$m $url -> " + utf8(res.response.contentAsByteArray))
        return bodyOf(res)
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    // ================= 门槛①（两支 assemble/sign） =================

    @Test @Order(1) fun `令牌就绪与 seed 对接行`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null, mapOf(
            "username" to "admin", "password" to "admin" + "123",
        )), "admin").path("token").asText()
        assertTrue(tok.isNotBlank() && admin.isNotBlank())
        val row = jdbc.queryForMap(
            "SELECT is_open, openapi_token, oms_token, ip_whitelist FROM systemlink_setting WHERE company_id = 1",
        )
        assertEquals(1, (row["is_open"] as Number).toInt(), "seed 的对接开关是打开的")
        assertEquals("gy_openapi_demo_token_1", row["openapi_token"], "演示密钥用 seed 假值")
        assertEquals("gy_oms_demo_token_1", row["oms_token"])
        assertEquals("", row["ip_whitelist"], "默认不限 IP")
    }

    @Test @Order(2) fun `门槛一 两支 assemble 逐串钉死`() {
        val params: Map<String, Any?> = linkedMapOf("10" to "x", "2" to "y", "b" to true, "a" to null)
        // 键按 UTF-8 字节序排序是 "10" < "2" < "a" < "b"；真正的差异在 `null` 与布尔的串化上：
        //  OPENAPI：null 键保留、值为空串；true→"1"、false→""（PHP 语义）
        //  OME：null 整键跳过；true→"1"、false→"0"
        val a = SystemLinkSign.assemble(params, SystemLinkSignMode.OPENAPI)
        assertEquals("10x2yab1", a, "两支签名的 OPENAPI assemble 形状")
        val b = SystemLinkSign.assemble(params, SystemLinkSignMode.OME)
        assertEquals("10x2yb1", b, "两支签名的 OME assemble 形状（null 整键跳过）")
        // 同参数同 token，两支签必须不同 —— "换一条入口就报签名错误"的反向验证
        assertNotEquals(SystemLinkSign.sign(params, "tokx", SystemLinkSignMode.OPENAPI),
            SystemLinkSign.sign(params, "tokx", SystemLinkSignMode.OME), "两支公式一样但 assemble 不同，签不相等")
    }

    // ================= 门槛④（site 全链）+ 门槛① 的入站错签分支 =================

    private var siteCompanyId = 0L
    private var siteExpired0 = 0L

    @Test @Order(3) fun `门槛四 site 全链与错签分支`() {
        val create = linkedMapOf<String, Any?>(
            "eid" to "e22s1", "shopexid" to "sp22s1", "tel" to "13900002222",
            "company_name" to "对接族演示站点二", "days" to "3650",
        )
        val en = envelope(openEntry(create, "ecapi.site.create", "gy_openapi_demo_token_1", SystemLinkSignMode.OPENAPI))
        assertEquals("succ", en.path("rsp").asText(), "开通成功")
        val d = mapper.readTree(en.path("data").asText())
        siteCompanyId = d.path("company_id").asLong()
        assertTrue(siteCompanyId > 1, "company_id 要是新建的租户")
        siteExpired0 = d.path("expired_at").asLong()
        val dbCompanyId = jdbc.queryForObject(
            "SELECT company_id FROM company WHERE license_code = 'e22s1'", Long::class.java,
        )
        assertEquals(siteCompanyId, dbCompanyId, "合法 eid 一处入库")

        // 口令随机 bcrypt：先把 company 2 的操作员口令重置成已知值，后门槛⑤用它登录
        jdbc.update(
            "UPDATE operators SET password = ? WHERE username = '13900002222'",
            BCrypt.hashpw("op22pw", BCrypt.gensalt(10)),
        )
        op22 = data(call("POST", "/api/operator/login", null, mapOf(
            "username" to "13900002222", "password" to "op22pw",
        )), "operator22").path("token").asText()
        assertTrue(op22.isNotBlank(), "SL-M1 开出来的超管操作员应当能登录")

        // 重复开通：同 eid 再来一次 → fail（fail 是语义错，不是 500）
        val dup = envelope(openEntry(create, "ecapi.site.create", "gy_openapi_demo_token_1", SystemLinkSignMode.OPENAPI))
        assertEquals("fail", dup.path("rsp").asText(), "重复 eid 不能二次开通")

        // 续费：从**原到期时间**加 10 天（不是从今天加）
        val renew = linkedMapOf("eid" to "e22s1", "days" to "10")
        val rr = envelope(openEntry(renew, "ecapi.site.renew", "gy_openapi_demo_token_1", SystemLinkSignMode.OPENAPI))
        assertEquals("succ", rr.path("rsp").asText())
        val rd = mapper.readTree(rr.path("data").asText())
        assertEquals(siteExpired0 + 10 * 86400L, rd.path("expired_at").asLong(), "续费从原到期时间起算")
        val chk1 = envelope(openEntry(linkedMapOf("eid" to "e22s1"), "ecapi.site.checkisopen",
            "gy_openapi_demo_token_1", SystemLinkSignMode.OPENAPI))
        assertTrue(mapper.readTree(chk1.path("data").asText()).path("is_open").asBoolean(), "关闭前站点开着")
        // 错的续费天数：days<=0 不给续
        val badRenew = envelope(openEntry(linkedMapOf("eid" to "e22s1", "days" to "0"), "ecapi.site.renew",
            "gy_openapi_demo_token_1", SystemLinkSignMode.OPENAPI))
        assertEquals("fail", badRenew.path("rsp").asText(), "非法天数不续")
        val close = envelope(openEntry(linkedMapOf("eid" to "e22s1"), "ecapi.site.close",
            "gy_openapi_demo_token_1", SystemLinkSignMode.OPENAPI))
        val cd = mapper.readTree(close.path("data").asText())
        assertEquals("closed", cd.path("status").asText(), "关站")
        val chk2 = envelope(openEntry(linkedMapOf("eid" to "e22s1"), "ecapi.site.checkisopen",
            "gy_openapi_demo_token_1", SystemLinkSignMode.OPENAPI))
        assertFalse(mapper.readTree(chk2.path("data").asText()).path("is_open").asBoolean(), "关闭后 is_open 翻 false")

        // 门槛①错签分支：token 不对 → sign error（不区分"token 不存在"与"签不对"）
        val wrong = envelope(openEntry(linkedMapOf("eid" to "e22s1"), "ecapi.site.checkisopen",
            "gy_openapi_wrong_token", SystemLinkSignMode.OPENAPI))
        assertEquals("fail", wrong.path("rsp").asText())
        assertEquals("sign error", wrong.path("err_msg").asText())
    }

    /** 组一条已签的 SL-G1 POST，把 MvcResult 给出去（错签分支要看 envelope 全貌） */
    private fun openEntry(params: Map<String, Any?>, method: String, token: String, mode: SystemLinkSignMode): MvcResult {
        val sign = SystemLinkSign.sign(params, token, mode)
        var req = post("/api/systemlink/openapi/$method")
        params.forEach { (k, v) -> req = req.param(k, v.toString()) }   // MockMvc 不做 query 百分号解码（C209），一律 .param()
        req.param("sign", sign)
        return mvc.perform(req).andReturn()
    }

    /** 同上，OME 入口（签名 B）；token 缺省就是 seed 演示密钥 */
    private fun omeEntry(params: Map<String, Any?>, method: String?, token: String = "gy_oms_demo_token_1"): MvcResult {
        val sign = SystemLinkSign.sign(params, token, SystemLinkSignMode.OME)
        val url = if (method == null) "/api/systemlink/ome" else "/api/systemlink/ome/$method"
        var req = post(url)
        params.forEach { (k, v) -> req = req.param(k, v.toString()) }
        req.param("sign", sign)
        return mvc.perform(req).andReturn()
    }

    // ================= 门槛②（IP 白名单位次） =================

    @Test @Order(4) fun `门槛二 白名单在签名之后`() {
        // 先把白名单设成"还有一个永远不是本机地址的条目"
        data(call("PUT", "/api/setting/systemlink", admin, mapOf("ip_whitelist" to "10.0.0.1")), "save白名单")
        val p = linkedMapOf<String, Any?>(
            "list_quantity" to "[{\"bn\":\"SKU-NUT-01\",\"quantity\":18}]",
        )
        // 签对 + IP 不在名单 → `ip access denied`（而不是 sign error）
        val good = envelope(omeEntry(p, "store.items.quantity.list.update"))
        assertEquals("fail", good.path("rsp").asText())
        assertEquals("ip access denied", good.path("err_msg").asText(), "白名单在签名之后：签名过了才轮到 IP")
        // 签错 → 还是 sign error（同一来源，错误不因 IP 而被换脸）
        val bad = envelope(omeEntry(p, "store.items.quantity.list.update", "gy_oms_wrong_token"))
        assertEquals("sign error", bad.path("err_msg").asText(), "签错就是 sign error，IP 不掺和")
        // 缺 sign → sign error（identify 对空 sign 直接不认）
        var noSign = post("/api/systemlink/ome/store.items.quantity.list.update")
        p.forEach { (k, v) -> noSign = noSign.param(k, v.toString()) }
        val ms = envelope(mvc.perform(noSign).andReturn())
        assertEquals("sign error", ms.path("err_msg").asText(), "缺 sign 与签错同一口径")
        // 还原白名单为不限制，让后面的 OME 用例恢复可发
        data(call("PUT", "/api/setting/systemlink", admin, mapOf("ip_whitelist" to "")), "还原白名单")
        val back = envelope(omeEntry(p, "store.items.quantity.list.update"))
        assertEquals("succ", back.path("rsp").asText(), "白名单为空 = 不限制，恢复放行")
    }

    // ================= 门槛③（OME 留痕） =================

    private var msgId22 = 0L

    @Test @Order(5) fun `门槛三 OME 商品库存与两行留痕`() {
        val payload = "[{\"bn\":\"SKU-NUT-01\",\"quantity\":17},{\"bn\":\"bn_404\",\"quantity\":2}]"
        val p = linkedMapOf<String, Any?>("list_quantity" to payload)
        val en = envelope(omeEntry(p, "store.items.quantity.list.update"))
        assertEquals("succ", en.path("rsp").asText())
        val d = mapper.readTree(en.path("data").asText())
        assertEquals(1, d.path("updated").asInt(), "一件可识别的 bn 被绝对回写")
        assertEquals(2, d.path("total").asInt(), "total 是行数不是成功数")
        assertTrue(d.path("no_update").toString().contains("bn_404"), "不认识的 bn 进 no_update")
        assertEquals(17L, jdbc.queryForObject(
            "SELECT store FROM items WHERE item_bn = 'SKU-NUT-01'", Long::class.java,
        ), "绝对回写不是增量")

        // 留痕两行：request + response，同一 msg_id；列表页不出 params/result
        val page = data(call("GET", "/api/systemlink/logs?worker=store.items.quantity.list.update", admin), "SL-A3")
        assertTrue(page.path("total_count").asInt() >= 2, "至少这次调用的两行")
        val rows = page.path("list")
        val first = rows.get(0)
        assertEquals(setOf("id", "api_type", "worker", "status", "runtime", "msg_id", "created"),
            first.fieldNames().asSequence().toSet(), "SL-A3 列表行键契约（不出 params/result）")
        val reqRows = rows.asSequence().filter { it.path("api_type").asText() == "request" }
        val respRows = rows.asSequence().filter { it.path("api_type").asText() == "response" }
        assertTrue(reqRows.count() == respRows.count(), "请求行与应答行数量配平")
        msgId22 = reqRows.first().path("msg_id").asLong()
        assertEquals(msgId22, respRows.first { it.path("msg_id").asLong() == msgId22 }.path("msg_id").asLong(),
            "同一次调用同 msg_id")

        // SL-A4 详情：request 行带原文 params，且里面就是发来的 list_quantity
        val reqId = reqRows.first().path("id").asLong()
        val detail = data(call("GET", "/api/systemlink/log/$reqId", admin), "SL-A4")
        assertTrue(detail.path("params").asText().contains("SKU-NUT-01"), "详情页带请求原文（排障证据）")

        // filter by msg_id：SL-A3 支持按 msg_id 过滤，直接得到这次调用的两行
        val byMsg = data(call("GET", "/api/systemlink/logs?msg_id=$msgId22", admin), "SL-A3 msg_id")
        assertEquals(2, byMsg.path("total_count").asInt(), "一个 msg_id 精确两行")
    }

    // ================= 门槛⑤（WX mock 全不出网） =================

    @Test @Order(6) fun `门槛五 WX mock 链与留痕`() {
        // A3 直连绑定（app_id + app_secret；secret 只进不出）
        val bind = ok("POST", "/api/wechat/openplatform/direct_bind", mapOf(
            "app_id" to "wx_direct_22", "app_secret" to "sec22", "app_type" to "oa",
        ), admin)
        assertEquals("wx_direct_22", bind.path("app_id").asText())
        val a4 = ok("GET", "/api/wechat/openplatform/authorizer", null, admin)
        assertFalse(a4.has("app_secret"), "授权信息不出 app_secret（字段不产出，不是置 null）")
        assertEquals("wx_direct_22", a4.path("app_id").asText())
        assertEquals(1, a4.path("status").asInt())
        // A6 建模板（mock 注册表进程内）
        val tmpl = ok("POST", "/api/wechat/template", mapOf("type" to "wxa", "template_id" to "T22-OP"), admin)
        assertTrue(tmpl.path("id").asLong() > 0, "模板注册有 id")
        // A5 模板列表
        val list = ok("GET", "/api/wechat/templates?type=wxa", null, admin)
        assertTrue(list.toString().contains("T22-OP"), "A5 能看到新模板")
        // A8 发送成功：mock=1、落一行 wx_message_log status=success
        val send = ok("POST", "/api/wechat/template/send", mapOf(
            "touser" to "oOpen22", "template_id" to "T22-OP",
            "data" to mapOf("first" to mapOf("value" to "m22")),
        ), admin)
        assertEquals("success", send.path("status").asText())
        assertEquals(1, send.path("mock").asInt(), "mock 发送要带 mock=1（全程不出网的标志）")
        assertEquals("wx_direct_22", send.path("app_id").asText())
        val okRow = jdbc.queryForMap(
            "SELECT status, errcode, mock FROM wx_message_log WHERE company_id = 1 ORDER BY id DESC LIMIT 1",
        )
        assertEquals("success", okRow["status"])
        assertEquals(0, (okRow["errcode"] as Number).toInt())
        assertEquals(1, (okRow["mock"] as Number).toInt())
        // A8 发送失败：模板未配 → 40037 fail 行也落库（fail 也是排障证据）
        val fail = ok("POST", "/api/wechat/template/send", mapOf(
            "touser" to "oOpen22", "template_id" to "T_NOPE",
            "data" to mapOf("first" to mapOf("value" to "m22x")),
        ), admin)
        assertEquals("fail", fail.path("status").asText())
        assertEquals(40037, fail.path("errcode").asInt(), "未配模板给微信原话 40037")
        val failRow = jdbc.queryForMap(
            "SELECT status, errcode, mock FROM wx_message_log WHERE company_id = 1 ORDER BY id DESC LIMIT 1",
        )
        assertEquals("fail", failRow["status"])
        assertEquals(40037, (failRow["errcode"] as Number).toInt())
        // 未绑定租户（company 2，operator22）：422 且不落行
        val before = countMsgLog(2L)
        expect("POST", "/api/wechat/template/send", mapOf(
            "touser" to "oOpen22", "template_id" to "T22-OP",
            "data" to mapOf("first" to mapOf("value" to "m22b")),
        ), 422, op22)
        assertEquals(before, countMsgLog(2L), "未绑定租户发消息不落 wx_message_log")
        // A4 第二租户：没绑定 → 全 null 的空授权行
        val empty = ok("GET", "/api/wechat/openplatform/authorizer", null, op22)
        assertTrue(empty.path("app_id").isNull, "company 2 未绑定：app_id 为 null")
        // A9 二进制 PNG + link JSON：unlimit 是可解析 PNG 字节头（89 50 4E 47），不是假前缀几十字节
        val png = call("POST", "/api/wechat/wxa_code", admin, mapOf("type" to "unlimit", "scene" to "a=1"))
        val bytes = png.response.contentAsByteArray
        assertTrue(bytes.size > 4, "PNG 不是空壳")
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals(0x50, bytes[1].toInt())
        assertEquals(0x4E, bytes[2].toInt())
        assertEquals(0x47, bytes[3].toInt())
        val link = ok("POST", "/api/wechat/wxa_code", mapOf("type" to "link"), admin)
        assertTrue(link.has("url_link"), "link 形态回 JSON")
        // G2 验签：错签 400（VALIDATION）；对签 ticket 进进程内 WxComponentState；unauthorized 翻 wx_app.status
        expect("POST", "/api/wechat/openplatform/notify/wxcomp22?signature=deadbeef&timestamp=${now()}&nonce=n1",
            mapOf("InfoType" to "component_verify_ticket", "ComponentVerifyTicket" to "t22_ticket_1"), 400)
        val ts = now().toString()
        val sig = com.ecshopx.kotlin.service.wx.WxCrypt.sha1Hex(
            listOf("wxcomp_token_22", ts, "n2").sorted().joinToString(""),
        )
        val resp = call("POST",
            "/api/wechat/openplatform/notify/wxcomp22?signature=$sig&timestamp=$ts&nonce=n2",
            mapOf("InfoType" to "component_verify_ticket", "ComponentVerifyTicket" to "t22_ticket_1"))
        assertEquals(200, resp.response.status)
        assertEquals("success", utf8(resp.response.contentAsByteArray), "G2 应答是裸文本 success")
        assertEquals("t22_ticket_1", state.latestTicket(), "component_verify_ticket 进进程内状态（不落租户表）")
        // unauthorized 事件翻 wx_app.status → A4 status 0 → 重新直连绑定回 1
        val sig3 = com.ecshopx.kotlin.service.wx.WxCrypt.sha1Hex(
            listOf("wxcomp_token_22", ts, "n3").sorted().joinToString(""),
        )
        val respU = call("POST",
            "/api/wechat/openplatform/notify/wxcomp22?signature=$sig3&timestamp=$ts&nonce=n3",
            mapOf("InfoType" to "unauthorized", "AuthorizerAppId" to "wx_direct_22"))
        assertEquals(200, respU.response.status)
        assertEquals(0, ok("GET", "/api/wechat/openplatform/authorizer", null, admin).path("status").asInt(),
            "unauthorized 事件把授权行翻停用")
        ok("POST", "/api/wechat/openplatform/direct_bind", mapOf(
            "app_id" to "wx_direct_22", "app_secret" to "sec22", "app_type" to "oa",
        ), admin)
        assertEquals(1, ok("GET", "/api/wechat/openplatform/authorizer", null, admin).path("status").asInt(),
            "重新绑定授权行恢复 status=1")
    }

    private fun countMsgLog(companyId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM wx_message_log WHERE company_id = ?", Int::class.java, companyId)!!

    // ================= 门槛④之续（OME 交易四条 SL-M5..M7） =================

    @Test @Order(8) fun `门槛四之续 OME 建单即付再发货与只读关单`() {
        // ORDER-2 建单 → mock wxpay 一步支付（PAY-3 参数包 + PAY-5 表单回调，M21/PAY 的同一条链）
        val order = ok("POST", "/api/h5app/wxapp/order", mapOf(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to 1)),
        )).path("order_id").asText()
        assertTrue(order.isNotBlank())
        val tradeId = ok("POST", "/api/h5app/wxapp/payment", mapOf(
            "order_id" to order, "pay_type" to "wxpay",
        )).path("package").asText().removePrefix("prepay_id=")
        assertTrue(tradeId.isNotBlank(), "支付参数包里的支付单号：$tradeId")
        val fee = jdbc.queryForObject("SELECT total_fee FROM trade WHERE trade_id = ?", Long::class.java, tradeId)
        assertTrue(utf8(notifyFormFee(tradeId, fee)).contains("SUCCESS"), "mock 渠道表单回调应答 SUCCESS")
        assertEquals("PAYED", orderCol(order, "pay_status"), "回调后一步结清")

        // SL-M5 fullinfo：发货之前就查得出整单（订单在，只是没发走）
        val pre = envelope(omeEntry(linkedMapOf("tid" to order), "store.trade.fullinfo.get"))
        assertEquals("succ", pre.path("rsp").asText())
        val preTrade = mapper.readTree(pre.path("data").asText()).path("trade")
        assertEquals(order, preTrade.path("tid").asText(), "M5 出 OME 形状的 {trade:{...}}")

        // SL-M6 发货：委托 OrderFlowService.ship（状态机与留痕都归它）
        val ship = envelope(omeEntry(linkedMapOf(
            "tid" to order, "company_code" to "SF", "logistics_no" to "SF22" + order.takeLast(6),
        ), "store.logistics.offline.send"))
        assertEquals("succ", ship.path("rsp").asText(), "ERP 回传发货")
        assertEquals("PAYED", orderCol(order, "pay_status"), "发货不动支付位")
        val shipStatus = jdbc.queryForMap(
            "SELECT order_status, pay_status, delivery_status FROM orders_normal_orders WHERE order_id = ?",
            order.toLong(),
        )
        assertEquals("DONE", shipStatus["delivery_status"].toString(), "整单发货 delivery_status=DONE")
        // orders_delivery 落一行（§27.6 门槛④：要有快递单）
        val delivery = jdbc.queryForMap(
            "SELECT delivery_corp, delivery_code, package_type FROM orders_delivery WHERE order_id = ? ORDER BY id DESC LIMIT 1",
            order.toLong(),
        )
        assertEquals("SF", delivery["delivery_corp"].toString())
        assertEquals("batch", delivery["package_type"].toString(), "oid_list 未给 = 整单批量")
        assertTrue(jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders_delivery WHERE order_id = ?", Long::class.java, order.toLong(),
        ) >= 1L, "快递单落了 orders_delivery")
        // 重复发货：订单已发货，请勿重复发货
        val again = envelope(omeEntry(linkedMapOf(
            "tid" to order, "company_code" to "SF", "logistics_no" to "SF22" + order.takeLast(6),
        ), "store.logistics.offline.send"))
        assertEquals("fail", again.path("rsp").asText())
        assertEquals("订单已发货，请勿重复发货", again.path("err_msg").asText())
        // M5 再查：delivery_status 已翻 DONE
        val post = envelope(omeEntry(linkedMapOf("tid" to order), "store.trade.fullinfo.get"))
        assertEquals("DONE", mapper.readTree(post.path("data").asText()).path("trade")
            .path("delivery_status").asText(), "M5 能看到发货之后的单")

        // SL-M7 关单语义：只读不写 —— 未走取消单，TRADE_CLOSED 不给 true，且**订单一行不变**
        val colBefore = jdbc.queryForMap(
            "SELECT order_status, pay_status, delivery_status FROM orders_normal_orders WHERE order_id = ?",
            order.toLong(),
        )
        val close = envelope(omeEntry(linkedMapOf(
            "tid" to order, "status" to "TRADE_CLOSED", "type" to "status",
        ), "store.trade.status.update"))
        assertEquals("fail", close.path("rsp").asText())
        assertEquals("订单信息有误", close.path("err_msg").asText(), "cancel_status 不是 SUCCESS 不答 true")
        val colAfter = jdbc.queryForMap(
            "SELECT order_status, pay_status, delivery_status FROM orders_normal_orders WHERE order_id = ?",
            order.toLong(),
        )
        assertEquals(colBefore, colAfter, "M7 只读不写（关单动作不在这一条链上）")

        // SL-M5 的 404 语义：不存在的 tid → fail 而不是 500（对端拿到的是语义错）
        val sl = envelope(omeEntry(linkedMapOf("tid" to "99999999"), "store.trade.fullinfo.get"))
        assertEquals("fail", sl.path("rsp").asText(), "M5 查不到订单：未找到订单（fail 不是 500）")
        assertEquals("未找到订单", sl.path("err_msg").asText())
    }

    private fun notifyFormFee(tradeId: String, fee: Long): String = utf8(
        mvc.perform(
            post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId)
                .param("total_fee", fee.toString())
                .param("transaction_id", "WX-" + tradeId.takeLast(6))
                .param("trade_state", "SUCCESS"),
        ).andReturn().response.contentAsByteArray,
    )

    // ================= 门槛⑥（PAY 三渠道） =================

    @Test @Order(7) fun `门槛六 PAY 三渠道 参数包 回调结清 错签不动账`() {
        // 三家的租户级配置行（真生产进 Admin 面之前先拿表说话；密钥全是演示假值）
        val keyByChannel = mapOf(
            "adapay" to "paykey_adapay_demo", "bspay" to "paykey_bspay_demo", "hfpay" to "paykey_hfpay_demo",
        )
        keyByChannel.forEach { (ch, key) ->
            payConfigs.insert(PayChannelConfigDO().apply {
                companyId = 1
                channel = ch
                merchantNo = "mer_$ch"
                appId = "app_$ch"
                apiKey = key
                notifyUrl = "https://demo.local/notify/$ch"
                status = 1
            })
        }

        val orders = mutableMapOf<String, String>() // channel -> tradeId
        keyByChannel.keys.forEach { ch ->
            val d = createOrder(payType = ch)
            // PAY 输入是渠道的参数包**直接平铺**（PayService 把 emerged params 合进出参）
            val tradeId = d.path("out_trade_no").asText()
            assertTrue(tradeId.startsWith("T"), "trade 单号形状：$tradeId")
            assertEquals("mer_$ch", d.path("merchant_no").asText(), "参数包带商户号")
            assertTrue(d.path("sign").asText().isNotBlank(), "参数包带签名")
            assertTrue(d.has("amount"), "参数包带 amount")
            assertEquals("NOTPAY", tradeState(tradeId), "只出参数包不结清（settled=false 这一位的 DB 反证）")
            orders[ch] = tradeId
            // 错签回调：400/500 口径 FAIL，且**不动账**
            val wrong = notifyBody("mer_$ch", d.path("amount").asText(), "success", tradeId, "wrongkey_$ch")
            val wrongRes = call("POST", "/api/notify/$ch", null, wrong)
            assertEquals(500, wrongRes.response.status, "错签回调必须 FAIL")
            assertTrue(utf8(wrongRes.response.contentAsByteArray).contains("\"code\":\"FAIL\""))
            assertEquals("NOTPAY", tradeState(tradeId), "错签不动账")
        }

        // 对签回调：验签过后结清 → 订单 PAYED
        keyByChannel.forEach { (ch, key) ->
            val tradeId = orders[ch]!!
            val amount = jdbc.queryForObject("SELECT total_fee FROM trade WHERE trade_id = ?", Long::class.java, tradeId)
            val good = notifyBody("mer_$ch", amount.toString(), "success", tradeId, key)
            val res = call("POST", "/api/notify/$ch", null, good)
            assertEquals(200, res.response.status, "验签通过的回调 200")
            assertTrue(utf8(res.response.contentAsByteArray).contains("\"code\":\"SUCCESS\""))
            assertEquals("SUCCESS", tradeState(tradeId), "回调把支付单结清")
            assertEquals("PAYED", orderCol(jdbc.queryForObject(
                "SELECT order_id FROM trade WHERE trade_id = ?", String::class.java, tradeId,
            ), "pay_status"), "订单随支付单 PAYED")
        }

        // 退款四件事的第二件：refund() 回 pendingCallback（三家退款都是异步受理）
        val tradeId = orders["adapay"]!!
        val trade = com.ecshopx.kotlin.dao.pay.TradeDO().apply {
            this.tradeId = tradeId
            totalFee = 8900L
        }
        val out = adaPay.refund(com.ecshopx.kotlin.service.pay.RefundRequest(
            com.ecshopx.kotlin.dao.order.NormalOrderDO().apply { companyId = 1 },
            trade,
            refundBn = 99001L,
            fee = 8900L,
            point = 0L,
        ))
        assertFalse(out.settled, "退款不当场结清")
        assertTrue(out.pendingCallback, "三家退款回执等回调推进")
    }

    private fun notifyBody(merchantNo: String, amount: String, status: String, tradeId: String, key: String): Map<String, Any?> {
        val params = linkedMapOf<String, Any?>(
            "merchant_no" to merchantNo,
            "out_trade_no" to tradeId,
            "amount" to amount,
            "status" to status,
            "transaction_id" to "CH22$tradeId",
        )
        // 与 [ChannelSign.canonical] 同一约定：sign 不参与连拼
        val v = LinkedHashMap<String, Any?>(params)
        v["sign"] = ChannelSign.hmacSha256Hex(key, params)
        return v
    }
}
