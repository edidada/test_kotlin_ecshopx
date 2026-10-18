package com.ecshopx.kotlin.boot

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
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1-3 #58 门槛①：**优惠叠加矩阵**（docs/05 立的「券 × 满减 × 会员折 × 积分上限」，本次把 #57 的活动价一起拉进组合）。
 *
 * 五档开关全组合 = 16 格：`秒杀活动价 × 会员等级折 × 满减 × 优惠券 × 积分抵现`。
 * 每格先用一个**独立写的算术模型**（[oracle]，照 docs/04 §14.5 的"逐级基数"定义手写，不复用责任链）
 * 反推出五个金额，再与 ORDER-1 出参逐分对比。链路顺序一旦改动（例如把会员折挪到满减之后），
 * 必然有几格对不上 —— 这比单点用例更难"改个数字继续绿"。
 *
 * 每格断言四件事：
 *  1. **逐级基数**：`行小计(活动价后) → 减会员折 → 满减门槛 → 券门槛 → 积分上限` 一层比一层小，
 *     券的门槛比的是**满减之后**的应付（`17700 − 885 − 3000 = 13815` 那两格就专门卡在 140 元门外）；
 *  2. **四个账各自独立**：`discount_fee == 会员折 + 满减`、`goods_discount == 满减`、
 *     `member_discount == 会员折`、`coupon_discount/point_fee` 各记各的，活动让利**不进 `discount_fee`**（D24）；
 *  3. **守恒式**：`Σ行 total_fee + 运费 == 单 total_fee`、三类抵扣逐行守恒、行应付不为负（D28）；
 *  4. **折让构成**：`discount_info[]` 的 `type` 序列 == 链路顺序，金额合计 == 四类让利之和。
 *
 * 造数口径：商品 3 现价 8900 分、秒杀活动价 5900 分、一次买 3 件；等级折用 seed 的金卡 95 折
 * （`members.grade_id = 3`，折让 5%）；满减「满 150 元减 30」；券「满 140 元减 20」；积分余额 90000、
 * `point.deduct.max_ratio = 50` —— 每格的积分都由**比例**钳住而不是由余额钳住（余额那条路
 * [MarketingEndToEndTest] Order 9 已单独验过）。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccoverlay;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
        "ecx.schedule.seckill-settle-initial-delay-ms=3600000",
        "ecx.schedule.seckill-settle-ms=3600000",
        "ecx.schedule.groups-settle-initial-delay-ms=3600000",
        "ecx.schedule.groups-settle-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DiscountOverlayMatrixTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var admin = ""
    private var member = ""

    private var sk = 0L
    private var mkId = 0L
    private var code = ""
    private var codeBig = ""

    // ===== 调用件 =====

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

    private fun longOf(sql: String, vararg v: Any): Long = jdbc.queryForObject(sql, Long::class.java, *v) ?: -1L

    private fun strOf(sql: String, vararg v: Any): String = jdbc.queryForObject(sql, String::class.java, *v) ?: ""

    // ===== 开关与造数 =====

    private fun previewBody(actOn: Boolean, couponCode: String?, pointOn: Boolean): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "cart_type" to "fastbuy",
            "items" to listOf(mapOf("item_id" to ITEM, "num" to NUM)),
            "order_class" to if (actOn) "seckill" else null,
            "act_id" to if (actOn) sk else null,
            "coupon_code" to couponCode,
            "point_use" to pointOn,
        ).filterValues { it != null }

    /** ORDER-1 预览：只读价、不动资产，所以同一张券能连用 16 格 */
    private fun preview(actOn: Boolean, couponOn: Boolean, pointOn: Boolean, couponCode: String? = code): JsonNode =
        data(call("POST", "/api/h5app/wxapp/getFreightFee", member,
            previewBody(actOn, couponCode.takeIf { couponOn }, pointOn)), "ORDER-1")

    private fun setGrade(on: Boolean) {
        jdbc.update("UPDATE members SET grade_id = ? WHERE user_id = 1", if (on) 3L else 1L)
    }

    /** 满减没有"只改开关"的端点（编辑必打回重审），矩阵用「建+审 / 删」两次调用切状态 */
    private fun enableMk() {
        if (mkId > 0) return
        val id = adminOf(
            "/api/marketing/activities",
            linkedMapOf(
                "marketing_type" to "full_minus", "marketing_name" to "矩阵满150减30", "ad_pic" to "/img/m.jpg",
                "start_time" to TimeSec.now() - 3600, "end_time" to TimeSec.now() + 86400,
                "use_bound" to 0, "condition_type" to "totalfee",
                "condition_value" to listOf(linkedMapOf("full" to "150.00", "minus" to "30.00")),
                "canjoin_repeat" to false, "join_limit" to 0, "free_postage" to false,
                "promotion_tag" to "满减", "status" to true,
            ),
            "POST",
        ).path("marketing_id").asLong()
        adminOf("/api/marketing/activities/$id/check", mapOf("check_status" to "reviewed"), "PUT")
        mkId = id
    }

    private fun disableMk() {
        if (mkId > 0) {
            adminOf("/api/marketing/activities/$mkId", null, "DELETE")
            mkId = 0L
        }
    }

    /** ORDER-2 下单：与 [preview] 同一份行、券与积分开关（券码字段名是 PHP 的 `coupon_discount`） */
    private fun submitOrder(actOn: Boolean, couponOn: Boolean, pointOn: Boolean): JsonNode {
        val body = linkedMapOf<String, Any?>(
            "cart_type" to "fastbuy", "order_type" to "normal",
            "items" to listOf(mapOf("item_id" to ITEM, "num" to NUM)),
            "receiver_name" to "张三", "receiver_mobile" to "13800000001", "receiver_state" to "浙江省",
            "receiver_city" to "杭州市", "receiver_district" to "西湖区", "receiver_address" to "文三路 1 号",
            "order_class" to if (actOn) "seckill" else null, "act_id" to if (actOn) sk else null,
            "coupon_discount" to if (couponOn) code else null, "point_use" to pointOn, "order_from" to "h5",
        ).filterValues { it != null }
        return data(call("POST", "/api/h5app/wxapp/order_new", member, body), "ORDER-2")
    }

    // ===== §14.5 的独立算术模型（手写，不复用责任链） =====

    private class Cell(
        val unit: Long, val itemFee: Long, val activityFee: Long,
        val member: Long, val mk: Long, val couponBase: Long, val coupon: Long,
        val point: Long, val maxPoint: Long, val total: Long, val types: List<String>,
    )

    private fun oracle(actOn: Boolean, gradeOn: Boolean, mkOn: Boolean, couponOn: Boolean, pointOn: Boolean): Cell {
        val unit = if (actOn) SK_PRICE else PRICE
        val itemFee = unit * NUM                                       // order 15 之后：行小计以活动价为基准
        val activityFee = if (actOn) (PRICE - SK_PRICE) * NUM else 0L
        val member = if (gradeOn) itemFee * GRADE_RATE / 100 else 0L   // order 20：基数 == 行小计
        val mkBase = itemFee - member                                  // order 30：基数 == 会员折之后
        val mk = if (mkOn && mkBase >= MK_THRESHOLD) MK_MINUS else 0L
        val couponBase = mkBase - mk                                   // order 40：基数 == 满减之后
        val coupon = if (couponOn && couponBase >= COUPON_THRESHOLD) minOf(COUPON_FEE, couponBase) else 0L
        val pointBase = couponBase - coupon                            // order 50：基数 == 券之后
        val cap = pointBase * RATIO / 100
        val point = if (pointOn) minOf(POINT_BALANCE, cap) else 0L
        val types = listOfNotNull(
            "seckill".takeIf { actOn }, "full_minus".takeIf { mk > 0 },
            "coupon".takeIf { coupon > 0 }, "point".takeIf { point > 0 },
        )
        return Cell(unit, itemFee, activityFee, member, mk, couponBase, coupon, point,
            if (pointOn) minOf(POINT_BALANCE, cap) else 0L, pointBase - point, types)
    }

    /** 一格的全部断言：四个账、逐级基数、折让构成、守恒式 */
    private fun assertCell(p: JsonNode, actOn: Boolean, gradeOn: Boolean, mkOn: Boolean, couponOn: Boolean, pointOn: Boolean) {
        val tag = "活动价=${actOn.sw()} 会员折=${gradeOn.sw()} 满减=${mkOn.sw()} 券=${couponOn.sw()} 积分=${pointOn.sw()}"
        val e = oracle(actOn, gradeOn, mkOn, couponOn, pointOn)
        val line = p.path("items")[0]

        assertEquals(e.unit, line.path("price").asLong(), "$tag：单价基准是活动价之后的价")
        assertEquals(PRICE, line.path("origin_price").asLong(), "$tag：现价位始终是原价，前台才说清省了多少")
        assertEquals(e.itemFee, line.path("item_fee").asLong(), "$tag：行小计 == 活动价 × 件数")
        assertEquals(if (actOn) "seckill" else "normal", line.path("activity_type").asText(), "$tag：行上申报的活动类目")
        assertEquals(if (actOn) sk else 0L, line.path("activity_id").asLong(), "$tag：行上申报的活动 id")
        assertEquals(0L, p.path("freight_fee").asLong(), "$tag：模板 1 全国包邮")

        assertEquals(e.member + e.mk, p.path("discount_fee").asLong(), "$tag：discount_fee == 会员折 + 满减")
        assertEquals(e.member, p.path("member_discount").asLong(), "$tag：会员折单独一账")
        assertEquals(e.mk, p.path("goods_discount").asLong(), "$tag：goods_discount 只算满减/满折")
        assertEquals(e.activityFee, p.path("activity_price_fee").asLong(), "$tag：活动让利只走 activity_price_fee（D24）")
        assertEquals(e.coupon, p.path("coupon_discount").asLong(), "$tag：券抵扣")
        assertEquals(e.point, p.path("point_fee").asLong(), "$tag：积分抵扣")
        assertEquals(e.total, p.path("total_fee").asLong(), "$tag：应付 == 逐级减完的钱")

        // 积分上限位：limit_point/max_point 都是"本单可用"口径，不勾开关就不给
        assertEquals(if (pointOn) RATIO_INT else 0, p.path("limit_point").asInt(), "$tag：比例位")
        assertEquals(POINT_BALANCE, p.path("user_point").asLong(), "$tag：余额")
        assertEquals(e.maxPoint, p.path("max_point").asLong(), "$tag：本单最多可抵 == min(余额, 券之后应付 × 比例)")
        assertTrue(e.point <= e.maxPoint, "$tag：抵扣不越过上限")

        // 折让构成：顺序 == 链路顺序，金额合计 == 本单省下的钱
        assertEquals(e.types, p.path("discount_info").map { it.path("type").asText() }, "$tag：discount_info 序列")
        assertEquals(
            e.activityFee + e.mk + e.coupon + e.point,
            p.path("discount_info").sumOf { it.path("discount_fee").asLong() },
            "$tag：discount_info 合计 == 四类让利之和",
        )

        // 被门槛挡下的格子：文案里的"当前金额"必须是满减之后的数，不是满减之前
        if (couponOn && e.coupon == 0L) {
            val reason = p.path("coupon_reason").asText()
            assertTrue(reason.contains("满 140 元可用"), "$tag：券门槛没到要说清门槛 -> $reason")
            assertTrue(reason.contains(yuan(e.couponBase)), "$tag：当前金额是满减之后的应付 -> $reason")
            assertTrue(p.path("coupon_info").isEmpty(), "$tag：抵 0 就不该编出券行")
        } else {
            assertTrue(p.path("coupon_reason").isNull, "$tag：没有被拒的券就不留原因")
        }

        // 守恒式（D28）
        val lines = p.path("items")
        assertEquals(
            p.path("total_fee").asLong() - p.path("freight_fee").asLong(),
            lines.sumOf { it.path("total_fee").asLong() }, "$tag：Σ行应付 + 运费 == 单应付",
        )
        assertEquals(p.path("discount_fee").asLong(), lines.sumOf { it.path("discount_fee").asLong() }, "$tag：折让逐行守恒")
        assertEquals(p.path("coupon_discount").asLong(), lines.sumOf { it.path("coupon_fee").asLong() }, "$tag：券逐行守恒")
        assertEquals(p.path("point_fee").asLong(), lines.sumOf { it.path("point_fee").asLong() }, "$tag：积分逐行守恒")
        assertEquals(e.activityFee, lines.sumOf { it.path("activity_price_fee").asLong() }, "$tag：活动让利逐行守恒")
        assertTrue(lines.all { it.path("total_fee").asLong() >= 0 }, "$tag：行应付不得为负")
    }

    // ===================== 用例 =====================

    @Test @Order(1) fun `造好五档开关`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank())

        // 秒杀场次：商品 3 活动价 5900，池子给足（矩阵只看价，不抢池）
        sk = adminOf("/api/seckill/activities", linkedMapOf(
            "activity_name" to "矩阵秒杀场", "activity_type" to 1, "ad_pic" to "/img/s.jpg", "distributor_id" to "1",
            "activity_start_time" to TimeSec.now() - 3600, "activity_end_time" to TimeSec.now() + 86400,
            "validity_period" to 10, "limit_money" to 0,
        ), "POST").path("seckill_id").asLong()
        adminOf("/api/seckill/activities/$sk/goods", mapOf("items" to listOf(linkedMapOf(
            "item_id" to ITEM, "item_name" to "矩阵圈品", "activity_price" to SK_PRICE,
            "activity_store" to 50, "limit_num" to 0, "is_show" to true,
        ))), "PUT")
        assertEquals(SK_PRICE, longOf("SELECT activity_price FROM promotions_seckill_rel_goods WHERE seckill_id = ? AND item_id = ?", sk, ITEM))

        // 两张券：140 减 20（进矩阵）与无门槛减 300（只给"抵到 0"那一格用）
        val small = adminOf("/api/coupons", linkedMapOf(
            "coupon_type" to "cash", "coupon_name" to "矩阵满140减20", "discount_fee" to COUPON_FEE,
            "min_order_fee" to COUPON_THRESHOLD, "total_num" to 20, "valid_type" to "range",
            "begin_time" to TimeSec.now() - 3600, "end_time" to TimeSec.now() + 86400, "get_limit" to 0,
        ), "POST").path("coupon_id").asLong()
        val big = adminOf("/api/coupons", linkedMapOf(
            "coupon_type" to "cash", "coupon_name" to "矩阵无门槛减300", "discount_fee" to 30000L,
            "min_order_fee" to 0, "total_num" to 20, "valid_type" to "range",
            "begin_time" to TimeSec.now() - 3600, "end_time" to TimeSec.now() + 86400, "get_limit" to 0,
        ), "POST").path("coupon_id").asLong()
        code = data(call("POST", "/api/h5app/wxapp/coupons/receive", member, mapOf("coupon_id" to small)), "领券").path("coupon_code").asText()
        codeBig = data(call("POST", "/api/h5app/wxapp/coupons/receive", member, mapOf("coupon_id" to big)), "领大额券").path("coupon_code").asText()
        assertTrue(code.isNotBlank() && codeBig.isNotBlank())

        // 积分：余额大到永远由比例钳住，比例回到 50
        jdbc.update("UPDATE point_member SET point = ?, total_point = ? WHERE company_id = 1 AND user_id = 1", POINT_BALANCE, POINT_BALANCE)
        jdbc.update("UPDATE sys_config SET config_value = '50' WHERE company_id = 1 AND config_key = 'point.deduct.max_ratio'")
        assertEquals(POINT_BALANCE, longOf("SELECT point FROM point_member WHERE company_id = 1 AND user_id = 1"))

        // 基线：普通会员 + 无活动 + 不用券不用积分 == 现价 × 件数
        val base = preview(actOn = false, couponOn = false, pointOn = false)
        assertEquals(PRICE * NUM, base.path("total_fee").asLong(), "基线一格不减")
        assertEquals(0L, base.path("discount_fee").asLong())
        assertEquals(0L, base.path("activity_price_fee").asLong(), "普通行不因库里有场次就变价")

        // 等级名与等级折只长在 CART-2 的行上（ORDER-1 的行给的是金额三件套），换等级两边都要跟着动
        assertEquals("普通会员", cartLine().path("grade_name").asText())
        setGrade(true)
        assertEquals("金卡会员", cartLine().path("grade_name").asText(), "等级名来自 members_grade（金卡 95 折）")
        assertEquals(PRICE * NUM * GRADE_RATE / 100, cartLine().path("member_discount").asLong(), "会员折 5%：26700 → 1335")
        setGrade(false)
    }

    /** CART-2 的行视图：等级名与等级折在这里，不在 ORDER-1 的行上 */
    private fun cartLine(): JsonNode =
        data(call("GET", "/api/h5app/wxapp/cart?cart_type=fastbuy&items=$ITEM:$NUM", member, null), "CART-2")
            .path("valid_cart")[0].path("list")[0]

    @Test @Order(2) fun `16 格叠加矩阵 逐级基数与守恒式全对`() {
        // 每格的状态都由循环自己开关：进场先复位，别让上一条用例的残留混进矩阵
        setGrade(false)
        disableMk()
        for (gradeOn in listOf(false, true)) {
            setGrade(gradeOn)
            for (mkOn in listOf(false, true)) {
                if (mkOn) enableMk() else disableMk()
                for (actOn in listOf(false, true)) {
                    for (couponOn in listOf(false, true)) {
                        for (pointOn in listOf(false, true)) {
                            assertCell(preview(actOn, couponOn, pointOn), actOn, gradeOn, mkOn, couponOn, pointOn)
                        }
                    }
                }
            }
        }
        setGrade(false)
        disableMk()
    }

    @Test @Order(3) fun `同一张券 只在满减关掉后用得上 门槛比的是折后应付`() {
        // 这一格挑"活动价 + 金卡折 + 满减"三个都在场：16815 − 3000 = 13815，正好卡在 140 元门外
        setGrade(true)
        enableMk()
        val withMk = preview(actOn = true, couponOn = true, pointOn = false)
        assertEquals(3000L, withMk.path("goods_discount").asLong(), "满减在场")
        assertEquals(885L, withMk.path("member_discount").asLong(), "会员折在场")
        assertEquals(0L, withMk.path("coupon_discount").asLong(), "13815 < 14000：券抵 0")
        assertTrue(withMk.path("coupon_reason").asText().contains("138.15"), withMk.path("coupon_reason").asText())

        // 换掉矩阵里唯一变化的开关（满减）→ 同一笔行、同一张券立刻能用：门槛基数可证
        disableMk()
        val off = preview(actOn = true, couponOn = true, pointOn = false)
        assertEquals(0L, off.path("goods_discount").asLong(), "删掉满减就没有满减折让（会员折另记）")
        assertEquals(885L, off.path("discount_fee").asLong(), "discount_fee 只剩会员折")
        assertEquals(885L, off.path("member_discount").asLong())
        assertEquals(COUPON_FEE, off.path("coupon_discount").asLong(), "16815 ≥ 14000：券能用")
        assertEquals(14815L, off.path("total_fee").asLong(), "17700 − 885 − 2000")
        assertCell(off, actOn = true, gradeOn = true, mkOn = false, couponOn = true, pointOn = false)
        setGrade(false)
        enableMk()
    }

    @Test @Order(4) fun `四类抵扣同时在场也不会把一行抵成负数`() {
        setGrade(true)
        enableMk()
        val big = data(call("POST", "/api/h5app/wxapp/getFreightFee", member,
            previewBody(actOn = true, couponCode = codeBig, pointOn = true)), "大额券预览")
        // 17700 − 885(会员折) − 3000(满减) = 13815：300 元面额按应付抵到 0，而不是抵成 −16185
        assertEquals(13815L, big.path("coupon_discount").asLong(), "抵扣 == min(面额, 满减折后应付)")
        assertEquals(0L, big.path("total_fee").asLong(), "抵光就是 0，不会负")
        assertEquals(0L, big.path("point_fee").asLong(), "没有应付可抵，积分一分不动")
        assertEquals(0L, big.path("max_point").asLong(), "上限位也跟着归零")
        assertEquals(0L, big.path("items")[0].path("total_fee").asLong(), "行应付同样为 0")
        assertEquals(listOf("seckill", "full_minus", "coupon"), big.path("discount_info").map { it.path("type").asText() },
            "积分没抵钱就不进折让构成")
        assertCell(preview(actOn = true, couponOn = true, pointOn = true),
            actOn = true, gradeOn = true, mkOn = true, couponOn = true, pointOn = true)
        setGrade(false)
        disableMk()
    }

    @Test @Order(5) fun `四叠加下单 落库金额与预览逐分一致`() {
        setGrade(false)
        enableMk()
        val p = preview(actOn = true, couponOn = true, pointOn = true)
        val e = oracle(actOn = true, gradeOn = false, mkOn = true, couponOn = true, pointOn = true)
        assertEquals(0L, p.path("member_discount").asLong(), "本格用普通会员，等级折不许留 residue")
        val order = submitOrder(actOn = true, couponOn = true, pointOn = true)
        val id = order.path("order_id").asLong()

        assertEquals(e.total, order.path("total_fee").asLong(), "下单价 == 模型价")
        assertEquals(p.path("total_fee").asLong(), order.path("total_fee").asLong(), "预览与下单同一个 quote")
        val row = jdbc.queryForMap(
            "SELECT item_fee, total_fee, discount_fee, coupon_discount, point_fee, point_use, order_class, act_id " +
                "FROM orders_normal_orders WHERE order_id = ?", id,
        )
        assertEquals(e.itemFee, (row["item_fee"] as Number).toLong(), "货款记活动价之后的钱（让利不在这里）")
        assertEquals(e.mk, (row["discount_fee"] as Number).toLong(), "落库折让只有满减")
        assertEquals(e.coupon, (row["coupon_discount"] as Number).toLong())
        assertEquals(e.point, (row["point_fee"] as Number).toLong(), "1 积分 == 1 分")
        assertEquals(e.point, (row["point_use"] as Number).toLong())
        assertEquals(e.total, (row["total_fee"] as Number).toLong())
        assertEquals("seckill", row["order_class"], "活动单头类目落库")
        assertEquals(sk, (row["act_id"] as Number).toLong())

        // 快照四行：活动价/满减/券/积分各一行，合计 == 原价 − 应付（跨活动类型的叠加只有快照能说清）
        val rels = jdbc.queryForList(
            "SELECT activity_type, discount_fee, status FROM orders_rel_promotions WHERE moid = ? ORDER BY id", id,
        )
        assertEquals(listOf("seckill", "full_minus", "coupon", "point"), rels.map { it["activity_type"] })
        assertEquals(PRICE * NUM - e.total, rels.sumOf { (it["discount_fee"] as Number).toLong() }, "Σ快照抵扣 == 原价合计 − 应付")
        assertTrue(rels.all { it["status"] == "valid" }, "四行快照全部有效")

        // ORDER-5 的折让构成走行上的 `discount_info` 列：只带行级两类（活动价 + 满减），
        // 券与积分在单级另有账位列（`coupon_discount`/`point_fee`），四行全账由上面的快照判。
        // 这一条是本轮实跑冒烟炸出来的真 bug：`jsonList` 回的是字符串数组，
        // 老代码 `as? Map` 永远 cast 不动 → 任何单据的 `discount_info` 都是 []。
        val detail = data(call("GET", "/api/h5app/wxapp/order/$id", member, null), "ORDER-5")
        val info = detail.path("orderInfo").path("discount_info")
        assertEquals(listOf("seckill", "full_minus"), info.map { it.path("type").asText() }, "ORDER-5 折让构成不是空壳")
        assertEquals(e.activityFee + e.mk, info.sumOf { it.path("discount_fee").asLong() }, "Σ行级折让构成 == 活动让利 + 满减")
        assertEquals(e.coupon, detail.path("orderInfo").path("coupon_discount").asLong(), "券抵扣在单级另记")
        assertEquals(e.point, detail.path("orderInfo").path("point_fee").asLong(), "积分抵扣在单级另记")

        // 资产占用：券 LOCKED、积分即时扣、池子按件占
        assertEquals("LOCKED", strOf("SELECT status FROM promotions_coupon_user WHERE coupon_code = ?", code))
        assertEquals(POINT_BALANCE - e.point, longOf("SELECT point FROM point_member WHERE company_id = 1 AND user_id = 1"))
        assertEquals(NUM, longOf("SELECT hold_store FROM promotions_seckill_rel_goods WHERE seckill_id = ? AND item_id = ?", sk, ITEM),
            "秒杀单占的是活动池，按件计")

        // 取消整条回退：券回 UNUSED、积分回补、池子释放、快照作废
        data(call("POST", "/api/h5app/wxapp/order/cancel", member,
            mapOf("order_id" to id.toString(), "cancel_reason" to "矩阵收尾")), "取消")
        assertEquals("UNUSED", strOf("SELECT status FROM promotions_coupon_user WHERE coupon_code = ?", code))
        assertEquals(POINT_BALANCE, longOf("SELECT point FROM point_member WHERE company_id = 1 AND user_id = 1"))
        assertEquals(0L, longOf("SELECT hold_store FROM promotions_seckill_rel_goods WHERE seckill_id = ? AND item_id = ?", sk, ITEM))
        assertEquals(0L, longOf("SELECT COUNT(*) FROM orders_rel_promotions WHERE moid = ? AND status = 'valid'", id))

        disableMk()
        val after = preview(actOn = false, couponOn = false, pointOn = false)
        assertEquals(PRICE * NUM, after.path("total_fee").asLong(), "撤干净之后回到基线价")
        assertEquals(0L, after.path("discount_fee").asLong())
    }

    // ===== 小工具 =====

    private fun Boolean.sw() = if (this) "on " else "off"

    /** 与生产同源的金额文案（分 → 元，去掉无意义的尾 0） */
    private fun yuan(fen: Long): String = BigDecimal(fen).movePointLeft(2).stripTrailingZeros().toPlainString()

    private companion object {
        const val ITEM = 3L
        const val NUM = 3L
        const val PRICE = 8900L
        const val SK_PRICE = 5900L
        const val GRADE_RATE = 5L            // 金卡 95 折 → 折让 5%
        const val MK_THRESHOLD = 15000L      // 满 150 元
        const val MK_MINUS = 3000L           // 减 30 元
        const val COUPON_THRESHOLD = 14000L  // 满 140 元
        const val COUPON_FEE = 2000L         // 减 20 元
        const val POINT_BALANCE = 90000L
        const val RATIO = 50L
        const val RATIO_INT = 50
    }
}
