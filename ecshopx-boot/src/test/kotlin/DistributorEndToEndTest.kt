package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.CartContract
import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.DistributorContract
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1-4 第一批（DS-A1..A8 / DS-F1..F2 / SI-A1..A4 / SI-F1..F2，docs/04 §18.1/§18.2/§18.4/§18.5/§18.8）。
 *
 * 断言的重点不是"接口能通"，而是 §18 立的那几条口径，外加 docs/05 §M1-4 的两条门槛：
 *  1. **三列状态各有各的出口**（D42）：改档案能把 `review_status` 留在原地，审核位只由 DS-A5 动，
 *     运营开关只由 DS-A6 动且 `closed` 要过"先结后关"，费率只由 DS-A7 动且**不回溯**已落佣金（D46）；
 *  2. **价格与池子是两条独立的腿**（§18.4）：`price>0` 才覆盖总部现价，`is_total_store` 才决定扣谁，
 *     扣与回补同一条腿（店铺池单绝不惊动 `items.store`），失效原因按"店铺自己的下架位优先于总部库存"判序；
 *  3. **前台与后台读同一份列**（§18.0）：配置台列出的店，下单不会说它没启用；未营业的店对前台一律 404；
 *  4. **门槛①**：同一 SKU 在两家店的车里各一行（`orders_cart` 唯一键含 `shop_id`），CART-2 按店铺拆成
 *     两组、各按本店价与本店池出数，ORDER-2 一单一店（混合单 422）、按选中的店扣对的那个池子；
 *  5. **门槛②**：`operator_type='distributor'` 的账号只看 `operators.distributor_ids` 绑定的店 ——
 *     列表面越界是"过滤到 0 行"（条件是查询参数），资源引用面越界是 **404**（不是 403，不给可枚举信息），
 *     而"没绑任何店"是看不见任何店而不是看见全场。
 *
 * 演示数据：seed 的两家店（1 商城总部 / 2 demo 旗舰店）都已审核；**seed 不播挂品行**，
 * 店铺挂品由本案 SI-A1 现场建、收尾 SI-A4 删干净，最后一条断言就是"删干净之后本店价回总部价、
 * 总部池只被店铺 1 那一单动过 1 件"。本案自己开的店（A/B）跑完即弃，不影响任何算价断言。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:excdist;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DistributorEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var admin = ""
    private var member = ""
    private var shop = ""
    private var viewer = ""

    /** DS-A1 现场开的两家店：A 全程营业（挂账与费率用它），B 用来走审核/关店闸门 */
    private var shopA = 0L
    private var shopB = 0L
    private var orderOnShop1 = 0L
    private var orderOnShop2 = 0L

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

    private fun shopOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, shop, body), "shop $m $url")

    private fun anon(url: String): JsonNode = data(call("GET", url, null, null), "anon GET $url")

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

    private fun shopBody(
        name: String,
        type: Int = 1,
        review: Boolean = true,
        mobile: String = "13900000009",
        rate: Int? = null,
        subject: Int? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "name" to name,
        "mobile" to mobile,
        "province" to "浙江省",
        "city" to "杭州市",
        "area" to "西湖区",
        "address" to "文三路 1 号",
        "lng" to "120.1",
        "lat" to "30.2",
        "logo" to "/img/shop.png",
        "distribution_type" to type,
        "review_status" to review,
        "is_ziti" to true,
        "auto_sync_goods" to true,
        "rate" to rate,
        "payment_subject" to subject,
        "introduce" to "$name 的简介",
    ).filterValues { it != null }

    private fun createShop(body: Map<String, Any?>): Long =
        adminOf("/api/distributor", body, "POST").path("distributor_id").asLong()

    private fun hang(
        distributorId: Long,
        vararg lines: Map<String, Any?>,
    ): JsonNode = adminOf(
        "/api/distributor/items",
        mapOf("distributor_id" to distributorId, "items" to lines.toList()),
        "POST",
    )

    private fun line(itemId: Long, vararg kv: Pair<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>("item_id" to itemId) + kv.toMap()

    private fun config(
        distributorId: Long,
        itemId: Long,
        vararg kv: Pair<String, Any?>,
    ): JsonNode = adminOf(
        "/api/distributors/item",
        linkedMapOf<String, Any?>("distributor_id" to distributorId, "item_id" to itemId) + kv.toMap(),
        "PUT",
    )

    /** 车内加购：`distributor_id` 决定这一行进哪家店的车（`orders_cart` 唯一键含 shop_id） */
    private fun addCart(itemId: Long, num: Int, distributorId: Long): JsonNode =
        memberOf("/api/h5app/wxapp/cart", mapOf("item_id" to itemId, "num" to num, "distributor_id" to distributorId), "POST")

    private fun cart(): JsonNode = memberOf("/api/h5app/wxapp/cart")

    private fun groupOf(node: JsonNode, shopId: Long): JsonNode =
        node.path("valid_cart").firstOrNull { it.path("shop_id").asLong() == shopId }
            ?: throw AssertionError("购物车里没有店铺 $shopId 的分组：" + node)

    private fun previewCart(distributorId: Long? = null): JsonNode = memberOf(
        "/api/h5app/wxapp/getFreightFee",
        linkedMapOf<String, Any?>("cart_type" to "cart", "distributor_id" to distributorId).filterValues { it != null },
        "POST",
    )

    /** ORDER-2 车内结算：`distributor_id` 选店（一单一店，混合单必 422） */
    private fun orderFromCart(distributorId: Long?): JsonNode = memberOf(
        "/api/h5app/wxapp/order_new",
        linkedMapOf<String, Any?>(
            "cart_type" to "cart",
            "order_type" to "normal",
            "distributor_id" to distributorId,
            "receiver_name" to "张三",
            "receiver_mobile" to "13800000001",
            "receiver_state" to "浙江省",
            "receiver_city" to "杭州市",
            "receiver_district" to "西湖区",
            "receiver_address" to "文三路 1 号",
            "order_from" to "h5",
        ).filterValues { it != null },
        "POST",
    )

    private fun cancel(orderId: Long): JsonNode =
        memberOf("/api/h5app/wxapp/order/cancel", mapOf("order_id" to orderId), "POST")

    private fun longOf(sql: String, vararg v: Any): Long = jdbc.queryForObject(sql, Long::class.java, *v) ?: -1L

    private fun intOf(sql: String, vararg v: Any): Int = longOf(sql, *v).toInt()

    private fun strOf(sql: String, vararg v: Any): String = jdbc.queryForObject(sql, String::class.java, *v) ?: ""

    private fun hqStore(itemId: Long): Long = longOf("SELECT store FROM items WHERE item_id = ?", itemId)

    private fun shopStore(distributorId: Long, itemId: Long): Long =
        longOf("SELECT store FROM distribution_distributor_items WHERE distributor_id = ? AND item_id = ?", distributorId, itemId)

    private fun shopSales(distributorId: Long, itemId: Long): Long =
        longOf("SELECT sales FROM distribution_distributor_items WHERE distributor_id = ? AND item_id = ?", distributorId, itemId)

    private fun addCommission(distributorId: Long, rebate: Long, closed: Boolean) {
        jdbc.update(
            "INSERT INTO distribution_distribute_logs(company_id, distributor_id, order_id, item_id, user_id, shop_id, " +
                "num, rebate, total_rebate, is_close, created, updated) VALUES(1, ?, 900001, 3, 1, ?, 1, ?, ?, ?, 0, 0)",
            distributorId, distributorId, rebate, rebate, closed,
        )
    }

    private fun addWithdrawal(distributorId: Long, money: Long, status: String) {
        jdbc.update(
            "INSERT INTO distribution_cash_withdrawal(company_id, distributor_id, user_id, shop_id, money, status, created, updated) " +
                "VALUES(1, ?, 1, 0, ?, ?, 0, 0)",
            distributorId, money, status,
        )
    }

    // ===================== 店铺档案：DS-A1..A8 =====================

    @Test @Order(1) fun `令牌就绪`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001",
            "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        shop = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "distributor01", "password" to "shop" + "123")), "shop").path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank() && shop.isNotBlank() && viewer.isNotBlank())
        // seed 不播挂品行：本案所有店铺挂品都由 SI-A1 现场建（收尾 SI-A4 删干净）
        assertEquals(0L, longOf("SELECT COUNT(*) FROM distribution_distributor_items"))
    }

    @Test @Order(2) fun `DS-A1 建店 契约与校验`() {
        val a = adminOf("/api/distributor", shopBody("端到端加盟店 A"), "POST")
        assertContract(a, DistributorContract.SHOP_ROW, "DS-A1/A2 店铺行")
        shopA = a.path("distributor_id").asLong()
        assertTrue(shopA > 0)
        assertEquals("加盟", a.path("distribution_type_text").asText())
        assertEquals("平台收款", a.path("payment_subject_text").asText())
        assertEquals(1, a.path("source_from").asInt(), "管理端建的店 source_from=1")
        assertEquals(1, a.path("distribution_type").asInt(), "1=加盟")
        assertTrue(a.path("review_status").asBoolean(), "管理端建店默认过审（小程序申请来的才走 DS-A5）")
        assertTrue(a.path("is_distributor").asBoolean().not())
        assertEquals(0L, a.path("item_count").asLong())
        assertEquals(0L, a.path("order_count").asLong())
        assertEquals("0%", a.path("rate_text").asText())
        assertTrue(a.path("is_ziti").asBoolean() && a.path("auto_sync_goods").asBoolean())

        // 建店即审可关：显式给 review_status=false 就留在未审核（B 店专门用来走闸门）
        shopB = createShop(shopBody("端到端加盟店 B", review = false))
        assertFalse(adminOf("/api/distributors/info?distributor_id=$shopB").path("review_status").asBoolean())

        // 表单不合法 400；业务拒绝才 422
        val noName = expect("POST", "/api/distributor", admin, shopBody("端到端缺名字").let { it - "name" }, 400)
        assertEquals(listOf("不能为空"), noName.path("errors").path("name").map { it.asText() })
        val noMobile = expect("POST", "/api/distributor", admin, shopBody("端到端缺电话").let { it - "mobile" }, 400)
        assertTrue(noMobile.path("errors").path("mobile").isArray)
        val badType = expect("POST", "/api/distributor", admin, shopBody("端到端错类型", type = 5), 400)
        assertTrue(badType.path("errors").path("distribution_type")[0].asText().contains("只支持"))
        val badSubject = expect("POST", "/api/distributor", admin, shopBody("端到端错主体", subject = 3), 400)
        assertTrue(badSubject.path("errors").path("payment_subject")[0].asText().contains("只支持"))
        val badRate = expect("POST", "/api/distributor", admin, shopBody("端到端错费率", rate = 10001), 400)
        assertEquals(listOf("费率是万分比，取值 0..10000"), badRate.path("errors").path("rate").map { it.asText() })
        val longMobile = expect("POST", "/api/distributor", admin, shopBody("端到端长电话", mobile = "1".repeat(33)), 400)
        assertEquals(listOf("最长 32 字"), longMobile.path("errors").path("mobile").map { it.asText() })

        // 自营主店铺位全场唯一（seed 已有 distributor_id=1 占位），再建第二条自营必拒
        val dupSelf = expect("POST", "/api/distributor", admin, shopBody("端到端第二个自营", type = 0), 400)
        assertEquals(listOf("自营主店铺已存在，全场只能有一家"), dupSelf.path("errors").path("distribution_type").map { it.asText() })
    }

    @Test @Order(3) fun `DS-A2 A3 列表过滤 详情与 404`() {
        val page = adminOf("/api/distributors?keyword=端到端&page=1&pageSize=50")
        assertContract(page.path("list")[0], DistributorContract.SHOP_ROW, "DS-A2 店铺行")
        assertEquals(2L, page.path("total_count").asLong(), "本案只有 A/B 两家")
        assertTrue(page.path("list").all { it.path("name").asText().contains("端到端") })
        assertTrue(page.path("list").none { it.path("name").asText().contains("商城总部") }, "名称过滤掉了 seed 店铺")

        assertTrue(adminOf("/api/distributors?distribution_type=0&pageSize=50").path("list")
            .all { it.path("distribution_type").asInt() == 0 }, "类型过滤")
        val bySource = adminOf("/api/distributors?source_from=2&pageSize=50")
        assertTrue(bySource.path("list").all { it.path("source_from").asInt() == 2 }, "来源过滤：小程序申请来的店")
        assertTrue(bySource.path("list").any { it.path("distributor_id").asLong() == 2L })
        assertTrue(adminOf("/api/distributors?review_status=false&pageSize=50").path("list")
            .all { !it.path("review_status").asBoolean() }, "未审核过滤")
        val one = adminOf("/api/distributors?distributor_id=$shopA")
        assertEquals(1L, one.path("total_count").asLong(), "显式 id 过滤")
        assertEquals(shopA, one.path("list")[0].path("distributor_id").asLong())
        assertTrue(adminOf("/api/distributors?ids=$shopA,$shopB&pageSize=50").path("list").size() == 2, "ids 批量")

        val d = adminOf("/api/distributors/info?distributor_id=$shopA")
        assertContract(d, DistributorContract.SHOP_DETAIL, "DS-A3 详情 = 列表行 + 简介")
        assertEquals("端到端加盟店 A 的简介", d.path("introduce").asText())
        expect("GET", "/api/distributors/info?distributor_id=999999", admin, null, 404)
        expect("GET", "/api/distributors/info?distributor_id=abc", admin, null, 404)
        expect("GET", "/api/distributors/info?distributor_id=0", admin, null, 404)
    }

    @Test @Order(4) fun `DS-A4 改档案 不会顺手改审核位`() {
        val u = adminOf("/api/distributor/$shopA", mapOf("name" to "端到端加盟店 A2", "city" to "宁波市", "banner" to "/img/b.png"), "PUT")
        assertEquals("端到端加盟店 A2", u.path("name").asText())
        assertEquals("宁波市", u.path("city").asText())
        assertEquals("/img/b.png", u.path("banner").asText())
        assertEquals("浙江省", u.path("province").asText(), "没传的字段保留旧值")

        // D42 的落点：源系统那条 UPDATE 把三列状态一起写，于是"改个联系电话"能把未审核的店顺手过审
        val b = adminOf("/api/distributor/$shopB", mapOf("name" to "端到端加盟店 B2"), "PUT")
        assertEquals("端到端加盟店 B2", b.path("name").asText())
        assertFalse(b.path("review_status").asBoolean(), "档案更新碰不到审核位（那条 UPDATE 里没有这一列）")

        val chgType = expect("PUT", "/api/distributor/$shopA", admin, mapOf("distribution_type" to 0), 400)
        assertEquals(listOf("店铺类型建后不可改"), chgType.path("errors").path("distribution_type").map { it.asText() })
        val longName = expect("PUT", "/api/distributor/$shopA", admin, mapOf("name" to "长".repeat(129)), 400)
        assertEquals(listOf("最长 128 字"), longName.path("errors").path("name").map { it.asText() })
        expect("PUT", "/api/distributor/999999", admin, mapOf("name" to "不存在"), 404)
        expect("PUT", "/api/distributor/abc", admin, mapOf("name" to "非法"), 404)
    }

    @Test @Order(5) fun `DS-A5 审核闸门 与重复审核 422`() {
        // 未审核 = 前台不存在（三筛少一筛），配置台仍然看得见
        assertTrue(anon("/api/h5app/wxapp/distributors?pageSize=50").path("list")
            .none { it.path("distributor_id").asLong() == shopB }, "未审核的店不进前台列表")
        expect("GET", "/api/h5app/wxapp/distributors/$shopB", null, null, 404)
        assertTrue(adminOf("/api/distributors?pageSize=100").path("list")
            .any { it.path("distributor_id").asLong() == shopB }, "配置台默认看全量（运营要能审它）")

        val ok = adminOf("/api/distributor/$shopB/review", mapOf("review_status" to true), "PUT")
        assertTrue(ok.path("review_status").asBoolean())
        assertTrue(anon("/api/h5app/wxapp/distributors?pageSize=50").path("list")
            .any { it.path("distributor_id").asLong() == shopB }, "过审后立刻进前台")

        // 重复审核由那条 `review_status <> #{to}` 的 UPDATE 自然回 0 行，服务层据此报 422
        val again = expect("PUT", "/api/distributor/$shopB/review", admin, mapOf("review_status" to true), 422)
        assertTrue(again.path("message").asText().contains("无需重复审核"), again.toString())
        assertFalse(adminOf("/api/distributor/$shopB/review", mapOf("review_status" to false), "PUT").path("review_status").asBoolean())
        val noBody = expect("PUT", "/api/distributor/$shopB/review", admin, emptyMap(), 400)
        assertEquals(listOf("不能为空"), noBody.path("errors").path("review_status").map { it.asText() })
        expect("PUT", "/api/distributor/999999/review", admin, mapOf("review_status" to true), 404)
    }

    @Test @Order(6) fun `DS-A6 运营开关 与先结后关`() {
        // 关店是终态：还有未结佣金就不许关，否则账上留下"店关了、钱还挂着"的悬空数
        addCommission(shopB, rebate = 3000, closed = false)
        val open = expect("PUT", "/api/distributor/$shopB/status", admin, mapOf("is_valid" to "closed"), 422)
        assertTrue(open.path("message").asText().contains("未结算佣金"), open.toString())

        val off = adminOf("/api/distributor/$shopB/status", mapOf("is_valid" to "false"), "PUT")
        assertEquals("false", off.path("is_valid").asText())
        expect("GET", "/api/h5app/wxapp/distributors/$shopB", null, null, 404)
        assertTrue(adminOf("/api/distributors?is_valid=false&pageSize=50").path("list")
            .all { it.path("is_valid").asText() == "false" }, "停用过滤")

        // 提现占用同样算"没结完"：applied/confirmed/paid 三态都占
        jdbc.update("DELETE FROM distribution_distribute_logs WHERE distributor_id = ?", shopB)
        addCommission(shopB, rebate = 2000, closed = true)
        addWithdrawal(shopB, money = 2000, status = "applied")
        val wip = expect("PUT", "/api/distributor/$shopB/status", admin, mapOf("is_valid" to "closed"), 422)
        assertTrue(wip.path("message").asText().contains("处理中的提现"), wip.toString())

        jdbc.update("DELETE FROM distribution_cash_withdrawal WHERE distributor_id = ?", shopB)
        val closed = adminOf("/api/distributor/$shopB/status", mapOf("is_valid" to "closed"), "PUT")
        assertEquals("closed", closed.path("is_valid").asText())
        val out = expect("PUT", "/api/distributor/$shopB/status", admin, mapOf("is_valid" to "true"), 422)
        assertTrue(out.path("message").asText().contains("已关店"), out.toString())

        val bad = expect("PUT", "/api/distributor/$shopA/status", admin, mapOf("is_valid" to "yes"), 400)
        assertTrue(bad.path("errors").path("is_valid")[0].asText().contains("只支持"), bad.toString())
        val noBody = expect("PUT", "/api/distributor/$shopA/status", admin, emptyMap(), 400)
        assertEquals(listOf("不能为空"), noBody.path("errors").path("is_valid").map { it.asText() })
        expect("PUT", "/api/distributor/999999/status", admin, mapOf("is_valid" to "true"), 404)

        // 关了的店不许再挂品（档案仍然可改：关店之后还得填联系电话做善后，这是 DS-A4 与 SI-A1 的分工）
        val hangClosed = expect("POST", "/api/distributor/items", admin,
            mapOf("distributor_id" to shopB, "items" to listOf(line(3L))), 422)
        assertTrue(hangClosed.path("message").asText().contains("已关店"), hangClosed.toString())
    }

    @Test @Order(7) fun `DS-A7 收款主体与费率 不回溯已落佣金`() {
        val s = adminOf("/api/distributor/$shopA/payment-subject", mapOf("payment_subject" to 1, "rate" to 300), "PUT")
        assertEquals("店铺收款", s.path("payment_subject_text").asText())
        assertEquals(300, s.path("rate").asInt())
        assertEquals("3%", s.path("rate_text").asText())
        adminOf("/api/distributor/$shopA/payment-subject", mapOf("rate" to 150), "PUT")
        assertEquals("1.5%", adminOf("/api/distributors/info?distributor_id=$shopA").path("rate_text").asText(), "非整百分比给小数")

        // D46：费率只影响之后的佣金，已落地的 `distribute_logs` 一分不改
        addCommission(shopA, rebate = 5000, closed = true)
        val before = longOf("SELECT rebate FROM distribution_distribute_logs WHERE distributor_id = ? AND is_close = TRUE", shopA)
        adminOf("/api/distributor/$shopA/payment-subject", mapOf("rate" to 800), "PUT")
        assertEquals(before, longOf("SELECT rebate FROM distribution_distribute_logs WHERE distributor_id = ? AND is_close = TRUE", shopA),
            "改费率不回溯历史佣金")

        val badRate = expect("PUT", "/api/distributor/$shopA/payment-subject", admin, mapOf("rate" to -1), 400)
        assertTrue(badRate.path("errors").path("rate").isArray)
        val badSubject = expect("PUT", "/api/distributor/$shopA/payment-subject", admin, mapOf("payment_subject" to 7), 400)
        assertTrue(badSubject.path("errors").path("payment_subject").isArray)
        expect("PUT", "/api/distributor/999999/payment-subject", admin, mapOf("rate" to 100), 404)
        // 空表单=两个值都按当前值回写，不是报错（配置台上"保存"按钮可能只带 id）
        val noop = adminOf("/api/distributor/$shopA/payment-subject", emptyMap(), "PUT")
        assertEquals(800, noop.path("rate").asInt())
    }

    @Test @Order(8) fun `DS-A8 统计 与 §18_5 余额公式`() {
        // A 店此刻：已结 5000、未结 0、提现占用 0
        var st = adminOf("/api/distributor/count/$shopA")
        assertContract(st, DistributorContract.SHOP_STATS, "DS-A8 七个派生数")
        assertEquals(shopA, st.path("distributor_id").asLong())
        assertEquals(0L, st.path("order_count").asLong())
        assertEquals(5000L, st.path("commission_closed").asLong())
        assertEquals(0L, st.path("commission_open").asLong())
        assertEquals(5000L, st.path("balance").asLong(), "balance = 已结佣金 − 提现占用")
        assertEquals(5000L, st.path("commission_total").asLong())

        addCommission(shopA, rebate = 3000, closed = false)
        addWithdrawal(shopA, money = 2000, status = "applied")
        addWithdrawal(shopA, money = 1000, status = "paid")
        addWithdrawal(shopA, money = 500, status = "refused")
        st = adminOf("/api/distributor/count/$shopA")
        assertEquals(8000L, st.path("commission_total").asLong())
        assertEquals(3000L, st.path("commission_open").asLong())
        // paid 也占额度：closedFee 是只增不减的累计数，已打款不扣就能把同一笔结算佣金反复提空
        assertEquals(3000L, st.path("withdraw_in_flight").asLong(), "applied+paid 都算占用，refused 不算")
        assertEquals(2000L, st.path("balance").asLong(), "5000 − 3000")

        expect("GET", "/api/distributor/count/999999", admin, null, 404)
        jdbc.update("DELETE FROM distribution_distribute_logs WHERE distributor_id = ?", shopA)
        jdbc.update("DELETE FROM distribution_cash_withdrawal WHERE distributor_id = ?", shopA)
    }

    // ===================== 店铺商品：SI-A1..A4 =====================

    @Test @Order(9) fun `SI-A1 挂品 upsert 只覆盖传进来的字段`() {
        val r = hang(2, line(3L, "price" to 5900, "store" to 40, "is_total_store" to false))
        assertContract(r, DistributorContract.SHOP_ITEMS_SAVED, "SI-A1 出参")
        assertContract(r.path("items")[0], DistributorContract.SHOP_ITEM_SAVED, "SI-A1 挂品行")
        assertEquals(2L, r.path("distributor_id").asLong())
        val n = r.path("items")[0]
        assertEquals(5900L, n.path("price").asLong())
        assertEquals(40L, n.path("store").asLong())
        assertFalse(n.path("is_total_store").asBoolean(), "FALSE=扣店铺自己的池子")
        assertTrue(n.path("is_show").asBoolean() && n.path("is_can_sale").asBoolean(), "不给就是默认上架可售")
        assertEquals(0L, n.path("sales").asLong())
        assertEquals(3L, n.path("goods_id").asLong())

        // price 不给 = 跟随总部现价（§18.4 第一行），0 与未挂品同一个回落
        hang(2, line(2L, "price" to 0))

        // 重复挂同一品：只覆盖传进来的 `price`，`store`/`is_total_store` 保留
        val up = hang(2, line(3L, "price" to 6900))
        assertEquals(6900L, up.path("items")[0].path("price").asLong())
        assertEquals(40L, up.path("items")[0].path("store").asLong(), "未传的字段不被清空")
        assertFalse(up.path("items")[0].path("is_total_store").asBoolean())
        config(2, 3, "price" to 5900)

        val empty = expect("POST", "/api/distributor/items", admin, mapOf("distributor_id" to 2, "items" to emptyList<Any?>()), 400)
        assertEquals(listOf("至少挂一个商品"), empty.path("errors").path("items").map { it.asText() })
        val badId = expect("POST", "/api/distributor/items", admin, mapOf("distributor_id" to 2, "items" to listOf(line(0L))), 400)
        assertTrue(badId.path("errors").path("items[0].item_id").isArray)
        val dup = expect("POST", "/api/distributor/items", admin,
            mapOf("distributor_id" to 2, "items" to listOf(line(3L), line(3L))), 400)
        assertEquals(listOf("同一商品一次只能挂一行"), dup.path("errors").path("items").map { it.asText() })
        val neg = expect("POST", "/api/distributor/items", admin,
            mapOf("distributor_id" to 2, "items" to listOf(line(3L, "price" to -1, "store" to -1))), 400)
        assertTrue(neg.path("errors").path("items[0].price").isArray && neg.path("errors").path("items[0].store").isArray)
        expect("POST", "/api/distributor/items", admin, mapOf("distributor_id" to 2, "items" to listOf(line(999L))), 404)
        expect("POST", "/api/distributor/items", admin, mapOf("distributor_id" to 0, "items" to listOf(line(3L))), 404)
    }

    @Test @Order(10) fun `SI-A2 A3 双态列表 与配置`() {
        val page = adminOf("/api/distributor/items?distributor_id=2&pageSize=50")
        assertContract(page.path("list")[0], DistributorContract.SHOP_ITEM_ROW, "SI-A2 双态行")
        assertEquals(2L, page.path("total_count").asLong())
        val nut = page.path("list").first { it.path("item_id").asLong() == 3L }
        assertEquals(5900L, nut.path("shop_price").asLong(), "左列：这家店的实况")
        assertEquals(8900L, nut.path("price").asLong(), "右列：总部现值")
        assertEquals(200L, nut.path("store").asLong())
        assertEquals(40L, nut.path("shop_store").asLong())
        assertEquals(5900L, nut.path("effect_price").asLong(), "生效价 = 店铺价")
        assertEquals(40L, nut.path("effect_store").asLong(), "生效池 = 店铺池")
        assertFalse(nut.path("is_total_store").asBoolean())
        val phone = page.path("list").first { it.path("item_id").asLong() == 2L }
        assertEquals(0L, phone.path("shop_price").asLong())
        assertEquals(199900L, phone.path("effect_price").asLong(), "price=0 回落总部现价")
        assertEquals(50L, phone.path("effect_store").asLong(), "is_total_store=TRUE 看总部池")
        assertEquals(1L, adminOf("/api/distributor/items?distributor_id=2&keyword=坚果").path("total_count").asLong(), "名称过滤")
        assertEquals(0L, adminOf("/api/distributor/items?distributor_id=2&is_show=false").path("total_count").asLong())

        val c = config(2, 3, "store" to 60, "price" to 4900)
        assertContract(c, DistributorContract.SHOP_ITEM_CONFIGURED, "SI-A3 出参")
        assertContract(c.path("item"), DistributorContract.SHOP_ITEM_SAVED, "SI-A3 行")
        assertEquals(60L, c.path("item").path("store").asLong(), "`store` 是绝对值（与 SK-A5 的增量口径不同）")
        assertEquals(4900L, c.path("item").path("price").asLong())
        config(2, 3, "store" to 40, "price" to 5900)

        val negStore = expect("PUT", "/api/distributors/item", admin, mapOf("distributor_id" to 2, "item_id" to 3, "store" to -5), 400)
        assertTrue(negStore.path("errors").path("store").isArray)
        val noItem = expect("PUT", "/api/distributors/item", admin, mapOf("distributor_id" to 2), 400)
        assertEquals(listOf("必填"), noItem.path("errors").path("item_id").map { it.asText() })
        // 未挂品的店改价必须先挂品（404 而不是悄悄插一行）
        val notHanged = expect("PUT", "/api/distributors/item", admin, mapOf("distributor_id" to 2, "item_id" to 999), 404)
        assertTrue(notHanged.path("message").asText().contains("未挂此商品"), notHanged.toString())
        expect("PUT", "/api/distributors/item", admin, mapOf("distributor_id" to 1, "item_id" to 3), 404)
    }

    // ===================== 会员面：DS-F1/F2 + SI-F1/F2 =====================

    @Test @Order(11) fun `会员面 店铺与店铺商品 匿名可访 未营业即 404`() {
        val list = anon("/api/h5app/wxapp/distributors?pageSize=50")
        assertContract(list.path("list")[0], DistributorContract.SHOP_FRONT_ROW, "DS-F1 前台店铺行")
        // 前台不外露账：balance/commission/rate 一个都不该出现
        assertFalse(list.path("list")[0].has("balance"))
        assertFalse(list.path("list")[0].has("rate"))
        assertTrue(list.path("list").any { it.path("distributor_id").asLong() == 2L })
        assertTrue(list.path("list").none { it.path("distributor_id").asLong() == shopB }, "已关店的店对前台不存在")

        val d = anon("/api/h5app/wxapp/distributors/2")
        assertContract(d, DistributorContract.SHOP_FRONT_DETAIL, "DS-F2 店铺主页")
        assertEquals("demo 旗舰店", d.path("name").asText())
        assertEquals(2L, d.path("item_count").asLong())
        expect("GET", "/api/h5app/wxapp/distributors/$shopB", null, null, 404)
        expect("GET", "/api/h5app/wxapp/distributors/999999", null, null, 404)
        expect("GET", "/api/h5app/wxapp/distributors/abc", null, null, 404)

        val items = anon("/api/h5app/wxapp/goods/shopitems?distributor_id=2")
        assertContract(items.path("list")[0], DistributorContract.SHOP_ITEM_FRONT_ROW, "SI-F1 前台店铺商品行")
        assertEquals(2L, items.path("total_count").asLong())
        val nut = items.path("list").first { it.path("item_id").asLong() == 3L }
        assertEquals(5900L, nut.path("price").asLong(), "前台只出店铺价")
        assertEquals(40L, nut.path("store").asLong(), "前台只出店铺池")
        assertFalse(nut.has("origin_price"), "总部现值不在会员面出参里")
        assertEquals(0L, anon("/api/h5app/wxapp/goods/shopitems?distributor_id=1").path("total_count").asLong(), "总部店没挂品=空列表")
        expect("GET", "/api/h5app/wxapp/goods/shopitems", null, null, 400)

        // 上下架两位：`is_show=false` 只让商品从店铺列表消失，不影响"能不能买"
        config(2, 2, "is_show" to false)
        assertEquals(1L, anon("/api/h5app/wxapp/goods/shopitems?distributor_id=2").path("total_count").asLong())
        assertEquals(2L, adminOf("/api/distributor/items?distributor_id=2&pageSize=50").path("total_count").asLong(), "配置台仍然看得见")
        assertEquals(200L, longOf("SELECT store FROM items WHERE item_id = 3"), "挂品与下架都不动总部池")
        config(2, 2, "is_show" to true)

        val ps = anon("/api/h5app/wxapp/goods/items_price_store/3?distributor_id=2")
        assertContract(ps, DistributorContract.SHOP_ITEM_PRICE_STORE, "SI-F2 价格与库存")
        assertEquals(5900L, ps.path("price").asLong())
        assertEquals(40L, ps.path("store").asLong())
        assertEquals(8900L, ps.path("origin_price").asLong())
        assertFalse(ps.path("is_total_store").asBoolean())
        val hq = anon("/api/h5app/wxapp/goods/items_price_store/3")
        assertEquals(8900L, hq.path("price").asLong(), "不给 distributor_id = 总部态")
        assertEquals(200L, hq.path("store").asLong())
        assertTrue(hq.path("is_total_store").asBoolean())
        assertEquals(0L, hq.path("distributor_id").asLong())
        // 未挂品的店回落总部态（与算价侧同一个判据，页面价与购物车价不分叉）
        val miss = anon("/api/h5app/wxapp/goods/items_price_store/3?distributor_id=1")
        assertEquals(8900L, miss.path("price").asLong())
        assertEquals(0L, miss.path("shop_price").asLong())
        expect("GET", "/api/h5app/wxapp/goods/items_price_store/999", null, null, 404)
    }

    // ===================== 门槛①：多店铺混合购物车 =====================

    @Test @Order(12) fun `门槛① 同一 SKU 两店各一行 按店铺拆单展示`() {
        addCart(3, 1, 1)
        addCart(3, 2, 2)
        val c = cart()
        assertContract(c, CartContract.CART_DATA, "CART-2 出参")
        assertEquals(2, c.path("valid_cart").size(), "同商品在不同店各成一组")

        // 失效判序（§18.4 第三条）：店铺自己的停售位排在总部库存之前
        config(2, 3, "is_can_sale" to false)
        val disabled = cart()
        val bad = disabled.path("invalid_cart").firstOrNull { it.path("shop_id").asLong() == 2L }
        val reason = previewCart(2).path("invalid_items")[0].path("reason").asText()
        // 先把状态改回来再断言：本案后面每一个金额都靠"两家店都正常在售"这个前提
        config(2, 3, "is_can_sale" to true)
        assertEquals(1, disabled.path("valid_cart").size(), "加盟店停售 → 那一行进 invalid_cart")
        assertEquals(1, groupOf(disabled, 1).path("list").size())
        assertTrue(bad != null && bad.path("list").size() == 1, disabled.toString())
        assertContract(bad.path("list")[0], CartContract.CART_LINE + DistributorContract.CART_LINE_SHOP_FIELDS, "失效行字段与有效行同构")
        assertEquals("该店铺暂不可售", reason, "总部池还满着，不能报成库存不足")

        val back = cart()
        assertEquals(2, back.path("valid_cart").size())
        val g1 = groupOf(back, 1)
        val g2 = groupOf(back, 2)
        assertContract(g1, CartContract.SHOP_GROUP, "CART-2 店铺分组外壳")
        assertContract(g1.path("list")[0], CartContract.CART_LINE + DistributorContract.CART_LINE_SHOP_FIELDS, "CART-2 购物车行")
        assertEquals("商城总部", g1.path("shop_name").asText())
        assertEquals("demo 旗舰店", g2.path("shop_name").asText())
        assertEquals(8900L, g1.path("cart_total_price").asLong(), "总部那一行走总部现价")
        assertEquals(11800L, g2.path("cart_total_price").asLong(), "加盟店那一行走店铺价 5900×2")
        assertEquals(1, g1.path("cart_total_count").asInt())
        assertEquals(1, g2.path("cart_total_count").asInt())
        assertEquals(3L, g1.path("cart_total_num").asLong() + g2.path("cart_total_num").asLong())
        assertEquals(0L, g1.path("freight").asLong(), "模板 1 全国包邮")
        assertEquals(8900L, g1.path("list")[0].path("price").asLong())
        assertEquals("TOTAL", g1.path("list")[0].path("stock_source").asText())
        assertEquals(5900L, g2.path("list")[0].path("price").asLong())
        assertEquals("SHOP", g2.path("list")[0].path("stock_source").asText(), "§18.4 池子归属进成员面")
        assertFalse(g2.path("list")[0].path("is_total_store").asBoolean())
        assertEquals(8900L, g2.path("list")[0].path("origin_price").asLong(), "行上说清「店里 59、总部 89」")
        assertEquals(40L, g2.path("list")[0].path("store").asLong(), "加盟店看到本店池子而不是总店 200")
        assertEquals(200L, g1.path("list")[0].path("store").asLong())

        val p = previewCart()
        assertEquals(2, p.path("group_count").asInt(), "ORDER-1 与 CART-2 同一个 quote")
        assertEquals(20700L, p.path("item_fee").asLong())
        assertEquals(0L, p.path("discount_fee").asLong(), "本案没配营销活动，折让恒 0")
        assertEquals(2, p.path("items").size())
        assertContract(p.path("items")[0], setOf("is_total_store", "stock_source"), "预览行带着两条腿")
        assertEquals(20700L, p.path("total_fee").asLong())
    }

    @Test @Order(13) fun `门槛① 混合单拒 一单一店 店铺池扣回同一条腿`() {
        // 混合购物车不许合并成交（拆单成多笔属 M1-4 之后的批次，口径先立住）
        val mixed = expect("POST", "/api/h5app/wxapp/order_new", member, linkedMapOf<String, Any?>(
            "cart_type" to "cart", "order_type" to "normal",
            "receiver_name" to "张三", "receiver_mobile" to "13800000001",
            "receiver_address" to "文三路 1 号", "order_from" to "h5",
        ), 422)
        assertTrue(mixed.path("message").asText().contains("多店铺"), mixed.toString())

        val hq = hqStore(3)
        val before = shopStore(2, 3)
        val o = orderFromCart(2)
        assertContract(o, setOf("order_id", "total_fee"), "ORDER-2 出参")
        orderOnShop2 = o.path("order_id").asLong()
        assertEquals(11800L, o.path("total_fee").asLong(), "按店铺价成交，不是总部价")
        assertEquals(before - 2, shopStore(2, 3), "店铺池扣 2 件")
        assertEquals(2L, shopSales(2, 3))
        assertEquals(hq, hqStore(3), "店铺池那一单绝不惊动 items.store")
        assertEquals(2L, longOf("SELECT distributor_id FROM orders_normal_orders WHERE order_id = ?", orderOnShop2))
        assertFalse(jdbc.queryForObject(
            "SELECT is_total_store FROM orders_normal_orders_items WHERE order_id = ?", Boolean::class.java, orderOnShop2,
        ) ?: true, "明细行记下这行货从店铺池出，回补只读这一列")

        // 会员面取消 → 同一条腿回补（店铺池 +2，总部池仍然纹丝不动）
        cancel(orderOnShop2)
        assertEquals(before, shopStore(2, 3), "回补走的是扣的那条腿")
        assertEquals(0L, shopSales(2, 3))
        assertEquals(hq, hqStore(3))

        // 另一家店的车行单独结算：这一单才动总部池
        val o1 = orderFromCart(1)
        orderOnShop1 = o1.path("order_id").asLong()
        assertEquals(8900L, o1.path("total_fee").asLong())
        assertEquals(hq - 1, hqStore(3))
        assertEquals(before, shopStore(2, 3), "总部池那一单不动店铺池")

        // DS-A2/A8 的订单侧派生数：只数有钱进过的（未支付不算成交额）
        val row = adminOf("/api/distributors?distributor_id=2").path("list")[0]
        assertEquals(1L, row.path("order_count").asLong())
        assertEquals(0L, row.path("pay_fee").asLong(), "pay_status 不是 PAYED 就不计成交额")
        val st = adminOf("/api/distributor/count/2")
        assertEquals(1L, st.path("order_count").asLong())
        assertEquals(0L, st.path("balance").asLong(), "本案没有佣金日志")
    }

    // ===================== 门槛②：distributor_id 过滤越权 =====================

    @Test @Order(14) fun `门槛② 店铺账号只看绑定的店`() {
        val page = shopOf("/api/distributors?pageSize=50")
        assertTrue(page.path("list").all { it.path("distributor_id").asLong() == 2L }, "列表只出授权集合")
        assertTrue(page.path("list").none { it.path("distributor_id").asLong() == shopA }, "别人家的店一条都不给")

        // 列表面：distributor_id 是查询条件 → 越界过滤到 0 行（不是 404）
        assertEquals(0L, shopOf("/api/distributors?distributor_id=1").path("total_count").asLong())
        assertEquals(0L, shopOf("/api/distributors?distributor_id=$shopA").path("total_count").asLong())
        assertEquals(1L, shopOf("/api/distributors?distributor_id=2").path("total_count").asLong())
        assertEquals(0L, shopOf("/api/distributors?ids=1,$shopA&pageSize=50").path("total_count").asLong(), "ids 里越界的部分一并被交集吃掉")

        // 资源引用面：越界的店等于不存在（404，而不是"存在但不给你看"的 403）
        expect("GET", "/api/distributors/info?distributor_id=1", shop, null, 404)
        expect("GET", "/api/distributors/info?distributor_id=$shopA", shop, null, 404)
        expect("GET", "/api/distributor/count/1", shop, null, 404)
        expect("GET", "/api/distributor/count/2", shop, null, 200)
        expect("PUT", "/api/distributor/1", shop, mapOf("name" to "改别人家的店"), 404)
        expect("POST", "/api/distributor/items", shop, mapOf("distributor_id" to 1, "items" to listOf(line(3L))), 404)
        expect("GET", "/api/distributor/items?distributor_id=1", shop, null, 404)
        // 自己家的店照常读写
        assertEquals(2L, shopOf("/api/distributor/items?distributor_id=2").path("total_count").asLong())

        // 订单查询面同样按 distributor_ids 收窄：列表 0 行 + 详情 404
        val orders = shopOf("/api/orders?pageSize=50")
        assertTrue(orders.path("list").all { it.path("distributor_id").asLong() == 2L }, orders.toString())
        assertTrue(orders.path("list").any { it.path("order_id").asLong() == orderOnShop2 })
        assertTrue(orders.path("list").none { it.path("order_id").asLong() == orderOnShop1 }, "别人家的单不进列表")
        assertEquals(0L, shopOf("/api/orders?distributor_id=1&pageSize=50").path("total_count").asLong())
        expect("GET", "/api/orders/$orderOnShop1", shop, null, 404)
        expect("GET", "/api/orders/$orderOnShop2", shop, null, 200)
        // admin 型不受限：两张单都看得见
        assertTrue(adminOf("/api/orders?pageSize=50").path("list").any { it.path("order_id").asLong() == orderOnShop1 })

        // 权限码与数据权限是两条独立的线：店铺运营账号有 `update` 但没有 `review`/`status`
        expect("PUT", "/api/distributor/2/review", shop, mapOf("review_status" to false), 403)
        expect("PUT", "/api/distributor/2/status", shop, mapOf("is_valid" to "false"), 403)
        expect("PUT", "/api/distributor/2/payment-subject", shop, mapOf("rate" to 500), 200)
        adminOf("/api/distributor/2/payment-subject", mapOf("rate" to 100), "PUT")

        // 面鉴权：会员 JWT 打到后台面 401（不泄露端点存在性），无资源角色 403，匿名 401
        expect("GET", "/api/distributors", member, null, 401)
        expect("GET", "/api/distributors", null, null, 401)
        expect("GET", "/api/distributors", viewer, null, 403)
        // 前台面不受操作员数据权限影响：匿名照样看到两家店
        assertTrue(anon("/api/h5app/wxapp/distributors?pageSize=50").path("list").size() >= 2)
    }

    @Test @Order(15) fun `门槛② 没绑店铺的分销商账号看不见任何店`() {
        val hash = strOf("SELECT password FROM operators WHERE operator_id = 3")
        jdbc.update("INSERT INTO operators(company_id, username, password, operator_type, distributor_ids, created, updated) " +
            "VALUES(1, 'distributor02', ?, 'distributor', '[]', 0, 0)", hash)
        val newId = longOf("SELECT operator_id FROM operators WHERE username = 'distributor02'")
        jdbc.update("INSERT INTO operator_rel_roles(operator_id, role_id) VALUES(?, 2)", newId)
        val t = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "distributor02", "password" to "shop" + "123")), "distributor02").path("token").asText()
        assertTrue(t.isNotBlank())

        // 空授权集合 = 什么都看不见（当成"不筛"等于给未配置的账号开了全场权限）
        assertEquals(0L, data(call("GET", "/api/distributors?pageSize=50", t, null), "unbound list").path("total_count").asLong())
        assertEquals(0L, data(call("GET", "/api/orders?pageSize=50", t, null), "unbound orders").path("total_count").asLong())
        expect("GET", "/api/distributors/info?distributor_id=2", t, null, 404)
        expect("GET", "/api/distributor/items?distributor_id=2", t, null, 404)
        jdbc.update("DELETE FROM operator_rel_roles WHERE operator_id = ?", newId)
        jdbc.update("DELETE FROM operators WHERE operator_id = ?", newId)
    }

    // ===================== 收尾 =====================

    @Test @Order(16) fun `SI-A4 取消挂品 之后算价回 seed 态`() {
        val del = adminOf("/api/distributor/items", mapOf("distributor_id" to 2, "item_ids" to listOf(2, 3, 999)), "DELETE")
        assertContract(del, DistributorContract.SHOP_ITEMS_DELETED, "SI-A4 出参")
        assertEquals(2L, del.path("distributor_id").asLong())
        assertEquals(2L, del.path("deleted").asLong(), "只删真挂着的两行，999 不在其中")

        val bad = expect("DELETE", "/api/distributor/items", admin, mapOf("distributor_id" to 2, "item_ids" to emptyList<Any?>()), 400)
        assertTrue(bad.path("errors").path("item_ids").isArray)
        expect("DELETE", "/api/distributor/items", admin, mapOf("distributor_id" to 999, "item_ids" to listOf(3)), 404)

        // 挂品行删干净：本店价回落总部现价，池子回到总部池，`items` 本身没被取消挂品动过
        assertEquals(0L, adminOf("/api/distributor/items?distributor_id=2").path("total_count").asLong())
        assertEquals(0L, anon("/api/h5app/wxapp/goods/shopitems?distributor_id=2").path("total_count").asLong())
        val ps = anon("/api/h5app/wxapp/goods/items_price_store/3?distributor_id=2")
        assertEquals(8900L, ps.path("price").asLong(), "删干净之后本店价==总部价")
        assertEquals(hqStore(3), ps.path("store").asLong(), "删干净之后读的就是总部池现值")
        assertEquals(0L, ps.path("shop_store").asLong())
        assertTrue(ps.path("is_total_store").asBoolean())
        val p = memberOf("/api/h5app/wxapp/getFreightFee",
            mapOf("cart_type" to "fastbuy", "items" to listOf(mapOf("item_id" to 3, "num" to 1))), "POST")
        assertEquals(8900L, p.path("items")[0].path("price").asLong())
        assertEquals("TOTAL", p.path("items")[0].path("stock_source").asText())
        assertEquals(0L, p.path("discount_fee").asLong())

        // 整场收尾：总部池只被店铺 1 那一单扣过 1 件，店铺池随挂品行一起消失，取消的挂品不影响订单快照
        assertEquals(199L, hqStore(3), "200 − 店铺 1 那一单 1 件；店铺池的单从未惊动总部池")
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders_normal_orders_items WHERE order_id = ? AND distributor_id = 2", Integer::class.java, orderOnShop2,
        ) ?: 0, "订单明细行的店别是当时的事实，挂品行删了也不改历史")
        assertEquals(0L, longOf("SELECT COUNT(*) FROM distribution_distribute_logs WHERE distributor_id = ?", shopA))
    }
}
