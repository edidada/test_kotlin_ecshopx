package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.CommissionContract
import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.service.distribution.CommissionSettleJob
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1-4 第二批（DL-A1..A3 / CW-A1..A3 / CW-F1..F2，docs/04 §18.5、§18.7、§18.8）。
 *
 * 本案盯的是"钱能不能对上"这四件事，而不是"接口能不能通"：
 *  1. **入账只跟着钱走**（§18.5 第①条）：未付款的单在 `distribute_logs` 里 0 行，
 *     结清那一刻才有；佣金数取订单行的 `total_rebate`（D46 下单时已按 `items.rebate × num` 落定），
 *     改费率不回溯已落的流水；`items.rebate=0` 的商品根本不写行；
 *  2. **幂等**（§18.5 第③条）：同一张单回调重放两次，流水仍是 1 行 —— 判据是"这一单有没有过行"，
 *     不是订单状态，因为状态在别处也会被改；
 *  3. **冲销分两段**（D52）：未结算的行直接减到 0；已结算的行**不回改**，
 *     改成在同 `(order_id, item_id)` 上插一条负向流水。对账时两条都在，能讲清发生了什么；
 *  4. **余额是纯派生数**（C149）：`balance = Σ(已结算 rebate) − Σ(applied+confirmed+paid 提现额)`，
 *     `paid` 也算占用、`refused` 不算，所以提现打款后余额不会反弹，拒绝则自动还回去。
 *
 * 提现状态机（`applied→confirmed→paid`，`refused` 只能从前两态进）单独用一例子把非法迁移全试一遍：
 * 非法目标 400、跨态迁移 422、终态再动 422。
 *
 * 权限面（§18.7）：seed 给店铺运营岗只配了 `distribution.logs`，
 * 于是同一个 token 在 DL-A1 上是 200 且只看自己店、在 DL-A3/CW-A1/CW-A2 上都是 403 ——
 * "能看钱"不等于"能结钱"，更不等于"能批提现"。
 *
 * 造数：本案把 `items.rebate` 现场设成 500 分/件（seed 不播佣金），
 * 所有单子走 mock 微信渠道 `pay→notify` 结清，避免余额账户干扰金额判定。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccommission;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
        "ecx.schedule.commission-settle-initial-delay-ms=3600000",
        "ecx.schedule.commission-settle-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommissionEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var settleJob: CommissionSettleJob

    private var admin = ""
    private var member = ""
    private var shop = ""

    /** 佣金基数：本案把商品 3 设成 500 分/件，于是一件单的佣金 500、两件 1000 */
    private val rebateUnit = 500L

    private var orderA = 0L   // 2 件 → 佣金 1000，结算后被部分退款冲掉 500
    private var orderB = 0L   // 1 件 → 佣金 500，结算后被全额退款 → 负向流水
    private var orderC = 0L   // 零佣金商品 → 根本不写行
    private var orderE = 0L   // 1 件 → 佣金 500，由 Job 到期结算
    private var orderF = 0L   // 1 件 → 佣金 500，未结算时全额退款 → 直接减到 0
    private var withdrawId = 0L

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
            else -> throw IllegalArgumentException(method)
        }
        token?.let { req.header("Authorization", "Bearer $it") }
        body?.let { req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)) }
        return mvc.perform(req).andReturn()
    }

    private fun data(res: MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun adminOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, admin, body), "admin $m $url")

    private fun memberOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, member, body), "member $m $url")

    private fun shopOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, shop, body), "shop $m $url")

    private fun expect(method: String, url: String, token: String?, body: Map<String, Any?>?, code: Int): JsonNode {
        val res = call(method, url, token, body)
        assertEquals(code, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    // ===== 造数与取数助手 =====

    /**
     * 建单走"进店加购 → 车内结算"这条路，而不是 fastbuy：
     * `distributor_id` 只在**车内**决定这一行进哪家店的购物车（`orders_cart` 唯一键含 `shop_id`），
     * 建单时再按选中的店拆出来。本案所有单子都落在 seed 的 demo 旗舰店（2），
     * 佣金才有归属对象（fastbuy 的店铺取的是商品上的 `items.distributor_id`=总部 1）。
     */
    private fun createOrder(itemId: Long, num: Int): Long {
        memberOf("/api/h5app/wxapp/cart", mapOf("item_id" to itemId, "num" to num, "distributor_id" to 2), "POST")
        return memberOf(
            "/api/h5app/wxapp/order_new",
            mapOf(
                "cart_type" to "cart", "order_type" to "normal", "distributor_id" to 2,
                "receiver_name" to "张三", "receiver_mobile" to "13800000001",
                "receiver_state" to "浙江省", "receiver_city" to "杭州市",
                "receiver_district" to "西湖区", "receiver_address" to "文三路 1 号",
                "order_from" to "h5",
            ),
            "POST",
        ).path("order_id").asLong()
    }

    /** 结清 = mock 微信渠道出支付单 + 回调；回调体可以重放，正好用来验幂等 */
    private fun payTrade(orderId: Long): String =
        memberOf("/api/h5app/wxapp/payment", mapOf("order_id" to orderId.toString(), "pay_type" to "wxpay"), "POST")
            .path("trade_id").asText()

    private fun notify(tradeId: String, fee: Long): String = utf8(
        mvc.perform(
            post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId).param("total_fee", fee.toString())
                .param("transaction_id", "WX-" + tradeId.take(6)).param("trade_state", "SUCCESS"),
        ).andReturn().response.contentAsByteArray,
    )

    private fun pay(orderId: Long, fee: Long): Unit {
        assertTrue(notify(payTrade(orderId), fee).contains("SUCCESS"), "回调必须应答成功")
    }

    private fun logRowsOf(orderId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT * FROM distribution_distribute_logs WHERE order_id = ? ORDER BY id", orderId)

    private fun totalFee(orderId: Long): Long =
        jdbc.queryForObject("SELECT total_fee FROM orders_normal_orders WHERE order_id = ?", Long::class.java, orderId)!!

    private fun longOf(sql: String, vararg v: Any): Long = jdbc.queryForObject(sql, Long::class.java, *v) ?: -1L

    /** 佣金聚合（与 DL-A2 同一条 SQL 口径），断言"页面上的数"与"账本上的数"是同一份 */
    private fun closedFee(distributorId: Long): Long = longOf(
        "SELECT COALESCE(SUM(rebate), 0) FROM distribution_distribute_logs WHERE company_id = 1 AND is_close = TRUE AND distributor_id = ?",
        distributorId,
    )

    private fun occupyingFee(distributorId: Long): Long = longOf(
        "SELECT COALESCE(SUM(money), 0) FROM distribution_cash_withdrawal WHERE company_id = 1 AND distributor_id = ? " +
            "AND status IN ('applied','confirmed','paid')",
        distributorId,
    )

    private fun stats(): JsonNode = adminOf("/api/distribution/count?distributor_id=2")

    private fun applyWithdraw(money: Long, distributorId: Long = 2) = memberOf(
        "/api/h5app/wxapp/cash_withdrawal",
        mapOf("distributor_id" to distributorId, "money" to money),
        "POST",
    )

    private fun checkWithdraw(id: Long, status: String, remarks: String? = null) = adminOf(
        "/api/distribution/cash_withdrawal/$id",
        linkedMapOf<String, Any?>("status" to status, "remarks" to remarks).filterValues { it != null },
        "PUT",
    )

    /** 售后「申请 → 同意 → 退款确认」一条龙（M1-1 那条链，本案只关心它对流水的影响） */
    private fun refundUnit(orderId: Long) {
        val bn = memberOf(
            "/api/h5app/wxapp/aftersales",
            mapOf(
                "order_id" to orderId.toString(), "item_id" to 3, "num" to 1,
                "aftersales_type" to "ONLY_REFUND", "reason" to "质量问题", "description" to "开箱即损",
            ),
            "POST",
        ).path("aftersales_bn").asText()
        adminOf("/api/aftersales/review", mapOf("aftersales_bn" to bn, "is_approved" to 1), "POST")
        adminOf("/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 1), "POST")
    }

    /** 流水行的类型化取数：JDBC 给的是 `Map<String, Any?>`，断言前先收成具体类型 */
    private fun ln(r: Map<String, Any?>, key: String): Long = (r[key] as Number).toLong()

    private fun lb(r: Map<String, Any?>, key: String): Boolean = r[key] as Boolean

    private fun now(): Long = TimeSec.now()

    // ===================== 入账 =====================

    @Test @Order(1) fun `令牌就绪与佣金基数`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        shop = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "distributor01", "password" to "shop" + "123")), "shop").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank() && shop.isNotBlank())
        jdbc.update("UPDATE items SET rebate = ? WHERE item_id = 3", rebateUnit)
        // seed 不播佣金与提现：本案从零账开始，每个数都能自己算出来
        assertEquals(0L, longOf("SELECT COUNT(*) FROM distribution_distribute_logs"))
        assertEquals(0L, longOf("SELECT COUNT(*) FROM distribution_cash_withdrawal"))
        // 结算延时读得到（sys_config 里那条 7 天），否则 plan_close_time 会全是 null
        assertTrue(adminOf("/api/distributor/count/2").path("balance").asLong() == 0L, "开局余额 0")
    }

    @Test @Order(2) fun `未付款的单不入账`() {
        orderA = createOrder(3, 2)
        assertEquals(0, logRowsOf(orderA).size, "钱没进来就不该有佣金流水")
        assertEquals(0L, stats().path("commission_total").asLong())
        assertEquals(0L, stats().path("balance").asLong())
        // 未到期也不入：DL-A1 全场查不到行
        assertEquals(0L, adminOf("/api/distribution/logs?pageSize=50").path("total_count").asLong())
    }

    @Test @Order(3) fun `结清即入账 佣金取行上的 total_rebate`() {
        pay(orderA, 8900L * 2)
        val rows = logRowsOf(orderA)
        assertEquals(1, rows.size, "一单一行（本案只有一个商品）")
        val r = rows[0]
        assertEquals(2L, ln(r, "num"), "件数是成交事实")
        assertEquals(rebateUnit * 2, ln(r, "rebate"), "500 分/件 × 2 件")
        assertEquals(rebateUnit * 2, ln(r, "total_rebate"))
        assertFalse(lb(r, "is_close"), "delay=7 天 ⇒ 入账即在途，不能直接可提")
        assertTrue(ln(r, "plan_close_time") > now(), "预计结算时刻在未来")

        val page = adminOf("/api/distribution/logs?order_id=$orderA")
        assertEquals(1L, page.path("total_count").asLong())
        val row = page.path("list")[0]
        assertContract(row, CommissionContract.LOG_ROW, "DL-A1 流水行")
        assertEquals(orderA.toString(), row.path("order_id").asText(), "单号出参是字符串")
        assertEquals(row.path("order_id").asText(), row.path("order_no").asText())
        assertEquals("每日坚果礼盒", row.path("item_name").asText())
        assertEquals(2, row.path("distributor_id").asInt())
        assertEquals("演示用户", row.path("nickname").asText(), "昵称取 members_info.username")
        assertEquals(false, row.path("is_close").asBoolean())
        assertTrue(row.path("plan_close_time").asLong() > 0)

        val s = stats()
        assertContract(s, CommissionContract.STATS, "DL-A2 统计")
        assertEquals(1000L, s.path("commission_total").asLong())
        assertEquals(1000L, s.path("commission_open").asLong())
        assertEquals(0L, s.path("commission_closed").asLong())
        assertEquals(0L, s.path("balance").asLong(), "在途的钱提不走")
    }

    @Test @Order(4) fun `回调重放不双计 零佣金不写行`() {
        orderC = createOrder(1, 1)
        val t = payTrade(orderC)
        val fee = totalFee(orderC)
        assertTrue(notify(t, fee).contains("SUCCESS"))
        assertTrue(notify(t, fee).contains("SUCCESS"), "重复回调仍应答成功以免渠道无限重试")
        assertEquals(0, logRowsOf(orderC).size, "items.rebate=0 的商品根本不写行")

        orderB = createOrder(3, 1)
        val tb = payTrade(orderB)
        assertTrue(notify(tb, 8900L).contains("SUCCESS"))
        assertTrue(notify(tb, 8900L).contains("SUCCESS"))
        assertEquals(1, logRowsOf(orderB).size, "幂等判据是『这一单有没有过行』")
        assertEquals(rebateUnit, ln(logRowsOf(orderB)[0], "rebate"))
        assertEquals(1500L, stats().path("commission_open").asLong(), "A 的 1000 + B 的 500")
    }

    // ===================== 结算 =====================

    @Test @Order(5) fun `DL-A3 未到期不结 到期一次结清且幂等`() {
        val future = now() + 8 * 86400
        val early = adminOf("/api/distribution/logs/close", mapOf("to_time" to now()), "POST")
        assertContract(early, CommissionContract.CLOSE_RESULT, "DL-A3 出参")
        assertEquals(0L, early.path("closed_count").asLong(), "plan_close_time 还没到，一行都不许动")
        assertEquals(0L, early.path("closed_fee").asLong())

        val hit = adminOf("/api/distribution/logs/close", mapOf("to_time" to future), "POST")
        assertEquals(2L, hit.path("closed_count").asLong(), "A、B 两条到期行一起结")
        assertEquals(1500L, hit.path("closed_fee").asLong())
        assertTrue(lb(logRowsOf(orderA)[0], "is_close"), "结完就是已结算")

        val again = adminOf("/api/distribution/logs/close", mapOf("to_time" to future), "POST")
        assertEquals(0L, again.path("closed_count").asLong(), "同一时间窗第二次 0 行")
        assertEquals(0L, again.path("closed_fee").asLong())

        val s = stats()
        assertEquals(0L, s.path("commission_open").asLong())
        assertEquals(1500L, s.path("commission_closed").asLong())
        assertEquals(1500L, s.path("balance").asLong(), "结完才谈得上提现")
    }

    @Test @Order(6) fun `结算只由到期判据决定 与入口无关`() {
        orderE = createOrder(3, 1)
        pay(orderE, 8900L)
        assertFalse(lb(logRowsOf(orderE)[0], "is_close"), "刚入账的行在途")
        assertEquals(500L, stats().path("commission_open").asLong())
        // 定时任务与 DL-A3 同判据：同一把"未来时刻"扫过去，只结算到期那批
        val future = now() + 8 * 86400
        assertTrue(settleJob.pending(future) >= 1L, "巡检口径：至少 E 这一行待结")
        assertEquals(1, settleJob.runNow(future))
        assertTrue(lb(logRowsOf(orderE)[0], "is_close"))
        assertEquals(0, settleJob.runNow(future), "第二次扫 0 行")
        assertEquals(0L, settleJob.pending(now()), "全场没有未处理的到期行")
        assertEquals(2000L, stats().path("commission_closed").asLong())
    }

    // ===================== 提现 =====================

    @Test @Order(7) fun `CW-F1 四道闸门`() {
        // ① 归属：会员 1 只在 demo 旗舰店（2）的在册行里，替别家店提现是 403
        val notMine = expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("distributor_id" to 1, "money" to 100), 403)
        assertTrue(notMine.path("message").asText().contains("无权"))
        // ② 金额：0 与负数都是表单级拒绝（422 + errors，与源系统"业务拒绝"同形）
        val zero = expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("distributor_id" to 2, "money" to 0), 422)
        assertEquals(listOf("提现金额必须大于 0"), zero.path("errors").path("money").map { it.asText() })
        expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("distributor_id" to 2, "money" to -5), 422)
        expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("money" to 100), 422)
        // ③ 余额：读的是聚合式，不是任何缓存列
        val over = expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("distributor_id" to 2, "money" to 999999), 422)
        assertTrue(over.path("errors").path("money")[0].asText().contains("可提现 2000 分"))
        // ④ 匿名面：前台这条链没有"不登录也能提"
        expect("POST", "/api/h5app/wxapp/cash_withdrawal", null, mapOf("distributor_id" to 2, "money" to 100), 401)

        val w = applyWithdraw(2000)
        withdrawId = w.path("id").asLong()
        assertContract(w, CommissionContract.WITHDRAW_ROW, "CW-F1 出参")
        assertEquals("applied", w.path("status").asText())
        assertEquals("待处理", w.path("status_text").asText())
        assertEquals(2000L, w.path("money").asLong())
        assertEquals("demo 旗舰店", w.path("shop_name").asText())
        assertEquals(0L, w.path("paid_time").asLong(), "非 paid 一律给 0（C151）")

        // 在途唯一排在余额之前：一店同时只许一笔没结的申请，第二笔看到的理由是"上一笔没处理完"
        val dup = expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("distributor_id" to 2, "money" to 100), 422)
        assertTrue(dup.path("message").asText().contains("已有提现申请待处理"))

        val s = stats()
        assertEquals(2000L, s.path("withdraw_applied").asLong(), "在途额进占用")
        assertEquals(0L, s.path("balance").asLong(), "提完就见底，且不会反弹")
    }

    @Test @Order(8) fun `CW-A3 打款前先给待财务打款`() {
        val info = adminOf("/api/distributor/cash_withdrawal/payinfo/$withdrawId")
        assertContract(info, CommissionContract.PAYINFO, "CW-A3 出参")
        assertContract(info.path("account"), CommissionContract.PAYINFO_ACCOUNT, "CW-A3 账户栏")
        assertEquals("offline", info.path("pay_type").asText(), "mock 回执：没有真渠道（D47）")
        assertEquals(0L, info.path("paid_time").asLong())
        assertEquals("待财务打款", info.path("account").path("tip").asText())
        assertEquals("demo 旗舰店", info.path("account").path("name").asText())
        assertEquals("139****0001", info.path("account").path("mobile").asText(), "店铺电话掩码")
        expect("GET", "/api/distributor/cash_withdrawal/payinfo/999999", admin, null, 404)
        expect("GET", "/api/distributor/cash_withdrawal/payinfo/abc", admin, null, 404)
    }

    @Test @Order(9) fun `CW-A2 状态机 非法目标 400 跨态与终态 422`() {
        val bad = expect("PUT", "/api/distribution/cash_withdrawal/$withdrawId", admin, mapOf("status" to "applied"), 400)
        assertTrue(bad.path("errors").path("status")[0].asText().contains("只支持"))
        val skip = expect(
            "PUT", "/api/distribution/cash_withdrawal/$withdrawId", admin,
            mapOf("status" to "paid"), 422,
        )
        assertTrue(skip.path("message").asText().contains("不能迁到"), "申请与打款必须分人分步（§18.7）")
        assertTrue(logRowsOf(orderA).isNotEmpty(), "状态机不许动到佣金流水")

        val c = checkWithdraw(withdrawId, "confirmed", "核对无误")
        assertEquals("confirmed", c.path("status").asText())
        assertEquals("已确认待打款", c.path("status_text").asText())
        assertEquals("核对无误", c.path("remarks").asText())
        assertEquals(0L, c.path("paid_time").asLong())

        val p = checkWithdraw(withdrawId, "paid", "已转账")
        assertEquals("paid", p.path("status").asText())
        assertEquals("已打款", p.path("status_text").asText())
        assertTrue(p.path("paid_time").asLong() > 0, "终态时刻落在 updated（本表无 paid_time 列，C151）")

        expect("PUT", "/api/distribution/cash_withdrawal/$withdrawId", admin, mapOf("status" to "confirmed"), 422)
        expect("PUT", "/api/distribution/cash_withdrawal/$withdrawId", admin, mapOf("status" to "refused"), 422)
        expect("PUT", "/api/distribution/cash_withdrawal/999999", admin, mapOf("status" to "paid"), 404)
        expect("PUT", "/api/distribution/cash_withdrawal/$withdrawId", shop, mapOf("status" to "paid"), 403)

        val info = adminOf("/api/distributor/cash_withdrawal/payinfo/$withdrawId")
        assertTrue(info.path("paid_time").asLong() > 0)
        assertEquals("已打款", info.path("account").path("tip").asText())
    }

    @Test @Order(10) fun `已打款仍占余额 refused 才还不回去`() {
        val s = stats()
        assertEquals(2000L, s.path("withdraw_paid").asLong())
        assertEquals(2000L, s.path("withdraw_occupying").asLong(), "paid 也占（C149）")
        assertEquals(0L, s.path("balance").asLong())
        expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("distributor_id" to 2, "money" to 100), 422)

        // 拒绝的额不占用：再申请一笔被拒，余额回到原值
        jdbc.update(
            "INSERT INTO distribution_cash_withdrawal(company_id, distributor_id, user_id, shop_id, money, status, created, updated) " +
                "VALUES(1, 2, 1, 0, 300, 'refused', 0, 0)",
        )
        assertEquals(300L, stats().path("withdraw_refused").asLong())
        assertEquals(2000L, stats().path("withdraw_occupying").asLong(), "refused 不进占用")
        // 已结算佣金此时是 2000 − 冲销前，先只断言"占用不变"，金额断言留给对账例子
    }

    @Test @Order(11) fun `CW-F2 只出本人的记录`() {
        val mine = memberOf("/api/h5app/wxapp/cash_withdrawals?pageSize=50")
        assertEquals(2L, mine.path("total_count").asLong(), "paid + refused 两笔都是我申请的（seed 的在册行就是 user 1）")
        assertContract(mine.path("list")[0], CommissionContract.WITHDRAW_ROW, "CW-F2 行")
        assertTrue(mine.path("list").all { it.path("user_id").asLong() == 1L })
        assertTrue(mine.path("list").any { it.path("status").asText() == "paid" })
        assertEquals(1L, memberOf("/api/h5app/wxapp/cash_withdrawals?status=paid").path("total_count").asLong())
        assertEquals(0L, memberOf("/api/h5app/wxapp/cash_withdrawals?status=applied").path("total_count").asLong())
        expect("GET", "/api/h5app/wxapp/cash_withdrawals", null, null, 401)
    }

    // ===================== 权限与数据权限 =====================

    @Test @Order(12) fun `看钱的岗不等于结钱的岗`() {
        // shop（distributor01，seed 只给了 distribution.logs）
        val l = shopOf("/api/distribution/logs?pageSize=50")
        assertTrue(l.path("total_count").asLong() > 0)
        assertTrue(l.path("list").all { it.path("distributor_id").asInt() == 2 }, "数据权限：只看绑定的店")
        assertEquals(0L, shopOf("/api/distribution/logs?distributor_id=1&pageSize=50").path("total_count").asLong(), "越界过滤到 0 行")
        expect("GET", "/api/distribution/count?distributor_id=1", shop, null, 404)
        assertTrue(shopOf("/api/distribution/count?distributor_id=2").path("balance").isNumber)
        // 资源引用面越界与缺码都是 403/404，不给"这家店存在"的信息
        expect("POST", "/api/distribution/logs/close", shop, mapOf("to_time" to now() + 86400), 403)
        expect("GET", "/api/distribution/cash_withdrawals", shop, null, 403)
        expect("PUT", "/api/distribution/cash_withdrawal/$withdrawId", shop, mapOf("status" to "paid"), 403)
        // admin 全场
        assertTrue(adminOf("/api/distribution/logs?pageSize=50").path("total_count").asLong() >= l.path("total_count").asLong())
        val byShop = adminOf("/api/distribution/logs?distributor_id=2&pageSize=50")
        assertEquals(l.path("total_count").asLong(), byShop.path("total_count").asLong(), "同一份数据，两种身份同一判据")
    }

    @Test @Order(13) fun `CW-A1 提现列表的过滤与契约`() {
        val page = adminOf("/api/distribution/cash_withdrawals?pageSize=50")
        assertTrue(page.path("total_count").asLong() >= 2L)
        assertContract(page.path("list")[0], CommissionContract.WITHDRAW_ROW, "CW-A1 行")
        assertTrue(page.path("list").all { it.path("distributor_id").asInt() == 2 })
        assertEquals(1L, adminOf("/api/distribution/cash_withdrawals?status=paid").path("total_count").asLong())
        assertEquals(1L, adminOf("/api/distribution/cash_withdrawals?status=refused").path("total_count").asLong())
        assertEquals(0L, adminOf("/api/distribution/cash_withdrawals?status=applied").path("total_count").asLong())
        assertEquals(0L, adminOf("/api/distribution/cash_withdrawals?distributor_id=1").path("total_count").asLong())
        assertEquals(2L, adminOf("/api/distribution/cash_withdrawals?distributor_id=2&pageSize=50").path("total_count").asLong())
    }

    // ===================== 冲销 =====================

    @Test @Order(14) fun `已结算的退款走负向流水 不回改历史行`() {
        val before = logRowsOf(orderB)
        assertEquals(1, before.size)
        assertTrue(lb(before[0], "is_close"))
        refundUnit(orderB)
        val rows = logRowsOf(orderB)
        assertEquals(2, rows.size, "正向行原样留着，冲销是另一条行")
        assertEquals(rebateUnit, ln(rows[0], "rebate"), "历史行不回改（D52）")
        assertTrue(lb(rows[0], "is_close"))
        assertEquals(-rebateUnit, ln(rows[1], "rebate"), "全额退 ⇒ 补一条等额负向行")
        assertEquals(-rebateUnit, ln(rows[1], "total_rebate"))
        assertTrue(lb(rows[1], "is_close"), "负向行天生已结算：已进过余额的钱要立刻退回去")
        assertEquals(0L, ln(rows[1], "num"), "件数是成交事实，退款改的是钱不是销量")
        assertEquals(2L, ln(rows[1], "distributor_id"), "归属抄原来那条行，不从订单头现读")

        val s = stats()
        assertEquals((closedFee(2) - occupyingFee(2)).coerceAtLeast(0L), s.path("balance").asLong(), "页面上的余额 = 账本聚合（负数兜成 0）")
        assertEquals(1500L, s.path("commission_closed").asLong(), "2000 − 500")
        assertEquals(1500L, s.path("commission_total").asLong(), "负向行在同一张表里，聚合式不用改")
    }

    @Test @Order(15) fun `未结算的退款直接把行减到零`() {
        orderF = createOrder(3, 1)
        pay(orderF, 8900L)
        val open = logRowsOf(orderF)
        assertEquals(1, open.size)
        assertFalse(lb(open[0], "is_close"))
        assertEquals(rebateUnit, ln(open[0], "rebate"))

        refundUnit(orderF)
        val rows = logRowsOf(orderF)
        assertEquals(1, rows.size, "还没进过余额，直接减就是减了，不必留负向痕迹")
        assertEquals(0L, ln(rows[0], "rebate"))
        assertEquals(0L, ln(rows[0], "total_rebate"))
        assertFalse(lb(rows[0], "is_close"), "减完仍在途（金额为 0 的在途行不影响任何账）")

        // 已结算的正向行不能被 reduce 碰：这条纪律由 SQL 的 is_close=FALSE 兜住
        val a = logRowsOf(orderA)
        assertEquals(1, a.size)
        assertEquals(1000L, ln(a[0], "rebate"))
        // 部分退款：A 单 2 件退 1 件 ⇒ 按行占比冲 500
        refundUnit(orderA)
        val a2 = logRowsOf(orderA)
        assertEquals(2, a2.size, "A 已结算，冲 500 走负向行")
        assertEquals(1000L, ln(a2[0], "rebate"))
        assertEquals(-rebateUnit, ln(a2[1], "rebate"), "1000 × 8900/17800 = 500，分母是行的成交额")
    }

    @Test @Order(16) fun `账本最终对得上`() {
        val s = stats()
        assertContract(s, CommissionContract.STATS, "DL-A2 统计")
        assertEquals(closedFee(2), s.path("commission_closed").asLong(), "已结算 = Σ(is_close=TRUE 的 rebate)")
        assertEquals(
            longOf("SELECT COALESCE(SUM(rebate), 0) FROM distribution_distribute_logs WHERE company_id = 1 AND distributor_id = 2"),
            s.path("commission_total").asLong(),
        )
        assertEquals(occupyingFee(2), s.path("withdraw_occupying").asLong())
        val balance = s.path("balance").asLong()
        assertEquals((closedFee(2) - occupyingFee(2)).coerceAtLeast(0L), balance)
        assertTrue(balance >= 0L, "余额永不负")
        // 1000(A) + 500(B) + 500(E) − 500(B 冲) − 500(A 冲) = 1000，全在已结算侧
        assertEquals(1000L, s.path("commission_closed").asLong())
        assertEquals(0L, s.path("commission_open").asLong(), "F 那行减到 0，也在途但不出数")
        // 提现：paid 2000 占着，refused 300 不占 ⇒ 余额见底
        assertEquals(2000L, s.path("withdraw_paid").asLong())
        assertEquals(0L, balance)
        // 佣金流水里没有任何一行是"结算后又被回改"的：正向行金额恒等于入账时那个数
        assertEquals(
            0L,
            longOf("SELECT COUNT(*) FROM distribution_distribute_logs WHERE rebate < 0 AND is_close = FALSE"),
            "负向行必为已结算",
        )
    }
}
