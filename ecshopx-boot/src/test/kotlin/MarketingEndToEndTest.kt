package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.MarketingContract
import com.ecshopx.kotlin.common.domain.TimeSec
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
 * M1-3 #56 满减满折 + 券/积分挂进算价链的端到端（MK-A1..A7 + MK-F1/F2/F3 + docs/04 §14.5 链路）。
 *
 * 断言的重点不是"接口能通"，而是 §14.5 立的那几条口径：
 *  1. **审核是唯一生效开关**：`non-reviewed` 的活动在算价链里等于不存在，编辑必打回重审；
 *  2. **基数是"折让之后的应付"**：等级折 → 满减 → 券 → 积分逐级往下比，所以券的门槛比的是满减之后的钱；
 *  3. **一店一活动择优**：同一组内多个活动只取折让最大的一个（同额按 `marketing_id` 升序定序），
 *     上不封顶再乘倍数，且折让永不超过本组应付；
 *  4. **三类抵扣都逐行分摊**：`Σ行 total_fee + 运费 == 单 total_fee`，售后按件分摊才不会重复退钱；
 *  5. **预览==下单**：ORDER-2 的重算与 ORDER-1 同一个 quote，落库金额、`orders_rel_promotions` 快照、
 *     券的 `LOCKED` 与扣分三件事必须与预览逐分一致，取消则整条回退。
 *
 * 演示数据：商品 2=199900 分、3=8900 分（均属 `distributor_id=1`、模板 1 全国包邮）、
 * 902 现场插入走模板 2（按件 8 元）；seed 的两条营销活动（1 已结束 / 2 未开始）**永远不进算价**，
 * 本案自己配的活动跑完即删（MK-A7），收尾一条断言就是"删干净之后全场折让回 0"。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccmark;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MarketingEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var admin = ""
    private var member = ""
    private var viewer = ""

    /** A 全场满100减20减50 / B 每满100减20（上不封顶）/ G 满300减10（待审核）/ D 满2件减30 / E 满100打9折 / F 满减包邮 */
    private var actA = 0L
    private var actB = 0L
    private var actD = 0L
    private var actE = 0L
    private var actF = 0L
    private var actG = 0L
    private var couponX = 0L
    private var codeX = ""
    private var code1 = ""
    private var code2 = ""

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

    // ===== 造数与取价助手 =====

    /** 阶梯档位：金额一律写成 PHP 那种"元字符串"，件数门槛写整数 */
    private fun step(full: Any, minus: String? = null, discount: Int? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "full" to full.toString(),
            "minus" to minus,
            "discount" to discount,
        ).filterValues { it != null }

    private fun mkBody(
        name: String,
        steps: List<Map<String, Any?>>,
        type: String = "full_minus",
        bound: Int = 0,
        quantity: Boolean = false,
        repeat: Boolean = false,
        freePostage: Boolean = false,
        tag: String? = "满减",
        status: Boolean = true,
    ) = linkedMapOf<String, Any?>(
        "marketing_type" to type,
        "marketing_name" to name,
        "ad_pic" to "/img/mk.jpg",
        "start_time" to TimeSec.now() - 3600,
        "end_time" to TimeSec.now() + 86400,
        "use_bound" to bound,
        "condition_type" to if (quantity) "quantity" else "totalfee",
        "condition_value" to steps,
        "canjoin_repeat" to repeat,
        "join_limit" to 0,
        "free_postage" to freePostage,
        "promotion_tag" to tag,
        "status" to status,
    )

    private fun create(body: Map<String, Any?>): Long =
        adminOf("/api/marketing/activities", body, "POST").path("marketing_id").asLong()

    private fun review(id: Long): JsonNode =
        adminOf("/api/marketing/activities/$id/check", mapOf("check_status" to "reviewed"), "PUT")

    private fun bind(id: Long, vararg itemIds: Long): JsonNode = adminOf(
        "/api/marketing/activities/$id/items",
        mapOf("items" to itemIds.map { linkedMapOf<String, Any?>("item_id" to it, "goods_id" to it, "item_name" to "圈品$it", "price" to 8900) }),
        "PUT",
    )

    /** ORDER-1 预览：fastbuy 临时行，`items` 写 "item_id:num,..."（券码字段名是 `coupon_code`） */
    private fun preview(items: String, coupon: String? = null, point: Boolean = false): JsonNode =
        memberOf(
            "/api/h5app/wxapp/getFreightFee",
            linkedMapOf<String, Any?>(
                "cart_type" to "fastbuy",
                "items" to parseItems(items),
                "point_use" to point,
                "coupon_code" to coupon,
            ).filterValues { it != null },
            "POST",
        )

    private fun parseItems(items: String): List<Map<String, Long>> =
        items.split(",").filter { it.isNotBlank() }.map { seg ->
            val s = seg.split(":")
            mapOf("item_id" to s[0].toLong(), "num" to s[1].toLong())
        }

    /** ORDER-2 下单：与 [preview] 同一份行与券，收货信息齐全（fastbuy 不落购物车行） */
    private fun orderBody(items: String, coupon: String? = null, point: Boolean = false): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "cart_type" to "fastbuy",
            "order_type" to "normal",
            "items" to parseItems(items),
            "receiver_name" to "张三",
            "receiver_mobile" to "13800000001",
            "receiver_state" to "浙江省",
            "receiver_city" to "杭州市",
            "receiver_district" to "西湖区",
            "receiver_address" to "文三路 1 号",
            "point_use" to point,
            "coupon_discount" to coupon,
            "order_from" to "h5",
        ).filterValues { it != null }

    private fun order(items: String, coupon: String? = null, point: Boolean = false, token: String? = null): JsonNode =
        data(call("POST", "/api/h5app/wxapp/order_new", token ?: member, orderBody(items, coupon, point)), "ORDER-2 $items")

    /** §14.5 守恒式：三类抵扣都逐行分摊，Σ行应付 + 运费 = 单应付 */
    private fun assertConservation(p: JsonNode, label: String) {
        val lines = p.path("items")
        assertTrue(lines.size() > 0, "$label：预览至少要有一行")
        assertEquals(
            p.path("total_fee").asLong() - p.path("freight_fee").asLong(),
            lines.sumOf { it.path("total_fee").asLong() },
            "$label：Σ行应付 + 运费 = 单应付",
        )
        assertEquals(p.path("discount_fee").asLong(), lines.sumOf { it.path("discount_fee").asLong() }, "$label：活动折让逐行守恒")
        assertEquals(p.path("coupon_discount").asLong(), lines.sumOf { it.path("coupon_fee").asLong() }, "$label：券抵扣逐行守恒")
        assertEquals(p.path("point_fee").asLong(), lines.sumOf { it.path("point_fee").asLong() }, "$label：积分抵扣逐行守恒")
        assertTrue(lines.all { it.path("total_fee").asLong() >= 0 }, "$label：行应付不得为负")
    }

    private fun longOf(sql: String, vararg v: Any): Long = jdbc.queryForObject(sql, Long::class.java, *v) ?: -1L

    private fun strOf(sql: String, vararg v: Any): String = jdbc.queryForObject(sql, String::class.java, *v) ?: ""

    private fun point(): Long = longOf("SELECT point FROM point_member WHERE company_id = 1 AND user_id = 1")

    private fun setPoint(v: Long) {
        jdbc.update("UPDATE point_member SET point = ? WHERE company_id = 1 AND user_id = 1", v)
    }

    private fun ratio(v: String) {
        jdbc.update("UPDATE sys_config SET config_value = ? WHERE company_id = 1 AND config_key = 'point.deduct.max_ratio'", v)
    }

    private fun statusOf(code: String): String =
        strOf("SELECT status FROM promotions_coupon_user WHERE coupon_code = ?", code)

    // ===================== 后台配置面 =====================

    @Test @Order(1) fun `令牌就绪`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001",
            "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank() && viewer.isNotBlank())
    }

    @Test @Order(2) fun `MK-A1 建活动 契约与配置校验`() {
        val a = adminOf(
            "/api/marketing/activities",
            mkBody("端到端满100减20减50", listOf(step("100.00", minus = "20.00"), step("200.00", minus = "50.00"))),
            "POST",
        )
        assertContract(a, MarketingContract.ACTIVITY_DETAIL, "MK-A1/A3 活动行")
        actA = a.path("marketing_id").asLong()
        assertTrue(actA > 0)
        assertEquals("non-reviewed", a.path("check_status").asText(), "新建活动默认未审核")
        assertEquals("doing", a.path("date_status").asText(), "时间窗派生，不落列")
        assertEquals(0, a.path("items").size(), "全场活动没有圈品行")
        // 元 → 分只在解析处发生一次：库内仍是 PHP 形态，出参给换算后的阶梯
        assertEquals(2, a.path("steps").size())
        assertContract(a.path("steps")[0], MarketingContract.STEP_ROW, "steps 档位行")
        assertEquals(10000L, a.path("steps")[0].path("full").asLong())
        assertEquals(2000L, a.path("steps")[0].path("minus").asLong())
        assertEquals("满100元减20", a.path("steps")[0].path("rule").asText())
        assertEquals(20000L, a.path("steps")[1].path("full").asLong(), "按 full 升序")
        assertTrue(a.path("condition_value").asText().contains("100.00"), "入库文本保持元为单位的 PHP 形态")

        // 配置台的拒绝全部是 400（表单不合法），业务拒绝才是 422
        val badType = expect("POST", "/api/marketing/activities", admin, mkBody("不认识类型", listOf(step("100.00", minus = "1.00")), type = "bargain"), 400)
        assertTrue(badType.path("errors").path("marketing_type")[0].asText().contains("只支持"), badType.toString())
        val badName = expect("POST", "/api/marketing/activities", admin, mkBody("", listOf(step("100.00", minus = "1.00"))), 400)
        assertTrue(badName.path("errors").path("marketing_name").isArray)
        val badWindow = expect("POST", "/api/marketing/activities", admin,
            mkBody("窗口反了", listOf(step("100.00", minus = "1.00")))
                .let { it + mapOf("start_time" to TimeSec.now() + 86400, "end_time" to TimeSec.now()) }, 400)
        assertTrue(badWindow.path("errors").path("end_time").isArray)
        val badBound = expect("POST", "/api/marketing/activities", admin, mkBody("按分类圈品", listOf(step("100.00", minus = "1.00")), bound = 2), 400)
        assertEquals(listOf("只支持 0 全场 / 1 指定商品（按分类属 P2）"), badBound.path("errors").path("use_bound").map { it.asText() })
        val noRule = expect("POST", "/api/marketing/activities", admin, mkBody("没配阶梯", emptyList()), 400)
        assertEquals(listOf("满减/满折必须配置阶梯规则"), noRule.path("errors").path("condition_value").map { it.asText() })
        // 解析完一档都不合法 = 这条活动永远不会减钱，配置台直接拒（存量脏行另说，见 Order 14）
        val allBad = expect("POST", "/api/marketing/activities", admin,
            mkBody("全是废档", listOf(step("0", minus = "5.00"), step("100.00", minus = "200.00"), step("50.00", discount = 0))), 400)
        assertEquals(listOf("至少要有一档合法规则"), allBad.path("errors").path("condition_value").map { it.asText() })

        // B（上不封顶）与 G（待审核）在这里建好，非 reviewed 期间不会干扰任何算价断言
        actB = create(mkBody("端到端每满100减20", listOf(step("100.00", minus = "20.00")), repeat = true))
        actG = create(mkBody("端到端待审核活动", listOf(step("300.00", minus = "10.00"))))
    }

    @Test @Order(3) fun `MK-A2 A3 列表过滤 详情 404`() {
        val all = adminOf("/api/marketing/activities?marketing_name=端到端&page=1&pageSize=50")
        assertContract(all.path("list")[0], MarketingContract.ACTIVITY_ROW, "MK-A2 活动行")
        assertEquals(3L, all.path("total_count").asLong(), "本案三条活动都在名称过滤结果里")
        assertTrue(all.path("list").none { it.path("marketing_name").asText().contains("双11") }, "名称过滤掉了 seed 活动")

        // 类型过滤：此刻本案还没有满折活动（E 要到 Order 6 才建）
        val byType = adminOf("/api/marketing/activities?marketing_type=full_discount&pageSize=100")
        assertTrue(byType.path("list").none { it.path("marketing_name").asText().contains("端到端") }, "类型过滤")
        assertTrue(byType.path("list").all { it.path("marketing_type").asText() == "full_discount" })

        assertTrue(adminOf("/api/marketing/activities?check_status=non-reviewed&pageSize=100").path("list")
            .all { it.path("check_status").asText() == "non-reviewed" }, "check_status 过滤")
        assertTrue(adminOf("/api/marketing/activities?date_status=doing&pageSize=100").path("list")
            .all { it.path("date_status").asText() == "doing" }, "date_status 过滤")
        assertTrue(adminOf("/api/marketing/activities?date_status=end&pageSize=100").path("list")
            .none { it.path("marketing_id").asLong() == actA }, "已结束过滤不含本案在窗活动")

        // 停用过滤：建一条 status=false 的活动，它既进不了 status=true 的列表也只出现在 status=false 里
        val off = create(mkBody("端到端停用活动", listOf(step("100.00", minus = "1.00")), status = false))
        val disabled = adminOf("/api/marketing/activities?status=false&pageSize=100")
        assertTrue(disabled.path("list").all { !it.path("status").asBoolean() }, "status=false 过滤")
        assertEquals(1L, disabled.path("total_count").asLong(), "本案只有这一条停用活动")
        assertTrue(adminOf("/api/marketing/activities?pageSize=100").path("list")
            .any { it.path("marketing_id").asLong() == off }, "不给 status 参数时停用活动照样在配置台可见（运营要能重新启用它）")
        adminOf("/api/marketing/activities/$off", null, "DELETE")

        val d = adminOf("/api/marketing/activities/$actA")
        assertEquals("端到端满100减20减50", d.path("marketing_name").asText())
        assertEquals(0, d.path("item_count").asInt())
        expect("GET", "/api/marketing/activities/999999", admin, null, 404)
        expect("GET", "/api/marketing/activities/abc", admin, null, 404)
    }

    @Test @Order(4) fun `MK-A6 审核闸门 与 MK-A4 编辑必重审`() {
        // 未审核 = 算价链里不存在（前台同样看不见）
        assertEquals(0L, preview("2:1").path("discount_fee").asLong(), "non-reviewed 不参与算价")
        expect("GET", "/api/h5app/wxapp/marketing/activities/$actA", member, null, 404)

        val checked = review(actA)
        assertEquals("reviewed", checked.path("check_status").asText())
        val p = preview("2:1")
        assertEquals(5000L, p.path("discount_fee").asLong(), "199900 分命中满200减50（最高档）")
        assertEquals(194900L, p.path("total_fee").asLong(), "模板 1 全国包邮：应付只有商品钱")
        assertEquals(5000L, p.path("goods_discount").asLong(), "goods_discount=活动折让（普通会员无等级折）")
        assertEquals(0L, p.path("member_discount").asLong(), "普通会员无等级折")
        assertContract(p, MarketingContract.PREVIEW_MARKETING, "ORDER-1 M1-3 增量字段")
        assertConservation(p, "审核后满减")
        val ua = p.path("discount_info")
        assertEquals(1, ua.size())
        assertContract(ua[0], MarketingContract.DISCOUNT_INFO, "discount_info 项")
        assertEquals("full_minus", ua[0].path("type").asText())
        assertEquals("满200元减50", ua[0].path("rule").asText())
        // CART-2 的行上活动痕迹：结算页要能说清"这一行的便宜是谁给的"（ORDER-1 的行只有金额）
        assertContract(memberOf("/api/h5app/wxapp/cart?cart_type=fastbuy&items=2:1")
            .path("valid_cart")[0].path("list")[0].path("activity_info")[0], MarketingContract.LINE_ACTIVITY, "行上活动痕迹")

        // 非法审核值 400；不存在的活动 404
        val badCheck = expect("PUT", "/api/marketing/activities/$actA/check", admin, mapOf("check_status" to "approved"), 400)
        assertTrue(badCheck.path("errors").path("check_status")[0].asText().contains("只支持"), badCheck.toString())
        expect("PUT", "/api/marketing/activities/$actA/check", admin, emptyMap(), 400)
        expect("PUT", "/api/marketing/activities/999999/check", admin, mapOf("check_status" to "reviewed"), 404)

        // 改力度必重审：不存在"改完继续悄悄生效"这条后门
        adminOf("/api/marketing/activities/$actA", mkBody("端到端满100减20减50", listOf(step("100.00", minus = "20.00"), step("200.00", minus = "60.00"))), "PUT")
        assertEquals("non-reviewed", adminOf("/api/marketing/activities/$actA").path("check_status").asText())
        assertEquals(0L, preview("2:1").path("discount_fee").asLong(), "打回未审核后立刻停止减钱")
        review(actA)
        assertEquals(6000L, preview("2:1").path("discount_fee").asLong(), "重审后按新力度")
        // 力度回到本案后续断言依赖的 5000
        adminOf("/api/marketing/activities/$actA", mkBody("端到端满100减20减50", listOf(step("100.00", minus = "20.00"), step("200.00", minus = "50.00"))), "PUT")
        review(actA)
        assertEquals(5000L, preview("2:1").path("discount_fee").asLong(), "重审回到 5000，后续金额断言才成立")
    }

    // ===================== 算价口径 =====================

    @Test @Order(5) fun `满减取最高档 上不封顶倍数 与一店一活动择优`() {
        assertEquals(2000L, preview("3:2").path("discount_fee").asLong(), "17800 分（178 元）够满100档、不够满200档")
        assertEquals(5000L, preview("3:25").path("discount_fee").asLong(), "222500 分够满200档")

        review(actB)
        val both = preview("3:25")
        // 同组只取一个活动：B 每满100减20 = floor(2225/100)×2000 = 44000 > A 的 5000
        assertEquals(44000L, both.path("discount_fee").asLong(), "上不封顶：22 倍")
        assertEquals(178500L, both.path("total_fee").asLong())
        assertEquals(1, both.path("discount_info").size(), "一店一活动：不会同时记两个满减")
        assertConservation(both, "择优")
        assertEquals(44000L, both.path("discount_info")[0].path("discount_fee").asLong(), "discount_info 记的是实际折让")
        assertEquals("每满100元减20", both.path("discount_info")[0].path("rule").asText(), "上不封顶的文案带「每」")
        // 择优发生在算价而不是取数：库里两条都还是 reviewed
        assertEquals(
            2L,
            longOf("SELECT COUNT(*) FROM promotions_marketing_activity WHERE marketing_id IN ($actA,$actB) AND check_status = 'reviewed'"),
            "取数只筛「审核+启用+在窗」，同组多活动由规则链择优",
        )

        // MK-A7 删除活动 → 算价恢复
        val del = adminOf("/api/marketing/activities/$actB", null, "DELETE")
        assertContract(del, MarketingContract.DELETED, "MK-A7 删除出参")
        assertEquals(true, del.path("deleted").asBoolean())
        expect("GET", "/api/marketing/activities/$actB", admin, null, 404)
        assertEquals(5000L, preview("3:25").path("discount_fee").asLong(), "删掉 B 之后 A 重新成为最优")
    }

    @Test @Order(6) fun `按件门槛 满折 与圈品命中`() {
        // 按件：门槛比件数，折让仍按应付（否则会算出"减额×件数"）
        actD = create(mkBody("端到端满2件减30", listOf(step(2, minus = "30.00")), quantity = true, bound = 1))
        val saved = bind(actD, 3)
        assertContract(saved, MarketingContract.ITEMS_SAVED, "MK-A5 圈品出参")
        assertContract(saved.path("items")[0], MarketingContract.BOUND_ITEM_ROW, "圈品行")
        assertEquals(3L, saved.path("items")[0].path("item_id").asLong())
        assertEquals(1, adminOf("/api/marketing/activities/$actD").path("item_count").asInt())

        review(actD)
        val two = preview("3:2")
        assertEquals(3000L, two.path("discount_fee").asLong(), "2 件命中满2件减30（比 A 的 2000 更优）")
        assertEquals("满2件减30", two.path("discount_info")[0].path("rule").asText())
        assertEquals(0L, preview("3:1").path("discount_fee").asLong(), "1 件不达标：按件不看金额")
        assertEquals(5000L, preview("2:1").path("discount_fee").asLong(), "商品 2 不在圈品里，D 不参与")

        // 圈品校验：指定商品活动不许空圈、不许重复、不许非法 id/负价
        val empty = expect("PUT", "/api/marketing/activities/$actD/items", admin, mapOf("items" to emptyList<Any>()), 400)
        assertEquals(listOf("指定商品活动至少要圈一个商品"), empty.path("errors").path("items").map { it.asText() })
        val dup = expect("PUT", "/api/marketing/activities/$actD/items", admin,
            mapOf("items" to listOf(mapOf("item_id" to 3, "price" to 1), mapOf("item_id" to 3, "price" to 2))), 400)
        assertTrue(dup.path("errors").path("items")[0].asText().contains("只能圈一次"), dup.toString())
        val badId = expect("PUT", "/api/marketing/activities/$actD/items", admin, mapOf("items" to listOf(mapOf("item_id" to 0))), 400)
        assertTrue(badId.path("errors").has("items[0].item_id"), badId.toString())
        val badPrice = expect("PUT", "/api/marketing/activities/$actD/items", admin, mapOf("items" to listOf(mapOf("item_id" to 3, "price" to -1))), 400)
        assertTrue(badPrice.path("errors").has("items[0].price"), badPrice.toString())

        // 全量覆盖：换绑到商品 2 之后商品 3 就不再命中 D，只剩 A 的满100减20
        bind(actD, 2)
        assertEquals(2000L, preview("3:2").path("discount_fee").asLong(), "覆盖式圈品：3 不再命中 D")
        // 2 只买 1 件：按件门槛看件数不看金额，199900 分也够不上"满2件"
        assertEquals(5000L, preview("2:1").path("discount_fee").asLong(), "按件门槛不因金额高而放行")
        assertEquals(5000L, preview("2:2").path("discount_fee").asLong(), "D 命中只给 3000，A 的 5000 更优：同组仍只记一个活动")
        bind(actD, 3)

        // 满折：折让 = 基数 ×(100-discount)/100 向下取整
        actE = create(mkBody("端到端满100打9折", listOf(step("100.00", discount = 90)), type = "full_discount", bound = 1, tag = "满折"))
        bind(actE, 3)
        review(actE)
        val disc = preview("3:12")
        assertEquals(10680L, disc.path("discount_fee").asLong(), "106800×10%=10680 > A 的 2000")
        assertEquals("full_discount", disc.path("discount_info")[0].path("type").asText())
        assertEquals("满100元打9折", disc.path("discount_info")[0].path("rule").asText())
        assertConservation(disc, "满折")
        // 折率不在 1..<100 的档丢弃，全丢就等于没配 → 配置台拒
        expect("POST", "/api/marketing/activities", admin, mkBody("折率离谱", listOf(step("100.00", discount = 120)), type = "full_discount"), 400)

        adminOf("/api/marketing/activities/$actE", null, "DELETE")
        assertEquals(5000L, preview("3:12").path("discount_fee").asLong(), "删满折后回落到 A 的满200减50（D 的 3000 不敌它）")
        adminOf("/api/marketing/activities/$actD", null, "DELETE")
        assertEquals(5000L, preview("3:12").path("discount_fee").asLong(), "删按件后仍只有 A")
    }

    @Test @Order(7) fun `命中包邮的活动把本组运费置 0`() {
        jdbc.update(
            "INSERT INTO items (item_id, company_id, goods_id, default_item_id, is_default, item_name, item_bn, " +
                "price, cost_price, market_price, store, sales, approve_status, audit_status, templates_id, item_type, distributor_id, created, updated) " +
                "VALUES (902, 1, 902, 902, TRUE, '按件运费测试品', 'SKU-902', 12000, 5000, 15000, 50, 0, 'onsale', 'approved', 2, 'normal', 1, 0, 0)",
        )
        val before = preview("902:1")
        assertEquals(2000L, before.path("discount_fee").asLong(), "A 的满100减20 命中：12000−2000")
        assertEquals(800L, before.path("freight_fee").asLong(), "模板 2 按件 8 元：一件 800 分")
        assertEquals(10800L, before.path("total_fee").asLong(), "运费不参与抵扣，只加在应付上")
        assertConservation(before, "包邮前")

        actF = create(mkBody("端到端满100减30包邮", listOf(step("100.00", minus = "30.00")), bound = 1, freePostage = true))
        bind(actF, 902)
        review(actF)
        val after = preview("902:1")
        assertEquals(3000L, after.path("discount_fee").asLong(), "F 的 3000 优于 A 的 2000")
        assertEquals(0L, after.path("freight_fee").asLong(), "free_postage 命中 → 本组运费 0")
        assertEquals(9000L, after.path("total_fee").asLong())
        assertConservation(after, "包邮后")
        assertEquals(true, memberOf("/api/h5app/wxapp/cart?cart_type=fastbuy&items=902:1")
            .path("valid_cart")[0].path("is_free_postage").asBoolean(), "CART-2 同步给出包邮标记")
        assertEquals(1, after.path("discount_info").size(), "discount_info 只记择优的那一个活动")

        // 包邮只给"命中活动的那一组"：F 圈的是 902，同模板的 903 运费照收
        jdbc.update(
            "INSERT INTO items (item_id, company_id, goods_id, default_item_id, is_default, item_name, item_bn, " +
                "price, cost_price, market_price, store, sales, approve_status, audit_status, templates_id, item_type, distributor_id, created, updated) " +
                "VALUES (903, 1, 903, 903, TRUE, '未圈品运费测试品', 'SKU-903', 5000, 2000, 6000, 50, 0, 'onsale', 'approved', 2, 'normal', 1, 0, 0)",
        )
        val untouched = preview("903:1")
        assertEquals(800L, untouched.path("freight_fee").asLong(), "未命中 F → 运费不受影响")
        assertEquals(0L, untouched.path("discount_fee").asLong(), "5000 分也够不上 A 的满100元")
        assertEquals(5800L, untouched.path("total_fee").asLong())
        assertEquals(false, memberOf("/api/h5app/wxapp/cart?cart_type=fastbuy&items=903:1")
            .path("valid_cart")[0].path("is_free_postage").asBoolean(), "未命中的组不给包邮标记")

        adminOf("/api/marketing/activities/$actF", null, "DELETE")
        assertEquals(800L, preview("902:1").path("freight_fee").asLong(), "删掉包邮活动后运费恢复")
        assertEquals(2000L, preview("902:1").path("discount_fee").asLong(), "折让回落到 A")
    }

    @Test @Order(8) fun `券叠加满减 门槛比折后价 与抵扣不超应付`() {
        couponX = adminOf("/api/coupons", mapOf(
            "coupon_type" to "cash", "coupon_name" to "端到端满1050减50", "discount_fee" to 5000, "min_order_fee" to 105000,
            "total_num" to 5, "valid_type" to "range", "begin_time" to TimeSec.now() - 3600, "end_time" to TimeSec.now() + 86400,
            "get_limit" to 1,
        ), "POST").path("coupon_id").asLong()
        assertTrue(couponX > 0)
        codeX = memberOf("/api/h5app/wxapp/coupons/receive", mapOf("coupon_id" to couponX), "POST").path("coupon_code").asText()
        code1 = memberOf("/api/h5app/wxapp/coupons/receive", mapOf("coupon_id" to 1), "POST").path("coupon_code").asText()
        code2 = memberOf("/api/h5app/wxapp/coupons/receive", mapOf("coupon_id" to 2), "POST").path("coupon_code").asText()
        assertTrue(codeX.isNotBlank() && code1.isNotBlank() && code2.isNotBlank())

        // 满减之后 101800 分 < 105000 门槛：券不能用，且给出人话原因（§14.5 基数口径）
        val miss = preview("3:12", coupon = codeX)
        assertEquals(5000L, miss.path("discount_fee").asLong(), "1068 元命中 A 的满200减50")
        assertEquals(0L, miss.path("coupon_discount").asLong(), "门槛按折后应付判定")
        assertTrue(miss.path("coupon_reason").asText().contains("满 1050 元可用"), miss.path("coupon_reason").asText())
        assertTrue(miss.path("coupon_reason").asText().contains("1018"), miss.path("coupon_reason").asText())
        // 下单不许"悄悄不用券"：预览说不能用，提交就拒绝，而不是按不用券成交
        val refused = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:12", codeX), 422)
        assertTrue(refused.path("message").asText().contains("满 1050 元可用"), refused.path("message").asText())

        // 券 + 满减叠加：两条抵扣各自记账，合计 7000
        val both = preview("3:12", coupon = code1)
        assertEquals(5000L, both.path("discount_fee").asLong(), "满减照旧（券不影响活动）")
        assertEquals(2000L, both.path("coupon_discount").asLong())
        assertEquals(99800L, both.path("total_fee").asLong())
        assertConservation(both, "券+满减叠加")
        assertContract(both.path("coupon_info")[0], MarketingContract.COUPON_INFO, "coupon_info 项")
        assertEquals(code1, both.path("coupon_info")[0].path("coupon_code").asText())
        assertEquals(2000L, both.path("coupon_info")[0].path("deduct_fee").asLong(), "deduct_fee 是实抵，面额仍是面额")
        assertEquals(2000L, both.path("coupon_info")[0].path("discount_fee").asLong())
        assertEquals("满 100 元减 20", both.path("coupon_info")[0].path("rule").asText(), "规则文案与 CP-F4 同源")
        assertTrue(both.path("coupon_reason").isNull, "券已抵扣就不给原因：" + both.path("coupon_reason"))
        assertEquals(listOf("full_minus", "coupon"), both.path("discount_info").map { it.path("type").asText() }, "折让构成按链路顺序记")

        // 无门槛券：8900 分也照抵，抵扣 = min(面额, 应付)
        val free = preview("3:1", coupon = code2)
        assertEquals(1000L, free.path("coupon_discount").asLong())
        assertEquals(7900L, free.path("total_fee").asLong())
        // 满100减20 的券面对 8900 分：门槛不足 → 抵扣 0，而不是抵成负应付
        val below = preview("3:1", coupon = code1)
        assertEquals(0L, below.path("coupon_discount").asLong())
        assertTrue(below.path("coupon_reason").asText().contains("满 100 元可用"), below.path("coupon_reason").asText())

        // 别人的券码等于不存在（归属校验在预取阶段，与算价链无关）
        val outsider = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "member2").path("token").asText()
        val steal = data(call("POST", "/api/h5app/wxapp/getFreightFee", outsider,
            mapOf("cart_type" to "fastbuy", "items" to parseItems("3:12"), "coupon_code" to code1)), "outsider preview")
        assertEquals("优惠券不属于当前会员", steal.path("coupon_reason").asText())
        assertEquals(0L, steal.path("coupon_discount").asLong())
        val stealOrder = expect("POST", "/api/h5app/wxapp/order_new", outsider, orderBody("3:12", code1), 422)
        assertTrue(stealOrder.path("message").asText().contains("不属于当前会员"), stealOrder.path("message").asText())
    }

    @Test @Order(9) fun `积分抵扣受上限比例与余额双重钳制`() {
        val p = preview("3:12", point = true)
        assertEquals(1000L, p.path("user_point").asLong(), "seed：会员 1 有 1000 积分")
        assertEquals(50, p.path("limit_point").asInt(), "sys_config point.deduct.max_ratio=50")
        assertEquals(1000L, p.path("max_point").asLong(), "min(余额 1000, 101800×50%)")
        assertEquals(1000L, p.path("point_fee").asLong(), "1 积分 = 1 分")
        assertEquals(100800L, p.path("total_fee").asLong())
        assertEquals(true, p.path("point_use").asBoolean())
        assertConservation(p, "积分")

        // 券 + 满减 + 积分：三级依次以"折让后的应付"为基数
        val all = preview("3:12", coupon = code1, point = true)
        assertEquals(5000L, all.path("discount_fee").asLong())
        assertEquals(2000L, all.path("coupon_discount").asLong())
        assertEquals(1000L, all.path("point_fee").asLong())
        assertEquals(98800L, all.path("total_fee").asLong())
        assertEquals(listOf("full_minus", "coupon", "point"), all.path("discount_info").map { it.path("type").asText() })
        assertConservation(all, "三叠加")

        // 不勾积分开关：一分不抵，比例位也不给（limit_point 是"本单可用"的口径）
        val off = preview("3:12")
        assertEquals(0L, off.path("point_fee").asLong())
        assertEquals(0, off.path("limit_point").asInt())

        // 余额不足：只剩 300 积分就抵 300
        setPoint(300)
        assertEquals(300L, preview("3:12", point = true).path("point_fee").asLong())
        assertEquals(300L, preview("3:12", point = true).path("max_point").asLong())
        setPoint(1000)

        // 比例关掉：上限为 0，勾了开关也不抵
        ratio("0")
        assertEquals(0L, preview("3:12", point = true).path("point_fee").asLong())
        assertEquals(0L, preview("3:12", point = true).path("max_point").asLong())
        ratio("50")

        // 余额很大时也不会突破比例上限：101800×50% = 50900
        setPoint(90000)
        assertEquals(50900L, preview("3:12", point = true).path("point_fee").asLong(), "受比例钳制")
        assertEquals(50900L, preview("3:12", point = true).path("max_point").asLong())
        setPoint(1000)
        assertEquals(1000L, point(), "积分余额已复位")
    }

    // ===================== 下单闭环 =====================

    @Test @Order(10) fun `预览==下单 资产占用与回退`() {
        val p = preview("3:12", coupon = code1, point = true)
        val order = order("3:12", code1, true)
        val id = order.path("order_id").asLong()
        assertEquals(p.path("total_fee").asLong(), order.path("total_fee").asLong(), "下单价必须等于预览价")
        assertEquals(98800L, order.path("total_fee").asLong())

        val row = jdbc.queryForMap(
            "SELECT total_fee, item_fee, discount_fee, coupon_discount, point_fee, point_use, freight_fee " +
                "FROM orders_normal_orders WHERE order_id = ?", id,
        )
        assertEquals(106800L, (row["item_fee"] as Number).toLong(), "货款是原价合计，抵扣只进 total_fee")
        assertEquals(5000L, (row["discount_fee"] as Number).toLong())
        assertEquals(2000L, (row["coupon_discount"] as Number).toLong())
        assertEquals(1000L, (row["point_fee"] as Number).toLong())
        assertEquals(1000L, (row["point_use"] as Number).toLong(), "point_use 记实际抵现积分数（1 积分 = 1 分）")
        assertEquals(0L, (row["freight_fee"] as Number).toLong(), "模板 1 包邮")
        assertEquals(98800L, (row["total_fee"] as Number).toLong())

        // 快照：每一笔折让一行，Σ快照抵扣 == 原价 − 应付（售后与对账只读它，不重算）
        val rels = jdbc.queryForList(
            "SELECT activity_type, discount_fee, coupon_code, status FROM orders_rel_promotions WHERE moid = ? ORDER BY id", id,
        )
        assertEquals(3, rels.size, "活动/券/积分各一行")
        assertEquals(listOf("full_minus", "coupon", "point"), rels.map { it["activity_type"] })
        assertEquals(8000L, rels.sumOf { (it["discount_fee"] as Number).toLong() }, "快照合计 == 总折让")
        assertTrue(rels.all { it["status"] == "valid" })
        assertEquals(code1, rels.first { it["activity_type"] == "coupon" }["coupon_code"])

        // 行级分摊落库（D28）
        val line = jdbc.queryForMap(
            "SELECT discount_fee, coupon_discount, point_fee, total_fee FROM orders_normal_orders_items WHERE order_id = ?", id,
        )
        assertEquals(5000L, (line["discount_fee"] as Number).toLong())
        assertEquals(2000L, (line["coupon_discount"] as Number).toLong())
        assertEquals(1000L, (line["point_fee"] as Number).toLong())
        assertEquals(98800L, (line["total_fee"] as Number).toLong(), "行应付不含运费")

        // 资产占用：券 LOCKED 且记住占它的单，积分即时扣减
        assertEquals("LOCKED", statusOf(code1))
        assertEquals(id, longOf("SELECT order_id FROM promotions_coupon_user WHERE coupon_code = ?", code1))
        assertEquals(0L, point(), "1000 积分被这笔单占用")

        // 已用积分抵现的单不能再全额积分支付（§14.5 互斥）
        val mix = expect("POST", "/api/h5app/wxapp/payment", member, mapOf("order_id" to id.toString(), "pay_type" to "point"), 422)
        assertTrue(mix.path("message").asText().contains("不能再用纯积分支付"), mix.path("message").asText())

        // 同一张券不能挂第二笔单
        val twice = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("2:1", code1), 422)
        assertTrue(twice.path("message").asText().contains("该券已被其他订单占用"), twice.path("message").asText())

        // 取消：券回退、积分回补、快照作废、库存回补
        memberOf("/api/h5app/wxapp/order/cancel", mapOf("order_id" to id.toString(), "cancel_reason" to "端到端取消，验资产回退"), "POST")
        assertEquals("UNUSED", statusOf(code1))
        assertEquals(0L, longOf("SELECT order_id FROM promotions_coupon_user WHERE coupon_code = ?", code1), "回退后券不再归属任何单")
        assertEquals(1000L, point(), "关单回补抵现积分")
        assertEquals(0L, longOf("SELECT COUNT(*) FROM orders_rel_promotions WHERE moid = ? AND status = 'valid'", id), "快照作废")
        assertEquals(3L, longOf("SELECT COUNT(*) FROM orders_rel_promotions WHERE moid = ?", id), "行不删，只改状态")
        assertEquals(
            1L,
            longOf("SELECT COUNT(*) FROM point_member_log WHERE order_id = ? AND remark = ?", id.toString(), "订单关闭，积分抵现回补"),
            "回补要有流水留痕",
        )

        // 结清核销：券 LOCKED→USED，积分不动，快照保持 valid
        val o2 = order("2:1", code1)
        val id2 = o2.path("order_id").asLong()
        assertEquals(192900L, o2.path("total_fee").asLong(), "199900 − 满减5000 − 券2000")
        jdbc.update("UPDATE user_deposit SET deposit_amount = 200000 WHERE company_id = 1 AND user_id = 1")
        val paid = memberOf("/api/h5app/wxapp/payment", mapOf("order_id" to id2.toString(), "pay_type" to "deposit"), "POST")
        assertEquals("SUCCESS", paid.path("trade_state").asText())
        assertEquals("USED", statusOf(code1))
        assertEquals(id2, longOf("SELECT order_id FROM promotions_coupon_user WHERE coupon_code = ?", code1))
        assertEquals(1000L, point(), "结清不再动积分：抵扣只在下单时扣一次")
        assertEquals(2L, longOf("SELECT COUNT(*) FROM orders_rel_promotions WHERE moid = ? AND status = 'valid'", id2), "核销后快照仍在")
        // 已核销的券不能再下单
        val dead = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("2:1", code1), 422)
        assertTrue(dead.path("message").asText().contains("该券当前状态不可用"), dead.path("message").asText())
    }

    /** 全额积分兑换与积分抵现是两条路：余额 1000 抵不了 8900 分的单，渠道直接拒 */
    @Test @Order(11) fun `全额积分支付与抵扣互不重叠`() {
        setPoint(1000)
        val o = order("3:1")
        val bad = expect("POST", "/api/h5app/wxapp/payment", member,
            mapOf("order_id" to o.path("order_id").asText(), "pay_type" to "point"), 422)
        assertTrue(bad.path("message").asText().contains("积分不足"), bad.path("message").asText())
        memberOf("/api/h5app/wxapp/order/cancel", mapOf("order_id" to o.path("order_id").asText(), "cancel_reason" to "清理"), "POST")
        assertEquals(1000L, point(), "被拒的全额积分支付不动账")
    }

    // ===================== 前台面 =====================

    @Test @Order(12) fun `MK-F1 F2 F3 会员面只给审核通过且在售的活动`() {
        val page = memberOf("/api/h5app/wxapp/marketing/activities?page=1&pageSize=50")
        assertContract(page.path("list")[0], MarketingContract.FRONT_ROW, "MK-F1 活动行")
        val ids = page.path("list").map { it.path("marketing_id").asLong() }
        assertTrue(actA in ids, "已审核在窗活动在列表里")
        assertTrue(actG !in ids, "未审核活动不进前台（§14.4 生效条件与算价同源）")
        assertTrue(page.path("list").none { it.path("marketing_id").asLong() in setOf(1L, 2L) }, "seed 的两条不在窗")
        assertTrue(page.path("list").all { it.path("date_status").asText() == "doing" })
        val a = page.path("list").first { it.path("marketing_id").asLong() == actA }
        assertEquals("满100元减20", a.path("condition_text").asText(), "前台文案取最低档")

        val detail = memberOf("/api/h5app/wxapp/marketing/activities/$actA")
        assertContract(detail, MarketingContract.FRONT_DETAIL, "MK-F2 详情")
        assertEquals(0, detail.path("items").size(), "全场活动没有圈品")
        expect("GET", "/api/h5app/wxapp/marketing/activities/$actG", member, null, 404)
        expect("GET", "/api/h5app/wxapp/marketing/activities/999999", member, null, 404)

        // 停用 = 前台不存在（这里直接改列，是为了配出"审核通过但停用"这一态；审核 API 不改 status）
        jdbc.update("UPDATE promotions_marketing_activity SET status = FALSE WHERE marketing_id = ?", actG)
        jdbc.update("UPDATE promotions_marketing_activity SET check_status = 'reviewed' WHERE marketing_id = ?", actG)
        expect("GET", "/api/h5app/wxapp/marketing/activities/$actG", member, null, 404)
        jdbc.update("UPDATE promotions_marketing_activity SET status = TRUE WHERE marketing_id = ?", actG)
        assertTrue(actG in memberOf("/api/h5app/wxapp/marketing/activities?marketing_type=full_minus&pageSize=100")
            .path("list").map { it.path("marketing_id").asLong() }, "MK-F1 类型过滤")

        // MK-F3 商详角标：全场活动对任何在售商品都算命中
        val promo = memberOf("/api/h5app/wxapp/items/3/promotions")
        assertContract(promo, MarketingContract.ITEM_PROMOTIONS, "MK-F3 出参")
        assertEquals(3L, promo.path("item_id").asLong())
        assertTrue(promo.path("marketing").map { it.path("marketing_id").asLong() }.contains(actA))
        assertContract(promo.path("marketing")[0], MarketingContract.FRONT_ROW, "MK-F3 活动行")
        assertTrue(promo.path("tags").map { it.asText() }.contains("满减"))
        assertTrue(promo.path("seckill").isNull, "秒杀键位属 #57，先占位为 null")

        val anon = expect("GET", "/api/h5app/wxapp/marketing/activities", null, null, 401)
        assertEquals("Unauthenticated.", anon.path("message").asText())
        expect("GET", "/api/h5app/wxapp/items/0/promotions", member, null, 404)
    }

    @Test @Order(13) fun `权限码分立与租户隔离`() {
        // 看/改/审三条码：viewer 一条都没有
        expect("GET", "/api/marketing/activities", viewer, null, 403)
        expect("POST", "/api/marketing/activities", viewer, mkBody("越权活动", listOf(step("100.00", minus = "1.00"))), 403)
        expect("PUT", "/api/marketing/activities/$actG/check", viewer, mapOf("check_status" to "reviewed"), 403)
        expect("PUT", "/api/marketing/activities/$actG/items", viewer, mapOf("items" to listOf(mapOf("item_id" to 3))), 403)
        expect("DELETE", "/api/marketing/activities/$actG", viewer, null, 403)
        expect("GET", "/api/marketing/activities", null, null, 401)

        // 活动是租户资产：company 2 的行既进不了 1 的后台列表，也进不了 1 的算价链
        // （故意配一条比 A 更狠的：只要它漏进本租户，折让立刻从 5000 变 9900）
        jdbc.update(
            "INSERT INTO promotions_marketing_activity (company_id, marketing_type, marketing_name, start_time, end_time, " +
                "release_time, use_bound, condition_type, condition_value, canjoin_repeat, join_limit, free_postage, check_status, status, created, updated) " +
                "VALUES (2, 'full_minus', '他租户全场满100减99', ?, ?, 0, 0, 'totalfee', '[{\"full\":\"100.00\",\"minus\":\"99.00\"}]', FALSE, 0, FALSE, 'reviewed', TRUE, 0, 0)",
            TimeSec.now() - 3600, TimeSec.now() + 86400,
        )
        val foreign = longOf("SELECT marketing_id FROM promotions_marketing_activity WHERE company_id = 2")
        assertTrue(foreign > 0)
        assertTrue(adminOf("/api/marketing/activities?pageSize=100").path("list").none { it.path("marketing_id").asLong() == foreign }, "后台列表隔离")
        assertTrue(memberOf("/api/h5app/wxapp/marketing/activities?pageSize=100").path("list").none { it.path("marketing_id").asLong() == foreign }, "前台列表隔离")
        expect("GET", "/api/marketing/activities/$foreign", admin, null, 404)
        expect("PUT", "/api/marketing/activities/$foreign/check", admin, mapOf("check_status" to "unreviewed"), 404)
        assertEquals(5000L, preview("3:12").path("discount_fee").asLong(), "他租户活动不参与本租户算价")
        jdbc.update("DELETE FROM promotions_marketing_activity WHERE marketing_id = ?", foreign)
    }

    /** 收尾：本案活动删干净之后，全场折让必须回 0（seed 那两条永远不在窗） */
    @Test @Order(14) fun `清理后算价恢复 seed 态`() {
        // 前面各案已经删掉的再删一次就是 404，这里只求"库里确实一条不剩"
        listOf(actA, actB, actD, actE, actF, actG).filter { it > 0 }.forEach { id ->
            val res = call("DELETE", "/api/marketing/activities/$id", admin, null)
            assertTrue(res.response.status in setOf(200, 404), "收尾删除 $id -> ${utf8(res.response.contentAsByteArray)}")
        }
        val now = TimeSec.now()
        assertEquals(
            0L,
            longOf("SELECT COUNT(*) FROM promotions_marketing_activity WHERE company_id = 1 AND check_status = 'reviewed' " +
                "AND start_time <= $now AND (end_time = 0 OR end_time >= $now)"),
            "本租户没有「已审核且在窗」的活动（seed 两条只审核、不在窗）",
        )
        assertEquals(0L, preview("3:25").path("discount_fee").asLong(), "已结束/未开始的 seed 活动永不进算价")
        assertEquals(0L, preview("2:1").path("discount_fee").asLong())
        assertEquals(0, memberOf("/api/h5app/wxapp/marketing/activities?pageSize=50").path("total_count").asInt(), "前台列表清空")
        assertEquals(0, memberOf("/api/h5app/wxapp/items/3/promotions").path("marketing").size(), "商详角标清空")

        // 脏数据不能把算价链打挂：规则文本配成非法 JSON 之后仍然只是"不优惠"
        val dirty = create(mkBody("脏规则", listOf(step("100.00", minus = "1.00"))))
        jdbc.update("UPDATE promotions_marketing_activity SET condition_value = '{not-json', check_status = 'reviewed' WHERE marketing_id = ?", dirty)
        assertEquals(0L, preview("3:25").path("discount_fee").asLong(), "脏规则视为未配置")
        assertEquals(0, adminOf("/api/marketing/activities/$dirty").path("steps").size(), "脏规则解析出空阶梯而不是异常")
        adminOf("/api/marketing/activities/$dirty", null, "DELETE")
        assertEquals(0L, preview("3:25").path("discount_fee").asLong(), "清理后回到 seed 态")
    }
}
