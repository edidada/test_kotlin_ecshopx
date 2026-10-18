package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.PayContract
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0-7 DoD：支付闭环端到端（PAY-1..5 + ORDER-3）与「加购→预览→下单→余额支付→回调→发货→收货」全链路。
 *
 * 演示数据：会员 user_id=1（积分 1000、余额 0）、商品 3=每日坚果礼盒 8900 分、地址 address_id=1、
 * 充值档位「充 100 送 10 / 充 500 送 80」（分）。超时关单任务推到 1 小时后，避免回调断言期间被关单抢状态。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccpay;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PayEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var timeoutJob: com.ecshopx.kotlin.service.order.OrderTimeoutJob

    private var tok: String = ""
    private var admin: String = ""
    private var other: String = ""

    private var czTrade: String = ""
    private var orderDep1: String = ""
    private var orderPoint: String = ""
    private var orderWx: String = ""
    private var orderWxXml: String = ""
    private var orderOffline: String = ""
    private var orderLow: String = ""
    private var orderCancel: String = ""

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

    /** 会员侧成功调用；payment/config 是唯一用 GET 的支付端点 */
    private fun ok(url: String, body: Map<String, Any?>? = null, asMember: String = tok): JsonNode {
        val method = if (body == null && url.contains("payment/config")) "GET" else "POST"
        return data(call(method, url, asMember, body), url)
    }

    /** 只读端点统一走 GET */
    private fun fetch(url: String, asMember: String = tok) = data(call("GET", url, asMember, null), url)

    private fun okAdmin(method: String, url: String, body: Map<String, Any?>? = null) =
        data(call(method, url, admin, body), "admin $method $url")

    private fun expect(method: String, url: String, body: Map<String, Any?>?, code: Int, token: String? = tok): JsonNode {
        val res = call(method, url, token, body)
        assertEquals(code, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    private fun balance(): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(deposit_amount + gift_amount), 0) FROM user_deposit WHERE user_id = 1", Long::class.java,
    )!!

    private fun point(): Long = jdbc.queryForObject("SELECT point FROM point_member WHERE user_id = 1", Long::class.java)!!
    private fun store(): Long = jdbc.queryForObject("SELECT store FROM items WHERE item_id = 3", Long::class.java)!!

    private fun tradeState(tradeId: String) =
        jdbc.queryForObject("SELECT trade_state FROM trade WHERE trade_id = ?", String::class.java, tradeId)!!

    private fun orderCol(orderId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM orders_normal_orders WHERE order_id = ?", Any::class.java, orderId.toLong()).toString()

    /** pay_type 非空走 ORDER-3（下单+预支付），否则 ORDER-2 只建单 */
    private fun createOrder(num: Int = 1, payType: String? = null): String {
        val body = linkedMapOf<String, Any?>(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to num)),
        )
        payType?.let { body["pay_type"] = it }
        return ok(if (payType == null) "/api/h5app/wxapp/order_new" else "/api/h5app/wxapp/order", body)
            .path("order_id").asText()
    }

    private fun pay(orderId: String, payType: String) =
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to orderId, "pay_type" to payType))

    private fun recharge(amount: Long) =
        ok("/api/h5app/wxapp/payment_deposit", mapOf("amount" to amount)).path("order_id").asText()

    private fun notifyForm(tradeId: String, fee: Any, state: String = "SUCCESS"): String = utf8(
        mvc.perform(
            post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId).param("total_fee", fee.toString())
                .param("transaction_id", "WX-" + tradeId.take(6)).param("trade_state", state),
        ).andReturn().response.contentAsByteArray,
    )

    @Test @Order(1) fun `令牌就绪与账户基线`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null, mapOf("username" to "admin", "password" to "admin" + "123")), "admin")
            .path("token").asText()
        other = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "other").path("token").asText()
        assertTrue(tok.isNotBlank() && admin.isNotBlank() && other.isNotBlank())
        assertEquals(200L, store(), "商品 3 基线库存")
        assertEquals(0L, balance(), "seed 余额为 0，充值用例才有时序意义")
        assertEquals(1000L, point(), "seed 积分 1000")
    }

    @Test @Order(2) fun `PAY-4 充值单与到账`() {
        czTrade = recharge(10000)
        assertTrue(czTrade.startsWith("CZ") && czTrade.length == 18 && czTrade.drop(2).toLong() > 0,
            "CZ 前缀 + 16 位号段单号：$czTrade")
        assertContract(
            ok("/api/h5app/wxapp/payment", mapOf("order_id" to czTrade, "pay_type" to "recharge")),
            PayContract.RECHARGE_SETTLED, "PAY-2 充值结清",
        )
        assertEquals(11000L, balance(), "本金 10000 + 档位赠金 1000")
        assertEquals(10000L, ok("/api/h5app/wxapp/payment/query", mapOf("trade_id" to czTrade))
            .also { assertContract(it, PayContract.TRADE_STATE, "PAY-3 充值单") }.path("pay_fee").asLong(),
            "pay_fee 记充值本金，赠金不算实付")
        expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to czTrade, "pay_type" to "recharge"), 422)
        assertEquals(11000L, balance(), "重复结清不动账")
        // 非档位金额不送赠金
        val cz2 = recharge(12345)
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to cz2, "pay_type" to "recharge"))
        assertEquals(23345L, balance(), "11000 + 12345 + 0 赠金")
        assertEquals(12345L, ok("/api/h5app/wxapp/payment/query", mapOf("trade_id" to cz2)).path("pay_fee").asLong())
    }

    @Test @Order(3) fun `PAY-1 支付参数`() {
        orderDep1 = createOrder()
        val d = ok("/api/h5app/wxapp/payment/config?order_id=$orderDep1")
        assertContract(d, PayContract.CONFIG, "PAY-1 配置")
        assertEquals(8900L, d.path("total_fee").asLong())
        assertEquals(0L, d.path("pay_fee").asLong(), "未付款实付 0")
        assertEquals("NOTPAY", d.path("trade_state").asText())
        assertEquals(listOf("wxpay", "point", "deposit", "offline_pay"), d.path("pay_types").map { it.asText() })
        assertEquals(23345L, d.path("deposit").path("balance").asLong())
        assertContract(d.path("deposit"), PayContract.DEPOSIT_VIEW, "PAY-1 余额视图")
        assertContract(d.path("point"), PayContract.POINT_VIEW, "PAY-1 积分视图")
        assertEquals(false, d.path("point").path("can_pay").asBoolean(), "1000 积分不足以结清 8900")
        assertTrue(d.path("auto_cancel_time").asLong() > 0, "待付款单必须有超时时刻")
        expect("GET", "/api/h5app/wxapp/payment/config?order_id=999999999999999", null, 404)
    }

    @Test @Order(4) fun `PAY-2 余额支付即时结清`() {
        val before = balance()
        val d = pay(orderDep1, "deposit")
        assertContract(d, PayContract.PAY_SETTLED, "PAY-2 结清")
        assertEquals(8900L, d.path("pay_fee").asLong())
        assertEquals("SUCCESS", d.path("trade_state").asText())
        assertEquals(8900L, d.path("deposit_used").asLong())
        assertEquals(before - 8900, balance(), "余额只扣一次")
        assertEquals("PAYED", orderCol(orderDep1, "pay_status"))
        assertEquals(8900L, orderCol(orderDep1, "pay_fee").toLong(), "订单落实付")
        assertEquals("SUCCESS", tradeState(d.path("trade_id").asText()))
        val b2 = balance()
        expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to orderDep1, "pay_type" to "deposit"), 422)
        assertEquals(b2, balance(), "被拒的支付不动余额")
        assertEquals("PAYED", fetch("/api/h5app/wxapp/order/$orderDep1").path("orderInfo").path("pay_status").asText())
        assertEquals(1L, fetch("/api/h5app/wxapp/orderscount").path("pending_delivery").asLong(), "结清后进待发货")
    }

    @Test @Order(5) fun `PAY-2 积分支付与赠送积分入账（M1-5 起走建单算出的 get_points，不再手写 UPDATE）`() {
        jdbc.update("UPDATE point_member SET point = 20000 WHERE user_id = 1")
        orderPoint = createOrder()
        // §23.3 PT-R1：这一列从此由建单写入（演示商品 item_id=3 每件 100 分 × 1 件）。
        // 本批之前的做法是这里补一句 `UPDATE ... SET get_points = 100` 再断言入账 ——
        // 那等于把"C54 这条判据在真实建单链上跑不通"伪装成测试通过（docs/05 §72 记过的遗留）。
        assertEquals("100", orderCol(orderPoint, "get_points"), "get_points 必须由建单写入")
        assertEquals(
            orderCol(orderPoint, "get_points"), orderCol(orderPoint, "item_point"),
            "单头可得积分 = Σ行 item_point（行侧不另开列，§23.3 PT-R1）",
        )
        val d = pay(orderPoint, "point")
        assertEquals("SUCCESS", d.path("trade_state").asText())
        assertEquals(8900L, d.path("point_used").asLong(), "1 积分 = 1 分")
        assertEquals(20000L - 8900 + 100, point(), "扣分 8900 后入账赠送 100")
        assertEquals("PAYED", orderCol(orderPoint, "pay_status"))
        val log = jdbc.queryForList(
            "SELECT change_point, before_point, after_point, remark FROM point_member_log WHERE order_id = ?", orderPoint,
        )
        assertEquals(1, log.size, "支付赠送积分必须留痕")
        assertEquals(
            mapOf("change_point" to 100L, "before_point" to 11100L, "after_point" to 11200L, "remark" to "订单支付赠送积分"),
            log[0].filterKeys { it in setOf("change_point", "before_point", "after_point", "remark") },
        )
        // 纯积分兑换结清：赠送分不回扣，取消也不退积分（退款属 M1）
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM point_member_log WHERE order_id = ?", Int::class.java, orderPoint))
    }

    @Test @Order(6) fun `余额积分不足与非法渠道`() {
        orderLow = createOrder()
        val before = balance()
        jdbc.update("UPDATE user_deposit SET deposit_amount = 0, gift_amount = 0 WHERE user_id = 1")
        val res = expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to orderLow, "pay_type" to "deposit"), 422)
        assertTrue(res.path("message").asText().contains("余额不足"), res.toString())
        assertEquals(setOf("message", "errors", "code", "status_code"), ContractDiff.keysOf(res), "错误体四件套")
        assertEquals("NOTPAY", orderCol(orderLow, "order_status"), "扣款失败订单不动")
        val tid = jdbc.queryForObject(
            "SELECT trade_id FROM trade WHERE order_id = ? AND trade_state = 'NOTPAY'", String::class.java, orderLow,
        )!!
        assertEquals("NOTPAY", tradeState(tid), "失败的支付不得把支付单写成成功")
        assertEquals(0L, orderCol(orderLow, "pay_fee").toLong())
        jdbc.update("UPDATE point_member SET point = 10 WHERE user_id = 1")
        expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to orderLow, "pay_type" to "point"), 422)
        assertEquals("NOTPAY", orderCol(orderLow, "pay_status"), "积分不足同样不结清")
        expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to orderLow, "pay_type" to "alipaymini"), 501)
        expect("POST", "/api/h5app/wxapp/payment", mapOf("pay_type" to "deposit"), 400)
        jdbc.update("UPDATE user_deposit SET deposit_amount = ?, gift_amount = 0 WHERE user_id = 1", before + 8900)
        // 换渠道重试：作废旧支付单，只留一笔活跃单（pay_type 与最后一次尝试一致）
        val retry = pay(orderLow, "deposit")
        assertEquals("SUCCESS", tradeState(retry.path("trade_id").asText()))
        assertEquals("CLOSED", tradeState(tid), "换过渠道的旧单收掉")
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade WHERE order_id = ? AND trade_state = 'NOTPAY'", Int::class.java, orderLow,
            ), "结清后同一订单不留活跃支付单",
        )
        assertEquals(
            1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade WHERE order_id = ? AND trade_state = 'SUCCESS'", Int::class.java, orderLow,
            ), "重试只留一笔成功单",
        )
    }

    @Test @Order(7) fun `PAY-2 预支付与 PAY-5 回调幂等`() {
        orderWx = createOrder()
        val d = pay(orderWx, "wxpay")
        assertContract(d, PayContract.PAY_JSAPI, "ORDER-3 JSAPI 参数包")
        val tradeId = d.path("trade_id").asText()
        assertTrue(d.path("package").asText().startsWith("prepay_id=$tradeId"), d.path("package").asText())
        assertEquals("NOTPAY", orderCol(orderWx, "pay_status"), "出参数不等于收到钱")
        assertEquals(0L, orderCol(orderWx, "pay_fee").toLong())
        assertTrue(notifyForm(tradeId, 1).contains("FAIL"), "金额校验必须挡住")
        assertEquals("NOTPAY", orderCol(orderWx, "pay_status"))
        assertTrue(notifyForm(tradeId, 8900).contains("SUCCESS"))
        assertEquals("PAYED", orderCol(orderWx, "pay_status"))
        assertEquals(8900L, orderCol(orderWx, "pay_fee").toLong())
        assertEquals("SUCCESS", tradeState(tradeId))
        assertTrue(notifyForm(tradeId, 8900).contains("SUCCESS"), "重复回调仍应答成功")
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade WHERE trade_id = ?", Int::class.java, tradeId))
        assertEquals(
            1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders_operate_logs WHERE order_id = ? AND behavior = 'payed'",
                Int::class.java, orderWx.toLong(),
            ), "结清留痕只有一条",
        )
        assertTrue(
            notifyForm(tradeId, 8900, state = "NOTPAY").contains("SUCCESS"),
            "已结清单的失败态回调不得回退订单",
        )
        assertEquals("PAYED", orderCol(orderWx, "pay_status"))
        // XML 报文写法（微信支付 v2 风格）
        orderWxXml = createOrder()
        val t2 = pay(orderWxXml, "wxpay").path("trade_id").asText()
        val xml = "<xml><out_trade_no><![CDATA[$t2]]></out_trade_no><total_fee><![CDATA[8900]]></total_fee>" +
            "<transaction_id><![CDATA[WX-XML-1]]></transaction_id><trade_state><![CDATA[SUCCESS]]></trade_state></xml>"
        assertTrue(
            utf8(mvc.perform(post("/api/notify/wxpay").contentType(MediaType.TEXT_XML).content(xml))
                .andReturn().response.contentAsByteArray).contains("SUCCESS"),
        )
        assertEquals("PAYED", orderCol(orderWxXml, "pay_status"))
        assertEquals(
            "WX-XML-1",
            jdbc.queryForObject("SELECT transaction_id FROM trade WHERE trade_id = ?", String::class.java, t2),
        )
        assertTrue(notifyForm("T00000000000000000", 8900).contains("FAIL"), "不存在的支付单要拒")
    }

    @Test @Order(8) fun `PAY-2 线下转账只落凭证`() {
        orderOffline = createOrder()
        val d = pay(orderOffline, "offline_pay")
        assertEquals("NOTPAY", d.path("trade_state").asText(), "线下转账要等审核")
        assertEquals("NOTPAY", orderCol(orderOffline, "pay_status"))
        assertEquals(
            1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM offline_payment WHERE order_id = ? AND check_status = 0",
                Int::class.java, orderOffline.toLong(),
            ),
        )
        assertEquals(0L, orderCol(orderOffline, "pay_fee").toLong(), "线下不得写实付")
        assertEquals(d.path("trade_id").asText(), jdbc.queryForObject("SELECT pay_sn FROM offline_payment WHERE order_id = ?", String::class.java, orderOffline.toLong()))
    }

    @Test @Order(9) fun `ORDER-3 下单加预支付一步结清`() {
        val czBig = recharge(50000)
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to czBig, "pay_type" to "recharge"))
        assertTrue(balance() > 17800, "本例与后面的全链路都要够付两单：${balance()}")
        val order3 = createOrder(payType = "deposit")
        val d = ok("/api/h5app/wxapp/payment/config?order_id=$order3")
        assertEquals("PAYED", d.path("pay_status").asText(), "余额渠道当场结清")
        assertEquals(8900L, d.path("pay_fee").asLong())
        assertEquals("PAYED", fetch("/api/h5app/wxapp/order/$order3").path("orderInfo").path("pay_status").asText())
        assertEquals(1, fetch("/api/h5app/wxapp/order/$order3").path("items").size())
        // ORDER-3 未知渠道：建单成功但预支付 501，订单留在待付款可再付
        val mixed = ok("/api/h5app/wxapp/order_new", mapOf(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to 1)),
        )).path("order_id").asText()
        expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to mixed, "pay_type" to "paypal"), 501)
        assertEquals("NOTPAY", orderCol(mixed, "order_status"), "预支付失败不影响已建订单")
        pay(mixed, "deposit")
        assertEquals("PAYED", orderCol(mixed, "pay_status"))
    }

    @Test @Order(10) fun `全链路 加购 预览 下单 余额支付 发货 收货`() {
        val cart = ok("/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 1))
        assertEquals(1, cart.path("valid_cart")[0].path("list").size())
        val preview = ok("/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "cart", "order_type" to "normal"))
        assertEquals(8900L, preview.path("total_fee").asLong())
        val created = ok("/api/h5app/wxapp/order_new", mapOf("cart_type" to "cart", "order_type" to "normal", "address_id" to 1))
        val orderId = created.path("order_id").asText()
        assertEquals(8900L, created.path("total_fee").asLong(), "下单价==预览价")
        assertEquals(0L, fetch("/api/h5app/wxapp/cartcount").path("count").asLong(), "下单清车")
        assertEquals("SUCCESS", pay(orderId, "deposit").path("trade_state").asText())
        val storeBefore = store()
        okAdmin("PUT", "/api/orders/delivery", mapOf(
            "order_id" to orderId.toLong(), "delivery_corp" to "zto", "delivery_code" to "SF-M07",
        ))
        assertEquals("WAIT_BUYER_CONFIRM", orderCol(orderId, "order_status"))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders_delivery WHERE order_id = ?", Int::class.java, orderId.toLong()))
        assertEquals("DONE", ok("/api/h5app/wxapp/order/confirmReceipt", mapOf("order_id" to orderId)).path("order_status").asText())
        assertEquals(storeBefore, store(), "已发货行确认收货不回补库存")
        val counts = fetch("/api/h5app/wxapp/orderscount")
        assertTrue(counts.path("done").asLong() >= 1 && counts.path("pending_receive").asLong() == 0L, counts.toString())
        val od = okAdmin("GET", "/api/orders/$orderId")
        assertEquals("PAYED", od.path("orderInfo").path("pay_status").asText())
        assertTrue(od.path("operate_logs").map { it.path("behavior").asText() }.contains("payed"), "结清必须留痕")
    }

    @Test @Order(11) fun `关单收掉未付支付单`() {
        orderCancel = createOrder()
        val tradeId = pay(orderCancel, "wxpay").path("trade_id").asText()
        ok("/api/h5app/wxapp/order/cancel", mapOf("order_id" to orderCancel, "cancel_reason" to "改变主意"))
        assertEquals("CLOSED", tradeState(tradeId), "订单关闭后支付单不得留在 NOTPAY")
        assertTrue(notifyForm(tradeId, 8900).contains("SUCCESS"), "已关闭单回调应答成功以免渠道无限重试")
        assertEquals("CANCEL", orderCol(orderCancel, "order_status"), "回调不得把已关闭单付活")
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade t JOIN orders_normal_orders o ON o.order_id = t.order_id " +
                    "WHERE o.order_status = 'CANCEL' AND t.trade_state = 'NOTPAY'", Int::class.java,
            ), "关闭单上没有活跃支付单",
        )
    }

    @Test @Order(12) fun `鉴权与越权`() {
        expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to orderDep1, "pay_type" to "deposit"), 401, token = null)
        expect("GET", "/api/h5app/wxapp/payment/config?order_id=$orderDep1", null, 401, token = null)
        expect("POST", "/api/h5app/wxapp/payment_deposit", mapOf("amount" to 100), 401, token = null)
        expect("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to orderPoint, "pay_type" to "deposit"), 403, token = other)
        expect("GET", "/api/h5app/wxapp/payment/config?order_id=$orderPoint", null, 403, token = other)
        expect("POST", "/api/h5app/wxapp/payment/query", mapOf("trade_id" to "T0000000000000000"), 404, token = other)
        expect("POST", "/api/h5app/wxapp/payment/query", mapOf("trade_id" to czTrade), 403, token = other)
        assertTrue(notifyForm("", 8900).contains("FAIL"), "缺 out_trade_no 必须拒")
        assertTrue(utf8(call("POST", "/api/notify/wxpay", null, null).response.contentAsByteArray).contains("FAIL"),
            "回调面匿名可达（渠道没有令牌），缺参只报 FAIL 不报 401")
    }

    @Test @Order(13) fun `账实相符 已付金额与订单实付逐分对齐`() {
        val sumTrade = jdbc.queryForObject(
            "SELECT COALESCE(SUM(pay_fee), 0) FROM trade WHERE trade_state = 'SUCCESS' AND user_id = '1'", Long::class.java,
        )!!
        val sumOrder = jdbc.queryForObject(
            "SELECT COALESCE(SUM(pay_fee), 0) FROM orders_normal_orders WHERE user_id = 1", Long::class.java,
        )!!
        assertEquals(sumOrder, sumTrade, "订单实付合计 == 成功支付单合计")
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders_normal_orders WHERE pay_status = 'PAYED' AND pay_fee <> total_fee", Int::class.java,
            ), "结清单实付等于应付（M0 无部分支付）",
        )
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade WHERE trade_state = 'SUCCESS' AND transaction_id IS NULL", Int::class.java,
            ), "每笔成功支付都要留渠道单号",
        )
        assertTrue(balance() >= 0 && point() >= 0, "账户不得为负")
    }

    @Test @Order(14) fun `超时关单同样收掉在途支付单`() {
        val o = createOrder()
        val tradeId = pay(o, "wxpay").path("trade_id").asText()
        assertEquals("NOTPAY", tradeState(tradeId), "预支付只挂单不结清")
        jdbc.update("UPDATE orders_normal_orders SET auto_cancel_time = 1 WHERE order_id = ?", o.toLong())
        timeoutJob.closeExpired(com.ecshopx.kotlin.common.domain.TimeSec.now(), 500)
        assertEquals("CANCEL", orderCol(o, "order_status"), "到点自动关单")
        assertEquals("CLOSED", tradeState(tradeId), "关单必须收掉在途支付单，否则渠道会把钱打进来")
        assertTrue(notifyForm(tradeId, 8900).contains("SUCCESS"), "关单后的渠道回调仍幂等应答")
        assertEquals("CANCEL", orderCol(o, "order_status"))
    }
}
