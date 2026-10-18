package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.PickupContract
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.order.WriteOffService
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M1-4 第三批 3A（PL-A1..A5 / PL-F1 / WO-A1..A2 / ZT-F1，docs/04 §18.1、§18.2、§18.6、§18.7）。
 *
 * 本案盯四件事，都不是"接口能不能通"：
 *  1. **码是建单时发的，不是查的时候现算的**：`ziti_code` 落库、`ziti_status='false'`，
 *     6 位数字形状（§18.7 新号段 `ziti_code`），再建一单也不回改上一单的码；
 *  2. **四条闸门各拦各的**（§18.6）：非自提单 / 未付款 / 码不符 / 已核销，四条 422 各有各的文案，
 *     而真正防"两个人同时点核销"的是 `UPDATE … WHERE ziti_status='false'` 的受影响行数 ——
 *     本案用两个线程同时打同一个 service 方法，断言恰好一路成功、留痕恰好一条；
 *  3. **核销即完成**：`order_status=DONE`、`delivery_status=DONE`、`end_time` 落此刻、
 *     两个自动时间清空（定时器不该再去动一张已完成的单）、路由表跟着主表同步，
 *     并留一条 `behavior='writeoff'` 的痕——这单之后不能再退、不能再改，谁点的这一下必须可追；
 *  4. **两列各管一件事**（§18.6）：`distributor_id` 是归属店（列表按它收窄），
 *     `rel_distributor_id=0` 是全平台通用点（每家店前台都看得到）。
 *     拿归属列去判前台可用性，平台通用点就会在每家店的主页上一起消失。
 *
 * 权限面（§18.7）：seed 给店铺运营岗配了 `pickup.lists` + `order.writeoff`、**没配 `pickup.update`**，
 * 于是同一个 token 在 PL-A1 上是 200 且只看自己店、在 PL-A2/A3/A4/A5 上全是 403；
 * `viewer`（无角色）在任何一条上都是 403。前台 PL-F1 匿名可取，ZT-F1 必须登录（码不给别人看）。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:excziti;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
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
class PickupWriteOffEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var writeOffs: WriteOffService

    private var admin = ""
    private var member = ""
    private var other = ""
    private var shop = ""
    private var viewer = ""

    /** seed 追加的两条演示点：店 2 自己的点、平台通用点（`distributor_id=0`） */
    private var ownPoint = 0L
    private val platformPoint = 2L

    private var backupPoint = 0L   // 本案建的"西溪备份点"（归属店 2、使用方店 2）
    private var zitiOrder = 0L     // 自提单（店 2，默认点）
    private var zitiOrder2 = 0L    // 自提单（店 2，显式选平台通用点）
    private var unpaidOrder = 0L   // 自提单但不付款 → 闸门②
    private var logisticOrder = 0L // 物流单 → 闸门①

    // ===== 传输层助手（与 CommissionEndToEndTest 同一套形状） =====

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
            "DELETE" -> delete(url)
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

    private fun shopOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, shop, body), "shop $m $url")

    private fun memberOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, member, body), "member $m $url")

    private fun anonOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, null, body), "anon $m $url")

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

    private fun pointBody(
        distributorId: Long,
        name: String,
        rel: Long? = null,
        status: Int? = null,
        phone: String? = "057188886666",
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "distributor_id" to distributorId,
        "rel_distributor_id" to rel,
        "name" to name,
        "province" to "浙江省",
        "city" to "杭州市",
        "area" to "西湖区",
        "address" to "$name 门口",
        "lng" to "120.026000",
        "lat" to "30.279000",
        "contract_phone" to phone,
        "status" to status,
    ).filterValues { it != null }

    /** 车内结算建自提单：`receipt_type=ziti`，可选显式点；店铺走 `distributor_id` */
    private fun createZitiOrder(distributorId: Long, pickupLocationId: Long? = null): Long {
        memberOf("/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 1, "distributor_id" to distributorId), "POST")
        return memberOf(
            "/api/h5app/wxapp/order_new",
            linkedMapOf<String, Any?>(
                "cart_type" to "cart", "order_type" to "normal", "distributor_id" to distributorId,
                "receipt_type" to "ziti", "pickup_location_id" to pickupLocationId,
                "receiver_name" to "张三", "receiver_mobile" to "13800000001",
                "order_from" to "h5",
            ).filterValues { it != null },
            "POST",
        ).path("order_id").asLong()
    }

    private fun createLogisticsOrder(distributorId: Long): Long {
        memberOf("/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 1, "distributor_id" to distributorId), "POST")
        return memberOf(
            "/api/h5app/wxapp/order_new",
            mapOf(
                "cart_type" to "cart", "order_type" to "normal", "distributor_id" to distributorId,
                "receiver_name" to "张三", "receiver_mobile" to "13800000001",
                "receiver_state" to "浙江省", "receiver_city" to "杭州市",
                "receiver_district" to "西湖区", "receiver_address" to "文三路 1 号",
                "order_from" to "h5",
            ),
            "POST",
        ).path("order_id").asLong()
    }

    /** 结清：mock 微信渠道出支付单 + 回调，金额必须报订单的 `total_fee`（渠道侧只校验不算） */
    private fun pay(orderId: Long) {
        val tradeId = memberOf(
            "/api/h5app/wxapp/payment", mapOf("order_id" to orderId.toString(), "pay_type" to "wxpay"), "POST",
        ).path("trade_id").asText()
        val fee = longOf("SELECT total_fee FROM orders_normal_orders WHERE order_id = ?", orderId)
        val res = utf8(
            mvc.perform(
                post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("out_trade_no", tradeId).param("total_fee", fee.toString())
                    .param("transaction_id", "WX-" + tradeId.take(6)).param("trade_state", "SUCCESS"),
            ).andReturn().response.contentAsByteArray,
        )
        assertTrue(res.contains("SUCCESS"), "回调必须应答成功：$res")
    }

    private fun longOf(sql: String, vararg v: Any): Long = jdbc.queryForObject(sql, Long::class.java, *v) ?: -1L

    private fun strOf(sql: String, vararg v: Any): String? = jdbc.queryForObject(sql, String::class.java, *v)

    private fun codeOf(orderId: Long): String =
        strOf("SELECT ziti_code FROM orders_normal_orders WHERE order_id = ?", orderId).orEmpty()

    /** 一定与真码不同的码：拿真码 +1 再补零，避免"随手写个 000000 恰好撞对" */
    private fun wrongCodeOf(orderId: Long): String {
        val n = codeOf(orderId).toLongOrNull() ?: 0L
        return ((n + 1) % 1_000_000L).toString().padStart(6, '0')
    }

    private fun woUrl(orderId: Long) = "/api/writeoff/$orderId"

    private fun writeOff(orderId: Long, code: String, token: String = admin): JsonNode =
        data(call("POST", woUrl(orderId), token, mapOf("ziti_code" to code)), "WO-A2 $orderId")

    private fun names(node: JsonNode): List<String> = node.map { it.path("name").asText() }

    private fun hasName(node: JsonNode, part: String): Boolean = names(node).any { it.contains(part) }

    // ===================== 自提点 =====================

    @Test @Order(1) fun `令牌就绪与演示自提点在册`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        other = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "member2").path("token").asText()
        shop = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "distributor01", "password" to "shop" + "123")), "shop").path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank() && shop.isNotBlank() && viewer.isNotBlank())

        // seed 追加的两条点：一店一点 + 一条平台通用点
        val page = adminOf("/api/pickuplocation?pageSize=50")
        assertContract(page, PickupContract.LIST_ENVELOPE, "PL-A1 外层")
        assertEquals(2L, page.path("total_count").asLong(), "seed 的两条演示点")
        page.path("list").forEach { assertContract(it, PickupContract.PICKUP_ROW, "PL-A1 行") }

        val own = page.path("list").first { it.path("distributor_id").asLong() == 2L }
        ownPoint = own.path("id").asLong()
        assertEquals("demo 旗舰店", own.path("shop_name").asText(), "归属店名 JOIN 出来（snake 别名才映射得上）")
        assertEquals("057188886666", own.path("contract_phone").asText(), "后台列表给明文：运营要打就是这个号")
        assertEquals(2L, own.path("rel_distributor_id").asLong())
        assertEquals(
            "浙江省杭州市西湖区文一西路 969 号 A 座 1 层",
            own.path("full_address").asText(), "整段地址在服务层拼，不让前端各拼一遍",
        )
        // 平台通用点没有归属店，JOIN 不到店铺名 → 空串而不是 null（列表要能稳定渲染）
        val plat = page.path("list").first { it.path("distributor_id").asLong() == 0L }
        assertEquals(platformPoint, plat.path("id").asLong())
        assertEquals("", plat.path("shop_name").asText())

        // 过滤位：status / rel_distributor_id / keyword 各筛一次
        assertEquals(2L, adminOf("/api/pickuplocation?status=1").path("total_count").asLong())
        assertEquals(0L, adminOf("/api/pickuplocation?status=0").path("total_count").asLong())
        assertEquals(1L, adminOf("/api/pickuplocation?rel_distributor_id=0").path("total_count").asLong())
        assertEquals(1L, adminOf("/api/pickuplocation?keyword=中心仓").path("total_count").asLong())

        // 店铺账号只看得到自己店的点：平台通用点（distributor_id=0）不在它的可见集合里
        val sp = shopOf("/api/pickuplocation?pageSize=50")
        assertEquals(1L, sp.path("total_count").asLong(), "§18.6：列表按归属列收窄")
        assertEquals(ownPoint, sp.path("list")[0].path("id").asLong())

        // viewer 无角色 → 三条码一条都没有；未登录 → 401
        expect("GET", "/api/pickuplocation", viewer, null, 403)
        expect("GET", "/api/pickuplocation", null, null, 401)
    }

    @Test @Order(2) fun `建点校验与平台通用点只有 admin 建得了`() {
        // 必填三栏：name / address / contract_phone
        val noName = pointBody(2, "只有名字没有地址").toMutableMap().apply { put("name", ""); remove("address") }
        expect("POST", "/api/pickuplocation", admin, noName, 400)
        val noPhone = pointBody(2, "缺电话的点").toMutableMap().apply { remove("contract_phone") }
        expect("POST", "/api/pickuplocation", admin, noPhone, 400)
        // 经纬度必须是数字形状（列是 VARCHAR，但入参不验就等于把脏值存进库）
        val badLng = pointBody(2, "坐标不是数").toMutableMap().apply { put("lng", "东经120") }
        expect("POST", "/api/pickuplocation", admin, badLng, 400)
        // status 只认 1/0：不"顺手归一"，7 就是 7（筛子筛不到脏值才是问题）
        expect("POST", "/api/pickuplocation", admin, pointBody(2, "状态越界", status = 7), 400)

        // PL-A2 没有"按 id 再读一次"的端点（PL-A1 是列表），出参就得是完整行：
        // 建完点前端直接把这一行插进列表，两个形状不一致就得各写一套渲染
        val created = adminOf("/api/pickuplocation", pointBody(2, "西溪备份点", rel = 2), "POST")
        assertContract(created, PickupContract.PICKUP_ROW, "PL-A2 出参")
        backupPoint = created.path("id").asLong()
        assertTrue(backupPoint > 0)
        assertEquals(2L, created.path("distributor_id").asLong())
        assertEquals(2L, created.path("rel_distributor_id").asLong())
        assertEquals("demo 旗舰店", created.path("shop_name").asText())
        assertEquals(1, created.path("status").asInt(), "不传 status 即启用")

        // 店不存在 → 404；使用方店不存在 → 422（挂在校验里，不是挂在权限里）
        expect("POST", "/api/pickuplocation", admin, pointBody(999, "无主之地"), 404)
        expect("POST", "/api/pickuplocation", admin, pointBody(2, "使用方是空气", rel = 999), 422)

        // 店铺账号：`pickup.update` 一条都没配（§18.7）→ 建/改/停/挂店全 403，权限判在归属之前
        expect("POST", "/api/pickuplocation", shop, pointBody(2, "店铺自己建的点"), 403)

        // 平台通用点：admin 建得了（distributor_id=0），列表里它的 shop_name 也是空
        val platform = adminOf("/api/pickuplocation", pointBody(0, "城北平台点"), "POST")
        assertEquals(0L, platform.path("distributor_id").asLong())
        assertEquals(0L, platform.path("rel_distributor_id").asLong())
    }

    @Test @Order(3) fun `改点只覆盖传进来的字段`() {
        val upd = adminOf("/api/pickuplocation/$ownPoint", mapOf("contract_phone" to "057166668888"), "PUT")
        assertContract(upd, PickupContract.PICKUP_ROW, "PL-A3 出参")
        assertEquals("057166668888", upd.path("contract_phone").asText())
        assertEquals("demo 旗舰店自提柜台", upd.path("name").asText(), "只改电话不该把名字改掉")
        assertEquals("demo 旗舰店", upd.path("shop_name").asText())
        assertEquals(1, upd.path("status").asInt(), "status 不在入参里就保持原值")
        // 读-改-写的第二条腿：改完再读库还是这个值
        assertEquals("057166668888", strOf("SELECT contract_phone FROM distribution_pickup_location WHERE id = ?", ownPoint))

        // 权限先于归属：店铺账号没有 pickup.update，改别人的点先吃 403（不是 404 的形状试探）
        expect("PUT", "/api/pickuplocation/$platformPoint", shop, mapOf("name" to "改了别人的通用点"), 403)
        // admin 改归属时把使用方指向不存在的店 → 422
        expect("PUT", "/api/pickuplocation/$ownPoint", admin, mapOf("rel_distributor_id" to 888), 422)
        // 点不存在 → 404；id 不是数字也是 404（与源系统"查不到这条记录"一致）
        expect("PUT", "/api/pickuplocation/9999", admin, mapOf("name" to "不存在"), 404)
        expect("PUT", "/api/pickuplocation/abc", admin, mapOf("name" to "不是数字"), 404)
    }

    @Test @Order(4) fun `挂店与摘店只动 rel_distributor_id`() {
        val bound = adminOf(
            "/api/pickuplocation/reldistributor",
            mapOf("id" to backupPoint, "distributor_id" to 2, "bind" to true),
            "PUT",
        )
        assertContract(bound, PickupContract.BIND_RESULT, "PL-A5 出参")
        assertEquals(backupPoint, bound.path("id").asLong())
        assertEquals(2L, bound.path("rel_distributor_id").asLong())
        assertEquals(true, bound.path("bind").asBoolean())
        // 归属列没被这条 UPDATE 碰过：挂店改的是"给谁用"，不是"这是谁的点"
        assertEquals(2L, longOf("SELECT distributor_id FROM distribution_pickup_location WHERE id = ?", backupPoint))

        val unbound = adminOf(
            "/api/pickuplocation/reldistributor",
            mapOf("id" to backupPoint, "distributor_id" to 2, "bind" to false),
            "PUT",
        )
        assertEquals(0L, unbound.path("rel_distributor_id").asLong(), "摘店=回全平台通用，而不是删行")

        // 挂到不存在的店 → 422；店铺账号挂店 → 403
        expect("PUT", "/api/pickuplocation/reldistributor", admin, mapOf("id" to backupPoint, "distributor_id" to 999, "bind" to true), 422)
        expect("PUT", "/api/pickuplocation/reldistributor", shop, mapOf("id" to platformPoint, "distributor_id" to 2, "bind" to true), 403)
        // 缺 id → 这条走的是"点不存在"（0 号点本来就没有）
        expect("PUT", "/api/pickuplocation/reldistributor", admin, mapOf("distributor_id" to 2, "bind" to true), 404)
    }

    @Test @Order(5) fun `前台取点掩码且平台通用点每家都看得到`() {
        // 匿名可取（PL-F1 在 §18.1 里标 *）
        val f = anonOf("/api/h5app/wxapp/distributor/pickuplocation?distributor_id=2")
        assertContract(f, PickupContract.FRONT_LIST, "PL-F1 外层")
        assertTrue(hasName(f.path("list"), "demo 旗舰店自提柜台"), "本店点：${names(f.path("list"))}")
        assertTrue(hasName(f.path("list"), "平台中心仓"), "平台通用点也要出得来：${names(f.path("list"))}")
        // 摘店后的备份点 rel=0，成了通用点，所以这里也在；反过来店 1 看不到别人的专属点
        val masked = f.path("list").first { it.path("name").asText().contains("自提柜台") }
        assertEquals("057****8888", masked.path("contract_phone").asText(), "§18.1：前台电话掩码")
        assertContract(masked, PickupContract.PICKUP_ROW, "PL-F1 行")

        // 带坐标才出距离；同城量级
        val withDist = anonOf("/api/h5app/wxapp/distributor/pickuplocation?distributor_id=2&lng=120.126&lat=30.279")
        val d = withDist.path("list")[0].path("distance")
        assertTrue(d.isNumber, "带坐标必须出距离：$withDist")
        assertTrue(d.asDouble() in 0.0..50.0, "同城量级：${d.asDouble()}")
        assertTrue(f.path("list")[0].path("distance").isNull, "没传坐标就不出数")

        // 商城总部（店 1）自己没建点，但两条平台通用点归它可用；别人的专属点看不到
        val hq = anonOf("/api/h5app/wxapp/distributor/pickuplocation?distributor_id=1")
        assertTrue(hasName(hq.path("list"), "平台中心仓") && hasName(hq.path("list"), "城北平台点"), "${names(hq.path("list"))}")
        assertTrue(hq.path("list").none { it.path("name").asText().contains("自提柜台") }, "店 2 的专属点不出现在店 1")

        // `distributor_id` 必填
        expect("GET", "/api/h5app/wxapp/distributor/pickuplocation", null, null, 400)

        // DS-F2 店铺主页带 pickup_locations，且与 PL-F1 同一份取数
        val home = anonOf("/api/h5app/wxapp/distributors/2")
        assertEquals(f.path("list").size(), home.path("pickup_locations").size(), "两处读的是同一条 SQL")
        assertEquals(
            "057****8888",
            home.path("pickup_locations").first { it.path("name").asText().contains("自提柜台") }.path("contract_phone").asText(),
        )
        // 没开自提的店：主页给空数组，不给"入口在、结算 422"
        val plain = adminOf(
            "/api/distributor",
            mapOf("name" to "不做自提的店", "mobile" to "13700000009", "is_ziti" to false, "introduce" to "只发快递"),
            "POST",
        )
        assertEquals(0, anonOf("/api/h5app/wxapp/distributors/${plain.path("distributor_id").asLong()}").path("pickup_locations").size())
    }

    // ===================== 建单发码 =====================

    @Test @Order(6) fun `自提单建单即发码且落点`() {
        zitiOrder = createZitiOrder(2)
        val code = codeOf(zitiOrder)
        assertTrue(Regex("^\\d{6}$").matches(code), "6 位数字码：$code")
        assertEquals("false", strOf("SELECT ziti_status FROM orders_normal_orders WHERE order_id = ?", zitiOrder))
        assertEquals("ziti", strOf("SELECT receipt_type FROM orders_normal_orders WHERE order_id = ?", zitiOrder))
        assertEquals(ownPoint, longOf("SELECT pickup_location_id FROM orders_normal_orders WHERE order_id = ?", zitiOrder), "没显式选点就落该店第一个启用点")

        // 显式选平台通用点
        zitiOrder2 = createZitiOrder(2, pickupLocationId = platformPoint)
        assertEquals(platformPoint, longOf("SELECT pickup_location_id FROM orders_normal_orders WHERE order_id = ?", zitiOrder2))
        assertTrue(codeOf(zitiOrder2) != code, "两单两张码")
        // 码是存下来的：再建一单也不回改上一单
        assertEquals(code, codeOf(zitiOrder))

        // 选了一个不属于这家店、也不通用的点 → 422（闸门在扣库存之前，拒一次不白占一次池子）
        val ghost = longOf("SELECT COUNT(*) FROM distribution_pickup_location") + 5000
        memberOf("/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 1, "distributor_id" to 2L), "POST")
        expect("POST", "/api/h5app/wxapp/order_new", member, mapOf(
            "cart_type" to "cart", "order_type" to "normal", "distributor_id" to 2, "receipt_type" to "ziti",
            "pickup_location_id" to ghost, "receiver_name" to "张三", "receiver_mobile" to "13800000001",
        ), 422)
        // 显式传 0 = "买家没选点"：回落该店第一个启用点，而不是报错
        val fallback = createZitiOrder(2, pickupLocationId = 0L)
        assertEquals(ownPoint, longOf("SELECT pickup_location_id FROM orders_normal_orders WHERE order_id = ?", fallback))
    }

    @Test @Order(7) fun `会员查自己的码且别人的单查不到`() {
        val d = memberOf("/api/h5app/wxapp/order/$zitiOrder/ziticode")
        assertContract(d, PickupContract.ZITI_CODE, "ZT-F1")
        assertEquals(zitiOrder.toString(), d.path("order_id").asText())
        assertEquals(codeOf(zitiOrder), d.path("ziti_code").asText())
        assertEquals("false", d.path("ziti_status").asText())
        assertContract(d.path("pickup"), PickupContract.PICKUP_BLOCK, "ZT-F1 的取货块")
        assertEquals(ownPoint, d.path("pickup").path("id").asLong())
        assertEquals("057166668888", d.path("pickup").path("contract_phone").asText(), "会员面给自己看码，电话不必掩码")
        assertTrue(d.path("valid_text").asText().contains("到店出示本码"))

        // 不是本人的单 → 404（不给"这单存在"的信息）
        expect("GET", "/api/h5app/wxapp/order/$zitiOrder/ziticode", other, null, 404)
        // 未登录 → 401 Unauthenticated.
        val anon = expect("GET", "/api/h5app/wxapp/order/$zitiOrder/ziticode", null, null, 401)
        assertEquals("Unauthenticated.", anon.path("message").asText())
        // 物流单没有码
        logisticOrder = createLogisticsOrder(2)
        expect("GET", "/api/h5app/wxapp/order/$logisticOrder/ziticode", member, null, 422)
    }

    // ===================== 核销 =====================

    @Test @Order(8) fun `核销查询出形状且码不符就 422`() {
        val v = adminOf(woUrl(zitiOrder))
        assertContract(v, PickupContract.WRITE_OFF_ORDER, "WO-A1")
        assertEquals(zitiOrder.toString(), v.path("order_id").asText())
        assertEquals("待核销", v.path("ziti_status_text").asText())
        assertEquals(zitiOrder.toString(), v.path("order_no").asText(), "本项目单号就是 order_id")
        assertContract(v.path("items")[0], PickupContract.WRITE_OFF_ITEM, "WO-A1 明细行")
        assertEquals("每日坚果礼盒", v.path("items")[0].path("item_name").asText())
        assertContract(v.path("pickup"), PickupContract.PICKUP_BLOCK, "WO-A1 的取货块")

        // 带码顺比一次：对的 200，错的 422
        val ok = adminOf("${woUrl(zitiOrder)}?ziti_code=${codeOf(zitiOrder)}")
        assertEquals(zitiOrder.toString(), ok.path("order_id").asText())
        expect("GET", "${woUrl(zitiOrder)}?ziti_code=${wrongCodeOf(zitiOrder)}", admin, null, 422)

        // 权限与登录：店铺账号能查（到店要把货给人），viewer 不能
        assertEquals(zitiOrder.toString(), shopOf(woUrl(zitiOrder)).path("order_id").asText())
        expect("GET", woUrl(zitiOrder), viewer, null, 403)
        expect("GET", woUrl(zitiOrder), null, null, 401)
        // 跨店：这张单归店 1，店铺账号查它是 404（不是 403，不给"这单存在"）
        val hqOrder = createZitiOrder(1)
        expect("GET", woUrl(hqOrder), shop, null, 404)
        expect("GET", "/api/writeoff/abc", admin, null, 404)
    }

    @Test @Order(9) fun `闸门一条条拦 未付款 非自提 码错 码空`() {
        unpaidOrder = createZitiOrder(2)
        // ② 未付款
        val u = expect("POST", woUrl(unpaidOrder), admin, mapOf("ziti_code" to codeOf(unpaidOrder)), 422)
        assertTrue(u.path("message").asText().contains("未付款"), u.path("message").asText())
        assertEquals("false", strOf("SELECT ziti_status FROM orders_normal_orders WHERE order_id = ?", unpaidOrder))
        // 查询面没有付款闸门（输码前要先看清这单），但码仍逐字比
        expect("GET", "${woUrl(unpaidOrder)}?ziti_code=${wrongCodeOf(unpaidOrder)}", admin, null, 422)
        // 「不区分前后空格」由下一条的 body 路径证明（`writeOff(zitiOrder, " code ")`）：
        // MockMvc 不对查询串做百分号解码，`?ziti_code=%20…` 递进控制器的是字面 `%20`，
        // 那是 URL 层的事、不是本服务的比对规则，放进断言会测错对象。

        // ① 非自提单（用物流单打核销端点=调用方用错了面）
        val l = expect("POST", woUrl(logisticOrder), admin, mapOf("ziti_code" to "123456"), 422)
        assertTrue(l.path("message").asText().contains("不是自提订单"))

        // ③ 码逐字：错码 422；空串也走比对（不是"没传"）；缺字段才 400
        pay(zitiOrder)
        expect("POST", woUrl(zitiOrder), admin, mapOf("ziti_code" to wrongCodeOf(zitiOrder)), 422)
        expect("POST", woUrl(zitiOrder), admin, mapOf("ziti_code" to ""), 422)
        expect("POST", woUrl(zitiOrder), admin, emptyMap(), 400)
        assertEquals("false", strOf("SELECT ziti_status FROM orders_normal_orders WHERE order_id = ?", zitiOrder), "以上四条都没改成行")
    }

    @Test @Order(10) fun `核销即完成 状态 时间与留痕一起到位`() {
        val before = TimeSec.now()
        val v = writeOff(zitiOrder, " ${codeOf(zitiOrder)} ")
        assertContract(v, PickupContract.WRITE_OFF_ORDER, "WO-A2 出参")
        assertEquals("true", v.path("ziti_status").asText())
        assertEquals("已核销", v.path("ziti_status_text").asText())
        assertEquals(OrderStatus.DONE, v.path("order_status").asText())
        assertEquals("DONE", strOf("SELECT delivery_status FROM orders_normal_orders WHERE order_id = ?", zitiOrder))
        assertTrue(longOf("SELECT end_time FROM orders_normal_orders WHERE order_id = ?", zitiOrder) >= before, "end_time 落在核销这一刻")
        // 两个自动时间清空：定时器不该再去动一张已完成的单
        assertEquals(
            0L,
            longOf("SELECT COUNT(*) FROM orders_normal_orders WHERE order_id = ? AND (auto_cancel_time IS NOT NULL OR auto_finish_time IS NOT NULL)", zitiOrder),
            "自动时间必须清干净",
        )
        // 路由表跟着主表走（orders_associations 与主表同名状态列）
        assertEquals(OrderStatus.DONE, strOf("SELECT order_status FROM orders_associations WHERE order_id = ?", zitiOrder))
        // 留痕：一条 writeoff，操作人是点了这个按钮的人
        val logs = jdbc.queryForList("SELECT * FROM orders_operate_logs WHERE order_id = ? AND behavior = 'writeoff'", zitiOrder)
        assertEquals(1, logs.size)
        assertEquals("seller", logs[0]["operator_role"])
        assertNotNull(logs[0]["operator"], "留痕要写清是谁核的")
        assertTrue((logs[0]["log_text"] as String).contains("自提核销"))

        // ④ 已核销：第二次读判据 422
        val again = expect("POST", woUrl(zitiOrder), admin, mapOf("ziti_code" to codeOf(zitiOrder)), 422)
        assertTrue(again.path("message").asText().contains("已核销"))
        // 会员那边跟着变：码失效
        val d = memberOf("/api/h5app/wxapp/order/$zitiOrder/ziticode")
        assertEquals("true", d.path("ziti_status").asText())
        assertTrue(d.path("valid_text").asText().contains("已失效"), d.path("valid_text").asText())
    }

    @Test @Order(11) fun `并发核销只认第一路`() {
        val order = createZitiOrder(2)
        pay(order)
        val code = codeOf(order)
        val threads = 2
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errs = ConcurrentLinkedQueue<String>()
        try {
            val results = (1..threads).map {
                pool.submit<Boolean> {
                    start.await()
                    try {
                        writeOffs.writeOff(1L, order, code, DistributorScope.ALL, 1L, "operator:1", "seller")
                        true
                    } catch (e: Exception) {
                        // 「抢输了」与「连不上库」必须能区分：混成一个数字就看不出是不是 CAS 生效
                        errs.add("${e::class.simpleName}: ${e.message}")
                        false
                    }
                }
            }
            start.countDown()
            val ok = results.count { it.get(60, TimeUnit.SECONDS) }
            // 两路都读到 ziti_status='false' 是允许的，但 UPDATE 的受影响行数只让一路成立
            assertEquals(1, ok, "恰好一路改成行；失败样本 ${errs.take(3)}")
            assertEquals(1L, longOf("SELECT COUNT(*) FROM orders_operate_logs WHERE order_id = ? AND behavior = 'writeoff'", order), "留痕也恰好一条")
            assertEquals(OrderStatus.DONE, strOf("SELECT order_status FROM orders_normal_orders WHERE order_id = ?", order))
        } finally {
            pool.shutdownNow()
        }
        assertTrue(errs.all { it.contains("已核销") }, "败者拿到的都是「已核销」而不是 500：${errs.take(3)}")
    }

    @Test @Order(12) fun `还有未核销单指着就不许停用这个点`() {
        // 先把备份点挂回店 2，再用它建一张停在"待核销"的单
        adminOf("/api/pickuplocation/reldistributor", mapOf("id" to backupPoint, "distributor_id" to 2, "bind" to true), "PUT")
        val hold = createZitiOrder(2, pickupLocationId = backupPoint)
        pay(hold)

        val blocked = expect("DELETE", "/api/pickuplocation/$backupPoint", admin, null, 422)
        assertTrue(blocked.path("message").asText().contains("未核销"), blocked.path("message").asText())

        // 没单指着就停得掉；行不真删
        val spare = adminOf("/api/pickuplocation", pointBody(2, "没人用的点"), "POST").path("id").asLong()
        val off = adminOf("/api/pickuplocation/$spare", null, "DELETE")
        assertContract(off, PickupContract.DISABLED, "PL-A4 出参")
        assertEquals(0, off.path("status").asInt())
        assertEquals(1L, longOf("SELECT COUNT(*) FROM distribution_pickup_location WHERE id = ?", spare), "历史单要能回看取货地址")
        expect("DELETE", "/api/pickuplocation/$spare", shop, null, 403)
        expect("DELETE", "/api/pickuplocation/9999", admin, null, 404)
        // 停用后前台不再出这个点，后台按 status=0 还能筛到
        assertTrue(
            names(anonOf("/api/h5app/wxapp/distributor/pickuplocation?distributor_id=2").path("list"))
                .none { it == "没人用的点" },
            "status=0 的点不出现在前台",
        )
        assertEquals(1L, adminOf("/api/pickuplocation?status=0").path("total_count").asLong())

        // 核销掉那张单之后备份点就能停了：闸门数的是"未完结"，不是"有过单"
        writeOff(hold, codeOf(hold))
        assertEquals(0, adminOf("/api/pickuplocation/$backupPoint", null, "DELETE").path("status").asInt())

        // 取消掉的那张同样不算"还有人会来取"（冒烟照出来的）：只排 DONE 的话，
        // 一张永远不会有人取货的取消单把这个点**永久锁死**，门店再想停也停不掉
        val lockPoint = adminOf("/api/pickuplocation", pointBody(2, "取消单占着的点"), "POST").path("id").asLong()
        val cancelled = createZitiOrder(2, pickupLocationId = lockPoint)
        expect("DELETE", "/api/pickuplocation/$lockPoint", admin, null, 422)
        memberOf(
            "/api/h5app/wxapp/order/cancel",
            mapOf("order_id" to cancelled, "cancel_reason" to "M1-4 取消不该锁住点"),
            "POST",
        )
        assertEquals("CANCEL", strOf("SELECT order_status FROM orders_normal_orders WHERE order_id = ?", cancelled))
        assertEquals(0, adminOf("/api/pickuplocation/$lockPoint", null, "DELETE").path("status").asInt(), "取消单不拦停用")
    }

    @Test @Order(13) fun `待自提页签与后台订单视角认这张单`() {
        val o = createZitiOrder(2)
        pay(o)
        // ORDER-4 的数字页签 4=待自提（§2.4：receipt_type=ziti 且已付款且未发齐）
        val tab = memberOf("/api/h5app/wxapp/orders?status=4&pageSize=50")
        assertTrue(tab.path("list").any { it.path("order_id").asLong() == o }, "付款后即待自提：${ids(tab)}")
        val done = writeOff(o, codeOf(o))
        assertEquals(OrderStatus.DONE, done.path("order_status").asText())
        val after = memberOf("/api/h5app/wxapp/orders?status=4&pageSize=50")
        assertTrue(after.path("list").none { it.path("order_id").asLong() == o }, "核销后离开待自提页签")
        // 后台订单详情带着这两个列（源系统就有，这批之前恒空）—— ORDER-9 是嵌套形状，两列在 orderInfo 下
        val detail = adminOf("/api/orders/$o")
        assertEquals(codeOf(o), detail.path("orderInfo").path("ziti_code").asText())
        assertEquals("true", detail.path("orderInfo").path("ziti_status").asText())
    }

    @Test @Order(14) fun `数据权限与权限码在每一条上都生效`() {
        val mine = createZitiOrder(2)
        pay(mine)
        // 店铺 token：核销自己店的单可以（role2 配了 order.writeoff）
        assertEquals(mine.toString(), shopOf(woUrl(mine), mapOf("ziti_code" to codeOf(mine)), "POST").path("order_id").asText())
        val hq = createZitiOrder(1)
        pay(hq)
        // 别人的店 → 404；无角色 → 403
        expect("POST", woUrl(hq), shop, mapOf("ziti_code" to codeOf(hq)), 404)
        expect("GET", woUrl(hq), viewer, null, 403)
        expect("POST", woUrl(hq), viewer, mapOf("ziti_code" to codeOf(hq)), 403)

        // 跨面一律 401：会员令牌打后台核销面、运营令牌打会员取码面
        expect("GET", woUrl(hq), member, null, 401)
        expect("POST", "/api/h5app/wxapp/order/$hq/ziticode", admin, null, 401)
        expect("GET", "/api/h5app/wxapp/order/$hq/ziticode", null, null, 401)

        // 审计：写路径都记了一笔（@Audited 的 route 就是权限码）
        assertTrue(
            longOf("SELECT COUNT(*) FROM operator_logs WHERE route_name IN ('pickup.update','order.writeoff')") >= 5,
            "PL-A2/A3/A5/A4 与 WO-A2 都要留痕",
        )
    }

    @Test @Order(15) fun `点改了地址 核销页显示的是现在去哪儿取`() {
        val o = createZitiOrder(2)
        pay(o)
        // 单上只存 id：门牌号改了，核销页要显示改后的（§18.6 不存快照的理由）
        adminOf("/api/pickuplocation/$ownPoint", mapOf("address" to "文一西路 1000 号 新门牌"), "PUT")
        val v = adminOf(woUrl(o))
        assertEquals("浙江省杭州市西湖区文一西路 1000 号 新门牌", v.path("pickup").path("full_address").asText())
        assertEquals(ownPoint, v.path("pickup_location_id").asLong())

        // 停用中的点也要读得到：历史单的取货地址不能被"停用"抹掉
        val late = adminOf("/api/pickuplocation", pointBody(2, "临时点后关"), "POST").path("id").asLong()
        val so = createZitiOrder(2, pickupLocationId = late)
        pay(so)
        // 有未核销单指着 → 先 422
        expect("DELETE", "/api/pickuplocation/$late", admin, null, 422)
        assertEquals(late, adminOf(woUrl(so)).path("pickup").path("id").asLong())
        writeOff(so, codeOf(so))
        assertEquals(0, adminOf("/api/pickuplocation/$late", null, "DELETE").path("status").asInt())
        // 停用了，核销完的单仍指得出那个点，并如实报停用态
        val after = adminOf(woUrl(so))
        assertEquals(late, after.path("pickup").path("id").asLong())
        assertEquals(0, after.path("pickup").path("status").asInt())
        assertEquals("true", after.path("ziti_status").asText())
    }

    private fun ids(node: JsonNode): List<Long> = node.path("list").map { it.path("order_id").asLong() }
}
