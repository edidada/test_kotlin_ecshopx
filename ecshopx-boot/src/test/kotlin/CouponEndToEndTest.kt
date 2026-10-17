package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.CouponContract
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.promotion.CouponStatus
import com.ecshopx.kotlin.service.promotion.CouponExpireJob
import com.ecshopx.kotlin.service.promotion.CouponService
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M1-3 #55 优惠券端到端（CP-A1..A8 + CP-F1..F4 + 生命周期 + 过期回收）。
 *
 * 断言重点不在"接口能通"，而在四条不变式：
 *  1. **不超发**：领量走 `send_num<total_num` 条件更新（并发那一路在 [CouponReceiveConcurrencyTest]）；
 *  2. **快照不回溯**：改模板面额之后，已发出去的券仍按领取时的面额抵扣（CP-A4 vs CP-F3）；
 *  3. **状态机四条边都是 CAS**：`UNUSED→LOCKED→USED`、`LOCKED→UNUSED`、`UNUSED→EXPIRED`，
 *     重复迁移拿 0 行，绝不二次改账（D23：源系统将 `freezeUserCard()` 留空，本项目补齐）；
 *  4. **门槛与算价同源**：CP-F4 的可用判定走 `CartService.quote`，与 ORDER-1/ORDER-2 同一个基数。
 *
 * 演示数据（sql/seed.sql）：券 1「满100减20」（`get_limit=2`、总量 100、range 长期窗）、
 * 券 2「新人10元无门槛券」（`get_limit=1`、`days` 30 天）；
 * 商品 3=每日坚果 8900 分（不足 100 元门槛）、商品 2=X1 白色 128G 199900 分，均属 `distributor_id=1`。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccoupon;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CouponEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var coupons: CouponService
    @Autowired lateinit var expireJob: CouponExpireJob

    private var admin = ""
    private var member = ""

    /** 本案现场建的券（不依赖 seed 的可变性） */
    private var tplId: Long = 0L
    private var tpl20000: Long = 0L
    private var sendable: Long = 0L
    /** 生命周期用例专用：`get_limit=0` 不限领，才能反复领同一张券 */
    private var lifeId: Long = 0L
    private var codeOfTpl = ""
    private var lockedCode = ""
    private var expiredCode = ""

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
            else -> delete(url)
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

    private fun couponBody(
        name: String,
        discount: Long,
        min: Long,
        total: Long,
        limit: Int,
        validType: String = "days",
        begin: Long = 0,
        end: Long = 0,
    ) = mapOf(
        "coupon_type" to "cash",
        "coupon_name" to name,
        "discount_fee" to discount,
        "min_order_fee" to min,
        "total_num" to total,
        "valid_type" to validType,
        "begin_time" to begin,
        "end_time" to end,
        "valid_days" to if (validType == "days") 30 else 0,
        "get_limit" to limit,
        "instruction" to "端到端用例券",
    )

    private fun sendNum(id: Long): Long =
        jdbc.queryForObject("SELECT send_num FROM promotions_coupon WHERE coupon_id = ?", Long::class.java, id) ?: -1L

    private fun statusOf(code: String): String =
        jdbc.queryForObject("SELECT status FROM promotions_coupon_user WHERE coupon_code = ?", String::class.java, code) ?: ""

    private fun orderIdOf(code: String): Long =
        jdbc.queryForObject("SELECT order_id FROM promotions_coupon_user WHERE coupon_code = ?", Long::class.java, code) ?: -1L

    // ===================== 后台面 =====================

    @Test @Order(1) fun `令牌就绪`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001",
            "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank())
    }

    @Test @Order(2) fun `CP-A1 建券 契约与校验`() {
        val created = adminOf("/api/coupons", couponBody("端到端20元券", 2000, 10000, 10, 2), "POST")
        assertContract(created, CouponContract.TEMPLATE_DETAIL, "CP-A1 建券出参")
        tplId = created.path("coupon_id").asLong()
        assertTrue(tplId > 0)
        assertEquals(0L, created.path("sent_num").asLong(), "新建券发放数为 0")
        assertEquals(true, created.path("status").asBoolean())
        assertEquals("领取后 30 天内有效", created.path("valid_text").asText())

        // 面额高于门槛 = 倒找钱；缺名 = 必填；days 不给天数 = 无效。三种都要 422 且 errors 形状不变
        val bad1 = expect("POST", "/api/coupons", admin, couponBody("倒找钱券", 3000, 1000, 10, 1), 400)
        assertEquals(listOf("面额不得高于使用门槛"), bad1.path("errors").path("discount_fee").map { it.asText() })
        val bad2 = expect("POST", "/api/coupons", admin, couponBody("", 100, 0, 10, 1), 400)
        assertTrue(bad2.path("errors").path("coupon_name").isArray)
        val bad3 = expect("POST", "/api/coupons", admin, couponBody("没有天数", 100, 0, 10, 1, "week"), 400)
        assertEquals(listOf("只支持 range / days"), bad3.path("errors").path("valid_type").map { it.asText() })

        // range 形态：起止必须成对且有序
        val now = TimeSec.now()
        val ranged = adminOf("/api/coupons", couponBody("端到端200元券", 20000, 100000, 5, 1, "range", now - 3600, now + 86400), "POST")
        tpl20000 = ranged.path("coupon_id").asLong()
        assertTrue(ranged.path("valid_text").asText().contains("至"))
        expect("POST", "/api/coupons", admin, couponBody("窗坏券", 100, 0, 10, 1, "range", now, now - 1), 400)

        // 生命周期/过期用例专用券：不限领，才能在同一案里反复领取与回退
        lifeId = adminOf("/api/coupons", couponBody("端到端无限领券", 2000, 10000, 20, 0), "POST").path("coupon_id").asLong()
    }

    @Test @Order(3) fun `CP-A2 A3 A4 A5 列表 详情 编辑 停发`() {
        val list = adminOf("/api/coupons?coupon_name=端到端&page=1&pageSize=20")
        assertContract(list.path("list")[0], CouponContract.TEMPLATE_ROW, "CP-A2 券模板行")
        assertTrue(list.path("total_count").asLong() >= 2, "两张现场券都在列表里")
        assertTrue(list.path("list").none { it.path("coupon_name").asText().contains("满100减20") }, "名称过滤生效")

        val detail = adminOf("/api/coupons/$tplId")
        assertEquals("端到端20元券", detail.path("coupon_name").asText())
        assertEquals(0L, detail.path("used").asLong())

        // 编辑：改名与加量（total 10→12）
        val edited = adminOf("/api/coupons/$tplId", couponBody("端到端20元券(改)", 2000, 10000, 12, 2), "PUT")
        assertEquals("端到端20元券(改)", edited.path("coupon_name").asText())
        assertEquals(12L, edited.path("total_num").asLong())

        // 停发/恢复（status 布尔）
        assertEquals(false, adminOf("/api/coupons/$tplId/status", mapOf("status" to false), "PUT").path("status").asBoolean())
        assertEquals(true, adminOf("/api/coupons/$tplId/status", mapOf("status" to true), "PUT").path("status").asBoolean())
        expect("PUT", "/api/coupons/$tplId/status", admin, emptyMap(), 400)
        expect("GET", "/api/coupons/999999", admin, null, 404)
    }

    @Test @Order(4) fun `CP-A6 未发可删 已发只能停发`() {
        val fresh = adminOf("/api/coupons", couponBody("待删除券", 500, 0, 10, 1), "POST").path("coupon_id").asLong()
        assertTrue(fresh > 0)
        val out = adminOf("/api/coupons/$fresh", null, "DELETE")
        assertEquals(fresh, out.path("coupon_id").asLong())
        assertEquals(true, out.path("deleted").asBoolean())
        expect("GET", "/api/coupons/$fresh", admin, null, 404)

        sendable = adminOf("/api/coupons", couponBody("已发过的券", 500, 0, 10, 1), "POST").path("coupon_id").asLong()
        val sent = adminOf("/api/coupons/send", mapOf("coupon_id" to sendable, "user_ids" to listOf(1)), "POST")
        assertEquals(1, sent.path("sent_num").asInt(), "点名发一张")
        val tooMany = expect("DELETE", "/api/coupons/$sendable", admin, null, 422)
        assertTrue(tooMany.path("message").asText().contains("不能删除"))
    }

    // ===================== 会员面 =====================

    @Test @Order(5) fun `CP-F1 F2 领取与限领`() {
        val page = memberOf("/api/h5app/wxapp/coupons?open_only=true&page=1&pageSize=50")
        val row = page.path("list").firstOrNull { it.path("coupon_id").asLong() == tplId }
        assertNotNull(row, "现场建的券出现在可领列表里")
        assertContract(row, CouponContract.OPEN_ROW, "CP-F1 可领券行")
        assertEquals(true, row.path("can_receive").asBoolean(), row.path("reason").asText())
        assertEquals(0L, row.path("received_num").asLong())

        val got = memberOf("/api/h5app/wxapp/coupons/receive", mapOf("coupon_id" to tplId), "POST")
        assertContract(got, CouponContract.RECEIVE, "CP-F2 领取出参")
        codeOfTpl = got.path("coupon_code").asText()
        assertTrue(codeOfTpl.startsWith("C"), "券码走号段前缀：$codeOfTpl")
        assertEquals(CouponStatus.UNUSED, got.path("status").asText())
        assertTrue(got.path("expire_time").asLong() > TimeSec.now(), "days 型从领取时刻起算")
        assertEquals(1L, sendNum(tplId), "抢量 +1")

        // 第二张（get_limit=2）允许，第三张拒绝
        memberOf("/api/h5app/wxapp/coupons/receive", mapOf("coupon_id" to tplId), "POST")
        assertEquals(2L, sendNum(tplId))
        val third = expect("POST", "/api/h5app/wxapp/coupons/receive", member, mapOf("coupon_id" to tplId), 422)
        assertTrue(third.path("message").asText().contains("每人限领 2 张"), third.path("message").asText())
        // 限领拒绝不能吃掉库存：抢到量没落券就是凭空少一张
        assertEquals(2L, sendNum(tplId), "被拒的第三次领取没有增加发放数")

        // 列表上同步反映限领口径（同一份判据：can_receive/reason 与领取报错不会漂）
        val again = memberOf("/api/h5app/wxapp/coupons?open_only=true&page=1&pageSize=50")
            .path("list").first { it.path("coupon_id").asLong() == tplId }
        assertEquals(false, again.path("can_receive").asBoolean())
        assertTrue(again.path("reason").asText().contains("每人限领 2 张"))

        // 停发中的券领不了（CP-F1 列表也不再展示，open_only）
        adminOf("/api/coupons/$tpl20000/status", mapOf("status" to false), "PUT")
        val stopped = expect("POST", "/api/h5app/wxapp/coupons/receive", member, mapOf("coupon_id" to tpl20000), 422)
        assertTrue(stopped.path("message").asText().contains("停止发放"))
        adminOf("/api/coupons/$tpl20000/status", mapOf("status" to true), "PUT")
        expect("POST", "/api/h5app/wxapp/coupons/receive", member, mapOf("coupon_id" to 0), 400)
    }

    @Test @Order(6) fun `CP-F3 我的券 分桶 + CP-A4 面额不回溯`() {
        val mine = memberOf("/api/h5app/wxapp/coupons/my?status=UNUSED&page=1&pageSize=50")
        assertContract(mine.path("list")[0], CouponContract.MY_ROW, "CP-F3 我的券行")
        val before = mine.path("list").first { it.path("coupon_code").asText() == codeOfTpl }
        assertEquals(2000L, before.path("discount_fee").asLong())
        assertEquals(2, mine.path("list").count { it.path("coupon_id").asLong() == tplId }, "这一张券领了两份")
        // 「全部」桶不含 LOCKED（在途单占着的券不露面，回退后自己回来），这里此刻还没有锁定行
        assertEquals(mine.path("total_count").asLong(), memberOf("/api/h5app/wxapp/coupons/my?page=1&pageSize=50").path("total_count").asLong())

        // 改模板面额 → 已发的券不变（快照列）
        adminOf("/api/coupons/$tplId", couponBody("端到端20元券(改面额)", 5000, 10000, 12, 2), "PUT")
        val after = memberOf("/api/h5app/wxapp/coupons/my?status=UNUSED&page=1&pageSize=50")
            .path("list").first { it.path("coupon_code").asText() == codeOfTpl }
        assertEquals(2000L, after.path("discount_fee").asLong(), "面额改动不回溯已发出的券")
        assertEquals(5000L, adminOf("/api/coupons/$tplId").path("discount_fee").asLong(), "模板本身是新面额")

        // 状态校验：非法 status 直接 422，不静默当"全部"
        expect("GET", "/api/h5app/wxapp/coupons/my?status=BOGUS", member, null, 400)
    }

    @Test @Order(7) fun `CP-F4 门槛与多店铺 与算价同源`() {
        // 单件 8900 分：不足 100 元门槛 → 券进 unusable 且给出人话原因
        val small = memberOf("/api/h5app/wxapp/coupons/usable?cart_type=fastbuy&items=3:1")
        assertContract(small, CouponContract.USABLE_ENVELOPE, "CP-F4 出参外层")
        assertEquals(1, small.path("shop_count").asInt())
        assertEquals(8900L, small.path("cart_total_price").asLong())
        val blocked = small.path("unusable").filter { it.path("coupon_code").asText() == codeOfTpl }
        assertEquals(1, blocked.size, "门槛不足的券在 unusable 里")
        assertTrue(blocked[0].path("reason").asText().contains("满 100 元可用"), blocked[0].path("reason").asText())
        assertEquals(0, small.path("usable").filter { it.path("coupon_code").asText() == codeOfTpl }.size)

        // seed 的无门槛券（券 2，days 型）此时还没领；先领一张无门槛券再验抵扣
        val newcomer = memberOf("/api/h5app/wxapp/coupons/receive", mapOf("coupon_id" to 2), "POST")
        val free = newcomer.path("coupon_code").asText()
        val withFree = memberOf("/api/h5app/wxapp/coupons/usable?cart_type=fastbuy&items=3:1")
        val u = withFree.path("usable").first { it.path("coupon_code").asText() == free }
        assertContract(u, CouponContract.USABLE_ROW, "CP-F4 usable 行")
        assertEquals(1000L, u.path("deduct_fee").asLong())
        assertEquals(0L, u.path("threshold_fee").asLong())

        // 199900 分组：门槛满足，抵扣 = min(面额, 组应付) = 2000
        val big = memberOf("/api/h5app/wxapp/coupons/usable?cart_type=fastbuy&items=2:1")
        assertEquals(199900L, big.path("cart_total_price").asLong())
        val hit = big.path("usable").first { it.path("coupon_code").asText() == codeOfTpl }
        assertEquals(2000L, hit.path("deduct_fee").asLong())

        // 多店铺：M0-6 一单一店，券在这里就提前判不可用，而不是等提交吃 422
        jdbc.update(
            "INSERT INTO items (item_id, company_id, goods_id, default_item_id, is_default, item_name, item_bn, " +
                "price, cost_price, market_price, store, sales, approve_status, audit_status, templates_id, item_type, distributor_id, created, updated) " +
                "VALUES (901, 1, 901, 901, TRUE, '旗舰店测试品', 'SKU-901', 5000, 1000, 6000, 50, 0, 'onsale', 'approved', 1, 'normal', 2, 0, 0)",
        )
        val multi = memberOf("/api/h5app/wxapp/coupons/usable?cart_type=fastbuy&items=2:1,901:1")
        assertEquals(2, multi.path("shop_count").asInt(), "两个店铺分组")
        assertEquals(0, multi.path("usable").size())
        assertTrue(multi.path("unusable").all { it.path("reason").asText().contains("只支持单店铺") }, "全部券给多店铺原因（§14.5：券只在单店铺组生效）")
        assertEquals(0L, multi.path("cart_total_price").asLong(), "多店时不给单组应付，避免误当基数")

        // 空车：没有可抵扣金额，券一律不可用
        val empty = memberOf("/api/h5app/wxapp/coupons/usable?cart_type=fastbuy")
        assertEquals(0L, empty.path("cart_total_price").asLong())
        assertTrue(empty.path("usable").isEmpty())
        assertTrue(empty.path("unusable").all { it.path("reason").asText().contains("没有可抵扣") })

        // 未登录 → 401 Unauthenticated.
        val anon = expect("GET", "/api/h5app/wxapp/coupons/usable", null, null, 401)
        assertEquals("Unauthenticated.", anon.path("message").asText())
    }

    // ===================== 生命周期 =====================

    @Test @Order(8) fun `锁 核销 回退 都是 CAS 且重复调用不改账`() {
        val code = coupons.receive(1L, 1L, lifeId)["coupon_code"] as String
        val c = coupons.byCode(code, 1L)!!
        assertEquals(CouponStatus.UNUSED, c.status)

        // 下单锁：同一张券不能同时挂两笔单（D23）
        assertTrue(coupons.lock(code, 1L, 1L, 111L, TimeSec.now()), "第一次锁成功")
        lockedCode = code
        assertEquals(CouponStatus.LOCKED, statusOf(code))
        assertEquals(111L, orderIdOf(code), "锁定要记住占它的单")
        assertTrue(!coupons.lock(code, 1L, 1L, 222L, TimeSec.now()), "第二笔单锁不上同一张券")
        assertEquals(111L, orderIdOf(code), "锁失败不许改写归属")
        // 别人的券码：DAO 层就是 0 行，不泄露券码是否存在
        assertTrue(!coupons.lock(code, 1L, 2L, 333L, TimeSec.now()))

        // 结清核销：只有 LOCKED→USED；重复回调第二次 0 行
        assertEquals(1, coupons.consume(111L, TimeSec.now()))
        assertEquals(CouponStatus.USED, statusOf(code))
        assertEquals(0, coupons.consume(111L, TimeSec.now()), "重复核销不再改账")
        assertEquals(0, coupons.release(111L), "已核销的券不能被回退")
        assertEquals(CouponStatus.USED, statusOf(code))

        // 取消/关单回退：LOCKED→UNUSED，回退后能重新下单
        val code2 = coupons.receive(1L, 1L, lifeId)["coupon_code"] as String
        assertTrue(coupons.lock(code2, 1L, 1L, 444L, TimeSec.now()))
        assertEquals(1, coupons.release(444L))
        assertEquals(CouponStatus.UNUSED, statusOf(code2))
        assertEquals(0, coupons.release(444L), "重复回退拿 0 行")
        assertTrue(coupons.lock(code2, 1L, 1L, 555L, TimeSec.now()), "回退后券可再次占用")
        assertEquals(1, coupons.unlocked(555L).size, "本单锁住的券查得回")
        coupons.release(555L)

        // 越权：用别人的 user_id 定位自己的券码 → 视为不存在
        assertEquals(null, coupons.byCodeAndUser(code2, 1L, 2L))
        assertEquals(1L, coupons.requireMine(code2, 1L, 1L).userId)
    }

    @Test @Order(9) fun `CouponExpireJob 只回收 UNUSED 且 CP-F3 的 EXPIRED 桶不等 Job`() {
        val now = TimeSec.now()
        val expCode = coupons.receive(1L, 1L, lifeId)["coupon_code"] as String
        expiredCode = expCode
        jdbc.update("UPDATE promotions_coupon_user SET expire_time = ? WHERE coupon_code = ?", now - 10, expCode)

        // 未回收也算过期（算价侧看 expire_time，不等调度）
        val expiredBefore = memberOf("/api/h5app/wxapp/coupons/my?status=EXPIRED&page=1&pageSize=50")
            .path("list").first { it.path("coupon_code").asText() == expCode }
        assertEquals(CouponStatus.UNUSED, expiredBefore.path("status").asText(), "Job 没跑时列值仍是 UNUSED")
        assertEquals(0, memberOf("/api/h5app/wxapp/coupons/usable?cart_type=fastbuy&items=1:1")
            .path("usable").filter { it.path("coupon_code").asText() == expCode }.size, "过期券不可用")
        assertTrue(memberOf("/api/h5app/wxapp/coupons/usable?cart_type=fastbuy&items=1:1")
            .path("unusable").any { it.path("coupon_code").asText() == expCode && it.path("reason").asText().contains("已过期") },
            "过期券给原因")

        // LOCKED 的行即便到期也不动：它在订单在途期间归属那笔单，回退由关单链负责
        val lockCode = coupons.receive(1L, 1L, lifeId)["coupon_code"] as String
        assertTrue(coupons.lock(lockCode, 1L, 1L, 777L, now))
        jdbc.update("UPDATE promotions_coupon_user SET expire_time = ? WHERE coupon_code = ?", now - 5, lockCode)

        val pending = expireJob.pending(now)
        assertTrue(pending >= 1, "待回收计数：$pending")
        val n = expireJob.runNow(now)
        assertTrue(n >= 1, "本轮回收 $n 张")
        assertEquals(CouponStatus.EXPIRED, statusOf(expCode))
        assertEquals(CouponStatus.LOCKED, statusOf(lockCode), "在途锁定的券不被回收")
        assertEquals(0, expireJob.runNow(now), "已回收的券不重复回收")

        // 回收后 EXPIRED 桶与列值一致；USED 与 LOCKED 的行都不在回收面里
        val after = memberOf("/api/h5app/wxapp/coupons/my?status=EXPIRED&page=1&pageSize=50")
        assertTrue(after.path("list").all { it.path("status").asText() == CouponStatus.EXPIRED })
        coupons.release(777L)
    }

    // ===================== 后台记录与发券 =====================

    @Test @Order(10) fun `CP-A7 记录 CP-A8 点名与按等级发券`() {
        val rec = adminOf("/api/coupons/$tplId/records?user_id=1&page=1&pageSize=50")
        assertContract(rec.path("list")[0], CouponContract.RECORD_ROW, "CP-A7 记录行")
        assertEquals("张三", rec.path("list")[0].path("user_nick").asText(), "昵称取 members_info.name")
        assertTrue(rec.path("total_count").asLong() >= 2)
        assertTrue(rec.path("list").none { it.path("user_id").asLong() != 1L }, "user_id 过滤")
        val usedOnly = adminOf("/api/coupons/$tplId/records?status=USED")
        assertTrue(usedOnly.path("list").all { it.path("status").asText() == CouponStatus.USED })
        expect("GET", "/api/coupons/999999/records", admin, null, 404)

        val out = adminOf(
            "/api/coupons/send",
            mapOf("coupon_id" to sendable, "user_ids" to listOf(1, 2, 999)),
            "POST",
        )
        assertContract(out, CouponContract.SEND, "CP-A8 发券出参")
        assertEquals(3, out.path("total").asInt())
        assertEquals(2, out.path("sent_num").asInt(), "不存在的会员 999 单独失败，不牵连同批")
        assertEquals(999L, out.path("failed")[0].path("user_id").asLong())
        assertTrue(out.path("failed")[0].path("reason").asText().isNotBlank())

        // 按等级发：grade 3 只有 user 2
        val byGrade = adminOf("/api/coupons/send", mapOf("coupon_id" to sendable, "grade_id" to 3), "POST")
        assertEquals(1, byGrade.path("sent_num").asInt(), "该等级只有 1 名在籍会员")
        assertEquals(2L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM promotions_coupon_user WHERE coupon_id = ? AND user_id = 2", Long::class.java, sendable,
        ))

        // 只给 grade_id 时读定向活动的等级→券映射；seed 没配映射 → 明确报"该等级没有配券"
        val none = expect("POST", "/api/coupons/send", admin, mapOf("grade_id" to 3), 422)
        assertTrue(none.path("message").asText().contains("没有配券"), none.path("message").asText())
        expect("POST", "/api/coupons/send", admin, emptyMap(), 400)

        // 发券同样吃总量守卫：券只剩 1 张时发 3 人，第 2、3 人失败并给原因
        val scarce = adminOf("/api/coupons", couponBody("稀缺券", 300, 0, 1, 0), "POST").path("coupon_id").asLong()
        val r = adminOf("/api/coupons/send", mapOf("coupon_id" to scarce, "user_ids" to listOf(1, 2)), "POST")
        assertEquals(1, r.path("sent_num").asInt())
        assertEquals(1, r.path("failed").size())
        assertEquals(1L, sendNum(scarce), "发不出去的没有吃掉库存")
    }

    // ===================== 权限面 =====================

    @Test @Order(11) fun `后台权限码与租户隔离`() {
        val viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        // viewer 只有商品面权限：券的三类权限码都没有
        expect("GET", "/api/coupons", viewer, null, 403)
        expect("POST", "/api/coupons", viewer, couponBody("越权券", 100, 0, 10, 1), 403)
        expect("POST", "/api/coupons/send", viewer, mapOf("coupon_id" to sendable, "user_ids" to listOf(1)), 403)

        // 跨租户：company_id=2 的令牌看不到 company 1 的券（券是租户资产）
        val other = jdbc.queryForObject("SELECT COUNT(*) FROM promotions_coupon WHERE company_id = 2", Long::class.java) ?: 0L
        assertEquals(0L, other, "seed 没有第二租户券，模板查询天然隔离")
        assertEquals(0, coupons.available(2L, 1L, TimeSec.now()).size, "会员在别的租户名下没有券")
    }

    /** 券实例归属：会员 1 领的券，user_id=2 既查不到也锁不上（CP-F2/CP-F3 全靠令牌身份） */
    @Test @Order(12) fun `券码不泄露归属`() {
        val asOther = expect("GET", "/api/h5app/wxapp/coupons/my?status=UNUSED", member, null, 200)
        assertTrue(asOther.path("list").none { it.path("coupon_code").asText() == lockedCode })
        val outsider = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "member2").path("token").asText()
        val mine2 = data(call("GET", "/api/h5app/wxapp/coupons/my", outsider, null), "member2 my")
        assertTrue(mine2.path("list").none { it.path("coupon_code").asText() == codeOfTpl }, "别人的券不在我的列表里")
        val usable = data(call("GET", "/api/h5app/wxapp/coupons/usable?cart_type=fastbuy&items=2:1", outsider, null), "member2 usable")
        assertTrue(usable.path("usable").none { it.path("coupon_code").asText() == codeOfTpl })
    }
}
