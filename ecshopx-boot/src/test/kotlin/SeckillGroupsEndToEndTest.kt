package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.GroupsContract
import com.ecshopx.kotlin.boot.contract.MarketingContract
import com.ecshopx.kotlin.boot.contract.OrderContract
import com.ecshopx.kotlin.boot.contract.SeckillContract
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.service.order.OrderTimeoutJob
import com.ecshopx.kotlin.service.promotion.GroupsSettleJob
import com.ecshopx.kotlin.service.promotion.SeckillSettleJob
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
 * M1-3 #57 秒杀与拼团的端到端（SK-A1..A7 + SK-F1/F2 + GP-A1..A6 + GP-F1..F4 + ORDER-1/2 活动单）。
 *
 * 断言的重点不是"接口能通"，而是 §14.5 与 D24/D25/D26/D29/D30 立的那几条口径：
 *  1. **活动价改的是单价基准，不是折让**：`price` 换成活动价、让利记 `activity_price_fee`，
 *     `discount_fee` 里只有会员折与满减，守恒式 `Σ行 total_fee + 运费 == 单 total_fee` 因此不破；
 *  2. **只认申报了活动的行**：普通行即使库里存在秒杀场次也不变价；申报了却查不到活动 = 直接拒单，
 *     绝不"按活动价下一笔普通单"；
 *  3. **池子三列 CAS**（D24）：下单占 `hold_store`、结清 `hold→sales`、取消释放，
 *     限购把未付在途单一起算，所以"页面说能买、提交被拒"不会出现；
 *  4. **未付不占成团人数，名额必须回补**（D25）：取消/超时 = 删成员行 + 席位回退 + 活动 `store` 回补，
 *     三件事同事务；
 *  5. **失败团的钱走 M1-1 链路**（D26）：`autoApprove` 仅退款 + 当场确认退款，与"商家同意退款"
 *     共用同一套额度守恒闸门与渠道幂等，这里额外补两份库存（商品 + 活动名额）；
 *  6. **未成团不得发货**（§14.6 末行）：订单状态本身不新增，卡口只读团队状态。
 *
 * 演示数据：商品 3=8900 分（`goods_id` 3、模板 1 全国包邮）、商品 2=199900 分、商品 1 不在任何场次里；
 * seed 的秒杀场次 1 与拼团活动 1 **都是已结束窗口**，永远不进算价链；本案自建的活动自己停用收尾。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccsg;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class SeckillGroupsEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var seckillJob: SeckillSettleJob
    @Autowired lateinit var groupsJob: GroupsSettleJob
    @Autowired lateinit var timeoutJob: OrderTimeoutJob

    private var admin = ""
    private var member = ""
    private var member2 = ""
    private var viewer = ""

    /** 秒杀：K1 主战场 / K2 单笔限额 / K3 未开始（只给过滤用）/ K4 短窗（验 `auto_cancel_time` 上限） */
    private var sk1 = 0L
    private var sk2 = 0L
    private var sk3 = 0L
    private var sk4 = 0L

    /** 拼团：G1 主战场 / G2 机器人+限次 / G3 未开始 */
    private var g1 = 0L
    private var g2 = 0L
    private var g3 = 0L

    /** G1 配满后的活动额度水位，用来验「额度 = 水位 − 仍被占住的席位」这条守恒式 */
    private var g1StoreFull = 0L

    private var secOrder = ""
    private var grpOrder = ""
    private var teamA = ""

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

    private fun memberOf(url: String, body: Map<String, Any?>? = null, m: String = "GET", token: String? = null) =
        data(call(m, url, token ?: member, body), "member $m $url")

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

    // ===== 业务件 =====

    private fun parseItems(items: String): List<Map<String, Long>> =
        items.split(",").filter { it.isNotBlank() }.map { seg ->
            val s = seg.split(":")
            mapOf("item_id" to s[0].toLong(), "num" to s[1].toLong())
        }

    /** ORDER-1 预览：fastbuy 临时行 + 活动坐标（§14.3 的 `order_class`/`act_id`） */
    private fun preview(items: String, cls: String? = null, actId: Long? = null, token: String? = null): JsonNode =
        memberOf(
            "/api/h5app/wxapp/getFreightFee",
            linkedMapOf<String, Any?>(
                "cart_type" to "fastbuy",
                "items" to parseItems(items),
                "order_class" to cls,
                "act_id" to actId,
            ).filterValues { it != null },
            "POST",
            token,
        )

    private fun orderBody(items: String, cls: String? = null, actId: Long? = null, teamId: String? = null): Map<String, Any?> =
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
            "order_class" to cls,
            "act_id" to actId,
            "team_id" to teamId,
            "order_from" to "h5",
        ).filterValues { it != null }

    private fun order(items: String, cls: String? = null, actId: Long? = null, teamId: String? = null, token: String? = null): JsonNode =
        memberOf("/api/h5app/wxapp/order_new", orderBody(items, cls, actId, teamId), "POST", token)

    /** PAY-2 建支付单 + wxpay 回调结清（本案不依赖余额与积分账户），返回支付单号供重放 */
    private fun pay(orderId: String, token: String? = null): String {
        val d = memberOf("/api/h5app/wxapp/payment", mapOf("order_id" to orderId, "pay_type" to "wxpay"), "POST", token)
        val tradeId = d.path("trade_id").asText()
        assertTrue(notifyWx(tradeId, d.path("total_fee").asLong(), "WX-$tradeId").contains("SUCCESS"), "回调应应答成功")
        return tradeId
    }

    /** 渠道重放同一条通知：真实渠道就会重复推，幂等必须扛住 */
    private fun notifyWx(tradeId: String, totalFee: Long, txn: String): String = utf8(
        mvc.perform(
            post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId)
                .param("total_fee", totalFee.toString())
                .param("transaction_id", txn)
                .param("trade_state", "SUCCESS"),
        ).andReturn().response.contentAsByteArray,
    )

    private fun cancel(orderId: String, token: String? = null) {
        memberOf("/api/h5app/wxapp/order/cancel", mapOf("order_id" to orderId, "cancel_reason" to "端到端清理"), "POST", token)
    }

    /** §14.5 守恒式：三类抵扣逐行分摊，活动让利不进 `discount_fee` */
    private fun assertConservation(p: JsonNode, label: String) {
        val lines = p.path("items")
        assertTrue(lines.size() > 0, "$label：预览至少要有一行")
        assertEquals(
            p.path("total_fee").asLong() - p.path("freight_fee").asLong(),
            lines.sumOf { it.path("total_fee").asLong() },
            "$label：Σ行应付 + 运费 = 单应付",
        )
        assertEquals(p.path("discount_fee").asLong(), lines.sumOf { it.path("discount_fee").asLong() }, "$label：折让逐行守恒")
        assertEquals(
            p.path("activity_price_fee").asLong(),
            lines.sumOf { it.path("activity_price_fee").asLong() },
            "$label：活动让利逐行守恒",
        )
        assertTrue(lines.all { it.path("total_fee").asLong() >= 0 }, "$label：行应付不得为负")
    }

    // ===== 库内读数 =====

    private fun longOf(sql: String, vararg v: Any): Long = jdbc.queryForObject(sql, Long::class.java, *v) ?: -1L

    private fun intOf(sql: String, vararg v: Any): Int = jdbc.queryForObject(sql, Int::class.java, *v) ?: -1

    private fun strOf(sql: String, vararg v: Any): String = jdbc.queryForObject(sql, String::class.java, *v) ?: ""

    /** 秒杀池三个数（D24） */
    private fun pool(act: Long, item: Long, col: String): Long =
        longOf("SELECT $col FROM promotions_seckill_rel_goods WHERE seckill_id = ? AND item_id = ?", act, item)

    /** 可购量是**算出来的**（D24：总 − 已售 − 占用），库里没有这一列，别去 SELECT */
    private fun avail(act: Long, item: Long): Long =
        pool(act, item, "activity_store") - pool(act, item, "sales_store") - pool(act, item, "hold_store")

    private fun gStore(act: Long): Long =
        longOf("SELECT store FROM promotion_groups_activity WHERE groups_activity_id = ?", act)

    private fun gEnd(act: Long): Long =
        longOf("SELECT end_time FROM promotion_groups_activity WHERE groups_activity_id = ?", act)

    private fun teamVal(teamId: String, col: String): Long =
        longOf("SELECT $col FROM promotion_groups_team WHERE team_id = ?", teamId)

    private fun orderCol(orderId: String, col: String): String =
        strOf("SELECT $col FROM orders_normal_orders WHERE order_id = ?", orderId.toLong())

    private fun orderNum(orderId: String, col: String): Long =
        longOf("SELECT $col FROM orders_normal_orders WHERE order_id = ?", orderId.toLong())

    private fun memberRows(teamId: String): Int =
        intOf("SELECT COUNT(*) FROM promotion_groups_team_member WHERE team_id = ?", teamId)

    /** 团况一览（失败时把每团的 状态/人数/机器人数 摊开，免得对着一个总数猜） */
    private fun teamsDump(act: Long): String =
        jdbc.queryForList(
            "SELECT team_id, team_status, join_person_num, robot_num FROM promotion_groups_team WHERE act_id = ? ORDER BY id", act,
        ).joinToString(" ") { m -> "${m["TEAM_ID"]}:${m["TEAM_STATUS"]}/n${m["JOIN_PERSON_NUM"]}/r${m["ROBOT_NUM"]}" }

    /**
     * 活动额度守恒（D25）：`store` 只该被**进行中与已成团**的席位占住，
     * 失败团与取消的单都必须把额度还回来。机器人只占团席位不占货，所以本案只在 `robot=false` 的 G1 上验。
     */
    private fun assertStoreInvariant(act: Long, full: Long, label: String) {
        val occupied = longOf(
            "SELECT COALESCE(SUM(join_person_num), 0) FROM promotion_groups_team WHERE act_id = ? AND team_status IN (1, 2)", act,
        )
        assertEquals(full - occupied, gStore(act), "$label：额度 = 满额 − 仍被占住的席位｜团况 ${teamsDump(act)}")
    }

    private fun itemStore(itemId: Long): Long = longOf("SELECT store FROM items WHERE item_id = ?", itemId)

    // ===================== 秒杀：后台配置面 =====================

    @Test @Order(1) fun `令牌就绪`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001",
            "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        member2 = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002",
            "check_type" to "mobile", "vcode" to "8888",
        )), "member2").path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank() && member2.isNotBlank() && viewer.isNotBlank())
    }

    @Test @Order(2) fun `SK-A1 建场次 契约与配置校验`() {
        val a = adminOf("/api/seckill/activities", skBody("端到端秒杀"), "POST")
        assertContract(a, SeckillContract.ACT_DETAIL, "SK-A1/A3 场次行")
        sk1 = a.path("seckill_id").asLong()
        assertTrue(sk1 > 0)
        assertEquals("doing", a.path("date_status").asText(), "时间窗派生，不落列")
        assertEquals(1, a.path("activity_type").asInt(), "出参双形态：整数码给源系统后台")
        assertEquals("normal", a.path("seckill_type").asText(), "本项目列值给新前端")
        assertEquals("普通秒杀", a.path("seckill_type_text").asText())
        assertEquals(0, a.path("items").size(), "场次只是容器，价格与库存归 SK-A6 的商品行")
        assertTrue(a.path("release_time").asLong() > 0, "建好即发布时间")

        // 两种类型写法都收：源系统后台发整数码，本项目表单发列值
        val ltd = adminOf("/api/seckill/activities", skBody("端到端限时抢购", activityType = 2), "POST")
        assertEquals(2, ltd.path("activity_type").asInt())
        assertEquals("limited_time_sale", ltd.path("seckill_type").asText())
        assertEquals("限时抢购", ltd.path("seckill_type_text").asText())

        val badName = expect("POST", "/api/seckill/activities", admin, skBody(""), 400)
        assertTrue(badName.path("errors").path("activity_name").isArray)
        val badWindow = expect("POST", "/api/seckill/activities", admin,
            skBody("窗口反了", startOffset = 3600, endOffset = -100), 400)
        assertTrue(badWindow.path("errors").path("activity_end_time").isArray, badWindow.toString())
        val badValidity = expect("POST", "/api/seckill/activities", admin, skBody("时限超上限", validityPeriod = 1441), 400)
        assertEquals(listOf("支付时限 0..1440 分钟"), badValidity.path("errors").path("validity_period").map { it.asText() })
        val badMoney = expect("POST", "/api/seckill/activities", admin, skBody("负限额", limitMoney = -1), 400)
        assertEquals(listOf("单笔限额不能为负"), badMoney.path("errors").path("limit_money").map { it.asText() })
        val badType = expect("POST", "/api/seckill/activities", admin, skBody("不认识类型", activityType = 7), 400)
        assertTrue(badType.path("errors").path("seckill_type")[0].asText().contains("只支持"), badType.toString())

        sk2 = createSk(skBody("端到端限额场次", limitMoney = 12000, validityPeriod = 5))
        sk3 = createSk(skBody("端到端未开始场次", startOffset = 3600))
        sk4 = createSk(skBody("端到端短窗场次", startOffset = -60, endOffset = 180))
        assertEquals("pending", adminOf("/api/seckill/activities/$sk3").path("date_status").asText())
    }

    @Test @Order(3) fun `SK-A6 圈品定价 库存只加不减`() {
        val saved = adminOf("/api/seckill/activities/$sk1/goods", skGoods(
            skItem(3, 5900, store = 10, limitNum = 3),
            skItem(2, 99900, store = 3),
        ), "PUT")
        assertContract(saved, SeckillContract.GOODS_SAVED, "SK-A6 保存出参")
        assertEquals(2, saved.path("items").size())
        val nut = saved.path("items").first { it.path("item_id").asLong() == 3L }
        assertContract(nut, SeckillContract.GOODS_ROW, "商品行")
        assertEquals(10L, nut.path("activity_store").asLong())
        assertEquals(0L, nut.path("sales_store").asLong())
        assertEquals(0L, nut.path("hold_store").asLong(), "后台默认看不见占用，但键位必须在")
        assertEquals(10L, nut.path("available").asLong(), "可购 = 总 − 已售 − 占用")
        assertEquals("每日坚果礼盒", nut.path("item_title").asText(), "标题服务端从商品表带入")

        // 已存在的行：`activity_store` 是增量，且只允许加
        val plus = adminOf("/api/seckill/activities/$sk1/goods", skGoods(skItem(3, 5900, store = 5, limitNum = 3)), "PUT")
        assertEquals(15L, goodsOf(plus, 3).path("activity_store").asLong(), "加量走 CAS")
        val minus = expect("PUT", "/api/seckill/activities/$sk1/goods", admin, skGoods(skItem(3, 5900, store = -5)), 422)
        assertTrue(minus.path("message").asText().contains("只能加不能减"), minus.toString())
        assertEquals(15L, pool(sk1, 3, "activity_store"), "被拒的那次没有动库存")

        // 其它场次**故意圈别的商品**：item 3 的活动价只由 sk1（在窗）与 sk3（未开始）提供，
        // 这样"停用 sk1 后商详角标清空"才是可证的，而不是被别的场次顶掉。
        adminOf("/api/seckill/activities/$sk2/goods", skGoods(skItem(2, 6000, store = 5)), "PUT")
        adminOf("/api/seckill/activities/$sk4/goods", skGoods(skItem(2, 8000, store = 2)), "PUT")
        adminOf("/api/seckill/activities/$sk3/goods", skGoods(skItem(3, 5000, store = 1)), "PUT")

        // 配置台的拒绝全是 400（表单不合法），业务拒绝才是 422
        val noItems = expect("PUT", "/api/seckill/activities/$sk1/goods", admin, mapOf("items" to emptyList<Any>()), 400)
        assertEquals(listOf("至少要配一个秒杀商品"), noItems.path("errors").path("items").map { it.asText() })
        val badPrice = expect("PUT", "/api/seckill/activities/$sk1/goods", admin, skGoods(skItem(4, 0, store = 1)), 400)
        assertTrue(badPrice.path("errors").has("items[0].activity_price"), badPrice.toString())
        val dup = expect("PUT", "/api/seckill/activities/$sk1/goods", admin, skGoods(skItem(3, 100, store = 1), skItem(3, 200, store = 1)), 400)
        assertEquals(listOf("同一商品只能配一行"), dup.path("errors").path("items").map { it.asText() })
        val notMine = expect("PUT", "/api/seckill/activities/$sk1/goods", admin, skGoods(skItem(999999, 100, store = 1)), 400)
        assertEquals(listOf("999999 不属于本租户"), notMine.path("errors").path("item_id").map { it.asText() })
        val negNew = expect("PUT", "/api/seckill/activities/$sk2/goods", admin, skGoods(skItem(1, 100, store = -3)), 400)
        assertEquals(listOf("不能为负"), negNew.path("errors").path("activity_store").map { it.asText() }, negNew.toString())
        expect("PUT", "/api/seckill/activities/999999/goods", admin, skGoods(skItem(3, 100)), 404)
    }

    @Test @Order(4) fun `SK-A2 A3 A4 A5 列表 详情 编辑 停用`() {
        val doing = adminOf("/api/seckill/activities?date_status=doing&pageSize=50")
        assertContract(doing.path("list")[0], SeckillContract.ACT_ROW, "SK-A2 场次行")
        assertTrue(doing.path("list").filter { it.path("activity_name").asText().contains("端到端") }
            .all { it.path("date_status").asText() == "doing" }, "date_status 过滤")
        assertTrue(adminOf("/api/seckill/activities?date_status=pending&pageSize=50").path("list")
            .any { it.path("seckill_id").asLong() == sk3 }, "未开始的场次只出现在 pending")
        assertTrue(adminOf("/api/seckill/activities?date_status=end&pageSize=50").path("list")
            .none { it.path("seckill_id").asLong() == sk1 }, "在窗场次不属于 end")
        assertTrue(adminOf("/api/seckill/activities?disabled=true&pageSize=50").path("list")
            .all { it.path("disabled").asBoolean() }, "disabled 过滤")

        // SK-A4 是 PATCH：只覆盖给出的列，改时限不会把限额与名称抹掉
        val upd = adminOf("/api/seckill/activities/$sk1", mapOf("activity_name" to "端到端秒杀改名", "validity_period" to 5), "PUT")
        assertEquals("端到端秒杀改名", upd.path("activity_name").asText())
        assertEquals(5, upd.path("validity_period").asInt())
        assertEquals(0L, upd.path("limit_money").asLong(), "没给的列保持原值")
        assertEquals(15L, pool(sk1, 3, "activity_store"), "编辑场次不动商品行")
        adminOf("/api/seckill/activities/$sk1", mapOf("validity_period" to 10), "PUT")

        // SK-A5 停用 = 前台入口与算价链一起关门
        assertTrue(adminOf("/api/seckill/activities/$sk1/status", mapOf("disabled" to true), "PUT").path("disabled").asBoolean())
        val front = memberOf("/api/h5app/wxapp/seckill/activities?pageSize=50").path("list")
        assertTrue(front.none { it.path("seckill_id").asLong() == sk1 }, "停用场次不进前台")
        assertTrue(memberOf("/api/h5app/wxapp/items/3/promotions").path("seckill").isNull, "停用后商详角标也没有秒杀价")
        expect("GET", "/api/h5app/wxapp/seckill/activities/$sk1/items", member, null, 404)
        assertEquals(17800L, preview("3:2", "seckill", sk1).path("item_fee").asLong(), "停用场次不改单价基准")
        val bad = expect("PUT", "/api/seckill/activities/$sk1/status", admin, emptyMap(), 400)
        assertEquals(listOf("不能为空"), bad.path("errors").path("disabled").map { it.asText() })

        adminOf("/api/seckill/activities/$sk1/status", mapOf("disabled" to false), "PUT")
        assertEquals(11800L, preview("3:2", "seckill", sk1).path("item_fee").asLong(), "恢复后活动价回来了")
        expect("GET", "/api/seckill/activities/999999", admin, null, 404)
        expect("GET", "/api/seckill/activities/abc", admin, null, 404)
    }

    @Test @Order(5) fun `SK-F1 F2 前台场次 商品 与 MK-F3 秒杀价`() {
        val acts = memberOf("/api/h5app/wxapp/seckill/activities?pageSize=50")
        assertContract(acts.path("list")[0], SeckillContract.FRONT_ROW, "SK-F1 场次行")
        val mine = acts.path("list").first { it.path("seckill_id").asLong() == sk1 }
        assertEquals(2, mine.path("items_count").asInt(), "计数一次分组统计，不在循环里查 N 次")
        assertEquals(10, mine.path("validity_period").asInt())

        val items = memberOf("/api/h5app/wxapp/seckill/activities/$sk1/items").path("items")
        assertContract(items[0], SeckillContract.FRONT_ITEM, "SK-F2 商品行")
        assertEquals(2, items.size())
        val nut = items.first { it.path("item_id").asLong() == 3L }
        assertEquals(5900L, nut.path("activity_price").asLong())
        assertEquals(8900L, nut.path("origin_price").asLong(), "现价取商品表，不是活动价")
        assertEquals(9900L, nut.path("market_price").asLong())
        assertEquals(15L, nut.path("available").asLong(), "可购 = 总 − 已售 − 占用")
        assertEquals(3, nut.path("limit_num").asInt())
        assertEquals(0L, nut.path("bought_by_me").asLong(), "此刻还没有秒杀单")
        assertEquals(3, nut.path("remain_for_me").asInt(), "限购余量与服务端判据同一个数")
        assertEquals("seckill", nut.path("activity_type").asText(), "前台拿它拼 ORDER-2 的活动坐标")
        assertEquals(sk1, nut.path("activity_id").asLong())
        assertEquals("onsale", nut.path("item_status").asText())

        // MK-F3 的 seckill 键位（#57 落实，此前占位为 null）
        val promo = memberOf("/api/h5app/wxapp/items/3/promotions")
        assertContract(promo, MarketingContract.ITEM_PROMOTIONS, "MK-F3 商详营销聚合")
        assertContract(promo.path("seckill"), SeckillContract.ITEM_OFFER, "MK-F3 seckill 键位")
        assertEquals(sk1, promo.path("seckill").path("seckill_id").asLong())
        assertEquals(5900L, promo.path("seckill").path("activity_price").asLong())
        assertEquals("seckill", promo.path("seckill").path("activity_type").asText())
        assertEquals(0L, promo.path("seckill").path("bought_by_me").asLong(), "角标面不带个人已购")
        assertTrue(memberOf("/api/h5app/wxapp/items/1/promotions").path("seckill").isNull, "没进场次的商品没有秒杀价")
    }

    // ===================== 算价与下单口径 =====================

    @Test @Order(6) fun `ORDER-1 秒杀预览 改的是单价基准不是折让`() {
        val plain = preview("3:2")
        assertEquals(17800L, plain.path("item_fee").asLong(), "没申报活动的行 = 现价，库里存在场次也不变价")
        assertEquals(0L, plain.path("activity_price_fee").asLong())
        assertEquals("normal", plain.path("items")[0].path("activity_type").asText())

        // 传错类目归一成 normal，不打断结算（与 ORDER-2 同一口径）
        assertEquals(17800L, preview("3:2", "bargain", sk1).path("item_fee").asLong())

        val p = preview("3:2", "seckill", sk1)
        assertEquals(11800L, p.path("item_fee").asLong(), "行小计按活动价算：5900×2")
        assertEquals(6000L, p.path("activity_price_fee").asLong(), "让利 3000×2 记在活动价字段")
        assertEquals(0L, p.path("discount_fee").asLong(), "活动价不进 discount_fee，否则守恒式当场破")
        assertEquals(11800L, p.path("total_fee").asLong())
        assertEquals(0L, p.path("freight_fee").asLong(), "模板 1 全国包邮")
        assertConservation(p, "秒杀预览")
        assertContract(p, MarketingContract.PREVIEW_MARKETING, "ORDER-1 M1-3 抵扣字段")
        val line = p.path("items")[0]
        assertEquals(5900L, line.path("price").asLong(), "行单价已被换成活动价")
        assertEquals(8900L, line.path("origin_price").asLong())
        assertEquals(6000L, line.path("activity_price_fee").asLong())
        assertEquals("seckill", line.path("activity_type").asText())
        assertEquals(sk1, line.path("activity_id").asLong())
        assertEquals(11800L, line.path("total_fee").asLong())
        assertContract(p.path("discount_info")[0], MarketingContract.DISCOUNT_INFO, "discount_info 项")
        assertEquals("seckill", p.path("discount_info")[0].path("type").asText())
        assertEquals("限时秒杀，现价 89 元，活动价 59 元", p.path("discount_info")[0].path("rule").asText())
        assertEquals(6000L, p.path("discount_info")[0].path("discount_fee").asLong())
        assertEquals(1, p.path("discount_info").size(), "本 suite 没有满减活动，折让构成只有活动价一条")

        // 商品不在场次里：预览照常出原价（拒单发生在下单侧），否则结算页会凭空少一行
        val miss = preview("1:1", "seckill", sk1)
        assertEquals(199900L, miss.path("total_fee").asLong())
        assertEquals(0L, miss.path("activity_price_fee").asLong())

        // 未开始与已结束的场次都不进算价链
        assertEquals(17800L, preview("3:2", "seckill", sk3).path("item_fee").asLong())
        assertEquals(17800L, preview("3:2", "seckill", 1).path("item_fee").asLong(), "seed 的已结束场次同样不进链")
    }

    @Test @Order(7) fun `ORDER-2 秒杀下单 占池 与 auto_cancel_time 双重上限`() {
        val o = order("3:2", "seckill", sk1)
        assertContract(o, OrderContract.CREATE, "ORDER-2 出参")
        secOrder = o.path("order_id").asText()
        assertTrue(o.path("team_id").isNull, "秒杀单没有团号")
        assertEquals(11800L, o.path("total_fee").asLong(), "下单重算与预览同一个 quote")
        assertEquals(2L, pool(sk1, 3, "hold_store"), "下单只占 hold_store，销量要等结清")
        assertEquals(13L, avail(sk1, 3))
        assertEquals("seckill", orderCol(secOrder, "order_class"))
        assertEquals(sk1, orderNum(secOrder, "act_id"), "单头记下活动坐标，关单时才知道放哪个池子")
        assertEquals("NOTPAY", orderCol(secOrder, "order_status"))
        assertEquals(600L, orderNum(secOrder, "auto_cancel_time") - orderNum(secOrder, "created"),
            "validity_period=10 分钟：D24 用它代替 Redis 资格票")

        // 优惠快照：`orders_rel_promotions` 里看得见"这单的便宜是秒杀给的"
        assertEquals(1, intOf("SELECT COUNT(*) FROM orders_rel_promotions WHERE moid = ?", secOrder.toLong()))
        assertEquals("seckill", strOf("SELECT activity_type FROM orders_rel_promotions WHERE moid = ? LIMIT 1", secOrder.toLong()))
        assertEquals(sk1, longOf("SELECT activity_id FROM orders_rel_promotions WHERE moid = ? LIMIT 1", secOrder.toLong()))
        assertEquals(6000L, longOf("SELECT discount_fee FROM orders_rel_promotions WHERE moid = ? LIMIT 1", secOrder.toLong()))

        // 第二个上限：活动结束比支付时限更早时取结束时间
        val short = order("2:1", "seckill", sk4)
        val shortId = short.path("order_id").asText()
        val span = orderNum(shortId, "auto_cancel_time") - orderNum(shortId, "created")
        assertTrue(span in 1..180, "auto_cancel_time 不得越过活动结束：$span")
        assertEquals(1L, pool(sk4, 2, "hold_store"))
        cancel(shortId)
        assertEquals(0L, pool(sk4, 2, "hold_store"), "关单即释放占用")
    }

    @Test @Order(8) fun `结清 hold 转 sales 重复回调不双计`() {
        val tradeId = pay(secOrder)
        assertEquals("PAYED", orderCol(secOrder, "order_status"))
        assertEquals(0L, pool(sk1, 3, "hold_store"), "占用清零")
        assertEquals(2L, pool(sk1, 3, "sales_store"), "占用转成已售，同一语句一起动")
        // 渠道重放同一条通知（不能再走 PAY-2：单已付，结清入口只吃回调）
        assertTrue(notifyWx(tradeId, 11800L, "WX-$tradeId").contains("SUCCESS"), "重放应答成功而不是报错")
        assertEquals(2L, pool(sk1, 3, "sales_store"), "重复回调不会把销量加两次（settle 的 CAS 守卫）")
        assertEquals(0L, pool(sk1, 3, "hold_store"))
        assertEquals(1, intOf("SELECT COUNT(*) FROM trade WHERE order_id = ? AND trade_state = 'SUCCESS'", secOrder.toLong()),
            "结清也只落一次账")

        // 限购把已付与在途一起算（否则反复下待付款单能绕过 limit_num）
        val nut = memberOf("/api/h5app/wxapp/seckill/activities/$sk1/items").path("items")
            .first { it.path("item_id").asLong() == 3L }
        assertEquals(2L, nut.path("bought_by_me").asLong())
        assertEquals(1, nut.path("remain_for_me").asInt())
    }

    @Test @Order(9) fun `取消释放占用 与 孤儿占用由结算任务回收`() {
        val o = order("3:1", "seckill", sk1).path("order_id").asText()
        assertEquals(1L, pool(sk1, 3, "hold_store"))
        cancel(o)
        assertEquals(0L, pool(sk1, 3, "hold_store"), "取消腿正常时占用当场回退")
        // 断腿模拟：单子已经取消但释放没跑到（进程被杀 / 人工改库），池子被永久占住
        val o2 = order("3:1", "seckill", sk1).path("order_id").asText()
        assertEquals(1L, pool(sk1, 3, "hold_store"))
        jdbc.update("UPDATE orders_normal_orders SET order_status = 'CANCEL' WHERE order_id = ?", o2.toLong())
        assertEquals(1L, pool(sk1, 3, "hold_store"), "改状态不会自动放池子，这正是「悬空占用」")
        assertEquals(1, seckillJob.pending(), "对账扫描认出这一行")
        assertTrue(seckillJob.runNow() >= 1, "按差额回收")
        assertEquals(0L, pool(sk1, 3, "hold_store"), "孤儿占用清零")

        // 回收只动差额：仍有一笔未付在途单时，那部分占用不能被当成孤儿
        val keep = order("3:1", "seckill", sk1).path("order_id").asText()
        assertEquals(1L, pool(sk1, 3, "hold_store"))
        assertEquals(0, seckillJob.pending(), "在途单占的不是孤儿")
        assertEquals(0, seckillJob.runNow(), "没有孤儿就不该动")
        assertEquals(1L, pool(sk1, 3, "hold_store"))
        cancel(keep)
        assertEquals(0L, pool(sk1, 3, "hold_store"))
    }

    @Test @Order(10) fun `限购 限额 名额 与活动申报一致性全部挡住`() {
        // 限购 3 件：已成交 2 件，再来 2 件就越线
        val over = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:2", "seckill", sk1), 422)
        assertTrue(over.path("message").asText().contains("限购 3 件"), over.toString())
        assertTrue(over.path("message").asText().contains("已使用 2"), over.path("message").asText())
        assertEquals(0L, pool(sk1, 3, "hold_store"), "被拒的单没占池子")

        // 名额不足：商品 2 只有 3 件活动库存
        val soldOut = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("2:4", "seckill", sk1), 422)
        assertTrue(soldOut.path("message").asText().contains("活动名额不足"), soldOut.toString())

        // 单笔限额 120 元：3×6000 = 18000 分（sk2 圈的是商品 2，现价 1999 元）
        val limited = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("2:3", "seckill", sk2), 422)
        assertTrue(limited.path("message").asText().contains("单笔限额 120 元"), limited.toString())

        // 申报了活动却查不到活动：场次里没有这个商品，直接拒，绝不回落成普通单
        val miss = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("1:1", "seckill", sk1), 422)
        assertTrue(miss.path("message").asText().contains("不在此场次"), miss.toString())

        // 只带 act_id 不带 order_class：400 表单错，提示补类目
        val noClass = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:1", null, sk1), 400)
        assertEquals(listOf("请同时指定 order_class"), noClass.path("errors").path("act_id").map { it.asText() })

        // 没给 act_id 的普通单照旧成交，且不占秒杀池
        val naive = order("3:1")
        assertEquals(8900L, naive.path("total_fee").asLong(), "普通单按现价成交")
        assertEquals("normal", orderCol(naive.path("order_id").asText(), "order_class"))
        assertEquals(0L, pool(sk1, 3, "hold_store"), "普通单不占秒杀池")
        cancel(naive.path("order_id").asText())
    }

    @Test @Order(11) fun `SK-A7 报表`() {
        val r = adminOf("/api/seckill/activities/$sk1/report")
        assertContract(r, SeckillContract.REPORT, "SK-A7 报表")
        assertEquals(2, r.path("item_count").asInt())
        assertEquals(18L, r.path("total_store").asLong(), "15 + 3")
        assertEquals(2L, r.path("sales_store").asLong(), "只有已付的算销量")
        assertEquals(0L, r.path("hold_store").asLong(), "本案的占用都清了")
        assertEquals(16L, r.path("available_store").asLong())
        assertEquals(11800L, r.path("sales_fee").asLong(), "已成交额 = 活动价 × 已售")
        assertEquals(0L, r.path("hold_fee").asLong(), "占用中的钱还没到账，不算业绩")
        assertContract(r.path("items")[0], SeckillContract.GOODS_ROW, "报表行")
        expect("GET", "/api/seckill/activities/999999/report", admin, null, 404)
    }

    // ===================== 拼团：后台配置面 =====================

    @Test @Order(12) fun `GP-A1 建拼团 契约与配置校验`() {
        val a = adminOf("/api/groups/activities", gpBody("端到端 2人拼团"), "POST")
        assertContract(a, GroupsContract.ADMIN_DETAIL, "GP-A1/A3 活动行")
        g1 = a.path("groups_activity_id").asLong()
        assertTrue(g1 > 0)
        assertEquals("每日坚果礼盒", a.path("goods_name").asText())
        assertEquals(8900L, a.path("origin_price").asLong())
        assertEquals(6900L, a.path("act_price").asLong())
        assertEquals("doing", a.path("date_status").asText())
        assertEquals(20L, a.path("store").asLong())
        assertEquals(0, a.path("team_total").asInt())

        val badPrice = expect("POST", "/api/groups/activities", admin, gpBody("拼团价不低于现价", actPrice = 8900), 400)
        assertEquals(listOf("拼团价必须低于商品现价 8900 分"), badPrice.path("errors").path("act_price").map { it.asText() })
        val badGoods = expect("POST", "/api/groups/activities", admin, gpBody("没圈商品", goodsId = 0), 400)
        assertTrue(badGoods.path("errors").path("goods_id").isArray)
        val notMine = expect("POST", "/api/groups/activities", admin, gpBody("别家商品", goodsId = 999999), 400)
        assertEquals(listOf("商品不存在或不属于本租户"), notMine.path("errors").path("goods_id").map { it.asText() })
        val badPerson = expect("POST", "/api/groups/activities", admin, gpBody("1 人团", personNum = 1), 400)
        assertEquals(listOf("成团人数 2..1000"), badPerson.path("errors").path("person_num").map { it.asText() })
        val badLimit = expect("POST", "/api/groups/activities", admin, gpBody("时效 0", limitTime = 0), 400)
        assertEquals(listOf("成团时效 1..720 小时"), badLimit.path("errors").path("limit_time").map { it.asText() })
        val badStore = expect("POST", "/api/groups/activities", admin, gpBody("负库存", store = -1), 400)
        assertEquals(listOf("不能为负"), badStore.path("errors").path("store").map { it.asText() })
        val badName = expect("POST", "/api/groups/activities", admin, gpBody(""), 400)
        assertTrue(badName.path("errors").path("act_name").isArray)

        g3 = createGp(gpBody("端到端未开始拼团", beginOffset = 3600))
    }

    @Test @Order(13) fun `GP-A2 A4 A6 列表 编辑 团列表`() {
        val doing = adminOf("/api/groups/activities?date_status=doing&pageSize=50")
        assertContract(doing.path("list")[0], GroupsContract.ACT_ROW, "GP-A2 活动行")
        assertTrue(doing.path("list").all { it.path("date_status").asText() == "doing" })
        assertTrue(adminOf("/api/groups/activities?date_status=pending&pageSize=50").path("list")
            .any { it.path("groups_activity_id").asLong() == g3 }, "未开始活动只在 pending 里")
        assertTrue(adminOf("/api/groups/activities?goods_id=3&pageSize=50").path("list")
            .all { it.path("goods_id").asLong() == 3L }, "按商品过滤")

        // GP-A4 也是 PATCH，且 `store` 只允许加
        val upd = adminOf("/api/groups/activities/$g1", mapOf("store" to 22), "PUT")
        assertEquals(22L, upd.path("store").asLong(), "活动库存只能加：改小等于把已成团的货退没")
        g1StoreFull = gStore(g1)
        assertEquals(6900L, upd.path("act_price").asLong(), "没给的列不动")
        val minus = expect("PUT", "/api/groups/activities/$g1", admin, mapOf("store" to 10), 422)
        assertTrue(minus.path("message").asText().contains("只能加不能减"), minus.toString())
        assertEquals(22L, gStore(g1), "被拒的改小没动库存")

        assertContract(adminOf("/api/groups/teams?pageSize=50"), setOf("total_count", "list"), "GP-A6 分页壳")
        assertEquals(0L, adminOf("/api/groups/teams?act_id=$g1&pageSize=50").path("total_count").asLong(), "此刻还没有团")
        expect("GET", "/api/groups/activities/999999", admin, null, 404)
        expect("GET", "/api/groups/activities/abc", admin, null, 404)
    }

    @Test @Order(14) fun `GP-F1 F2 前台活动列表 详情`() {
        val acts = memberOf("/api/h5app/wxapp/groups/activities?pageSize=50")
        assertContract(acts.path("list")[0], GroupsContract.FRONT_ROW, "GP-F1 活动行")
        val mine = acts.path("list").first { it.path("groups_activity_id").asLong() == g1 }
        assertEquals(0, mine.path("team_count").asInt(), "还没人开团")
        assertEquals("groups", mine.path("activity_type").asText(), "前台拿它拼 ORDER-2 的活动坐标")
        assertEquals(g1, mine.path("activity_id").asLong())
        assertTrue(acts.path("list").none { it.path("groups_activity_id").asLong() == g3 }, "未开始活动不进默认列表")
        assertTrue(acts.path("list").all { !it.path("disabled").asBoolean() }, "前台面不出停用位")

        val d = memberOf("/api/h5app/wxapp/groups/activities/$g1")
        assertContract(d, GroupsContract.FRONT_DETAIL, "GP-F2 详情")
        assertEquals(0, d.path("teams").size())
        assertEquals("拼团说明", d.path("instruction").asText())
        expect("GET", "/api/h5app/wxapp/groups/activities/999999", member, null, 404)
        expect("GET", "/api/h5app/wxapp/groups/teams/NOT-EXIST", member, null, 404)
    }

    @Test @Order(15) fun `ORDER-1 拼团预览 与 会员折的先后顺序`() {
        val p = preview("3:1", "groups", g1)
        assertEquals(6900L, p.path("item_fee").asLong(), "行基准换成拼团价")
        assertEquals(2000L, p.path("activity_price_fee").asLong())
        assertEquals(0L, p.path("discount_fee").asLong(), "普通会员无等级折")
        assertEquals(6900L, p.path("total_fee").asLong())
        assertConservation(p, "拼团预览")
        assertEquals("groups", p.path("discount_info")[0].path("type").asText())
        assertEquals("2人团，现价 89 元，活动价 69 元", p.path("discount_info")[0].path("rule").asText())

        // 金卡会员：等级折打在拼团价**之后**（§14.5 的 15 → 20 顺序）
        val gold = preview("3:1", "groups", g1, token = member2)
        assertEquals(2000L, gold.path("activity_price_fee").asLong(), "活动让利只由拼团价差决定")
        assertEquals(345L, gold.path("member_discount").asLong(), "5% 折让算在活动价之后的 6900 上，不是 8900")
        assertEquals(345L, gold.path("discount_fee").asLong())
        assertEquals(6555L, gold.path("total_fee").asLong())
        assertConservation(gold, "金卡拼团预览")

        // 未开始活动与 goods 对不上都不给活动价
        assertEquals(8900L, preview("3:1", "groups", g3).path("total_fee").asLong())
        assertEquals(199900L, preview("2:1", "groups", g1).path("total_fee").asLong(), "一个拼团活动只圈一个 goods_id")
    }

    @Test @Order(16) fun `ORDER-2 开团 占名额 与 待成团单唯一`() {
        val storeBefore = gStore(g1)
        val o = order("3:1", "groups", g1)
        assertContract(o, GroupsContract.ORDER_CREATE, "ORDER-2 拼团出参")
        grpOrder = o.path("order_id").asText()
        teamA = o.path("team_id").asText()
        assertTrue(teamA.startsWith("GT"), "团号 = GT + 号段（D29）：$teamA")
        assertEquals(6900L, o.path("total_fee").asLong(), "预览==下单：同一个 quote")
        assertEquals("groups", orderCol(grpOrder, "order_class"))
        assertEquals(teamA, orderCol(grpOrder, "team_id"), "团号落在单头上")
        assertEquals(teamA, memberOf("/api/h5app/wxapp/order/$grpOrder").path("orderInfo").path("team_id").asText(),
            "ORDER-5 详情也报团号：参团页与「我的团」都从订单详情跳回团")
        assertEquals(storeBefore - 1, gStore(g1), "开团即扣活动额度")
        assertEquals(1L, teamVal(teamA, "join_person_num"))
        assertEquals(1L, teamVal(teamA, "team_status"), "1 = 进行中")
        assertEquals(gEnd(g1), teamVal(teamA, "end_time"), "团时限 = min(now+24h, 活动结束)，本案取后者")
        assertEquals(1, memberRows(teamA))

        // 同一活动只允许一笔待成团单（源系统同口径）
        val again = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:1", "groups", g1), 422)
        assertTrue(again.path("message").asText().contains("未付款的参团订单"), again.toString())
        assertEquals(storeBefore - 1, gStore(g1), "被拒的第二次没扣额度")

        // 一件坐一个团席位：拼团一次只能拍 1 件
        val many = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:2", "groups", g1), 422)
        assertTrue(many.path("message").asText().contains("只能拍 1 件"), many.toString())
        assertEquals(storeBefore - 1, gStore(g1))

        // 团号不存在 = 404；活动未开始 = 422（且申报校验在算价侧就先拦住）
        val ghost = expect("POST", "/api/h5app/wxapp/order_new", member2, orderBody("3:1", "groups", g1, "GT-not-exist"), 404)
        assertTrue(ghost.path("message").asText().contains("团不存在"), ghost.toString())
        val notStarted = expect("POST", "/api/h5app/wxapp/order_new", member2, orderBody("3:1", "groups", g3, teamA), 422)
        assertTrue(notStarted.path("message").asText().contains("不在此场次"), notStarted.toString())

        val d = memberOf("/api/h5app/wxapp/groups/activities/$g1")
        assertContract(d.path("teams")[0], GroupsContract.FRONT_TEAM, "GP-F2 teams[]")
        assertEquals(1, d.path("teams").size())
        assertEquals("138****0001", d.path("teams")[0].path("head_nickname").asText(), "团长昵称只出掩码")
        assertEquals(2, d.path("teams")[0].path("person_num").asInt())
        assertEquals(1, d.path("teams")[0].path("lack_num").asInt())
        assertEquals(1, memberOf("/api/h5app/wxapp/groups/activities").path("list")
            .first { it.path("groups_activity_id").asLong() == g1 }.path("team_count").asInt())
    }

    @Test @Order(17) fun `结清成团 与 未成团不得发货`() {
        pay(grpOrder)
        assertEquals(1L, teamVal(teamA, "team_status"), "只有 1 人，还没成团")

        // 钱已到账，货不能发：§14.6 末行的卡口
        val early = expect("PUT", "/api/orders/delivery", admin,
            mapOf("order_id" to grpOrder.toLong(), "delivery_corp" to "zto", "delivery_code" to "SG-1"), 422)
        assertTrue(early.path("message").asText().contains("尚未成团"), early.toString())

        val storeBefore = gStore(g1)
        val t2 = order("3:1", "groups", g1, teamA, token = member2)
        assertEquals(teamA, t2.path("team_id").asText(), "参团回到同一个团号")
        val orderB = t2.path("order_id").asText()
        assertEquals(2L, teamVal(teamA, "join_person_num"))
        assertEquals(storeBefore - 1, gStore(g1), "参团同样扣活动额度（一件一个名额）")
        assertEquals(0L, teamVal(teamA, "robot_num"), "本案这一步还没有机器人上场")

        // 第二个人也结清 → 人数够了，团当场成
        pay(orderB, token = member2)
        assertEquals(2L, teamVal(teamA, "team_status"), "2 = 已成功")
        assertEquals(2, intOf("SELECT COUNT(*) FROM promotion_groups_team_member WHERE team_id = ? AND paid = TRUE", teamA))

        // 成团后发货放行（订单状态本身不新增：钱进来就是 PAYED，"待成团"是团队状态）
        adminOf("/api/orders/delivery", mapOf(
            "order_id" to grpOrder.toLong(), "delivery_corp" to "zto", "delivery_code" to "SG-1",
        ), "PUT")
        assertEquals("WAIT_BUYER_CONFIRM", orderCol(grpOrder, "order_status"))

        // 已成团的团号不能再进
        val dup = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:1", "groups", g1, teamA), 422)
        assertTrue(dup.path("message").asText().contains("该团已成团"), dup.toString())
    }

    @Test @Order(18) fun `GP-F3 F4 团成员 与 我的团`() {
        val t = memberOf("/api/h5app/wxapp/groups/teams/$teamA")
        assertContract(t, GroupsContract.TEAM_DETAIL, "GP-F3 团详情")
        assertEquals(2, t.path("team_status").asInt())
        assertEquals("已成团", t.path("team_status_text").asText())
        assertEquals(0, t.path("lack_num").asInt())
        assertEquals(2, t.path("members").size())
        assertContract(t.path("members")[0], GroupsContract.MEMBER_ROW, "团成员行")
        assertEquals("138****0001", t.path("members")[0].path("nickname").asText())
        assertTrue(t.path("members")[0].path("is_head").asBoolean())
        assertTrue(t.path("members").all { it.path("paid").asBoolean() && !it.path("is_robot").asBoolean() })
        assertEquals(grpOrder, t.path("members")[0].path("order_id").asText())

        val mine = memberOf("/api/h5app/wxapp/groups/my/teams")
        assertContract(mine.path("teams")[0], GroupsContract.MY_TEAM, "GP-F4 我的团")
        val row = mine.path("teams").first { it.path("team_id").asText() == teamA }
        assertTrue(row.path("is_head").asBoolean())
        assertEquals(0, row.path("lack_num").asInt())
        assertEquals(grpOrder, row.path("order_id").asText())
        assertEquals(1, mine.path("teams").size(), "本案 user 1 只参与过这一个团")

        // 后台团列表看得见同一个团
        val teams = adminOf("/api/groups/teams?act_id=$g1&team_status=2&pageSize=50")
        assertContract(teams.path("list")[0], GroupsContract.TEAM_ROW, "GP-A6 团行")
        assertEquals(teamA, teams.path("list")[0].path("team_id").asText())
    }

    @Test @Order(19) fun `未付取消与超时关单 都要退席位回补额度`() {
        val base = gStore(g1)
        val o = order("3:1", "groups", g1)
        val t = o.path("team_id").asText()
        assertEquals(base - 1, gStore(g1))
        cancel(o.path("order_id").asText())
        assertEquals(0L, teamVal(t, "join_person_num"), "席位回退")
        assertEquals(base, gStore(g1), "活动额度同事务回补，否则会出现「名额用完、团里却没人」")
        assertEquals(0, memberRows(t), "未付成员行直接删掉，不进成团计数")

        // 参团单同样归超时关单管：把 auto_cancel_time 改到过去，跑同一个关单入口
        val o2 = order("3:1", "groups", g1)
        val t2 = o2.path("team_id").asText()
        assertEquals(base - 1, gStore(g1))
        jdbc.update("UPDATE orders_normal_orders SET auto_cancel_time = ? WHERE order_id = ?",
            TimeSec.now() - 1, o2.path("order_id").asLong())
        assertEquals(1, timeoutJob.closeExpired(TimeSec.now(), 50), "超时关单扫到这笔参团单")
        assertEquals("CANCEL", orderCol(o2.path("order_id").asText(), "order_status"))
        assertEquals(0L, teamVal(t2, "join_person_num"), "关单腿同样释放席位")
        assertEquals(base, gStore(g1))
        assertEquals(0, memberRows(t2))
    }

    @Test @Order(20) fun `到期未成团 走 M1-1 退款链 并回补两份库存`() {
        val itemStoreBefore = itemStore(3)
        val storeBefore = gStore(g1)
        val o = order("3:1", "groups", g1)
        val failed = o.path("order_id").asText()
        val t = o.path("team_id").asText()
        pay(failed)
        assertEquals(1L, teamVal(t, "team_status"), "只有一人，还没成团")

        // 把团推到"已到期"，交给结算任务（本案把 @Scheduled 间隔拉到 1 小时，只手动跑一轮）
        jdbc.update("UPDATE promotion_groups_team SET end_time = ? WHERE team_id = ?", TimeSec.now() - 1, t)
        assertEquals(1, groupsJob.runNow(TimeSec.now()), "到期团处理掉一笔")
        assertEquals(3L, teamVal(t, "team_status"), "3 = 已失败")

        // 退款走 M1-1：仅退款 autoApprove + 当场确认，额度守恒与渠道幂等全部复用
        assertEquals(1, intOf("SELECT COUNT(*) FROM aftersales WHERE order_id = ? AND aftersales_type = 'ONLY_REFUND' AND aftersales_status = 2", failed.toLong()),
            "售后单已建好并走到已处理")
        assertEquals(6900L, longOf("SELECT refunded_fee FROM trade WHERE order_id = ? AND trade_state = 'SUCCESS'", failed.toLong()),
            "全额退，不新开退款通道")
        assertEquals("REFUND", orderCol(failed, "pay_status"), "整单退完只改 pay_status")
        assertEquals("PAYED", orderCol(failed, "order_status"), "订单状态不动（与 M1-1 同口径）")

        // 两份额度都要回来：商品库存 + 活动名额（M1-1 仅退款不补库存，这里显式补）
        assertEquals(storeBefore, gStore(g1), "活动额度回补")
        assertEquals(itemStoreBefore, itemStore(3), "商品库存回补")
        // 成员行不删（对账要看"这个团里谁出过钱"），但团已判死：再跑一轮结算不会复活它、更不会重复退款
        assertEquals(1, memberRows(t), "已付成员行保留做对账")
        assertEquals(0, groupsJob.runNow(TimeSec.now()), "失败团不复活")
        assertEquals(6900L, longOf("SELECT refunded_fee FROM trade WHERE order_id = ? AND trade_state = 'SUCCESS'", failed.toLong()),
            "重跑结算后退款额仍然只有一份")
        assertEquals(0L, longOf("SELECT COUNT(*) FROM aftersales WHERE order_id = ? AND aftersales_status = 0", failed.toLong()),
            "没有留下待处理的售后单")
    }

    @Test @Order(21) fun `机器人凑团 到点补人立即成团 限购按次`() {
        g2 = createGp(gpBody("端到端机器人团", actPrice = 7900, personNum = 3, store = 10, limitBuyNum = 1, robot = true, endOffset = 300))
        val o = order("3:1", "groups", g2)
        val t = o.path("team_id").asText()
        val oid = o.path("order_id").asText()
        pay(oid)
        assertEquals(1L, teamVal(t, "team_status"))
        assertEquals(1, memberRows(t))

        // 距团结束 ≤600 秒且活动开了机器人 → 补 2 个虚拟成员并当场成团
        assertEquals(600L, groupsJob.robotHorizon())
        assertEquals(1, groupsJob.runNow(TimeSec.now()), "机器人补位一条团")
        assertEquals(2L, teamVal(t, "team_status"), "补完立刻 CAS 成团")
        assertEquals(3L, teamVal(t, "join_person_num"))
        assertEquals(2L, teamVal(t, "robot_num"))
        assertEquals(3, memberRows(t))

        val d = memberOf("/api/h5app/wxapp/groups/teams/$t")
        assertEquals(2, d.path("robot_num").asInt())
        assertEquals(0, d.path("lack_num").asInt())
        val robots = d.path("members").filter { it.path("is_robot").asBoolean() }
        assertEquals(2, robots.size)
        assertTrue(robots.all { it.path("nickname").asText() == "虚拟成团" && it.path("user_id").asLong() < 0 },
            "机器人用负数 user_id：唯一索引下同一团能坐多行")
        assertTrue(d.path("members").filter { !it.path("is_robot").asBoolean() }.all { it.path("paid").asBoolean() })

        // 成团后放行发货
        adminOf("/api/orders/delivery", mapOf("order_id" to oid.toLong(), "delivery_corp" to "yto", "delivery_code" to "SG-2"), "PUT")
        assertEquals("WAIT_BUYER_CONFIRM", orderCol(oid, "order_status"))

        // limit_buy_num=1：拼团限购按"次"计（与秒杀按件同字段不同单位）
        val again = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:1", "groups", g2), 422)
        assertTrue(again.path("message").asText().contains("限购 1 次"), again.toString())
    }

    @Test @Order(22) fun `GP-A5 停用只关门 强制结束立即判失败退款`() {
        // 停用：只关新入口，在途的团继续按时间结算
        val off = adminOf("/api/groups/activities/$g2/status", mapOf("disabled" to true), "PUT")
        assertTrue(off.path("disabled").asBoolean())
        assertEquals(0, off.path("failed_teams").asInt(), "停用不当场砸团")
        val bad = expect("PUT", "/api/groups/activities/$g2/status", admin, emptyMap(), 400)
        assertTrue(bad.path("message").asText().contains("至少要给一个"), bad.toString())

        // end=true：进行中的团立刻失败并退款（PHP `finish/{id}` 的语义）
        val doing = intOf("SELECT COUNT(*) FROM promotion_groups_team WHERE act_id = ? AND team_status = 1", g1)
        val o = order("3:1", "groups", g1)
        val t = o.path("team_id").asText()
        val paid = o.path("order_id").asText()
        pay(paid)
        val storeBefore = gStore(g1)
        val ended = adminOf("/api/groups/activities/$g1/status", mapOf("end" to true), "PUT")
        assertEquals((doing + 1).toLong(), ended.path("failed_teams").asLong(), "只砸进行中的团：已成与已失败的团不动")
        assertEquals(3L, teamVal(t, "team_status"), "被强制结束的团是失败态")
        assertEquals(6900L, longOf("SELECT refunded_fee FROM trade WHERE order_id = ? AND trade_state = 'SUCCESS'", paid.toLong()),
            "强制结束同样走 M1-1 退款链")
        assertEquals(storeBefore + 1, gStore(g1), "退款侧回补被强制结束那一团的额度｜团况 ${teamsDump(g1)}")
        assertStoreInvariant(g1, g1StoreFull, "强制结束后")
        assertTrue(adminOf("/api/groups/activities/$g1").path("disabled").asBoolean(), "强制结束先停用")
        assertEquals(0, intOf("SELECT COUNT(*) FROM promotion_groups_team WHERE act_id = ? AND team_status = 1", g1), "没有进行中的团了")
        val rejected = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:1", "groups", g1), 422)
        assertTrue(rejected.path("message").asText().contains("活动已结束或该商品不在此场次"), rejected.toString())

        val failed = adminOf("/api/groups/teams?act_id=$g1&team_status=3&pageSize=50")
        assertContract(failed.path("list")[0], GroupsContract.TEAM_ROW, "GP-A6 失败团行")
        assertTrue(failed.path("list").any { it.path("team_id").asText() == t })
        val failedNow = intOf("SELECT COUNT(*) FROM promotion_groups_team WHERE act_id = ? AND team_status = 3", g1)
        assertEquals(failedNow, failed.path("list").size(), "后台失败团列表与库内一致")
        assertEquals(doing + 2, failedNow, "到期失败 1 团 + 取消留下的空团与强制结束都算失败态")
    }

    @Test @Order(23) fun `停用闸门 与 越权面 与 残余折让归零`() {
        adminOf("/api/seckill/activities/$sk1/status", mapOf("disabled" to true), "PUT")
        assertEquals(17800L, preview("3:2", "seckill", sk1).path("item_fee").asLong(), "停用场次不再改单价基准")
        assertEquals(0L, preview("3:2", "seckill", sk1).path("activity_price_fee").asLong())
        assertTrue(memberOf("/api/h5app/wxapp/items/3/promotions").path("seckill").isNull, "商详角标同步清空")
        val rejected = expect("POST", "/api/h5app/wxapp/order_new", member, orderBody("3:1", "seckill", sk1), 422)
        assertTrue(rejected.path("message").asText().contains("不在此场次"), rejected.toString())

        assertTrue(memberOf("/api/h5app/wxapp/groups/activities?pageSize=50").path("list")
            .none { it.path("groups_activity_id").asLong() == g1 }, "停用拼团不进前台列表")
        expect("GET", "/api/h5app/wxapp/groups/activities/$g1", member, null, 404)

        // 收尾：本案造成的折让全部归零，池子没有悬空占用
        assertEquals(0L, preview("3:2", "seckill", sk1).path("discount_fee").asLong() +
            preview("3:2", "groups", g1).path("discount_fee").asLong(), "本案结束时没有任何残余折让")
        assertEquals(0L, longOf("SELECT COALESCE(SUM(hold_store), 0) FROM promotions_seckill_rel_goods WHERE seckill_id IN (?,?,?,?)", sk1, sk2, sk3, sk4),
            "本案秒杀池无残留占用")
        assertEquals(0, seckillJob.pending(), "孤儿占用扫描清空")

        // 权限码 27..37 已在 seed 里：viewer 一条都没有
        expect("GET", "/api/seckill/activities", viewer, null, 403)
        expect("POST", "/api/seckill/activities", viewer, skBody("越权场次"), 403)
        expect("PUT", "/api/seckill/activities/$sk1/goods", viewer, skGoods(skItem(3, 100, store = 1)), 403)
        expect("GET", "/api/seckill/activities/$sk1/report", viewer, null, 403)
        expect("GET", "/api/groups/activities", viewer, null, 403)
        expect("POST", "/api/groups/activities", viewer, gpBody("越权拼团"), 403)
        expect("GET", "/api/groups/teams", viewer, null, 403)

        // 前台面未鉴权：401 + 源系统同款文案
        val anon = expect("GET", "/api/h5app/wxapp/groups/my/teams", null, null, 401)
        assertEquals("Unauthenticated.", anon.path("message").asText())
    }

    // ===== 造数模板 =====

    private fun goodsOf(node: JsonNode, itemId: Long): JsonNode =
        node.path("items").first { it.path("item_id").asLong() == itemId }

    private fun skBody(
        name: String,
        activityType: Int? = 1,
        validityPeriod: Int? = 10,
        limitMoney: Long? = 0,
        startOffset: Long = -3600,
        endOffset: Long = 86400,
    ): Map<String, Any?> = linkedMapOf(
        "activity_name" to name,
        "activity_type" to activityType,
        "ad_pic" to "/img/sk.jpg",
        "distributor_id" to "1",
        "activity_start_time" to TimeSec.now() + startOffset,
        "activity_end_time" to TimeSec.now() + endOffset,
        "validity_period" to validityPeriod,
        "limit_money" to limitMoney,
    ).filterValues { it != null }

    private fun skItem(id: Long, price: Long, store: Long? = null, limitNum: Int? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "item_id" to id,
            "item_name" to "圈品$id",
            "activity_price" to price,
            "activity_store" to store,
            "limit_num" to limitNum,
            "is_show" to true,
        ).filterValues { it != null }

    private fun skGoods(vararg items: Map<String, Any?>): Map<String, Any?> = mapOf("items" to items.toList())

    private fun createSk(body: Map<String, Any?>): Long =
        adminOf("/api/seckill/activities", body, "POST").path("seckill_id").asLong()

    private fun gpBody(
        name: String,
        goodsId: Long = 3,
        actPrice: Long = 6900,
        personNum: Int = 2,
        store: Long = 20,
        limitBuyNum: Int = 0,
        limitTime: Int = 24,
        beginOffset: Long = -3600,
        endOffset: Long = 86400,
        robot: Boolean = false,
    ): Map<String, Any?> = linkedMapOf(
        "act_name" to name,
        "goods_id" to goodsId,
        "act_price" to actPrice,
        "person_num" to personNum,
        "store" to store,
        "limit_buy_num" to limitBuyNum,
        "limit_time" to limitTime,
        "begin_time" to TimeSec.now() + beginOffset,
        "end_time" to TimeSec.now() + endOffset,
        "robot" to robot,
        "instruction" to "拼团说明",
    )

    private fun createGp(body: Map<String, Any?>): Long =
        adminOf("/api/groups/activities", body, "POST").path("groups_activity_id").asLong()
}
