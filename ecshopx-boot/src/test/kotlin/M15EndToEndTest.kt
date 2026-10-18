package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.AssetContract
import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.InvoiceContract
import com.ecshopx.kotlin.boot.contract.RateContract
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1-5 DoD（docs/04 §23.5 四道门槛）：积分/储值/评价/发票端到端。
 *
 * 门槛①「账实相符」是本类的骨架。两条恒等式各有一段**每一次动账都要过一遍**的断言：
 *  - 积分：`Σ point_member_log.change_point == point_member.point`。
 *    seed 给了 1000 分却没有对应流水（`point_member_log` 无唯一键，seed 的 `INSERT IGNORE`
 *    在常驻演示库上会每启一次多插一份，所以**没有**给它配种子流水），
 *    因此这里按**差值形式**断：进场记下 `(point₀, Σ₀)`，此后 `point - point₀ == Σ - Σ₀`。
 *    差值形式与绝对形式在"起点已知"这个前提下等价，§23.5 第 1 条写的就是"对起始余额已知的会员"。
 *  - 储值：seed 是 0/0，于是这条可以直接按**绝对形式**断：
 *    `Σ(成功动账 ±) == user_deposit.deposit_amount + gift_amount`（本金/赠金都是钱）。
 *
 * 门槛②「赠分能被证伪」在第 6 例：`get_points` 必须**由建单写入**，
 * 本类从头到尾没有一句 `UPDATE ... get_points`，也没有 `UPDATE point_member SET point=...` ——
 * 给会员加分走的是 PT-A2 调账（它自己写流水，恒等式照过），这是本批刻意选择的姿势。
 *
 * 演示数据：会员 user_id=1（积分 1000、余额 0、手机号 13800000001）、商品 3=每日坚果礼盒
 * 8900 分 / 每件赠 100 分（归 distributor_id=1）、地址 1、充值档「充 100 送 10 / 充 500 送 80」。
 * 超时关单推到 1 小时后，避免断言期间被关单抢状态。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccm15;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class M15EndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var tok = ""
    private var other = ""
    private var admin = ""
    private var shop = ""

    /** 账实相符的坐标系：进场时刻的四个基线，此后只断差值 */
    private var pointBase = 0L
    private var pointLogBase = 0L
    private var totalPointBase = 0L

    private var rateId1 = 0L
    private var fixtureShop1 = 0L
    private var fixtureShop2 = 0L

    // ---------- 调用件 ----------

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

    private fun ok(
        url: String,
        body: Map<String, Any?>? = null,
        asMember: String = tok,
        m: String = if (body == null) "GET" else "POST",
    ): JsonNode = data(call(m, url, asMember, body), url)

    private fun okAdmin(
        url: String,
        body: Map<String, Any?>? = null,
        m: String = if (body == null) "GET" else "POST",
        asToken: String = admin,
    ): JsonNode = data(call(m, url, asToken, body), "admin $m $url")

    /** 位置式写法 `okAdmin("POST", url, body)`：本批后台调用大多数是动词开头的，读起来比具名参数顺 */
    private fun okAdmin(
        m: String,
        url: String,
        body: Map<String, Any?>? = null,
        asToken: String = admin,
    ): JsonNode = data(call(m, url, asToken, body), "admin $m $url")

    private fun okShop(url: String, body: Map<String, Any?>? = null, m: String = if (body == null) "GET" else "POST") =
        okAdmin(url, body, m, shop)

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

    // ---------- 账本读口径 ----------

    private fun point(): Long =
        jdbc.queryForObject("SELECT point FROM point_member WHERE company_id = 1 AND user_id = 1", Long::class.java)!!

    private fun totalPoint(): Long =
        jdbc.queryForObject("SELECT total_point FROM point_member WHERE company_id = 1 AND user_id = 1", Long::class.java)!!

    private fun pointLogsSum(): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(change_point), 0) FROM point_member_log WHERE company_id = 1 AND user_id = 1", Long::class.java,
    )!!

    private fun balance(): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(deposit_amount + gift_amount), 0) FROM user_deposit WHERE company_id = 1 AND user_id = 1", Long::class.java,
    )!!

    /** 只认成功动账：NOTPAY 的充值单还在那一步等支付，钱没进来，账也不能算进来 */
    private fun depositLogsSum(): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(CASE WHEN trade_type = 'consume' THEN -(amount + gift_amount) " +
            "ELSE amount + gift_amount END), 0) FROM deposit_trade WHERE company_id = 1 AND user_id = 1 AND status = 'SUCCESS'",
        Long::class.java,
    )!!

    /** 门槛①：每一步动账之后都过一遍这两条恒等式 */
    private fun assertLedgerBalanced(tag: String) {
        assertEquals(point() - pointBase, pointLogsSum() - pointLogBase, "$tag：积分余额差值必须等于流水差值（§23.5 门槛①）")
        assertEquals(balance(), depositLogsSum(), "$tag：余额必须等于成功动账之和（§23.5 门槛①）")
    }

    private fun orderCol(orderId: String, col: String): String = jdbc.queryForObject(
        "SELECT $col FROM orders_normal_orders WHERE order_id = ?", Any::class.java, orderId.toLong(),
    ).toString()

    private fun logsOf(orderId: String): List<Map<String, Any>> = jdbc.queryForList(
        "SELECT change_point, before_point, after_point, remark, order_id FROM point_member_log WHERE order_id = ? ORDER BY id",
        orderId,
    )

    // ---------- 业务便捷件 ----------

    private fun createOrder(num: Int = 1, pointUse: Boolean = false): String {
        val body = linkedMapOf<String, Any?>(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to num)),
        )
        if (pointUse) body["point_use"] = true
        return ok("/api/h5app/wxapp/order_new", body).path("order_id").asText()
    }

    private fun pay(orderId: String, payType: String) =
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to orderId, "pay_type" to payType))

    private fun recharge(amount: Long): String =
        ok("/api/h5app/wxapp/payment_deposit", mapOf("amount" to amount)).path("order_id").asText()

    private fun settleRecharge(tradeId: String) {
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to tradeId, "pay_type" to "recharge"))
    }

    private fun notifyForm(tradeId: String, fee: Any, state: String = "SUCCESS"): String = utf8(
        mvc.perform(
            post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId).param("total_fee", fee.toString())
                .param("transaction_id", "WX-" + tradeId.take(6)).param("trade_state", state),
        ).andReturn().response.contentAsByteArray,
    )

    /** 微信渠道成交：不动余额、不动积分本金（只 +赠送分），发票与评价用例用它造已付单 */
    private fun wxPaidOrder(num: Int = 1): String {
        val o = createOrder(num)
        val d = pay(o, "wxpay")
        assertTrue(notifyForm(d.path("trade_id").asText(), 8900L * num).contains("SUCCESS"))
        return o
    }

    private fun refundThrough(orderId: String, fee: Long? = null) {
        val body = linkedMapOf<String, Any?>(
            "order_id" to orderId, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND",
            "reason" to "质量问题", "description" to "开箱即损",
        )
        fee?.let { body["refund_fee"] = it }
        val bn = ok("/api/h5app/wxapp/aftersales", body).path("aftersales_bn").asText()
        okAdmin("POST", "/api/aftersales/review", mapOf("aftersales_bn" to bn, "is_approved" to 1))
        okAdmin("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 1))
    }

    private fun doneOrder(): String {
        val o = wxPaidOrder()
        okAdmin("PUT", "/api/orders/delivery", mapOf(
            "order_id" to o.toLong(), "delivery_corp" to "zto", "delivery_code" to "SF-M15",
        ))
        assertEquals("DONE", ok("/api/h5app/wxapp/order/confirmReceipt", mapOf("order_id" to o)).path("order_status").asText())
        return o
    }

    private fun rate(orderId: String, items: List<Map<String, Any?>>, asMember: String = tok) =
        ok("/api/h5app/wxapp/order/rate", mapOf("order_id" to orderId, "items" to items), asMember)

    private fun applyInvoice(orderId: String, extra: Map<String, Any?> = emptyMap(), asMember: String = tok): JsonNode {
        val body = linkedMapOf<String, Any?>("order_id" to orderId, "email" to "inv@demo.cn")
        body.putAll(extra)
        return ok("/api/h5app/wxapp/order/invoice/apply", body, asMember)
    }

    private fun defaultHeadCount(): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM members_invoices WHERE company_id = 1 AND user_id = 1 AND is_def = TRUE", Int::class.java,
    )!!

    // ---------- 门槛用例 ----------

    @Test @Order(1) fun `令牌就绪与账本坐标系`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        other = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "other").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null, mapOf("username" to "admin", "password" to "admin123")), "admin")
            .path("token").asText()
        shop = data(call("POST", "/api/operator/login", null, mapOf("username" to "distributor01", "password" to "shop123")), "shop")
            .path("token").asText()
        assertTrue(tok.isNotBlank() && other.isNotBlank() && admin.isNotBlank() && shop.isNotBlank())

        pointBase = point(); totalPointBase = totalPoint(); pointLogBase = pointLogsSum()
        assertEquals(1000L, pointBase, "seed 积分 1000")
        assertEquals(0L, pointLogBase, "本批没给积分流水配种子行（无唯一键，INSERT IGNORE 会每启一次多插一份）")
        assertEquals(1000L, totalPointBase, "seed 累计获得 = 初始余额")
        assertEquals(0L, balance(), "seed 余额 0：储值的绝对恒等式因此可用")
        assertEquals(0L, depositLogsSum())
        assertLedgerBalanced("进场")
    }

    @Test @Order(2) fun `PT-CZ 六条读口与出参契约`() {
        val p = ok("/api/h5app/wxapp/point")
        assertContract(p, AssetContract.POINT_OVERVIEW, "PT-F1 积分总览")
        assertEquals(1000L, p.path("point").asLong())
        assertEquals(1000L, p.path("total_point").asLong())
        assertEquals(0L, p.path("user_point").asLong(), "还没有一条负流水，累计扣减必为 0")
        assertEquals(50, p.path("ratio").asInt(), "seed point.deduct.max_ratio=50")
        assertEquals(true, p.path("enable").asBoolean())

        val logs = ok("/api/h5app/wxapp/point_logs")
        assertContract(logs, AssetContract.LIST_ENVELOPE, "PT-F2 信封")
        assertEquals(0L, logs.path("total_count").asLong(), "起点无流水")

        val rule = ok("/api/h5app/wxapp/point_rule")
        assertContract(rule, AssetContract.POINT_RULE, "PT-F3 规则")
        assertEquals(1L, rule.path("point_per_fen").asLong(), "1 积分 = 1 分（PointRate）")
        assertTrue(rule.path("text").asText().isNotBlank(), "文案是拼出来的，不能空")

        val d = ok("/api/h5app/wxapp/deposit")
        assertContract(d, AssetContract.DEPOSIT_OVERVIEW, "CZ-F1 余额")
        assertEquals(0L, d.path("balance").asLong())
        assertEquals(0L, d.path("total_recharge").asLong())

        assertContract(ok("/api/h5app/wxapp/deposit_logs"), AssetContract.LIST_ENVELOPE, "CZ-F2 信封")

        val rules = ok("/api/h5app/wxapp/deposit_rules")
        assertContract(rules, AssetContract.DEPOSIT_RULES, "CZ-F3 档位页")
        assertEquals(2, rules.path("list").size(), "seed 两档：充 100 送 10、充 500 送 80")
        rules.path("list").forEach { assertContract(it, AssetContract.RECHARGE_RULE_ROW, "CZ-F3 档位行") }
        assertEquals(10000L, rules.path("list")[0].path("recharge_amount").asLong(), "按金额升序")
        assertEquals(0.0, rules.path("recharge_point_rate").asDouble(), "deposit.recharge.point.rate 默认关")

        // 六条读口全在会员链上：没令牌就是 401，不给"未登录也能看看别人的账"
        expect("GET", "/api/h5app/wxapp/point", null, 401, token = null)
        expect("GET", "/api/h5app/wxapp/deposit_logs", null, 401, token = null)
        expect("GET", "/api/point/members", null, 401, token = null)
    }

    @Test @Order(3) fun `储值三段账实 充值 消费 退款`() {
        val cz = recharge(10000)
        assertEquals(0L, balance(), "充值单没结清之前钱不能进余额")
        assertEquals(0L, depositLogsSum(), "NOTPAY 那一行还挂在 deposit_trade 上，但恒等式右侧不认它：钱没进来就不能算进来")
        settleRecharge(cz)
        assertEquals(11000L, balance(), "本金 10000 + 档位赠金 1000")
        assertLedgerBalanced("充值结清")

        val rows = ok("/api/h5app/wxapp/deposit_logs").path("list")
        assertContract(rows[0], AssetContract.DEPOSIT_TRADE_ROW, "CZ-F2 充值行")
        assertEquals("recharge", rows[0].path("trade_type").asText())
        assertEquals(10000L, rows[0].path("amount").asLong(), "amount 是本金部分")
        assertEquals(1000L, rows[0].path("gift_amount").asLong(), "gift_amount 是其中赠金")
        assertEquals("SUCCESS", rows[0].path("status").asText())

        val o = createOrder()
        pay(o, "deposit")
        assertEquals(2100L, balance(), "11000 - 8900")
        assertLedgerBalanced("余额消费")
        val consume = ok("/api/h5app/wxapp/deposit_logs?trade_type=consume").path("list")
        assertEquals(1, consume.size(), "消费必须留一条痕（退款要按本金/赠金比例原路回补）")
        assertEquals(o, consume[0].path("rel_id").asText())
        assertEquals(1000L, consume[0].path("gift_amount").asLong(), "赠金优先扣完")
        assertEquals(7900L, consume[0].path("amount").asLong(), "本金兜底剩下的部分")

        refundThrough(o)
        assertEquals(11000L, balance(), "退款原路回补，赠金不退成本金")
        assertLedgerBalanced("储值退款")
        val refund = ok("/api/h5app/wxapp/deposit_logs?trade_type=refund").path("list")
        assertEquals(1, refund.size())
        assertEquals(8900L, refund[0].path("amount").asLong() + refund[0].path("gift_amount").asLong(), "退款合计=消费合计")

        val dep = ok("/api/h5app/wxapp/deposit")
        assertEquals(11000L, dep.path("balance").asLong(), "PT/CZ 读口与库内同一份账")
        assertEquals(10000L, dep.path("deposit_amount").asLong(), "退款按消费时的分栏原路回补：本金回到 10000")
        assertEquals(1000L, dep.path("gift_amount").asLong(), "赠金回到 1000，不折成本金（那样等于把送的钱又送一遍）")
        assertEquals(
            dep.path("deposit_amount").asLong() + dep.path("gift_amount").asLong(),
            dep.path("balance").asLong(), "balance 就是两栏之和（CZ-R1）",
        )
    }

    @Test @Order(4) fun `PT-A2 调账 加分 负分 钳位与非法`() {
        // 上一例那笔余额消费的单结清时赠了 100 分，所以这里的起点不是 1000 而是"当前值"
        val p0 = point()
        val tp0 = totalPoint()
        assertContract(okAdmin("POST", "/api/point/adjustment", mapOf("user_id" to 1, "change_point" to 20000, "remark" to "演示补分")), AssetContract.POINT_ADJUST, "PT-A2 调账")
        assertEquals(p0 + 20000, point(), "调账 = 现值 + 入参")
        assertLedgerBalanced("调账加分")
        assertEquals(tp0 + 20000, totalPoint(), "正向调账算获得，要动 total_point（PT-A2 注释那条 granted）")

        val clamp = okAdmin("POST", "/api/point/adjustment", mapOf("user_id" to 1, "change_point" to -100000, "remark" to "钳位测试"))
        assertEquals(0L, point(), "负数钳到 0，不出现负余额")
        assertEquals(-(p0 + 20000), clamp.path("change_point").asLong(), "回的是真正落账的数，不是入参那个 -100000")
        assertLedgerBalanced("钳位")
        assertEquals(tp0 + 20000, totalPoint(), "负向调账不动 total_point（PT-R2）")

        expect("POST", "/api/point/adjustment", mapOf("user_id" to 1, "change_point" to -5, "remark" to "已经 0 分了"), 422, token = admin)
        expect("POST", "/api/point/adjustment", mapOf("user_id" to 1, "change_point" to 0, "remark" to "调 0 分"), 400, token = admin)
        expect("POST", "/api/point/adjustment", mapOf("user_id" to 1, "change_point" to 100), 400, token = admin)
        expect("POST", "/api/point/adjustment", mapOf("change_point" to 100, "remark" to "没有 user_id"), 400, token = admin)

        okAdmin("POST", "/api/point/adjustment", mapOf("user_id" to 1, "change_point" to 20000, "remark" to "补回可付额度"))
        assertEquals(20000L, point())
        assertLedgerBalanced("二次加分")

        val a1 = okAdmin("/api/point/members")
        assertContract(a1, AssetContract.POINT_MEMBERS, "PT-A1 整页（信封 + 卡头四个数）")
        // 卡头三个合计数一律与 SQL 同一条口径对：断"够大"看不出漏算，断相等才钉得住
        val sumSql = jdbc.queryForObject("SELECT COALESCE(SUM(point), 0) FROM point_member WHERE company_id = 1", Long::class.java)!!
        val usedSql = jdbc.queryForObject(
            "SELECT COALESCE(SUM(-change_point), 0) FROM point_member_log WHERE company_id = 1 AND change_point < 0", Long::class.java,
        )!!
        val grantedSql = jdbc.queryForObject("SELECT COALESCE(SUM(total_point), 0) FROM point_member WHERE company_id = 1", Long::class.java)!!
        assertEquals(sumSql, a1.path("total_point_sum").asLong(), "余额合计（${a1}）")
        assertEquals(grantedSql, a1.path("total_granted").asLong(), "获得合计读 total_point，不是 Σ(正流水)")
        assertEquals(usedSql, a1.path("total_used").asLong(), "total_used = Σ(负流水绝对值)，不是 total_point - point（回补会把后者算没）")
        assertTrue(usedSql > 0L, "钳位那笔负流水必须已经被这条口径认下来")
        a1.path("list").forEach { assertContract(it, AssetContract.POINT_MEMBER_ROW, "PT-A1 行") }

        val a3 = okAdmin("/api/point/logs?user_id=1")
        assertContract(a3, AssetContract.LIST_ENVELOPE, "PT-A3 信封")
        a3.path("list").forEach { assertContract(it, AssetContract.POINT_LOG_ADMIN_ROW, "PT-A3 行") }
        val adminMobile = jdbc.queryForObject("SELECT mobile FROM members WHERE user_id = 1", String::class.java)!!
        assertEquals(adminMobile, a3.path("list")[0].path("mobile").asText(), "admin 型穿透，看全值")
        assertEquals("138****0001", a3.path("list")[0].path("mobile_masked").asText(), "掩码列与全值列并存（MASK-R1）")

        val cfg = okAdmin("/api/member/point/rule")
        assertContract(cfg, AssetContract.POINT_CONFIG, "PT-A4 三张对照")
        assertEquals(50, cfg.path("effective").path("point.deduct.max_ratio").asInt())
        val written = okAdmin("/api/member/point/rule", mapOf("point.deduct.max_ratio" to "30"), m = "PUT")
        assertEquals(30, written.path("effective").path("point.deduct.max_ratio").asInt())
        expect("PUT", "/api/member/point/rule", mapOf("point.deduct.max_ratio" to "150"), 422, token = admin)
        okAdmin("/api/member/point/rule", mapOf("point.deduct.max_ratio" to "50"), m = "PUT")
        assertEquals(50, okAdmin("/api/member/point/rule").path("effective").path("point.deduct.max_ratio").asInt(), "写回原值，后面的抵扣算式才认得")
    }

    @Test @Order(5) fun `积分抵现与关单回补 两条腿都要留痕`() {
        val tp0 = totalPoint()
        val o = createOrder(pointUse = true)
        assertEquals("4450", orderCol(o, "point_fee"), "8900 × 50% 上限")
        assertEquals("4450", orderCol(o, "total_fee"), "应付扣掉抵现")
        assertEquals(15550L, point(), "20000 - 4450")
        val rows = logsOf(o)
        assertEquals(1, rows.size, "抵扣腿必须自己留一条负流水，否则恒等式在第一次抵现就断了")
        assertEquals(-4450L, rows[0]["change_point"] as Long)
        assertEquals("订单积分抵现", rows[0]["remark"])
        assertEquals(20000L, rows[0]["before_point"] as Long)
        assertEquals(15550L, rows[0]["after_point"] as Long)
        assertLedgerBalanced("下单抵扣")
        assertEquals(tp0, totalPoint(), "抵扣不是获得（PT-R2）")

        ok("/api/h5app/wxapp/order/cancel", mapOf("order_id" to o, "cancel_reason" to "改变主意"))
        assertEquals("CANCEL", orderCol(o, "order_status"))
        assertEquals(20000L, point(), "关单把抵现的积分回补")
        val back = logsOf(o)
        assertEquals(2, back.size, "扣一条、回一条，两行都得在")
        assertEquals(4450L, back[1]["change_point"] as Long)
        assertEquals("订单关闭，积分抵现回补", back[1]["remark"])
        assertLedgerBalanced("取消回补")
    }

    @Test @Order(6) fun `纯积分兑换 赠送积分入账与退款扣回（全程无手写 UPDATE）`() {
        val tp0 = totalPoint()
        val o = createOrder()
        // 门槛②：get_points 由建单算出，本类没有任何一句 UPDATE ... get_points
        assertEquals("100", orderCol(o, "get_points"), "每件 100 分 × 1 件，必须由建单写入")
        assertEquals(orderCol(o, "get_points"), orderCol(o, "item_point"), "单头 = Σ行 item_point（§23.3 PT-R1）")

        val d = pay(o, "point")
        assertEquals("SUCCESS", d.path("trade_state").asText())
        assertEquals(8900L, d.path("point_used").asLong())
        assertEquals(11200L, point(), "20000 - 8900 + 100 赠送")
        val rows = logsOf(o)
        assertEquals(2, rows.size, "抵扣与赠送各一条")
        assertEquals(-8900L, rows[0]["change_point"] as Long)
        assertEquals("积分支付抵扣", rows[0]["remark"])
        assertEquals(100L, rows[1]["change_point"] as Long)
        assertEquals("订单支付赠送积分", rows[1]["remark"])
        assertLedgerBalanced("积分结清")
        val tp = totalPoint()
        assertEquals(tp0 + 100, tp, "赠送算获得、抵扣不算")

        refundThrough(o)
        assertEquals(20100L, point(), "退款把 8900 分原路退回（赠送分不回扣）")
        assertEquals(tp, totalPoint(), "回补不是获得，total_point 一分不动（PT-R2）")
        assertEquals(3, logsOf(o).size, "第三条是退款回补的痕")
        assertLedgerBalanced("积分退款扣回")
    }

    @Test @Order(7) fun `CZ-A2 档位维护 CZ-A3 代客充值 CZ-A4 总览`() {
        val created = okAdmin("POST", "/api/deposit/recharge_rule", mapOf("rule_name" to "充200送30", "recharge_amount" to 20000, "gift_amount" to 3000))
        assertContract(created, AssetContract.RECHARGE_RULE_ROW, "CZ-A2 新档位")
        val id = created.path("id").asLong()
        assertTrue(id > 0)

        okAdmin("/api/deposit/recharge_rules")
        val front = ok("/api/h5app/wxapp/deposit_rules")
        assertTrue(front.path("list").map { it.path("id").asLong() }.contains(id), "上架档位前台可见")

        // 读-改-写：只传 status，金额保持原值（不传不等于清零）
        val off = okAdmin("/api/deposit/recharge_rule/$id", mapOf("status" to 0), m = "PUT")
        assertEquals(20000L, off.path("recharge_amount").asLong(), "没传的字段不能被写成 0")
        assertFalseish(off.path("status"))
        assertEquals(
            false, ok("/api/h5app/wxapp/deposit_rules").path("list").map { it.path("id").asLong() }.contains(id),
            "下架档位前台不可见",
        )
        okAdmin("DELETE", "/api/deposit/recharge_rule/$id")
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM deposit_recharge_rule WHERE id = ?", Int::class.java, id))
        expect("POST", "/api/deposit/recharge_rule", mapOf("rule_name" to "零元档", "recharge_amount" to 0, "gift_amount" to 0), 400, token = admin)

        val before = balance()
        val r = okAdmin("POST", "/api/deposit/recharge", mapOf("user_id" to 1, "amount" to 5000, "gift_amount" to 500, "remark" to "客服补偿"))
        assertContract(r, AssetContract.OPERATOR_RECHARGE, "CZ-A3 出参")
        assertEquals("SUCCESS", r.path("trade_state").asText(), "代客充值不走支付，当场入账")
        assertEquals(before + 5500, balance())
        assertLedgerBalanced("代客充值")
        assertTrue(r.path("trade_id").asText().startsWith("CZ"), r.path("trade_id").asText())
        expect("POST", "/api/deposit/recharge", mapOf("user_id" to 1, "amount" to 100, "remark" to "没有赠金默认 0 都行"), 200, token = admin)
        assertLedgerBalanced("代客充值（无赠金）")

        val count = okAdmin("/api/deposit/count")
        assertContract(count, AssetContract.DEPOSIT_COUNT, "CZ-A4")
        assertTrue(count.path("principal_sum").asLong() > 0 && count.path("balance_sum").asLong() > 0, count.toString())

        val trades = okAdmin("/api/deposit/trades?trade_type=recharge")
        assertContract(trades, AssetContract.LIST_ENVELOPE, "CZ-A1 信封")
        trades.path("list").forEach { assertContract(it, AssetContract.DEPOSIT_TRADE_ADMIN_ROW, "CZ-A1 行") }

        // 越权：代客充值是平台权力（role 2 只有 54/56/59/60/61 五条看码 + 本店回复）
        expect("POST", "/api/deposit/recharge", mapOf("user_id" to 1, "amount" to 1, "remark" to "越权"), 403, token = shop)
        expect("POST", "/api/deposit/recharge_rule", mapOf("rule_name" to "越权档", "recharge_amount" to 1, "gift_amount" to 0), 403, token = shop)
        expect("POST", "/api/point/adjustment", mapOf("user_id" to 1, "change_point" to 1, "remark" to "越权调账"), 403, token = shop)
    }

    @Test @Order(8) fun `评价全链 待评价 提交 三面读 回复 上下架 与分桶`() {
        val o = doneOrder()
        assertLedgerBalanced("微信结清（含赠送分入账）")

        val pending = ok("/api/h5app/wxapp/order/rate/pending?order_id=$o")
        assertContract(pending, RateContract.RATE_PENDING, "RATE-F5")
        assertEquals("false", pending.path("is_rate").asText())
        assertEquals(1, pending.path("list").size())
        assertContract(pending.path("list")[0], RateContract.RATE_PENDING_ROW, "RATE-F5 行")
        assertEquals(3L, pending.path("list")[0].path("item_id").asLong())

        val counts0 = ok("/api/h5app/wxapp/orderscount")
        val evaluation0 = counts0.path("evaluation").asLong()
        val done0 = counts0.path("done").asLong()
        assertTrue(evaluation0 >= 1 && done0 >= 1, "$counts0")

        val submitted = rate(o, listOf(mapOf("item_id" to 3, "star" to 5, "content" to "很新鲜", "rate_pic" to listOf("/img/a.jpg"))))
        assertContract(submitted, RateContract.RATE_SUBMIT, "RATE-F1")
        assertEquals(1L, submitted.path("rate_count").asLong())
        assertEquals("true", submitted.path("is_rate").asText())
        rateId1 = jdbc.queryForObject("SELECT rate_id FROM trade_rate WHERE order_id = ?", Long::class.java, o)

        // 重复提交：CAS 在整单位上，行数一根毛都不动
        val cnt = jdbc.queryForObject("SELECT COUNT(*) FROM trade_rate WHERE order_id = ?", Int::class.java, o)
        expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to o, "items" to listOf(mapOf("item_id" to 3, "star" to 1))), 422)
        assertEquals(cnt, jdbc.queryForObject("SELECT COUNT(*) FROM trade_rate WHERE order_id = ?", Int::class.java, o), "二次提交不得多落一行")
        assertEquals(
            1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM point_member_log WHERE remark = '订单支付赠送积分' AND order_id = ?", Int::class.java, o,
            ), "赠送分仍只有一条：它由结清那一步写，提交评价不碰账本",
        )

        // 评完就整单退出待评价桶
        val counts1 = ok("/api/h5app/wxapp/orderscount")
        assertEquals(evaluation0 - 1, counts1.path("evaluation").asLong(), "评价后 evaluation 桶减 1")
        assertEquals(done0, counts1.path("done").asLong(), "done 桶不动：evaluation 是 done 的子集，不是第七个互斥桶")
        assertEquals(
            counts1.path("total").asLong(),
            counts1.path("notpay").asLong() + counts1.path("pending_delivery").asLong() +
                counts1.path("pending_receive").asLong() + counts1.path("done").asLong() + counts1.path("cancel").asLong(),
            "BUCKET-R1 分桶求和恒等（六桶互斥、evaluation 不参与求和）",
        )

        val list = ok("/api/h5app/wxapp/order/rate/list?item_id=3&page_size=100")
        assertContract(list, AssetContract.LIST_ENVELOPE, "RATE-F2 信封")
        val mine = list.path("list").first { it.path("rate_id").asLong() == rateId1 }
        assertContract(mine, RateContract.RATE_FRONT_ROW, "RATE-F2 行")
        assertEquals("演示用户", mine.path("nickname").asText())
        assertEquals(o, mine.path("order_id").asText(), "自己看自己的行，订单号出")
        // 别人的行不出订单号
        val asOther = ok("/api/h5app/wxapp/order/rate/list?item_id=3&page_size=100", asMember = other)
        val foreign = asOther.path("list").first { it.path("rate_id").asLong() == rateId1 }
        assertTrue(foreign.path("order_id").isNull, "别人的订单号不能出（他买了什么、什么时候买的）")

        val detail = ok("/api/h5app/wxapp/order/rate/$rateId1")
        assertContract(detail, RateContract.RATE_DETAIL, "RATE-F3")
        assertContract(detail.path("rate"), RateContract.RATE_FRONT_ROW, "RATE-F3 rate")
        assertEquals(0, detail.path("replies").size())

        assertContract(ok("/api/h5app/wxapp/order/rate/my?page_size=100").path("list")[0], RateContract.RATE_FRONT_ROW, "RATE-F4 行")

        val a1 = okAdmin("/api/comments?order_id=$o")
        assertContract(a1.path("list")[0], RateContract.RATE_ADMIN_ROW, "RATE-A1 行")
        assertEquals(3L, a1.path("list")[0].path("item_id").asLong())
        assertEquals("每日坚果礼盒", a1.path("list")[0].path("item_name").asText(), "后台行必须带商品名，否则客服只拿到一串 id")

        val replied = okAdmin("POST", "/api/comment/reply", mapOf("rate_id" to rateId1, "content" to "感谢支持"))
        assertContract(replied, RateContract.RATE_REPLY_OUT, "RATE-A2")
        assertEquals(1, ok("/api/h5app/wxapp/order/rate/$rateId1").path("replies").size())
        assertContract(ok("/api/h5app/wxapp/order/rate/$rateId1").path("replies")[0], RateContract.RATE_REPLY_ROW, "回复行")
        assertEquals(true, ok("/api/h5app/wxapp/order/rate/list?item_id=3&page_size=100").path("list").first { it.path("rate_id").asLong() == rateId1 }.path("is_reply").asBoolean())

        val visibleBefore = okAdmin("/api/comments?order_id=$o")
        val visibleCount = visibleBefore.path("total_count").asLong()
        val off = okAdmin("/api/comment/$rateId1/disable", mapOf("disabled" to true), m = "PUT")
        assertContract(off, RateContract.RATE_DISABLE_OUT, "RATE-A3")
        assertEquals(
            false, ok("/api/h5app/wxapp/order/rate/list?item_id=3&page_size=100").path("list").map { it.path("rate_id").asLong() }.contains(rateId1),
            "下架后商品页看不见",
        )
        assertTrue(ok("/api/h5app/wxapp/order/rate/my?page_size=100").path("list").map { it.path("rate_id").asLong() }.contains(rateId1), "自己的评价仍然看得见")
        // RATE-A1 的可见性筛必须同时作用在 `count` 与 `page` 上。本批第一版只挂在取数那一段：
        // 总数算着下架的行、列表却不给，分页器按总数发页码，最后一页就成了空页（实跑冒烟抓到）。
        // 更早还有一版更隐蔽 —— 服务层把 `include_disabled` 归一成布尔（永远非 null），
        // 而 SQL 那道判的是 `<if test="f.includeDisabled == null">`，于是判据从未生效，
        // 后台默认面把下架的行也一并端出来。两处都由这三行钉住。
        val hidden = okAdmin("/api/comments?order_id=$o")
        assertEquals(false, hidden.path("list").map { it.path("rate_id").asLong() }.contains(rateId1), "下架后后台默认列表看不见")
        assertEquals(visibleCount - 1, hidden.path("total_count").asLong(), "total_count 跟着可见性一起减（不是只减列表）")
        val shown = okAdmin("/api/comments?order_id=$o&include_disabled=1")
        assertEquals(visibleCount, shown.path("total_count").asLong(), "带 include_disabled 时两张数一起放开")
        assertEquals(true, shown.path("list").first { it.path("rate_id").asLong() == rateId1 }.path("disabled").asBoolean())
        okAdmin("/api/comment/$rateId1/disable", mapOf("disabled" to false), m = "PUT")
        assertTrue(ok("/api/h5app/wxapp/order/rate/list?item_id=3&page_size=100").path("list").map { it.path("rate_id").asLong() }.contains(rateId1), "上架回来")
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_rate WHERE rate_id = ?", Int::class.java, rateId1), "上下架只翻可见位，不删行")
    }

    @Test @Order(9) fun `评价越权与状态闸门`() {
        val o = doneOrder()
        expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to o, "items" to listOf(mapOf("item_id" to 3, "star" to 5))), 404, token = other)
        expect("GET", "/api/h5app/wxapp/order/rate/pending?order_id=$o", null, 404, token = other)
        expect("GET", "/api/h5app/wxapp/order/rate/$rateId1", null, 404, token = other)

        val unpaid = createOrder()
        expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to unpaid, "items" to listOf(mapOf("item_id" to 3, "star" to 5))), 422)
        val r = expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to o, "items" to emptyList<Any>()), 400)
        assertEquals(setOf("message", "errors", "code", "status_code"), ContractDiff.keysOf(r), "错误体四件套")
        assertEquals(listOf("不能为空"), r.path("errors").path("items").map { it.asText() })
        expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to o, "items" to listOf(mapOf("item_id" to 3))), 400)
        expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to o, "items" to listOf(mapOf("item_id" to 3, "star" to 6))), 400)
        expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to o, "items" to listOf(mapOf("item_id" to 1, "star" to 5))), 400)
        expect("POST", "/api/h5app/wxapp/order/rate", mapOf("order_id" to "999999999999999", "items" to listOf(mapOf("item_id" to 3, "star" to 5))), 404)
        expect("GET", "/api/h5app/wxapp/order/rate/list", null, 400, token = tok)

        // 匿名：别人看是"匿名用户"，自己看是真名（RATE-R3）
        rate(o, listOf(mapOf("item_id" to 3, "star" to 4, "anonymous" to true)))
        val anonId = jdbc.queryForObject("SELECT rate_id FROM trade_rate WHERE order_id = ?", Long::class.java, o)
        assertEquals("匿名用户", ok("/api/h5app/wxapp/order/rate/list?item_id=3&page_size=100", asMember = other).path("list").first { it.path("rate_id").asLong() == anonId }.path("nickname").asText())
        assertEquals("演示用户", ok("/api/h5app/wxapp/order/rate/list?item_id=3&page_size=100").path("list").first { it.path("rate_id").asLong() == anonId }.path("nickname").asText())

        // 店铺收窄：两行分属两家店的夹具（演示库所有商品都归 distributor 1，role 2 名下没有真单可评，只能造行）
        fixtureShop1 = insertRate("M15-S1", 1)
        fixtureShop2 = insertRate("M15-S2", 2)
        val shopList = okShop("/api/comments?item_id=3&page_size=100")
        val seen = shopList.path("list").map { it.path("rate_id").asLong() }
        assertTrue(seen.contains(fixtureShop2) && !seen.contains(fixtureShop1), "role 2 只出本店行：$seen")
        assertTrue(shopList.path("list").all { it.path("distributor_id").asLong() == 2L }, "收窄判据 = distributor_ids")
        val adminSeen = okAdmin("/api/comments?item_id=3&page_size=100").path("list").map { it.path("rate_id").asLong() }
        assertTrue(adminSeen.containsAll(listOf(fixtureShop1, fixtureShop2)), "admin 型看全场：$adminSeen")

        // 越权 = 404 而不是 403（§23.5 门槛③）：`scope.require` 把"别人店的行"与"不存在的行"回成同一个应答，
        // 403 等于承认"这条评价存在、只是不给你动"，把别家的评价量枚举出来了
        expect("POST", "/api/comment/reply", mapOf("rate_id" to fixtureShop1, "content" to "越权回复"), 404, token = shop)
        expect("PUT", "/api/comment/$fixtureShop1/disable", mapOf("disabled" to true), 404, token = shop)
        expect("POST", "/api/comment/reply", mapOf("rate_id" to fixtureShop2, "content" to "本店回复"), 200, token = shop)
        assertEquals("FIXTURE-M15-S2", jdbc.queryForObject("SELECT order_id FROM trade_rate WHERE rate_id = ?", String::class.java, fixtureShop2))
    }

    /**
     * 评价夹具行。
     *
     * 演示库里每个商品都只挂在 `distributor_id=1`，店 2 的账号（role 2）拿不到任何一条真评价，
     * 「RATE-A1 只出本店行」「同一行两个账号两个出参」这两条判据就没有载体，
     * 所以这里手工插两行、把归属写死成一店一行。
     * `order_id` 是 VARCHAR 且后台列表不按单号 join 订单，假单号不会把视图带崩；
     * `content` 兼作反查标记，避免与同一真单上的真实评价撞车。
     */
    private fun insertRate(
        tag: String,
        distributorId: Int,
    ): Long {
        jdbc.update(
            "INSERT INTO trade_rate(company_id, order_id, item_id, goods_id, user_id, star, content, anonymous, is_reply, disabled, distributor_id, shop_id, nickname, created, updated) " +
                "VALUES(1, ?, 3, 3, 1, 5, ?, FALSE, FALSE, FALSE, ?, 0, '演示用户', 0, 0)",
            "FIXTURE-$tag", tag, distributorId,
        )
        return jdbc.queryForObject("SELECT rate_id FROM trade_rate WHERE content = ?", Long::class.java, tag)!!
    }

    @Test @Order(10) fun `datapass 同一行两个账号两个出参`() {
        val mobile = jdbc.queryForObject("SELECT mobile FROM members WHERE user_id = 1", String::class.java)!!
        val full = okAdmin("/api/point/logs?user_id=1").path("list")[0]
        val masked = okShop("/api/point/logs?user_id=1").path("list")[0]
        assertEquals(mobile, full.path("mobile").asText(), "admin 型穿透看全值")
        assertEquals("138****0001", masked.path("mobile").asText(), "未配 datapass.pass 一律掩码")
        assertEquals(full.path("mobile_masked").asText(), masked.path("mobile_masked").asText(), "掩码列两边同值")
        assertEquals(full.path("id").asLong(), masked.path("id").asLong(), "同一行数据")

        val row = okShop("/api/comments?item_id=3&page_size=100").path("list").first { it.path("rate_id").asLong() == fixtureShop2 }
        assertEquals("138****0001", row.path("mobile").asText(), "RATE-A1 同一判据")
        assertEquals(mobile, okAdmin("/api/comments?item_id=3&page_size=100").path("list").first { it.path("rate_id").asLong() == fixtureShop2 }.path("mobile").asText())
    }

    @Test @Order(11) fun `INV 抬头本 词表 默认位 与引用守卫`() {
        val heads = ok("/api/h5app/wxapp/member/invoices")
        assertEquals(2, heads.size(), "seed 两条演示抬头")
        assertContract(heads[0], InvoiceContract.INVOICE_HEAD_ROW, "INV-F4 行")
        assertEquals(1, defaultHeadCount(), "同一会员下默认抬头只有一个")
        assertEquals(true, heads.first { it.path("invoices_id").asLong() == 2L }.path("is_def").asBoolean(), "seed 的默认位挂在企业抬头上")

        expect("POST", "/api/h5app/wxapp/member/invoice", mapOf("invoices_type" to "corporate", "name" to "无税号公司"), 422)
        expect("POST", "/api/h5app/wxapp/member/invoice", mapOf("invoices_type" to "personal"), 400)
        expect("POST", "/api/h5app/wxapp/member/invoice", mapOf("invoices_type" to "company", "name" to "错词表"), 400)

        // 个人抬头不要求税号（INV-R3：词表就是源系统那两套，本项目不收紧）
        val personal = ok("/api/h5app/wxapp/member/invoice", mapOf("invoices_type" to "personal", "name" to "张三"))
        assertContract(personal, InvoiceContract.INVOICE_HEAD_ROW, "INV-F5")
        val pid = personal.path("invoices_id").asLong()
        assertEquals(1, defaultHeadCount(), "没传 is_def 不动默认位")

        val newDef = ok("/api/h5app/wxapp/member/invoice", mapOf("invoices_type" to "corporate", "name" to "新默认公司", "tax_number" to "91330100MA27XXXX0C", "is_def" to true))
        val nid = newDef.path("invoices_id").asLong()
        assertEquals(1, defaultHeadCount(), "置默认走先清其余、再置自己")
        assertEquals(true, newDef.path("is_def").asBoolean())
        assertEquals(
            false, ok("/api/h5app/wxapp/member/invoices").first { it.path("invoices_id").asLong() == 2L }.path("is_def").asBoolean(),
            "原默认被清",
        )
        val edited = ok("/api/h5app/wxapp/member/invoice", mapOf("invoices_id" to nid, "invoices_type" to "corporate", "name" to "新默认公司改", "tax_number" to "91330100MA27XXXX0C", "is_def" to true))
        assertEquals(nid, edited.path("invoices_id").asLong(), "带上 invoices_id 就是改这一行，不是再插一行")
        assertEquals("新默认公司改", edited.path("name").asText())
        assertEquals(1, defaultHeadCount(), "改的时候重申默认，仍然只有一个是默认")
        assertEquals(4, ok("/api/h5app/wxapp/member/invoices").size(), "改不是新增：2 条种子 + 张三 + 新默认公司")

        ok("/api/h5app/wxapp/member/invoice/$pid", m = "DELETE").let { assertContract(it, InvoiceContract.INVOICE_HEAD_DELETE, "INV-F6") }
        assertEquals(3, ok("/api/h5app/wxapp/member/invoices").size())
        expect("DELETE", "/api/h5app/wxapp/member/invoice/$pid", null, 404)
        // 越权：user 2 删不动 user 1 的抬头，也读不到
        expect("DELETE", "/api/h5app/wxapp/member/invoice/$nid", null, 404, token = other)
        // 抬头本只出本人：user 2 的列表是空的（seed 两条演示抬头都挂在 user 1 名下）
        assertEquals(0, ok("/api/h5app/wxapp/member/invoices", asMember = other).size(), "别人的抬头一条都不给看")
    }

    @Test @Order(12) fun `INV 申请 处理 与终态不可改`() {
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to "900000000000001", "email" to "a@b.c", "company_title" to "T", "invoice_type" to "individual"), 404)
        val unpaid = createOrder()
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to unpaid, "email" to "a@b.c", "company_title" to "演示公司", "invoice_type" to "individual"), 422)

        okAdmin("/api/config", mapOf("invoice.enable" to "0"), m = "PUT")
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to unpaid, "email" to "a@b.c"), 422)
        okAdmin("/api/config", mapOf("invoice.enable" to "1"), m = "PUT")

        val p1 = wxPaidOrder()
        val p2 = wxPaidOrder()
        assertLedgerBalanced("发票用例造单之后")
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to p1, "email" to "a@b.c"), 404, token = other)

        val r = expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to p1, "invoice_type" to "individual"), 400)
        assertTrue(r.path("errors").has("email"), "email 必填")
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to p1, "email" to "abc", "company_title" to "T", "invoice_type" to "individual"), 400)
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to p1, "email" to "a@b.c", "company_title" to "T", "invoice_type" to "person"), 400)
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to p1, "email" to "a@b.c", "company_title" to "T", "invoice_type" to "enterprise"), 422)
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to p1, "email" to "a@b.c", "company_title" to "T", "invoice_type" to "individual", "invoice_type_code" to "03"), 422)

        val apply = applyInvoice(p1, mapOf("invoices_id" to 2))
        assertContract(apply, InvoiceContract.INVOICE_APPLY_ROW, "INV-F1")
        assertEquals("pending", apply.path("invoice_status").asText())
        assertEquals("enterprise", apply.path("invoice_type").asText(), "抬头本带出票种：corporate → enterprise（两套词表，见 InvoiceHeadType）")
        assertEquals("02", apply.path("invoice_type_code").asText(), "不传票种代码就落白名单第一个（seed 只开普票）")
        assertEquals(8900L, apply.path("invoice_amount").asLong(), "金额只对着这一单的实付算（INV-R1）")
        assertEquals(2L, apply.path("invoices_id").asLong())
        assertTrue(apply.path("invoice_apply_bn").asText().startsWith("FP"), apply.path("invoice_apply_bn").asText())
        val id1 = apply.path("id").asLong()

        val again = applyInvoice(p1, mapOf("invoices_id" to 2, "company_title" to "改过的抬头名"))
        assertEquals(id1, again.path("id").asLong(), "pending 态是 upsert，不是新插一行")
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders_invoice WHERE company_id = 1 AND order_id = ?", Int::class.java, p1), "同一单只一条申请")
        assertEquals("改过的抬头名", again.path("company_title").asText())
        assertEquals("91330100MA27XXXX0B", again.path("tax_number").asText(), "抬头带过来的税号仍在")

        // 被引用的抬头删不掉（历史发票不能变成没有抬头的发票）
        val g = expect("DELETE", "/api/h5app/wxapp/member/invoice/2", null, 422)
        assertEquals(listOf("已被发票申请引用"), g.path("errors").path("invoices_id").map { it.asText() })

        applyInvoice(p2, mapOf("invoice_type" to "individual", "company_title" to "个人抬头张三"))
        val id2 = jdbc.queryForObject("SELECT id FROM orders_invoice WHERE company_id = 1 AND order_id = ?", Long::class.java, p2)

        val list = okAdmin("/api/order/invoices")
        assertContract(list.path("list")[0], InvoiceContract.INVOICE_ADMIN_ROW, "INV-A1 行")
        assertEquals(2L, list.path("total_count").asLong())
        assertContract(okAdmin("/api/order/invoice/$id1"), InvoiceContract.INVOICE_ADMIN_ROW, "INV-A3 详情")
        // 受限账号：发票表没有店铺列，判据是"这会员在我名下下过单"；role 2 名下没单 ⇒ 列表空 + 详情 404
        assertEquals(0L, okShop("/api/order/invoices").path("total_count").asLong(), "distributor_ids 收窄")
        expect("GET", "/api/order/invoice/$id1", null, 404, token = shop)
        expect("PUT", "/api/order/invoice/$id1", mapOf("invoice_status" to "issued", "invoice_file_url" to "/f.pdf"), 403, token = shop)

        expect("PUT", "/api/order/invoice/$id1", mapOf("invoice_status" to "issued"), 422, token = admin)
        expect("PUT", "/api/order/invoice/$id1", mapOf("invoice_status" to "rejected"), 422, token = admin)
        okAdmin("/api/order/invoice/$id1", mapOf("invoice_status" to "issued", "email" to "x@y.z"), m = "PUT").let {
            assertContract(it, InvoiceContract.INVOICE_ADMIN_ROW, "INV-A2 出参")
            assertEquals("issued", it.path("invoice_status").asText())
            assertEquals("x@y.z", it.path("email").asText())
        }
        expect("PUT", "/api/order/invoice/$id1", mapOf("invoice_status" to "issued", "email" to "x@y.z"), 422, token = admin)
        expect("POST", "/api/h5app/wxapp/order/invoice/apply", mapOf("order_id" to p1, "email" to "a@b.c", "invoices_id" to 2), 422)
        val ok2 = okAdmin("/api/order/invoice/$id2", mapOf("invoice_status" to "rejected", "remark" to "抬头与订单主体不符"), m = "PUT")
        assertEquals("rejected", ok2.path("invoice_status").asText())
        assertEquals("抬头与订单主体不符", ok2.path("remark").asText())

        val mine = ok("/api/h5app/wxapp/order/invoice")
        assertEquals(2L, mine.path("total_count").asLong())
        assertContract(ok("/api/h5app/wxapp/order/invoice?invoice_status=issued").path("list")[0], InvoiceContract.INVOICE_APPLY_ROW, "INV-F2 状态筛")
        assertEquals(1L, ok("/api/h5app/wxapp/order/invoice?invoice_status=issued").path("total_count").asLong())
        assertEquals(id1, ok("/api/h5app/wxapp/order/invoice/$id1").path("id").asLong())
        expect("GET", "/api/h5app/wxapp/order/invoice/$id1", null, 404, token = other)
        expect("GET", "/api/h5app/wxapp/order/invoice/abc", null, 404)
        assertEquals("issued", jdbc.queryForObject("SELECT invoice_status FROM orders_normal_orders WHERE order_id = ?", String::class.java, p1.toLong()).orEmpty(), "INV-R2 订单侧状态同步（只同步状态，不回写快照）")
    }

    @Test @Order(13) fun `收尾 恒等式与权限面兜底`() {
        assertLedgerBalanced("全链收尾")
        assertTrue(pointLogsSum() > pointLogBase, "本批至少写过几条流水，否则上面那条恒等式是空的")
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM deposit_trade WHERE status = 'SUCCESS'", Int::class.java)!! >= 4)

        // 调账是唯一"无订单来源"的动账口：order_id 为空的行必须靠 remark 解释
        val noOrder = jdbc.queryForList(
            "SELECT remark FROM point_member_log WHERE company_id = 1 AND user_id = 1 AND order_id IS NULL",
        )
        assertTrue(noOrder.isNotEmpty() && noOrder.all { (it["remark"] as String?).isNullOrBlank().not() }, "人工调整要留得住事由")
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM point_member_log l WHERE l.order_id IS NOT NULL " +
                    "AND NOT EXISTS (SELECT 1 FROM orders_normal_orders o WHERE o.order_id = CAST(l.order_id AS BIGINT))",
                Int::class.java,
            ),
            "每条带订单号的流水都要指得回一张真单",
        )

        expect("GET", "/api/comments", null, 401, token = null)
        expect("GET", "/api/order/invoices", null, 401, token = null)
        expect("POST", "/api/comment/reply", mapOf("rate_id" to 1, "content" to "无令牌"), 401, token = null)
        assertEquals(0L, okShop("/api/point/members").path("member_count").asLong(), "会员是平台资产：名下没单的店一个会员都看不到")
        assertTrue(okAdmin("/api/point/members").path("member_count").asLong() >= 1)
    }
}

private fun assertFalseish(node: JsonNode) = assertTrue(node.asInt() == 0, "status 应为 0（下架）：$node")
