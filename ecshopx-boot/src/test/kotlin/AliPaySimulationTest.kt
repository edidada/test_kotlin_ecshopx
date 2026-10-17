package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.service.pay.AliPayChannel
import com.ecshopx.kotlin.service.pay.PayChannelRegistry
import com.ecshopx.kotlin.service.pay.PayReconcileJob
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M1-2 支付宝 profile 用例（docs/05 §M1-2 的 "Alipay 沙箱 profile + 沙箱真单全链路 + 回调乱序/重复/伪造"）。
 *
 * 与 [WxPayV3SimulationTest] 同一套等价口径：跑在 `ecx.pay.active-channel-profile=alipay` 上，
 * 注册的是 [com.ecshopx.kotlin.service.pay.AliPayChannel]（官方 SDK 实现），网关指向本地假网关
 * [SimAliPayServer]，密钥复用 [SimWx] 的两把自签 RSA——**签名与验签全部为真**，
 * 只有"谁持有支付宝私钥"是仿的（缺的是沙箱 app_id 与两把密钥，不是代码）。
 *
 * 覆盖项：
 *  1. profile 切换：`pay_types` 里 wxpay 消失、alipay 就位；拿 wxpay 发起支付直接 501（真渠道没注册就是没有）；
 *  2. 下单：SDK 真签 `alipay.trade.create`，网关用商户公钥验过；金额分→元、buyer_id 来自会员绑定；
 *  3. 异步通知结清：`rsaCheckV1` 真验签 → PAYED，应答体必须是纯文本 `success`（否则支付宝无限重试）；
 *     乱序的 `WAIT_BUYER_PAY` 回 `failure`，重复通知幂等；
 *  4. 伪造：假签名 / 改金额保留原签名 / 错 app_id / 商户私钥冒签 / 签名有效但金额不符 / v3 JSON 报文，
 *     六种都拿不到结清，之后真通知仍能把这笔收回来；
 *  5. 掉单补单：渠道原话是 `TRADE_SUCCESS`，**必须归一化成 SUCCESS** 才会结清（不归一化就永远补不上）；
 *  6. 渠道侧关单：本地已关、渠道那张还活着 → 补单作业发 `trade.close`；已支付的单关不掉也只置确认位；
 *  7. 退款同步给结果：`alipay.trade.refund` 当场 `fund_change=Y`，退款单直接 SUCCESS、没有等回执这一态；
 *  8. 线下转账审核流不受 profile 影响（非渠道面与 mock 同一条路）。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exctxali;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        // 只开 alipay：mock 渠道与微信真渠道都不注册，registry 里 wxpay 这个 key 直接消失
        "ecx.pay.active-channel-profile=alipay",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.reconcile-enabled=false",
        "ecx.schedule.reconcile-initial-delay-ms=3600000",
        "ecx.schedule.reconcile-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AliPaySimulationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var registry: PayChannelRegistry
    @Autowired lateinit var reconcile: PayReconcileJob

    private var tok = ""
    private var other = ""
    private var admin = ""

    // ---------- 调用件 ----------

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
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

    private fun okAdmin(method: String, url: String, body: Map<String, Any?>? = null): JsonNode =
        data(call(method, url, admin, body), "admin $method $url")

    private fun expect(url: String, body: Map<String, Any?>?, code: Int, token: String?, contains: String? = null) {
        val res = call("POST", url, token, body)
        assertEquals(code, res.response.status, "$url -> " + utf8(res.response.contentAsByteArray))
        contains?.let {
            assertTrue(utf8(res.response.contentAsByteArray).contains(it), "错误体要说明原因：" + utf8(res.response.contentAsByteArray))
        }
    }

    /** 支付宝异步通知：form-urlencoded，参数逐条投给 `/api/notify/alipay`（真通知就是这么发的） */
    private fun postNotify(n: SimAliNotification): MvcResult {
        val req = post("/api/notify/alipay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
        n.params.forEach { (k, v) -> req.param(k, v) }
        return mvc.perform(req).andReturn()
    }

    private fun body(res: MvcResult): String = utf8(res.response.contentAsByteArray)

    /**
     * 支付宝只看响应文本：等于 `success` 才算处理完，其余（含一段微信 v2 的 XML）一律按固定间隔重发。
     * 所以这里断言的是**逐字节相等**，不是 contains。
     */
    private fun ackSuccess(res: MvcResult, label: String) {
        assertEquals(200, res.response.status, "$label 应答码 -> ${body(res)}")
        assertTrue(res.response.contentType?.startsWith("text/plain") == true, "$label 应答类型 ${res.response.contentType}")
        assertEquals("success", body(res), label)
    }

    private fun ackFail(res: MvcResult, label: String) {
        assertEquals("failure", body(res), "$label 应让渠道重发（非 success 就是没处理完）")
    }

    private fun orderVal(orderId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM orders_normal_orders WHERE order_id = ?", Any::class.java, orderId.toLong()).toString()

    private fun tradeVal(tradeId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM trade WHERE trade_id = ?", Any::class.java, tradeId).toString()

    private fun pointOf(userId: Long): Long =
        jdbc.query("SELECT point FROM point_member WHERE user_id = ?", { rs, _ -> rs.getLong(1) }, userId).firstOrNull() ?: 0L

    private fun refundStatusOf(refundBn: String): String =
        jdbc.queryForObject("SELECT refund_status FROM aftersales_refund WHERE refund_bn = ?", Any::class.java, refundBn.toLong()).toString()

    private fun newOrder(num: Int = 1, asMember: String = tok, addressId: Int = 1): String = ok(
        "/api/h5app/wxapp/order_new",
        mapOf(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to addressId,
            "items" to listOf(mapOf("item_id" to 3, "num" to num)),
        ),
        asMember = asMember,
    ).path("order_id").asText()

    private fun prepay(orderId: String, payType: String = "alipay", asMember: String = tok): JsonNode =
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to orderId, "pay_type" to payType), asMember)

    /** 前置动作：下一笔已结清真单（通知走真验签，渠道侧也记成已付） */
    private fun paidOrder(): Pair<String, String> {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        aliServer.markPaid(tradeId)
        ackSuccess(
            postNotify(SimAliNotification.of(tradeId, aliServer.tradeNoOf(tradeId), AliPayChannel.fenToYuan(d.path("total_fee").asLong()))),
            "前置结清通知",
        )
        return o to tradeId
    }

    // ---------- 用例 ----------

    @Test @Order(1) fun `profile 生效：只有 alipay 在注册表里，商户与平台两个身份互不相认`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        other = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "other").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()

        assertEquals("AliPayChannel", registry.get("alipay")::class.simpleName, "profile=alipay 必须注册官方 SDK 实现")
        // 微信真渠道没被这个 profile 启用 → 连 key 都不在注册表里，前端也就看不到它
        val types = ok("/api/h5app/wxapp/payment/config?order_id=${newOrder()}").path("pay_types").map { it.asText() }
        assertEquals(listOf("alipay", "point", "deposit", "offline_pay"), types, "PAY-1 渠道清单要按固定优先级、且不含未启用的渠道：$types")
        expect("/api/h5app/wxapp/payment", mapOf("order_id" to newOrder(), "pay_type" to "wxpay"), 501, tok, "暂不支持该支付方式")

        // 两把公钥必须分属两个身份：SDK 的 checkAlipayPublicKey 一旦发现"商户私钥能对上所配公钥"
        // 就断定密钥填反并直接抛异常，所以夹具也不能让两边同认一把钥匙
        assertNotEquals(SimWx.merchantPublicKeyB64, SimWx.platformPublicKeyB64, "商户公钥与支付宝公钥是两把钥匙")
        val probe = "ecx-sim-probe"
        val byMerchant = SimWx.sign(SimWx.merchantKey, probe)
        assertTrue(SimWx.verify(SimWx.merchantCert, probe, byMerchant), "商户签名要能被商户证书验过")
        assertTrue(!SimWx.verify(SimWx.platformCert, probe, byMerchant), "商户签名绝不能被平台证书验过（否则就是密钥配反）")
    }

    @Test @Order(2) fun `下单：网关验过 SDK 的真签名、金额分转元、buyer_id 来自会员绑定`() {
        // 不配兜底 buyer_id：真渠道必须知道钱从谁的支付宝出，与微信 openid 同一条不变量
        jdbc.update(
            "INSERT INTO members_associations(user_id, company_id, unionid, open_id, user_type) " +
                "VALUES(1, 1, 'sim-ali-union-0001', ?, 'alipay')", SimAli.BUYER_ID,
        )
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()

        assertEquals("NOTPAY", d.path("trade_state").asText(), "下单只是挂交易，钱还没进来")
        assertEquals("NOTPAY", tradeVal(tradeId, "trade_state"))
        assertEquals("NOTPAY", orderVal(o, "order_status"), "下单动作不许推订单状态")
        assertEquals("SIMALI$tradeId", d.path("trade_no").asText(), "trade_no 来自渠道应答")
        assertEquals(tradeId, d.path("out_trade_no").asText(), "渠道按支付单号认这笔交易")
        assertEquals(AliPayChannel.fenToYuan(total), d.path("total_amount").asText(), "出参给前端的金额是元")

        assertTrue(aliServer.calls.any { it.startsWith("alipay.trade.create $tradeId") }, "没打网关：${aliServer.calls}")
        assertEquals(true, aliServer.lastSignVerified, "网关验不过商户签名 = SDK 签名没接上")
        assertEquals(SimAli.APP_ID, aliServer.lastAppId, "请求里的 app_id 就是配置那一个")
        assertTrue(aliServer.lastBiz.contains("\"total_amount\":\"${AliPayChannel.fenToYuan(total)}\""), "金额换算没走对：${aliServer.lastBiz}")
        assertTrue(aliServer.lastBiz.contains("\"buyer_id\":\"${SimAli.BUYER_ID}\""), "buyer_id 没从会员绑定取：${aliServer.lastBiz}")
        assertEquals("WAIT_BUYER_PAY", aliServer.stateOf(tradeId), "渠道侧这笔还等买家付款")

        // 金额换算的边界：分→元定两位小数，元→分只接受两位以内
        assertEquals("89.00", AliPayChannel.fenToYuan(8900L))
        assertEquals("0.05", AliPayChannel.fenToYuan(5L))
        assertEquals("0.00", AliPayChannel.fenToYuan(0L))
        assertEquals(8900L, AliPayChannel.yuanToFen("89.00"))
        assertEquals(10L, AliPayChannel.yuanToFen("0.1"), "一位小数也认（渠道偶有省略末零）")
        assertEquals(123L, AliPayChannel.yuanToFen("1.234"), "超出两位的小数按四舍五入收口，不抛错也不多吃一分钱")

        // 没绑支付宝的会员发起不了：失败点要落在"缺 buyer_id"，先给它自己的收货地址排除地址因素
        val now = TimeSec.now()
        jdbc.update(
            "INSERT INTO members_address(address_id, user_id, company_id, username, telephone, adrdetail, is_def, created, updated) " +
                "VALUES(9001, 2, 1, '仿真收货人', '13800000002', '仿真路 2 号', TRUE, ?, ?)", now, now,
        )
        val o2 = newOrder(asMember = other, addressId = 9001)
        expect("/api/h5app/wxapp/payment", mapOf("order_id" to o2, "pay_type" to "alipay"), 422, other, "未绑定支付宝")
    }

    @Test @Order(3) fun `异步通知：乱序的先拒、真通知结清、应答纯文本 success、重复幂等`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()
        val yuan = AliPayChannel.fenToYuan(total)
        val p0 = pointOf(1L)

        // 乱序：钱还没到先来一封"等买家付款"——绝不能把订单推成已付
        ackFail(postNotify(SimAliNotification.of(tradeId, aliServer.tradeNoOf(tradeId), yuan, "WAIT_BUYER_PAY")), "未支付通知")
        assertEquals("NOTPAY", orderVal(o, "order_status"), "非成功状态不结清")
        assertEquals("NOTPAY", tradeVal(tradeId, "trade_state"))

        ackSuccess(postNotify(SimAliNotification.of(tradeId, aliServer.tradeNoOf(tradeId), yuan)), "结清通知")
        assertEquals("PAYED", orderVal(o, "order_status"))
        assertEquals("SUCCESS", tradeVal(tradeId, "trade_state"))
        assertEquals(total, tradeVal(tradeId, "pay_fee").toLong(), "实付记订单应付（元→分回来必须等于分）")
        assertEquals("SIMALI$tradeId", tradeVal(tradeId, "transaction_id"), "渠道流水号取通知里的 trade_no")
        assertEquals("alipay", tradeVal(tradeId, "pay_type"))
        val p1 = pointOf(1L)
        assertTrue(p1 >= p0, "赠送积分只可能不减：$p0 -> $p1")

        // 重复通知：trade 的 NOTPAY CAS 吃掉第二次，积分不二次入账
        ackSuccess(postNotify(SimAliNotification.of(tradeId, aliServer.tradeNoOf(tradeId), yuan)), "重复通知")
        assertEquals(p1, pointOf(1L), "重复通知不得二次入账")
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM trade WHERE trade_id = ? AND trade_state = 'SUCCESS'", Int::class.java, tradeId,
        ), "结清只有一行")
        assertEquals("SUCCESS", ok("/api/h5app/wxapp/payment/query", mapOf("trade_id" to tradeId)).path("trade_state").asText(), "PAY-3 轮询到已结清")

        // 结清后整链照旧走得通：换 profile 不换业务
        okAdmin("PUT", "/api/orders/delivery", mapOf(
            "order_id" to o.toLong(), "delivery_corp" to "zto", "delivery_code" to "SIM-M12-ALI",
        ))
        assertEquals("WAIT_BUYER_CONFIRM", orderVal(o, "order_status"))
        assertEquals("DONE", ok("/api/h5app/wxapp/order/confirmReceipt", mapOf("order_id" to o)).path("order_status").asText())
    }

    @Test @Order(4) fun `伪造与篡改：六种报文都结不清，真通知最后仍能把这笔收回来`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()
        val yuan = AliPayChannel.fenToYuan(total)
        val tn = aliServer.tradeNoOf(tradeId)
        val real = SimAliNotification.of(tradeId, tn, yuan)

        // 1 假签名：没有平台私钥只能瞎填
        ackFail(postNotify(SimAliNotification.forgedSignature(real)), "假签名")
        // 2 改包留原签名：中间人把金额改了
        ackFail(postNotify(SimAliNotification.tampered(real) { it + ("total_amount" to "99999.00") }), "改金额留原签名")
        // 3 签名有效但不是发给本应用的（同域名多应用复用回调地址）
        ackFail(postNotify(SimAliNotification.of(tradeId, tn, yuan, appId = SimAli.OTHER_APP_ID)), "app_id 不符")
        // 4 用商户私钥冒签平台通知：格式全对，身份不对
        ackFail(postNotify(SimAliNotification.signedByWrongKey(LinkedHashMap(real.params).apply { remove("sign") })), "商户密钥冒签")
        // 5 签名有效、金额与支付单不符：验签闸门放过，金额闸门必须拦下
        ackFail(postNotify(SimAliNotification.of(tradeId, tn, AliPayChannel.fenToYuan(total + 1L))), "金额不符")
        // 6 支付宝不发 JSON 报文：v3 那面在 alipay 渠道上必然解析不出东西
        val json = mvc.perform(
            post("/api/notify/alipay").contentType(MediaType.APPLICATION_JSON).content("""{"out_trade_no":"$tradeId"}"""),
        ).andReturn()
        assertEquals(500, json.response.status, "JSON 面必须回 FAIL：" + body(json))
        assertTrue(body(json).contains("FAIL"), "应答体要说明失败：" + body(json))

        assertEquals("NOTPAY", orderVal(o, "order_status"), "六次尝试一次都没结清")
        assertEquals("NOTPAY", tradeVal(tradeId, "trade_state"))
        // 真报文最后仍能结清：证明前面的失败不是"这笔单子坏了"
        ackSuccess(postNotify(real), "真通知结清")
        assertEquals("PAYED", orderVal(o, "order_status"))
    }

    @Test @Order(5) fun `掉单补单：渠道原话 TRADE_SUCCESS 必须先归一化成 SUCCESS 才结清`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()

        // 第一轮：渠道那边确实还没付（原话 WAIT_BUYER_PAY），补单不许结清
        assertEquals(0, reconcile.reconcileNow(TimeSec.now() + 3600).getValue("settled"), "没付的钱不能被补出来")
        assertEquals("NOTPAY", orderVal(o, "order_status"))

        // 第二轮：钱进了渠道、回调丢了。查单回来的是支付宝原话 TRADE_SUCCESS，
        // 不归一化就判不出"已付"，这笔单会永远躺在待补列表里——报文看起来完全正常
        aliServer.markPaid(tradeId)
        assertTrue(reconcile.reconcileNow(TimeSec.now() + 3600).getValue("settled") >= 1, "补单没结清这笔掉单")
        assertEquals("PAYED", orderVal(o, "order_status"))
        assertEquals("SUCCESS", tradeVal(tradeId, "trade_state"))
        assertEquals(total, tradeVal(tradeId, "pay_fee").toLong(), "补单结清的实付同样按支付单金额，不取渠道的元")
        assertEquals("SIMALI$tradeId", tradeVal(tradeId, "transaction_id"))
        assertTrue(aliServer.calls.any { it.startsWith("alipay.trade.query $tradeId") }, "没发查单：${aliServer.calls}")
        assertEquals(true, aliServer.lastSignVerified, "查单请求同样要真签真验")
        // 再跑一轮：已结清的行退出扫描集
        assertEquals(0, reconcile.reconcileNow(TimeSec.now() + 3600).getValue("settled"))
    }

    @Test @Order(6) fun `渠道侧关单：本地 CLOSED 后补关，已支付的关不掉也只置确认位`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        ok("/api/h5app/wxapp/order/cancel", mapOf("order_id" to o, "cancel_reason" to "不想要了"))
        assertEquals("CLOSED", tradeVal(tradeId, "trade_state"), "取消时本地先收掉支付单")
        assertEquals("0", tradeVal(tradeId, "channel_closed"))
        assertEquals("WAIT_BUYER_PAY", aliServer.stateOf(tradeId), "本地 CLOSED 不等于渠道 CLOSED：收银台还挂着")

        assertTrue(reconcile.reconcileNow(TimeSec.now() + 3600).getValue("closed") >= 1, "没关掉渠道交易")
        assertEquals("TRADE_CLOSED", aliServer.stateOf(tradeId), "渠道侧必须真的撤掉了")
        assertEquals("1", tradeVal(tradeId, "channel_closed"))
        val closeCall = "alipay.trade.close $tradeId"
        reconcile.reconcileNow(TimeSec.now() + 3600)
        assertEquals(1, aliServer.calls.count { it == closeCall }, "已确认的不再重发")

        // 已付款的交易关不掉：ACQ.TRADE_HAS_SUCCESS 是终局结论，同样只置确认位，订单不复活
        val o2 = newOrder()
        val d2 = prepay(o2)
        val t2 = d2.path("trade_id").asText()
        ok("/api/h5app/wxapp/order/cancel", mapOf("order_id" to o2, "cancel_reason" to "不想要了"))
        aliServer.markPaid(t2)
        assertEquals(1, reconcile.reconcileNow(TimeSec.now() + 3600).getValue("closed"))
        assertEquals("TRADE_SUCCESS", aliServer.stateOf(t2), "已付款的交易关不掉")
        assertEquals("1", tradeVal(t2, "channel_closed"), "渠道给了终局结论就停止重发")
        assertEquals("CANCEL", orderVal(o2, "order_status"), "关单结论不复活订单，款项归对账处理")
    }

    @Test @Order(7) fun `退款同步给结果：渠道当场成功，没有等回执这一态`() {
        val (o, tradeId) = paidOrder()
        val bn = ok("/api/h5app/wxapp/aftersales", mapOf(
            "order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND",
            "reason" to "质量问题", "description" to "开箱即损",
        )).path("aftersales_bn").asText()
        okAdmin("POST", "/api/aftersales/review", mapOf("aftersales_bn" to bn, "is_approved" to 1, "refuse_reason" to ""))
        assertEquals(0L, tradeVal(tradeId, "refunded_fee").toLong(), "退款前额度未被占用")

        val r = okAdmin("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 1))
        val refundBn = r.path("refund_bn").asText()
        val fee = jdbc.queryForObject(
            "SELECT refund_fee FROM aftersales_refund WHERE refund_bn = ?", Any::class.java, refundBn.toLong(),
        ).toString().toLong()
        assertTrue(fee > 0L, "退款额由行货款算出：$fee")
        assertEquals(true, r.path("settled").asBoolean(), "支付宝退款是同步接口，当场就是结果")
        assertEquals(false, r.path("pending_callback").asBoolean(), "支付宝没有「等回执」这一态，不能凭空造出来")
        assertEquals("SUCCESS", refundStatusOf(refundBn))
        assertEquals("SIMALI$tradeId", r.path("refund_id").asText(), "退款号取渠道应答里的 trade_no")
        assertEquals(fee, tradeVal(tradeId, "refunded_fee").toLong(), "额度从受理起就占用")
        assertTrue(tradeVal(tradeId, "refunded_fee").toLong() <= tradeVal(tradeId, "pay_fee").toLong(), "整单守恒：已退不得超实付")
        assertTrue(aliServer.calls.any { it.startsWith("alipay.trade.refund $tradeId") }, "退款没打到网关：${aliServer.calls}")
        assertEquals(refundBn, aliServer.lastRefundRequestNo, "幂等键必须是退款单号")
        assertTrue(aliServer.lastBiz.contains("\"refund_amount\":\"${AliPayChannel.fenToYuan(fee)}\""), "退款金额没按元给出：${aliServer.lastBiz}")
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders_operate_logs WHERE order_id = ? AND behavior = 'refund_success'", Int::class.java, o.toLong(),
        ), "同步退款只留一条终局流水")
    }

    @Test @Order(8) fun `线下转账审核流与渠道无关：通过即结清、驳回不动钱`() {
        val o = newOrder()
        val d = ok("/api/h5app/wxapp/payment", mapOf("order_id" to o, "pay_type" to "offline_pay"))
        val paySn = d.path("trade_id").asText()
        assertEquals("NOTPAY", orderVal(o, "order_status"))
        assertEquals("NOTPAY", tradeVal(paySn, "trade_state"))
        val row = jdbc.queryForObject("SELECT id FROM offline_payment WHERE order_id = ?", Any::class.java, o.toLong()).toString()

        // 驳回：只标凭证
        okAdmin("POST", "/api/offline-payments/check", mapOf("id" to row, "check_status" to 2, "remark" to "转账凭证看不清"))
        assertEquals("NOTPAY", orderVal(o, "order_status"), "驳回不动钱")

        val o2 = newOrder()
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to o2, "pay_type" to "offline_pay"))
        val row2 = jdbc.queryForObject("SELECT id FROM offline_payment WHERE order_id = ?", Any::class.java, o2.toLong()).toString()
        val fee = orderVal(o2, "total_fee").toLong()
        val r = okAdmin("POST", "/api/offline-payments/check", mapOf("id" to row2, "check_status" to 1, "pay_fee" to fee, "remark" to "柜台点收"))
        assertEquals("PAYED", orderVal(o2, "order_status"), "审核通过与渠道回调共用同一条 CAS 结清")
        assertEquals("offline_pay", tradeVal(r.path("trade_id").asText(), "pay_type"))
    }

    companion object {
        /** 本地假网关：第一次被读配置时就起来，端口由系统分配，跑完整类再停 */
        val aliServer: SimAliPayServer by lazy { SimAliPayServer().start() }

        @JvmStatic
        @DynamicPropertySource
        fun simProps(registry: DynamicPropertyRegistry) {
            registry.add("ecx.pay.alipay.server-url") { aliServer.gatewayUrl }
            registry.add("ecx.pay.alipay.app-id") { SimAli.APP_ID }
            // 商户私钥签请求、支付宝公钥验应答：两个身份各用各的钥匙
            registry.add("ecx.pay.alipay.private-key") { SimWx.merchantPrivateKeyB64 }
            registry.add("ecx.pay.alipay.alipay-public-key") { SimWx.platformPublicKeyB64 }
            registry.add("ecx.pay.alipay.notify-url") { "http://127.0.0.1/api/notify/alipay" }
            registry.add("ecx.pay.alipay.charset") { SimAli.CHARSET }
            registry.add("ecx.pay.alipay.sign-type") { SimAli.SIGN_TYPE }
            // 故意不给兜底 buyer_id：真渠道必须知道钱是谁付的
            registry.add("ecx.pay.alipay.fallback-buyer-id") { "" }
        }

        @JvmStatic
        @AfterAll
        fun stopSim() {
            aliServer.stop()
        }
    }
}
