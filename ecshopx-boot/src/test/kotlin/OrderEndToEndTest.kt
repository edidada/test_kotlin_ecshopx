package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.OrderContract
import com.ecshopx.kotlin.service.order.OrderFlowService
import com.ecshopx.kotlin.service.order.OrderTimeoutJob
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M0-6 DoD：订单端到端（ORDER-2/4/5/6/7/8/9）+ 契约 diff + 状态机全链路 + 超时关单回补库存。
 *
 * 演示数据：会员 user_id=1（grade 1，折扣 100 → member_discount 0）、地址 address_id=1；
 * 商品 3=每日坚果礼盒 8900 分 / store 200 / 模板 1 包邮 / distributor_id=1「商城总部」；
 * 后台 admin（角色含 orders.lists/update/delivery）、viewer（无角色 → 403）。
 *
 * 定时任务在属性里推到 1 小时后，超时关单由测试显式调用 [OrderTimeoutJob.closeExpired]，
 * 避免后台线程与断言抢状态。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccorder;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OrderEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var flow: OrderFlowService
    @Autowired lateinit var timeoutJob: OrderTimeoutJob

    private var memberToken: String = ""
    private var goldToken: String = ""      // user_id=2 金卡会员，越权用例
    private var adminToken: String = ""
    private var viewerToken: String = ""

    private var orderA: Long = 0L           // fastbuy 2 件，后被用户取消
    private var orderB: Long = 0L           // cart 模式 1 件，留在待付款
    private var orderC: Long = 0L           // 超时关单用例
    private var orderD: Long = 0L           // 付款→发货→确认收货全链路
    private var itemDLineId: Long = 0L      // D 的明细行 id（分包发货用）

    private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun login(url: String, body: Map<String, Any?>): String {
        val res = mvc.perform(
            post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)),
        ).andReturn()
        assertEquals(200, res.response.status, "$url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data").path("token").asText()
    }

    private fun member(mobile: String): String = login(
        "/api/h5app/wxapp/new_login",
        mapOf("auth_type" to "local", "username" to mobile, "check_type" to "mobile", "vcode" to "8888"),
    )

    private fun admin(user: String, pw: String): String =
        login("/api/operator/login", mapOf("username" to user, "password" to pw))

    private fun req(method: String, url: String, token: String, content: Map<String, Any?>? = null) =
        when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
            else -> delete(url)
        }.also { it.header("Authorization", "Bearer $token") }
            .let {
                if (content != null) it.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content))
                it
            }

    /** 前台：会员令牌 */
    private fun ok(method: String, url: String, content: Map<String, Any?>? = null): JsonNode =
        dataOf(mvc.perform(req(method, url, memberToken, content)).andReturn(), "$method $url")

    /** 后台：admin 令牌 */
    private fun okAdmin(method: String, url: String, content: Map<String, Any?>? = null): JsonNode =
        dataOf(mvc.perform(req(method, url, adminToken, content)).andReturn(), "admin $method $url")

    private fun dataOf(res: org.springframework.test.web.servlet.MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun fail(method: String, url: String, content: Map<String, Any?>?, expect: Int, token: String = memberToken) {
        val res = mvc.perform(req(method, url, token, content)).andReturn()
        assertEquals(expect, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    private fun store(): Long = jdbc.queryForObject("SELECT store FROM items WHERE item_id = 3", Long::class.java)!!

    private fun sales(): Long = jdbc.queryForObject("SELECT sales FROM items WHERE item_id = 3", Long::class.java)!!

    private fun statusOf(orderId: Long): String =
        jdbc.queryForObject("SELECT order_status FROM orders_normal_orders WHERE order_id = ?", String::class.java, orderId)

    private fun assocStatus(orderId: Long): String =
        jdbc.queryForObject("SELECT order_status FROM orders_associations WHERE order_id = ?", String::class.java, orderId)

    private fun fastbuy(num: Long, extra: Map<String, Any?> = emptyMap()): JsonNode = ok(
        "POST", "/api/h5app/wxapp/order_new",
        mapOf(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to num)),
        ) + extra,
    )

    @Test @Order(1) fun `准备令牌与库存基线`() {
        memberToken = member("13800000001")
        goldToken = member("13800000002")
        adminToken = admin("admin", "admin123")
        viewerToken = admin("viewer", "viewer123")
        assertEquals(200L, store(), "seed 库存 200")
        // 上一环节遗留的车内数据清空，本类的 cart 断言从空车开始
        jdbc.update("DELETE FROM orders_cart WHERE user_id = 1")
    }

    @Test @Order(2) fun `ORDER-2 fastbuy 直购 金额只来自服务端算价 CAS 扣库存`() {
        val data = fastbuy(2)
        assertContract(data, OrderContract.CREATE, "ORDER-2 data")
        val oid = data.path("order_id").asText()
        assertEquals(16, oid.length, "16 位单号必须按字符串出参（超 JS 安全整数）")
        orderA = oid.toLong()
        assertEquals(17800L, data.path("total_fee").asLong(), "8900×2，会员 1 是普通会员不打折")
        assertEquals("normal", data.path("order_type").asText())
        assertEquals(true, data.has("team_id") && data.path("team_id").isNull(), "非拼团 team_id 为 null")
        assertEquals(198L, store(), "CAS 扣减 2 件")
        assertEquals(2L, sales() - 30L, "sales +2")
        // 入参伪造金额一律忽略
        val forged = ok(
            "POST", "/api/h5app/wxapp/order_new",
            mapOf(
                "cart_type" to "fastbuy", "address_id" to 1, "total_fee" to 1, "price" to 1,
                "items" to listOf(mapOf("item_id" to 3, "num" to 1, "price" to 1)),
            ),
        )
        orderB = forged.path("order_id").asText().toLong()
        assertEquals(8900L, forged.path("total_fee").asLong(), "前端金额不参与算价")
        assertEquals(197L, store())
    }

    @Test @Order(3) fun `ORDER-2 client_token 幂等 重复提交同一单`() {
        val first = fastbuy(1, mapOf("client_token" to "T-CONVERGE-1"))
        val oid = first.path("order_id").asText()
        val after = store()
        repeat(3) {
            val again = fastbuy(1, mapOf("client_token" to "T-CONVERGE-1"))
            assertEquals(oid, again.path("order_id").asText(), "同 token 必须返回同一单号")
            assertEquals(first.path("total_fee").asLong(), again.path("total_fee").asLong())
        }
        assertEquals(after, store(), "幂等命中不得重复扣库存")
        val other = fastbuy(1, mapOf("client_token" to "T-CONVERGE-2"))
        assertNotEquals(oid, other.path("order_id").asText(), "不同 token 是新单")
        // 收尾：把这两笔待付款单留着做超时用例，只留 orderC
        orderC = other.path("order_id").asText().toLong()
        ok("POST", "/api/h5app/wxapp/order/cancel", mapOf("order_id" to oid, "cancel_reason" to "重复测试单清理"))
    }

    @Test @Order(4) fun `ORDER-2 四道拦截 空车 库存 多店铺 收货信息`() {
        fail("POST", "/api/h5app/wxapp/order_new", mapOf("cart_type" to "fastbuy", "address_id" to 1), 400)
        fail(
            "POST", "/api/h5app/wxapp/order_new",
            mapOf("cart_type" to "fastbuy", "address_id" to 1, "items" to listOf(mapOf("item_id" to 3, "num" to 100000))),
            422,
        )
        fail(
            "POST", "/api/h5app/wxapp/order_new",
            mapOf("cart_type" to "fastbuy", "items" to listOf(mapOf("item_id" to 3, "num" to 1))),
            400,
        )
        val res = mvc.perform(
            post("/api/h5app/wxapp/order_new")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("cart_type" to "fastbuy", "items" to listOf(mapOf("item_id" to 3, "num" to 1))),
                    ),
                ),
        ).andReturn()
        assertEquals(400, res.response.status, utf8(res.response.contentAsByteArray))
        val errs = mapper.readTree(utf8(res.response.contentAsByteArray)).path("errors")
        assertEquals("收货人不能为空", errs.path("receiver_name")[0].asText())
        assertEquals("联系电话不能为空", errs.path("receiver_mobile")[0].asText())
        assertEquals("收货地址不能为空", errs.path("receiver_address")[0].asText())
        // 地址簿不属于本人 → 拒绝，不能借他人地址下单
        fail(
            "POST", "/api/h5app/wxapp/order_new",
            mapOf("cart_type" to "fastbuy", "address_id" to 2, "items" to listOf(mapOf("item_id" to 3, "num" to 1))),
            422,
        )
        // 非 normal 订单类型：明确 501 而不是静默按 normal 落单
        fail(
            "POST", "/api/h5app/wxapp/order_new",
            mapOf(
                "cart_type" to "fastbuy", "order_type" to "seckill", "address_id" to 1,
                "items" to listOf(mapOf("item_id" to 3, "num" to 1)),
            ),
            501,
        )
        // 多店铺：车里两行分属 shop_id 1 / 0 → 拒绝合并下单（拆单属 M1-4）
        ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 1))
        jdbc.update(
            "INSERT INTO orders_cart(company_id, user_id, shop_type, shop_id, item_type, item_id, item_name, num, price, is_checked, created, updated) " +
                "VALUES (1, 1, 'shop', 0, 'normal', 2, '测试手机 X1 白色 128G', 1, 199900, TRUE, 0, 0)",
        )
        val multi = mvc.perform(
            post("/api/h5app/wxapp/order_new")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("cart_type" to "cart", "order_type" to "normal", "address_id" to 1))),
        ).andReturn()
        assertEquals(422, multi.response.status, utf8(multi.response.contentAsByteArray))
        assertTrue(
            utf8(multi.response.contentAsByteArray).contains("暂不支持多店铺合并下单"),
            "多店铺应给出拆单未开放文案，实际=" + utf8(multi.response.contentAsByteArray),
        )
        jdbc.update("DELETE FROM orders_cart WHERE user_id = 1")
    }

    @Test @Order(5) fun `ORDER-2 cart 模式 结算勾选行并清空购物车`() {
        ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 3))
        val before = store()
        val data = ok(
            "POST", "/api/h5app/wxapp/order_new",
            mapOf("cart_type" to "cart", "order_type" to "normal", "address_id" to 1),
        )
        val cartOrder = data.path("order_id").asText().toLong()
        assertEquals(26700L, data.path("total_fee").asLong(), "8900×3，包邮")
        assertEquals(before - 3, store(), "车内勾选行扣减")
        assertEquals(0, ok("GET", "/api/h5app/wxapp/cartcount").path("count").asInt(), "成交后清车")
        val uncheck = ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 2))
        val cid = uncheck.path("valid_cart")[0].path("list")[0].path("cart_id").asLong()
        ok("PUT", "/api/h5app/wxapp/cartupdate/checkstatus", mapOf("cart_ids" to listOf(cid), "is_checked" to false))
        val empty = mvc.perform(
            post("/api/h5app/wxapp/order_new")
                .header("Authorization", "Bearer $memberToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("cart_type" to "cart", "address_id" to 1))),
        ).andReturn()
        assertEquals(422, empty.response.status, utf8(empty.response.contentAsByteArray))
        assertTrue(utf8(empty.response.contentAsByteArray).contains("没有可结算的商品"))
        assertEquals(before, store() + 3, "失败下单不留库存变化")
        jdbc.update("DELETE FROM orders_cart WHERE user_id = 1")
        // 该单只用于本用例，取消掉以免污染后面的角标口径
        ok("POST", "/api/h5app/wxapp/order/cancel", mapOf("order_id" to cartOrder, "cancel_reason" to "用例清理"))
        assertEquals(before, store(), "cart 单取消同样回补 3 件")
    }

    @Test @Order(6) fun `ORDER-4 列表 状态页签 契约 分页`() {
        val all = ok("GET", "/api/h5app/wxapp/orders")
        assertEquals(all.path("total_count").asLong(), all.path("list").size().toLong(), "默认页取全")
        val row = all.path("list")[0]
        assertContract(row, OrderContract.ROW, "ORDER-4 行")
        assertContract(row.path("items")[0], OrderContract.ITEM_BRIEF, "ORDER-4 items 行")
        assertContract(row.path("distributor"), OrderContract.DISTRIBUTOR, "ORDER-4 distributor")
        assertEquals(1L, row.path("distributor").path("distributor_id").asLong())
        assertEquals("商城总部", row.path("distributor").path("name").asText())
        assertTrue(row.path("order_id").isTextual, "单号出参是字符串")
        assertEquals("CANCEL", row.path("order_status").asText(), "order_id 倒序：最新单（刚取消的 cart 单）在前")
        // A 最早创建 → 排在最后
        assertEquals(orderA.toString(), all.path("list")[all.path("list").size() - 1].path("order_id").asText())
        assertEquals("待付款", all.path("list")[all.path("list").size() - 1].path("order_status_des").asText())

        // 页签：5 待付款 = A/B/C，1 待收货此刻还没有
        val notpay = ok("GET", "/api/h5app/wxapp/orders?status=5")
        assertEquals(3L, notpay.path("total_count").asLong(), "A、B、C 三笔待付款")
        assertTrue(notpay.path("list").all { it.path("order_status").asText() == "NOTPAY" })
        assertEquals(0L, ok("GET", "/api/h5app/wxapp/orders?status=1").path("total_count").asLong(), "此刻还没有待收货单")
        assertEquals(0L, ok("GET", "/api/h5app/wxapp/orders?status=3").path("total_count").asLong(), "还没有交易成功单")
        // 精确态优先于 status 数字
        val exact = ok("GET", "/api/h5app/wxapp/orders?order_status=cancel&status=5")
        assertEquals(2L, exact.path("total_count").asLong(), "T1 + cart 单已关闭")
        assertTrue(exact.path("list").all { it.path("order_status").asText() == "CANCEL" })
        // 分页：pageSize=2 时 list 只给 2 行，total_count 仍是全量
        val page = ok("GET", "/api/h5app/wxapp/orders?pageSize=2&page=1")
        assertEquals(2, page.path("list").size())
        assertTrue(page.path("total_count").asLong() > 2L)
        val page2 = ok("GET", "/api/h5app/wxapp/orders?pageSize=2&page=2")
        assertTrue(page.path("list")[0].path("order_id").asText() != page2.path("list")[0].path("order_id").asText())
        // 收件人筛选走 LIKE
        assertEquals(
            page.path("total_count").asLong(),
            ok("GET", "/api/h5app/wxapp/orders?receiver_name=张三").path("total_count").asLong(),
            "地址簿里的收货人=张三",
        )
        // 越权：金卡会员看不到别人的单
        val other = mvc.perform(get("/api/h5app/wxapp/orders").header("Authorization", "Bearer $goldToken")).andReturn()
        assertEquals(200, other.response.status)
        assertEquals(0L, mapper.readTree(utf8(other.response.contentAsByteArray)).path("data").path("total_count").asLong())
    }

    @Test @Order(7) fun `ORDER-5 详情 契约齐备 金额与超时字段`() {
        val d = ok("GET", "/api/h5app/wxapp/order/$orderB")
        assertContract(d, OrderContract.DETAIL, "ORDER-5 data")
        val info = d.path("orderInfo")
        assertContract(info, OrderContract.ORDER_INFO, "ORDER-5 orderInfo")
        assertContract(d.path("items")[0], OrderContract.ITEM_FULL, "ORDER-5 items 行")
        assertEquals("待付款", d.path("order_status_des").asText())
        assertEquals("请会员及时完成付款，超时订单将自动关闭", d.path("order_status_msg").asText())
        assertEquals(8900L, info.path("item_fee").asLong())
        assertEquals(0L, info.path("freight_fee").asLong(), "模板 1 包邮")
        assertEquals(0L, info.path("member_discount").asLong(), "普通会员折扣 100")
        assertEquals(8900L, info.path("total_fee").asLong())
        assertEquals(0L, info.path("pay_fee").asLong(), "未付款实付 0")
        assertEquals("logistics", info.path("receipt_type").asText())
        assertEquals("张三", info.path("receiver_name").asText(), "address_id 服务端展开")
        assertEquals("广东省", info.path("receiver_state").asText())
        assertEquals("科技园科苑路 15 号 3 栋 301", info.path("receiver_address").asText())
        assertEquals("13800000001", info.path("mobile").asText())
        assertEquals("商城总部", info.path("shop_name").asText())
        assertEquals("distributor", info.path("order_holder").asText())
        assertEquals(1L, info.path("distributor_id").asLong())
        assertEquals(true, info.path("is_distribution").asBoolean())
        assertEquals(false, info.path("is_invoiced").asBoolean())
        val ct = info.path("create_time").asLong()
        assertTrue(ct > 0, "StampInterceptor 落 created")
        assertEquals(ct + 1800, info.path("auto_cancel_time").asLong(), "seed: order.auto_cancel_minutes=30")
        val line = d.path("items")[0]
        assertEquals(8900L, line.path("price").asLong(), "成交价=算价结果")
        assertEquals(1L, line.path("num").asLong())
        assertEquals(3L, line.path("goods_id").asLong(), "坚果 SPU=3，SKU 即主行")
        assertEquals("PENDING", line.path("delivery_status").asText())
        assertEquals("normal", line.path("order_item_type").asText())
        assertEquals(0L, line.path("cancel_item_num").asLong())
        assertEquals(8900L, line.path("item_fee").asLong())
        assertEquals(0, d.path("logistics_items").size(), "未发货无包裹")
        assertEquals(true, d.path("cancelData").isObject && d.path("cancelData").size() == 0, "未申请取消回空对象")
        assertEquals(0, d.path("operate_logs").size(), "前台不回流转日志")
        assertEquals(0, d.path("tradeInfo").size(), "trade 摘要在 M0-7 回填")
        // 后台视角：全量留痕
        val adminView = okAdmin("GET", "/api/orders/$orderB")
        assertTrue(adminView.path("operate_logs").size() >= 1, "下单留痕必有")
        assertEquals("create", adminView.path("operate_logs")[0].path("behavior").asText())
        assertEquals("buyer", adminView.path("operate_logs")[0].path("operator_role").asText())
        // 越权：别人的单读不到
        val res = mvc.perform(get("/api/h5app/wxapp/order/$orderB").header("Authorization", "Bearer $goldToken")).andReturn()
        assertEquals(403, res.response.status, utf8(res.response.contentAsByteArray))
        assertEquals("无权访问该资源", mapper.readTree(utf8(res.response.contentAsByteArray)).path("message").asText())
        // 非法单号 404
        fail("GET", "/api/h5app/wxapp/order/not-a-number", null, 404)
        fail("GET", "/api/h5app/wxapp/order/9999999999999999", null, 404)
    }

    @Test @Order(8) fun `ORDER-6 取消 回补库存 关闭后再操作 422`() {
        val before = store()
        val salesBefore = sales()
        val data = ok("POST", "/api/h5app/wxapp/order/cancel", mapOf("order_id" to orderA, "cancel_reason" to "下错规格"))
        assertEquals(orderA.toString(), data.path("order_id").asText())
        assertEquals("CANCEL", data.path("order_status").asText())
        assertEquals("CANCEL_AGREE", data.path("cancel_status").asText())
        assertEquals("交易关闭", data.path("order_status_des").asText())
        assertEquals("交易已关闭", data.path("order_status_msg").asText())
        assertEquals(before + 2, store(), "取消回补 2 件")
        assertEquals(2, jdbc.queryForObject("SELECT cancel_item_num FROM orders_normal_orders_items WHERE order_id = ?", Int::class.java, orderA))
        assertEquals(salesBefore - 2, sales(), "销量随回补一起回退，不留下虚高销量")
        assertEquals("CANCEL", assocStatus(orderA), "路由表状态同步")
        val d = ok("GET", "/api/h5app/wxapp/order/$orderA")
        assertEquals(1, d.path("cancelData").path("progress").asInt())
        assertEquals("buyer", d.path("cancelData").path("cancel_from").asText())
        assertEquals("下错规格", d.path("cancelData").path("cancel_reason").asText())
        assertEquals("NONE", d.path("cancelData").path("refund_status").asText(), "未付款取消无退款")
        assertEquals("交易已关闭", d.path("order_status_msg").asText(), "关闭态不再给取消申请话术")
        assertEquals(0, d.path("orderInfo").path("auto_cancel_time").asInt(), "clearTimers 清掉超时器")
        fail("POST", "/api/h5app/wxapp/order/cancel", mapOf("order_id" to orderA), 422)
        fail("POST", "/api/h5app/wxapp/order/confirmReceipt", mapOf("order_id" to orderA), 422)
        fail("POST", "/api/h5app/wxapp/order/cancel", mapOf("order_id" to 9999999999999999), 404)
        fail("POST", "/api/h5app/wxapp/order/cancel", emptyMap(), 400)
        // 越权：金卡会员取消不了别人的单
        fail("POST", "/api/h5app/wxapp/order/cancel", mapOf("order_id" to orderB), 403, goldToken)
        assertEquals("NOTPAY", statusOf(orderB), "越权失败不能改状态")
    }

    @Test @Order(9) fun `超时关单 到点自动关闭并回补库存 幂等`() {
        val before = store()
        val now = System.currentTimeMillis() / 1000
        // 后台排查端点：还没到点
        assertEquals(0, okAdmin("GET", "/api/orders/timeout").path("order_ids").size(), "新单 auto_cancel_time 在未来")
        jdbc.update("UPDATE orders_normal_orders SET auto_cancel_time = ? WHERE order_id = ?", now - 1, orderC)
        assertEquals(1, okAdmin("GET", "/api/orders/timeout?limit=50").path("order_ids").size())
        assertEquals(orderC.toString(), okAdmin("GET", "/api/orders/timeout").path("order_ids")[0].asText())
        val closed = timeoutJob.closeExpired(now, 200)
        assertEquals(1, closed, "只关到点的那一笔")
        assertEquals("CANCEL", statusOf(orderC))
        assertEquals("CANCEL_AGREE", jdbc.queryForObject("SELECT cancel_status FROM orders_normal_orders WHERE order_id = ?", String::class.java, orderC))
        assertEquals(before + 1, store(), "超时回补 1 件")
        assertEquals(0L, jdbc.queryForObject("SELECT auto_cancel_time FROM orders_normal_orders WHERE order_id = ?", Long::class.java, orderC) ?: 0L, "clearTimers 清掉定时器")
        val logs = okAdmin("GET", "/api/orders/$orderC").path("operate_logs")
        assertEquals("timeout_cancel", logs.map { it.path("behavior").asText() }.last())
        assertTrue(logs.map { it.path("log_text").asText() }.last().contains("系统自动关闭"))
        assertEquals("system", logs[logs.size() - 1].path("operator_role").asText())
        // 再扫一遍：已关闭的单不会被重复回补
        assertEquals(0, timeoutJob.closeExpired(now, 200))
        assertEquals(before + 1, store(), "重复扫描不双补库存")
        // 已付款的单不受超时任务影响
        assertTrue(flow.markPaid(orderB, "wxpay", "mock"), "结清挂点")
        assertEquals(0, timeoutJob.closeExpired(now + 10_000, 200), "已付款不再扫到")
        assertEquals("PAYED", statusOf(orderB))
        assertEquals(false, flow.markPaid(orderB, "wxpay", "mock"), "重复回调幂等")
        val paid = okAdmin("GET", "/api/orders/$orderB")
        assertEquals("买家已付款，等待卖家发货", paid.path("order_status_msg").asText())
        assertEquals(8900L, paid.path("orderInfo").path("pay_fee").asLong(), "结清后实付=应付")
        assertEquals("wxpay", paid.path("orderInfo").path("pay_type").asText())
        assertEquals("mock", paid.path("orderInfo").path("pay_channel").asText())
        assertEquals(0, paid.path("orderInfo").path("auto_cancel_time").asInt(), "结清清定时器")
    }

    @Test @Order(10) fun `ORDER-9 后台发货 分包 PART_DONE 与整单 DONE`() {
        val data = fastbuy(2)
        orderD = data.path("order_id").asText().toLong()
        assertTrue(flow.markPaid(orderD, "wxpay", "mock"))
        // 未付款不许发货
        val undelivered = fastbuy(1)
        val oidE = undelivered.path("order_id").asText().toLong()
        val bad = mvc.perform(
            put("/api/orders/delivery")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("order_id" to oidE, "delivery_corp" to "zto"))),
        ).andReturn()
        assertEquals(422, bad.response.status, utf8(bad.response.contentAsByteArray))
        assertTrue(utf8(bad.response.contentAsByteArray).contains("当前订单状态不允许该操作"))
        ok("POST", "/api/h5app/wxapp/order/cancel", mapOf("order_id" to oidE, "cancel_reason" to "清理"))

        itemDLineId = okAdmin("GET", "/api/orders/$orderD").path("items")[0].path("id").asLong()
        // 先发 1 件 → PART_DONE
        val part = okAdmin(
            "PUT", "/api/orders/delivery",
            mapOf(
                "order_id" to orderD, "delivery_corp" to "zto", "delivery_corp_name" to "中通快递",
                "delivery_code" to "ZTO1234567890", "items" to listOf(mapOf("order_items_id" to itemDLineId, "num" to 1)),
            ),
        )
        assertEquals("WAIT_BUYER_CONFIRM", part.path("order_status").asText())
        assertEquals("PART_DONE", part.path("delivery_status").asText())
        assertEquals(1L, part.path("delivery_num").asLong())
        var d = okAdmin("GET", "/api/orders/$orderD")
        assertContract(d.path("logistics_items")[0], OrderContract.LOGISTICS, "ORDER-5 包裹行")
        assertEquals(1, d.path("logistics_items").size())
        assertEquals("sep", d.path("logistics_items")[0].path("package_type").asText())
        assertEquals("中通快递", d.path("logistics_items")[0].path("delivery_corp_name").asText())
        assertEquals(1L, d.path("logistics_items")[0].path("items")[0].path("num").asLong())
        assertEquals("PART_DONE", d.path("items")[0].path("delivery_status").asText(), "只发 1 件：行是部分发货，还能补发")
        assertEquals(1L, d.path("items")[0].path("delivery_item_num").asLong())
        assertEquals("待收货", d.path("order_status_des").asText())
        val finish = d.path("orderInfo").path("auto_finish_time").asLong()
        assertTrue(finish > 0, "发货起算自动确认收货：+10 天")
        assertEquals(10L * 86400L, finish - d.path("orderInfo").path("delivery_time").asLong(), "seed: order.auto_finish_days=10")
        // 超发拦截
        val over = mvc.perform(
            put("/api/orders/delivery")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf("order_id" to orderD, "delivery_corp" to "zto", "items" to listOf(mapOf("order_items_id" to itemDLineId, "num" to 9))),
                    ),
                ),
        ).andReturn()
        assertEquals(422, over.response.status, utf8(over.response.contentAsByteArray))
        // 补发剩余 1 件（items 缺省=整单未发行全发）→ DONE
        val full = okAdmin(
            "PUT", "/api/orders/delivery",
            mapOf("order_id" to orderD, "delivery_corp" to "yto", "delivery_corp_name" to "圆通速递", "delivery_code" to "YT9876543210"),
        )
        assertEquals("DONE", full.path("delivery_status").asText(), "未发行发齐=整单 DONE")
        assertEquals(1L, full.path("delivery_num").asLong())
        d = okAdmin("GET", "/api/orders/$orderD")
        assertEquals(2, d.path("logistics_items").size(), "多包裹分包")
        assertEquals("batch", d.path("logistics_items")[1].path("package_type").asText())
        assertEquals("DONE", d.path("items")[0].path("delivery_status").asText())
        assertEquals(2L, d.path("items")[0].path("delivery_item_num").asLong())
        assertEquals(2L, d.path("logistics_items").sumOf { it.path("items").sumOf { i -> i.path("num").asLong() } })
        assertEquals("DONE", jdbc.queryForObject("SELECT delivery_status FROM orders_associations WHERE order_id = ?", String::class.java, orderD))
        // 发齐后再发 → 422
        val again = mvc.perform(
            put("/api/orders/delivery")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("order_id" to orderD, "delivery_corp" to "zto"))),
        ).andReturn()
        assertEquals(422, again.response.status, utf8(again.response.contentAsByteArray))
        assertTrue(utf8(again.response.contentAsByteArray).contains("订单已发货完毕"))
        // 后台列表：待发货页签 6 只剩 B（已付款未发货），待收货 1 是 D
        val waitDelivery = okAdmin("GET", "/api/orders?status=6")
        assertEquals(1L, waitDelivery.path("total_count").asLong(), "只有 B 已付款未发货")
        assertEquals(orderB.toString(), waitDelivery.path("list")[0].path("order_id").asText())
        assertEquals(orderD.toString(), okAdmin("GET", "/api/orders?status=1").path("list")[0].path("order_id").asText())
        assertEquals(orderD.toString(), okAdmin("GET", "/api/orders?order_id=$orderD").path("list")[0].path("order_id").asText())
        assertEquals(1, okAdmin("GET", "/api/orders?distributor_id=1&page=1&pageSize=1").path("list").size())
        assertEquals(0L, okAdmin("GET", "/api/orders?distributor_id=99").path("total_count").asLong())
    }

    @Test @Order(11) fun `ORDER-7 确认收货 DONE 与后台备注`() {
        val before = store()
        val data = ok("POST", "/api/h5app/wxapp/order/confirmReceipt", mapOf("order_id" to orderD))
        assertEquals("DONE", data.path("order_status").asText())
        assertEquals("交易成功", data.path("order_status_des").asText())
        assertEquals(before, store(), "已发货的行不回补库存")
        val d = okAdmin("GET", "/api/orders/$orderD")
        assertEquals("DONE", d.path("orderInfo").path("delivery_status").asText())
        assertEquals(false, d.path("orderInfo").path("is_rate").asBoolean(), "M0-6 不落评价，评价属 M1")
        val counts = ok("GET", "/api/h5app/wxapp/orderscount")
        assertContract(counts, OrderContract.COUNTS, "ORDER-8")
        assertEquals(1L, counts.path("done").asLong(), "D 交易成功")
        assertEquals(0L, counts.path("pending_receive").asLong(), "收货后待收货清零")
        assertEquals(1L, counts.path("pending_delivery").asLong(), "orderB 已付款待发货")
        assertEquals(1L, counts.path("evaluation").asLong(), "DONE 且未评价 → 待评价")

        val remark = okAdmin("PUT", "/api/orders/remark", mapOf("order_id" to orderD, "remark" to "已电话联系，优先安排"))
        assertEquals("已电话联系，优先安排", remark.path("remark").asText())
        assertEquals(orderD.toString(), remark.path("order_id").asText())
        assertEquals("已电话联系，优先安排", okAdmin("GET", "/api/orders/$orderD").path("orderInfo").path("remark").asText())
        val last = okAdmin("GET", "/api/orders/$orderD").path("operate_logs").lastOrNull()
            ?: throw AssertionError("备注应有留痕")
        assertEquals("remark", last.path("behavior").asText())
        assertEquals("seller", last.path("operator_role").asText())
        okAdmin("PUT", "/api/orders/remark", mapOf("order_id" to orderD, "remark" to ""))
        assertEquals("", okAdmin("GET", "/api/orders/$orderD").path("orderInfo").path("remark").asText())
        fail("PUT", "/api/orders/remark", emptyMap(), 400, adminToken)
    }

    @Test @Order(12) fun `ORDER-8 角标口径与总数不变式`() {
        val c = ok("GET", "/api/h5app/wxapp/orderscount")
        val sum = listOf("notpay", "pending_delivery", "pending_receive", "done", "cancel").sumOf { c.path(it).asLong() }
        assertEquals(c.path("total").asLong(), sum, "分桶互斥且覆盖全态")
        assertEquals(7L, c.path("total").asLong(), "A B T1 C cartOrder D E")
        assertEquals(5L, c.path("cancel").asLong(), "A、T1、C(超时)、cart、E")
        assertEquals(0L, c.path("notpay").asLong(), "本类里所有待付款单最终都关掉了")
        assertEquals(1L, c.path("pending_delivery").asLong(), "B 已付款未发货")
        assertEquals(0L, c.path("pending_receive").asLong())
        assertEquals(1L, c.path("done").asLong())
        // 金卡会员（user 2）没有下过单：全 0
        val other = mapper.readTree(
            utf8(
                mvc.perform(get("/api/h5app/wxapp/orderscount").header("Authorization", "Bearer $goldToken"))
                    .andReturn().response.contentAsByteArray,
            ),
        ).path("data")
        assertEquals(0L, other.path("total").asLong())
        // 后台列表可按 user_id 筛，前台参数改不了自己令牌里的 user_id
        assertEquals(c.path("total").asLong(), okAdmin("GET", "/api/orders?user_id=1").path("total_count").asLong())
        assertEquals(0L, okAdmin("GET", "/api/orders?user_id=2").path("total_count").asLong())
    }

    @Test @Order(13) fun `鉴权 匿名 401 无权限 403 错误体兼容 PHP`() {
        for (url in listOf(
            "/api/h5app/wxapp/orders", "/api/h5app/wxapp/orderscount", "/api/h5app/wxapp/order/1",
        )) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthenticated."))
                .andExpect(jsonPath("$.status_code").value(401))
        }
        mvc.perform(post("/api/h5app/wxapp/order_new").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized())
        // viewer 无角色 → 订单权限码一个都不给
        for (url in listOf("/api/orders", "/api/orders/timeout")) {
            val res = mvc.perform(get(url).header("Authorization", "Bearer $viewerToken")).andReturn()
            assertEquals(403, res.response.status, "$url -> " + utf8(res.response.contentAsByteArray))
        }
        val forbidden = mvc.perform(
            put("/api/orders/delivery")
                .header("Authorization", "Bearer $viewerToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("order_id" to orderD, "delivery_corp" to "zto"))),
        ).andReturn()
        assertEquals(403, forbidden.response.status, utf8(forbidden.response.contentAsByteArray))
        val body = mapper.readTree(utf8(forbidden.response.contentAsByteArray))
        assertEquals(403, body.path("status_code").asInt())
        assertTrue(body.has("errors") && body.has("code") && body.has("message"), "错误体四件套：$body")
        // 会员令牌不能走后台链
        assertEquals(401, mvc.perform(get("/api/orders").header("Authorization", "Bearer $memberToken")).andReturn().response.status)
    }

    @Test @Order(14) fun `库存账平 建单扣减与关单回补对得上`() {
        // 口径：当前库存 = 初始 200 - 未关闭订单占用的件数（关闭单在关单时已回补）
        val active = jdbc.queryForObject(
            "SELECT COALESCE(SUM(i.num), 0) FROM orders_normal_orders_items i " +
                "JOIN orders_normal_orders o ON o.order_id = i.order_id " +
                "WHERE i.item_id = 3 AND o.order_status <> 'CANCEL'",
            Int::class.java,
        ) ?: 0
        val closed = jdbc.queryForObject(
            "SELECT COALESCE(SUM(i.cancel_item_num), 0) FROM orders_normal_orders_items i " +
                "JOIN orders_normal_orders o ON o.order_id = i.order_id " +
                "WHERE i.item_id = 3 AND o.order_status = 'CANCEL'",
            Int::class.java,
        ) ?: 0
        assertEquals(3, active, "B 1 件 + D 2 件（其余全部关闭回补）")
        assertEquals(8, closed, "A2 + T1 1 + C 1 + cart3 + E 1 都记在明细上")
        assertEquals(200L - active, store(), "库存守恒：扣减与回补一一对应")
        assertEquals(30L + active, sales(), "销量只统计未关闭单")
        // 关闭单的明细行不留库存占用痕迹：cancel_item_num = num - 已发件数
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders_normal_orders_items i " +
                    "JOIN orders_normal_orders o ON o.order_id = i.order_id " +
                    "WHERE o.order_status = 'CANCEL' AND i.cancel_item_num <> i.num - i.delivery_item_num",
                Int::class.java,
            ) ?: 0,
            "已关闭明细的 cancel_item_num 必须等于未发件数",
        )
    }
}
