package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.CommissionContract
import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.OrderContract
import com.ecshopx.kotlin.boot.contract.PickupContract
import com.ecshopx.kotlin.boot.contract.SalesmanContract
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1-4 第三批后半（3B）：导购 SM-A/F 族 + 员工端第三条链 ST-E/SO-E + 导购提现 CW-F3/F4
 * （docs/04 §18.1、§18.2、§18.3、§18.5、§18.7、§18.8、§22）。
 *
 * 本案盯五件事，都不是"接口能不能通"：
 *
 *  1. **三条链互不认对方的令牌**（§18.3）。员工面前缀 `/api/admin/wxapp` 走 `staffChain`（`@Order(0)`），
 *     排在运营面 `adminChain`（`@Order(2)`）之前 —— 这个顺序就是这条链的全部难点：一旦排到后面，
 *     员工令牌会掉进 `JwtOperatorFilter`，症状同样是 401 `Unauthenticated.`，从响应里根本分不出来。
 *     本案把四个方向都打一遍：无令牌、会员令牌、运营令牌打员工面 401，员工令牌打后台与前台也 401。
 *
 *  2. **停用即刻生效**（D44）。员工面每个请求都回查 `is_valid`，所以 SM-A5 把岗位停掉之后，
 *     那枚**还没过期**的 JWT 立刻打不进任何一条 ST-E/SO-E，启回来又当场恢复。
 *     源系统"停用后会话继续活着"的缺陷不继承。
 *
 *  3. **会话强制，入参说了不算**（§18.3、C177）。SO-E1 的 `salesman_id` 由会话注入，
 *     入参传 99 也落 2 —— 否则任何人传一个 id 就能把佣金记到别人头上；
 *     而会员自己在前台下单时不给 `salesman_id`，服务层回落到 `members.salesman_id`（当前绑定），
 *     **落单即定**：之后换绑不改历史单的归属，也不回溯重算佣金。
 *     ST-E5 同理：`salesman_id` 由会话填，入参带别人的 id 也查不出别人的账。
 *
 *  4. **一套钱、两个口径**（C176 的准确表述）。佣金表里只有**一口池**（按 `distributor_id` 聚合，
 *     D46 每行只有一个 `rebate` 数），`salesman_id` 切的是"这一份属于哪个岗"，不是"另开一份钱"。
 *     岗的可提额取 `min(本岗已结算−本岗占用, 店铺池余额)`，两边加起来永远不超过池子；
 *     而**提现记录**分两栏（CW-F2 只出 `salesman_id=0`、CW-F4 只出 `salesman_id>0`）。
 *     本案让老板与导购各提一次，再交叉读四个列表，证明"谁也没看到对方的记录"。
 *
 *  5. **口令只进不出**（C175）+ **登录不回答"这手机号在不在册"**。任何出参都没有 `password`；
 *     ST-E1 的三种失败（停用 / 没设口令 / 口令不符）是同一句 401，文案一字不差。
 *
 * 权限面（§18.7）：`salesman.lists` 看人、`salesman.update` 管人（建/改/调岗/启停/岗位 CRUD），
 * 店铺运营账号受 `operators.distributor_ids` 收窄（§18.8 门槛②：列表过滤到 0 行、资源引用 404）；
 * **员工面一条权限码都不吃**，判据只有会话里那个 `salesman_id`。
 *
 * 造数：本案把 `items.rebate` 现场设成 500 分/件（seed 不播佣金），单子一律走 mock 微信渠道
 * `payment + notify/wxpay` 结清；JDBC 直接落行只有两处，都是为了造出一个"接口自己建不出来的形状"——
 * 一条从没设过口令的在册导购（ST-E1 的第三种 401），和一个不属于任何店的会员（CW-F3 的 403 路人）。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:excsman;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
        "ecx.schedule.commission-settle-initial-delay-ms=3600000",
        "ecx.schedule.commission-settle-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SalesmanStaffEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var admin = ""
    private var member = ""     // 13800000001 = 店 2 老板（salesman_id=1，role_id=0 没配岗）
    private var member2 = ""    // 13800000002 = 小李（salesman_id=2，岗 1 门店导购）
    private var stranger = ""   // 13800000003 = 本案 JDBC 造的路人，不在任何店的在册表里
    private var shop = ""
    private var viewer = ""

    /** 员工令牌：默认是 salesman 2 的会话（店 2、岗 1） */
    private var staff = ""

    /** 同一个手机号在店 1 的第二身份（本案建，用来跑 C172 回落与"多岗必须指明"） */
    private var crossId = 0L
    private var crossToken = ""

    /** 本案建的第三个导购（店 2），用来跑"改档 / 清空口令" */
    private var plainId = 0L

    private var newRole = 0L      // 本案建的店 2 岗位「收银岗」
    private var platformRole = 0L // 本案建的平台通用岗

    private var orderBound = 0L     // 会员自单：SM-F2 绑定后回落出来的（salesman_id=2）
    private var orderRebind = 0L    // 换绑之后再下一单：归属跟着换（salesman_id=1）
    private var orderExplicit = 0L  // 前台显式给了 salesman_id：给了就用给的
    private var orderStaff = 0L     // SO-E1 代客下单
    private var orderZiti = 0L      // SO-E1 自提单 → SO-E4 核销
    private var orderOtherShop = 0L // 店 1 的自提单：员工面打它是 404（数据权限）

    private val rebateUnit = 500L
    private val demoPwd = "shop" + "123"

    // ===== 传输层助手（与 Pickup/Commission 两案同一套形状） =====

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    /**
     * `header` 决定令牌放哪一栏：员工面的规范 header 是 `x-wxapp-session`（§18.3），
     * 服务端的 `JwtSalesmanFilter` 同时兼容 `Authorization: Bearer` —— 本案两种都打过一次，
     * 因为真实的小程序走的是前者，而既有测试与冒烟脚本走的是后者。
     */
    private fun call(
        method: String,
        url: String,
        token: String?,
        body: Map<String, Any?>?,
        header: String = "Authorization",
    ): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
            "DELETE" -> delete(url)
            else -> throw IllegalArgumentException(method)
        }
        token?.let { req.header(header, if (header == "Authorization") "Bearer $it" else it) }
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

    private fun memberOf(url: String, body: Map<String, Any?>? = null, m: String = "POST") =
        data(call(m, url, member, body), "member $m $url")

    private fun member2Of(url: String, body: Map<String, Any?>? = null, m: String = "POST") =
        data(call(m, url, member2, body), "member2 $m $url")

    private fun anonOf(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, null, body), "anon $m $url")

    /** 员工面默认用 `x-wxapp-session`，与规范一致 */
    private fun staffOf(
        url: String,
        body: Map<String, Any?>? = null,
        m: String = "GET",
        token: String = staff,
    ): JsonNode = data(call(m, url, token, body, "x-wxapp-session"), "staff $m $url")

    private fun expect(method: String, url: String, token: String?, body: Map<String, Any?>?, code: Int): JsonNode {
        val res = call(method, url, token, body)
        assertEquals(code, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }

    private fun expectStaff(method: String, url: String, token: String?, body: Map<String, Any?>?, code: Int): JsonNode {
        val res = call(method, url, token, body, "x-wxapp-session")
        assertEquals(code, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    // ===== 造数与取数助手 =====

    private fun staffLogin(mobile: String, password: String, distributorId: Long? = null): JsonNode =
        data(
            call(
                "POST", "/api/admin/wxapp/login", null,
                linkedMapOf<String, Any?>("mobile" to mobile, "password" to password, "distributor_id" to distributorId)
                    .filterValues { it != null },
            ),
            "ST-E1 $mobile",
        )

    private fun smBody(
        distributorId: Long,
        name: String,
        mobile: String,
        roleId: Long? = null,
        userId: Long? = null,
        password: String? = null,
        isValid: String? = null,
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "distributor_id" to distributorId,
        "salesman_name" to name,
        "mobile" to mobile,
        "role_id" to roleId,
        "user_id" to userId,
        "password" to password,
        "is_valid" to isValid,
    ).filterValues { it != null }

    /** 车内结算建普通物流单：`distributor_id` 在加购那一步就决定了这行进哪家店的车 */
    private fun createLogistics(distributorId: Long, num: Int, token: String = member, salesmanId: Long? = null): Long =
        orderOf(
            distributorId, num, token,
            linkedMapOf<String, Any?>(
                "receiver_state" to "浙江省", "receiver_city" to "杭州市",
                "receiver_district" to "西湖区", "receiver_address" to "文三路 1 号",
                "salesman_id" to salesmanId,
            ).filterValues { it != null },
        )

    private fun createZiti(distributorId: Long, num: Int, token: String = member): Long =
        orderOf(distributorId, num, token, mapOf("receipt_type" to "ziti"))

    private fun orderOf(distributorId: Long, num: Int, token: String, extra: Map<String, Any?>): Long {
        data(
            call("POST", "/api/h5app/wxapp/cart", token,
                mapOf("item_id" to 3, "num" to num, "distributor_id" to distributorId)),
            "cart $distributorId",
        )
        val body = linkedMapOf<String, Any?>(
            "cart_type" to "cart", "order_type" to "normal", "distributor_id" to distributorId,
            "receiver_name" to "张三", "receiver_mobile" to "13800000001", "order_from" to "h5",
        ).apply { putAll(extra) }
        return data(call("POST", "/api/h5app/wxapp/order_new", token, body), "order_new").path("order_id").asLong()
    }

    /** SO-E1 员工端下单：`user_id` 是被代下单的会员，店与岗都由会话锁死 */
    /**
     * SO-E1 的入参形状：本案要保证"报错的成因是被挡的那一条"，
     * 所以四条负向用例也都带齐收货四件套 —— 少带一个字段会先撞上 400 收货信息不完整，
     * 那个 400 会把真正要验的 403/404/422 盖掉。
     */
    private fun soBody(
        userId: Long,
        distributorId: Long,
        extra: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "cart_type" to "cart", "order_type" to "normal", "user_id" to userId,
        "distributor_id" to distributorId,
        "receiver_name" to "张三", "receiver_mobile" to "13800000001", "order_from" to "h5",
        // 物流单要收货四件套齐全（`receiver_address` 单独空着是 400），自提单靠 `receipt_type` 走另一条校验
        "receiver_state" to "浙江省", "receiver_city" to "杭州市",
        "receiver_district" to "西湖区", "receiver_address" to "文三路 1 号",
    ).apply { putAll(extra) }

    /**
     * SO-E1 员工端下单：`cart_type=cart` 吃的是**被代下单那个会员**的车，
     * 所以本案先拿他的令牌加购再下单 —— 车是空的，`order/create` 只会回 422 没有可结算的商品。
     */
    private fun staffOrder(
        userId: Long,
        distributorId: Long,
        num: Int = 1,
        extra: Map<String, Any?> = emptyMap(),
        token: String = staff,
        cartToken: String = member,
    ): Long {
        data(
            call("POST", "/api/h5app/wxapp/cart", cartToken,
                mapOf("item_id" to 3, "num" to num, "distributor_id" to distributorId)),
            "SO-E1 加购 $distributorId",
        )
        return data(call("POST", "/api/admin/wxapp/order/create", token, soBody(userId, distributorId, extra), "x-wxapp-session"), "SO-E1")
            .path("order_id").asLong()
    }

    /** 结清：出支付单要**买家的令牌**（归属判据在支付侧），金额报订单的 `total_fee` */
    private fun pay(orderId: Long, token: String = member) {
        val tradeId = data(
            call("POST", "/api/h5app/wxapp/payment", token,
                mapOf("order_id" to orderId.toString(), "pay_type" to "wxpay")),
            "PAY-1 $orderId",
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

    private fun idsOf(sql: String, vararg v: Any): List<Long> = jdbc.queryForList(sql, Long::class.java, *v)

    private fun codeOf(orderId: Long): String =
        strOf("SELECT ziti_code FROM orders_normal_orders WHERE order_id = ?", orderId).orEmpty()

    /** 一定与真码不同的码（真码 +1 再补零），避免"随手写个 000000 恰好撞对" */
    private fun wrongCodeOf(orderId: Long): String {
        val n = codeOf(orderId).toLongOrNull() ?: 0L
        return ((n + 1) % 1_000_000L).toString().padStart(6, '0')
    }

    private fun salesmanOf(orderId: Long): Long =
        longOf("SELECT salesman_id FROM orders_normal_orders WHERE order_id = ?", orderId)

    private fun closedOf(key: String, id: Long): Long = longOf(
        "SELECT COALESCE(SUM(rebate), 0) FROM distribution_distribute_logs WHERE company_id = 1 AND is_close = TRUE AND $key = ?",
        id,
    )

    // ===================== 员工端那条链 =====================

    @Test @Order(1) fun `令牌就绪与三条链互不认对方`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        member = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        member2 = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "member2").path("token").asText()
        shop = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "distributor01", "password" to demoPwd)), "shop").path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        assertTrue(admin.isNotBlank() && member.isNotBlank() && member2.isNotBlank() && shop.isNotBlank())

        // ST-E1：口令对上了才发 `Kind.SALESMAN` 的令牌
        val login = staffLogin("13800000002", demoPwd)
        assertContract(login, SalesmanContract.STAFF_LOGIN, "ST-E1 出参")
        assertEquals(2L, login.path("salesman_id").asLong(), "seed 的小李")
        assertEquals(2L, login.path("distributor_id").asLong(), "会话里的店：后面每一条 ST-E/SO-E 都按它收窄")
        assertEquals(1L, login.path("role_id").asLong())
        assertEquals("门店导购", login.path("role_name").asText())
        assertFalse(login.has("password"), "登录出参也不带口令")
        staff = login.path("token").asText()
        assertTrue(staff.isNotBlank())

        // 规范 header `x-wxapp-session` 与兼容的 `Authorization: Bearer` 都认（§18.3）
        assertContract(
            data(call("GET", "/api/admin/wxapp/getinfo", staff, null, "Authorization"), "Bearer 兼容"),
            SalesmanContract.STAFF_INFO, "ST-E2（Authorization 头）",
        )

        // 必填：少一个字段是 400（参数面），不是 401（认证面）—— 两码事不混
        expect("POST", "/api/admin/wxapp/login", null, mapOf("mobile" to "13800000002"), 400)
        expect("POST", "/api/admin/wxapp/login", null, mapOf("password" to demoPwd), 400)

        // 三种失败同一句 401，文案一字不差：员工端不该回答"这个手机号在不在册"
        val wrongPwd = expect("POST", "/api/admin/wxapp/login", null, mapOf("mobile" to "13800000002", "password" to "错的口令"), 401)
        val ghost = expect("POST", "/api/admin/wxapp/login", null, mapOf("mobile" to "13900009999", "password" to demoPwd), 401)
        // 第三条要一个"在册但从没设过口令"的人：seed 的两条都被 §seed 末尾那枚哈希铺过了，本案自己补一行
        jdbc.update(
            "INSERT INTO distribution_distributor_salesman " +
                "(salesman_id, company_id, distributor_id, shop_id, user_id, mobile, salesman_name, role_id, is_valid, created, updated) " +
                "VALUES (90, 1, 2, 0, 0, '13900008888', '没设口令的店员', 1, 'true', 0, 0)",
        )
        val noPwd = expect("POST", "/api/admin/wxapp/login", null, mapOf("mobile" to "13900008888", "password" to demoPwd), 401)
        assertEquals("Unauthenticated.", wrongPwd.path("message").asText())
        assertEquals(wrongPwd.path("message").asText(), ghost.path("message").asText(), "口令不符与查无此人同文案")
        assertEquals(wrongPwd.path("message").asText(), noPwd.path("message").asText(), "从没设过口令也同文案")
        assertEquals(401, wrongPwd.path("status_code").asInt())
        assertEquals(401, ghost.path("code").asInt())

        // 跨面：无令牌 / 会员令牌 / 运营令牌都进不了员工链
        expectStaff("GET", "/api/admin/wxapp/getinfo", null, null, 401)
        expectStaff("GET", "/api/admin/wxapp/getinfo", member, null, 401)
        expectStaff("GET", "/api/admin/wxapp/getinfo", admin, null, 401)
        expectStaff("GET", "/api/admin/wxapp/salespersonCount", "乱写的字符串", null, 401)
        // 反向：员工令牌进不了后台（那条链的权限码在这里一条都不认），也进不了前台会员面
        expect("GET", "/api/salesmans", staff, null, 401)
        expect("GET", "/api/h5app/wxapp/cart", staff, null, 401)
    }

    @Test @Order(2) fun `同手机号多店在册时按最小 salesman_id 回落`() {
        // 给 13800000002 在店 1（总部）再落一个身份：跨店重号允许，`uk_salesman_mobile` 只锁店内
        val created = adminOf(
            "/api/salesman",
            smBody(1, "跨店的第二身份", "13800000002", roleId = 1, userId = 2, password = demoPwd),
            "POST",
        )
        assertContract(created, SalesmanContract.SALESMAN_ROW, "SM-A2 出参")
        crossId = created.path("salesman_id").asLong()
        assertTrue(crossId > 2L, "两条 seed 身份之后新增的那一条")
        assertEquals(1L, created.path("distributor_id").asLong())
        assertEquals(2L, created.path("user_id").asLong(), "同一个会员在两家店各有一个岗")

        // 不带 distributor_id：命中 salesman_id 最小的那条（=2），C172 如实记这个回落
        assertEquals(2L, staffLogin("13800000002", demoPwd).path("salesman_id").asLong())
        // 带 distributor_id：店内认人，拿到第二身份
        val cross = staffLogin("13800000002", demoPwd, 1L)
        assertEquals(crossId, cross.path("salesman_id").asLong())
        crossToken = cross.path("token").asText()
        assertEquals(1L, cross.path("distributor_id").asLong())

        // 给了一个自己没有岗的店 → 与"手机号不存在"同一句 401
        val none = expect("POST", "/api/admin/wxapp/login", null,
            mapOf("mobile" to "13800000002", "password" to demoPwd, "distributor_id" to 99), 401)
        assertEquals("Unauthenticated.", none.path("message").asText())

        // 第二身份的会话只看得到自己那家店（同一个 service，只是 distributor_id 换了来源）
        assertEquals(1L, staffOf("/api/admin/wxapp/shoplist", token = crossToken).path("distributor_id").asLong())
        assertEquals(crossId, staffOf("/api/admin/wxapp/getinfo", token = crossToken).path("salesman_id").asLong())
    }

    @Test @Order(3) fun `本人员工信息与本店档案`() {
        val info = staffOf("/api/admin/wxapp/getinfo")
        assertContract(info, SalesmanContract.STAFF_INFO, "ST-E2 出参")
        assertEquals(2L, info.path("salesman_id").asLong())
        assertEquals("小李（门店导购）", info.path("salesman_name").asText())
        // 自己的手机号不掩码：掩码只发生在 SM-A1 那种"别人看列表"的面上
        assertEquals("13800000002", info.path("mobile").asText())
        assertEquals(2L, info.path("distributor_id").asLong())
        assertEquals("demo 旗舰店", info.path("shop_name").asText())
        assertEquals("门店导购", info.path("role_name").asText())
        assertEquals("true", info.path("is_valid").asText())
        assertFalse(info.has("password"), "C175：连自己也不能读回口令")

        val s = staffOf("/api/admin/wxapp/shoplist")
        assertContract(s, SalesmanContract.STAFF_SHOP, "ST-E3 出参")
        assertEquals(2L, s.path("distributor_id").asLong())
        assertEquals(true, s.path("is_ziti").asBoolean(), "seed 给店 2 开了自提，SO-E4 才有单可核")
        assertEquals("", s.path("hour").asText(), "营业时段属 P2 未开的域：给空串保住字段形状，不给 null")
    }

    @Test @Order(4) fun `扫码进店查得到有岗的导购查不到没岗的`() {
        val byId = anonOf("/api/h5app/wxapp/salesperson?salesman_id=2")
        assertContract(byId, SalesmanContract.FRONT_SALESMAN, "SM-F1 出参")
        assertFalse(byId.has("mobile"), "匿名面不给手机号明文")
        assertEquals("demo 旗舰店", byId.path("shop_name").asText())
        assertEquals("salesman=2", byId.path("qrcode_text").asText())
        assertEquals(true, byId.path("is_ziti").asBoolean())
        assertEquals("门店导购", byId.path("role_name").asText())

        // 两种查法是同一个人
        assertEquals(2L, anonOf("/api/h5app/wxapp/salesperson?mobile=13800000002").path("salesman_id").asLong())

        // 在册但没配岗位（seed 的老板 salesman_id=1）→ 前台"查无此人"，而不是"这人存在但没岗"
        expect("GET", "/api/h5app/wxapp/salesperson?salesman_id=1", null, null, 404)
        expect("GET", "/api/h5app/wxapp/salesperson?salesman_id=999", null, null, 404)
        expect("GET", "/api/h5app/wxapp/salesperson?mobile=13900009999", null, null, 404)
        // 两个查询键都不给（空串当没给）→ 参数面 400
        expect("GET", "/api/h5app/wxapp/salesperson", null, null, 400)
        expect("GET", "/api/h5app/wxapp/salesperson?salesman_id=0&mobile=", null, null, 400)
    }

    // ===================== 后台 SM-A =====================

    @Test @Order(5) fun `导购列表掩码统计数与两账号面`() {
        val all = longOf("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE company_id = 1")
        val page = adminOf("/api/salesmans?pageSize=50")
        assertContract(page, SalesmanContract.LIST_ENVELOPE, "SM-A1 外层")
        assertEquals(all, page.path("total_count").asLong())
        page.path("list").forEach { assertContract(it, SalesmanContract.SALESMAN_ROW, "SM-A1 行") }

        val top = page.path("list")[0]
        assertEquals(crossId, top.path("salesman_id").asLong(), "ORDER BY salesman_id DESC：最新在册的那条在最前")
        assertTrue(top.hasNonNull("order_count") && top.hasNonNull("commission_fee"), "两个统计数现算，不落冗余列")

        // 掩码：列表给的是 138****0002 这种形状（§18.2），运营看的是"哪个岗有几个人"
        val li = page.path("list").first { it.path("salesman_id").asLong() == 2L }
        assertEquals("138****0002", li.path("mobile").asText(), "SM-A1 手机号掩码")
        assertEquals("demo 旗舰店", li.path("shop_name").asText(), "店名是 JOIN 出来的")
        assertEquals("门店导购", li.path("role_name").asText())
        assertFalse(li.has("password"), "C175：行类压根没有 password 字段")

        // 四个过滤位各筛一次，期望值都从库里数出来（不写死行数，免得新增数据把本案弄脆）
        assertEquals(
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE company_id = 1 AND distributor_id = 2"),
            adminOf("/api/salesmans?distributor_id=2").path("total_count").asLong(),
        )
        assertEquals(
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE company_id = 1 AND role_id = 1"),
            adminOf("/api/salesmans?role_id=1").path("total_count").asLong(),
        )
        assertEquals(
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE company_id = 1 AND is_valid = 'true'"),
            adminOf("/api/salesmans?is_valid=true").path("total_count").asLong(),
        )
        assertEquals(1L, adminOf("/api/salesmans?keyword=小李").path("total_count").asLong())
        // 不存在的店：admin 型不受限，但那条店下面本来就没人的，自然 0 行
        assertEquals(0L, adminOf("/api/salesmans?distributor_id=999").path("total_count").asLong())

        // 店铺账号（distributor_ids=[2]）只看得到自己店的人（§18.8 门槛②：列表面是过滤不是报错）
        val sp = shopOf("/api/salesmans?pageSize=50")
        assertEquals(
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE company_id = 1 AND distributor_id = 2"),
            sp.path("total_count").asLong(),
        )
        sp.path("list").forEach { assertEquals(2L, it.path("distributor_id").asLong(), "越店的人一条都不给") }

        // viewer 两条码都没有 → 403；未登录 → 401
        expect("GET", "/api/salesmans", viewer, null, 403)
        expect("GET", "/api/salesmans", null, null, 401)
    }

    @Test @Order(6) fun `建人校验与口令只进不出`() {
        // 三栏必填各自 400，错误体带字段级 errors
        val e = expect("POST", "/api/salesman", admin, smBody(2, "只有名字", ""), 400)
        assertTrue(e.path("errors").has("mobile"), "字段错误结构给前端定位：" + e.path("errors"))

        // 店不存在 → 404（资源引用面按 404，不按 422）
        expect("POST", "/api/salesman", admin, smBody(999, "无主之地", "13900001111"), 404)
        // 店内手机号重复 → 422：撞唯一索引之前先在服务层挡，报错才讲得清
        expect("POST", "/api/salesman", admin, smBody(2, "撞号的人", "13800000002"), 422)
        // 岗位本店不可选 → 422（999 根本不存在，判在角色可用性里）
        expect("POST", "/api/salesman", admin, smBody(2, "选错岗的人", "13900002222", roleId = 999), 422)

        // 正常建一个人：出参就是 SM-A1 的完整行（建完前端直接把这一行插进列表）
        val created = adminOf("/api/salesman", smBody(2, "新来的店员", "13900000001", roleId = 1, password = demoPwd), "POST")
        assertContract(created, SalesmanContract.SALESMAN_ROW, "SM-A2 出参")
        plainId = created.path("salesman_id").asLong()
        assertFalse(created.has("password"), "C175：口令写进去就不出来")
        assertEquals(1L, created.path("role_id").asLong())
        assertEquals("true", created.path("is_valid").asText(), "不传 is_valid 即启用")
        assertEquals(0L, created.path("user_id").asLong(), "没绑会员账号：只有工号")

        // 口令真的落成了可比对的哈希：拿它登录成功
        val t = staffLogin("13900000001", demoPwd)
        assertEquals(plainId, t.path("salesman_id").asLong())
        assertEquals(
            1L,
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE salesman_id = ? AND password IS NOT NULL", plainId),
            "库里存的是 bcrypt 哈希而不是明文（哈希由服务层算）",
        )

        // 店铺账号只能往自己店里添人：往店 1 建是 404（不给"这店存在"的信息）
        expect("POST", "/api/salesman", shop, smBody(1, "别人店的人", "13900003333"), 404)
        expect("POST", "/api/salesman", viewer, smBody(2, "无权限的人", "13900004444"), 403)
        expect("POST", "/api/salesman", null, smBody(2, "没登录", "13900005555"), 401)
    }

    @Test @Order(7) fun `改档调岗启停与停用即刻生效`() {
        // SM-A3 读-改-写：只改名字，手机号与岗位不动
        val upd = adminOf("/api/salesman/$plainId", mapOf("salesman_name" to "改过名的店员"), "PUT")
        assertContract(upd, SalesmanContract.SALESMAN_ROW, "SM-A3 出参")
        assertEquals("改过名的店员", upd.path("salesman_name").asText())
        // SM-A1..A5 共用同一个行装配，掩码也就跟着共用：运营改一个人，回显里照样拿不到明文手机号
        assertEquals("139****0001", upd.path("mobile").asText(), "只改名字不该把手机号换掉")
        assertEquals("13900000001", strOf("SELECT mobile FROM distribution_distributor_salesman WHERE salesman_id = ?", plainId), "库里落的还是原号")
        assertEquals(1L, upd.path("role_id").asLong())
        assertFalse(upd.has("password"))
        // 空串手机号当"没传"（不是把手机号清空）
        adminOf("/api/salesman/$plainId", mapOf("mobile" to ""), "PUT")
        assertEquals("13900000001", strOf("SELECT mobile FROM distribution_distributor_salesman WHERE salesman_id = ?", plainId), "空串不当清空：库里那一格没动")
        // 改成别人店内已有的号 → 422
        expect("PUT", "/api/salesman/$plainId", admin, mapOf("mobile" to "13800000002"), 422)
        // 店铺账号改自己店里的人可以，改别人店里的 → 404
        assertEquals(plainId, shopOf("/api/salesman/$plainId", mapOf("salesman_name" to "店铺改自己人"), "PUT").path("salesman_id").asLong())
        expect("PUT", "/api/salesman/$crossId", shop, mapOf("salesman_name" to "改了别人店的人"), 404)
        expect("PUT", "/api/salesman/abc", admin, mapOf("salesman_name" to "不是数字"), 404)
        expect("PUT", "/api/salesman/9999", admin, mapOf("salesman_name" to "不存在"), 404)

        // SM-A4 调岗：岗 2「店长」是店 2 自己的岗，但 cross 在店 1 —— 选不到，先改回店 2 的人身上
        expect("PUT", "/api/salesman/role/$crossId", admin, mapOf("role_id" to 2), 422)
        val moved = adminOf("/api/salesman/role/$plainId", mapOf("role_id" to 2), "PUT")
        assertContract(moved, SalesmanContract.SALESMAN_ROW, "SM-A4 出参")
        assertEquals(2L, moved.path("role_id").asLong())
        assertEquals("店长", moved.path("role_name").asText())
        assertEquals(
            2L,
            longOf("SELECT role_id FROM distribution_distributor_salesman WHERE salesman_id = ?", plainId),
            "只动 role_id 这一列",
        )
        // 岗 1 是平台通用岗，店 1 也选得到（判据是 `distributor_id IN (0, 本店)`，不是归属列）
        assertEquals(1L, adminOf("/api/salesman/role/$crossId", mapOf("role_id" to 1), "PUT").path("role_id").asLong())
        expect("PUT", "/api/salesman/role/$crossId", admin, mapOf("role_id" to 999), 422)
        expect("PUT", "/api/salesman/role/$crossId", admin, emptyMap(), 400)

        // D44：停用即刻关掉员工端 —— 拿还没过期的令牌当场打一条，必须 401
        val off = adminOf("/api/salesman/$crossId", mapOf("is_valid" to "false"), "PUT")
        assertContract(off, SalesmanContract.STATUS, "SM-A5 出参")
        assertEquals("false", off.path("is_valid").asText())
        val denied = expectStaff("GET", "/api/admin/wxapp/getinfo", crossToken, null, 401)
        assertEquals("Unauthenticated.", denied.path("message").asText(), "与口令不符同一句：不回答这人怎么了")
        expectStaff("POST", "/api/admin/wxapp/order/create", crossToken, mapOf("cart_type" to "cart"), 401)
        // 停用的人也登不进来（findLogin 只认 is_valid='true'）
        expect("POST", "/api/admin/wxapp/login", null, mapOf("mobile" to "13800000002", "password" to demoPwd, "distributor_id" to 1), 401)
        // 启回 → 同一个令牌立刻恢复：会话本来就没被撤销，判据只在库里那一列
        assertEquals("true", adminOf("/api/salesman/$crossId", mapOf("is_valid" to "true"), "PUT").path("is_valid").asText())
        assertEquals(crossId, staffOf("/api/admin/wxapp/getinfo", token = crossToken).path("salesman_id").asLong())
        expect("PUT", "/api/salesman/$crossId", admin, mapOf("is_valid" to "yes"), 400)
        expect("PUT", "/api/salesman/9999/status", admin, mapOf("is_valid" to "false"), 404)

        // 清空口令（`password` 传空串）之后，同一个手机号立刻登不进来 —— 与"从没设过"同一条拒
        adminOf("/api/salesman/$plainId", mapOf("password" to ""), "PUT")
        expect("POST", "/api/admin/wxapp/login", null, mapOf("mobile" to "13900000001", "password" to demoPwd), 401)
        assertEquals(
            0L,
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE salesman_id = ? AND password IS NOT NULL", plainId),
        )
    }

    @Test @Order(8) fun `岗位 CRUD 走复数路径且删不掉还有人的岗`() {
        val roles = adminOf("/api/salesman/roles?pageSize=50")
        assertContract(roles, SalesmanContract.LIST_ENVELOPE, "SM-A6 外层")
        assertEquals(
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman_role WHERE company_id = 1"),
            roles.path("total_count").asLong(),
        )
        roles.path("list").forEach { assertContract(it, SalesmanContract.ROLE_ROW, "SM-A6 行") }

        // 只列本店的岗 vs 连平台通用岗一起列
        assertEquals(
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman_role WHERE company_id = 1 AND distributor_id = 2"),
            adminOf("/api/salesman/roles?distributor_id=2").path("total_count").asLong(),
        )
        assertEquals(
            longOf("SELECT COUNT(*) FROM distribution_distributor_salesman_role WHERE company_id = 1 AND distributor_id IN (0, 2)"),
            adminOf("/api/salesman/roles?distributor_id=2&include_platform=true").path("total_count").asLong(),
            "distributor_id=0 是平台通用岗：判能不能选看的是这一列，不是归属",
        )
        // 店铺账号问别人的店 → 404（`scope.require` 判在查询之前）
        expect("GET", "/api/salesman/roles?distributor_id=1", shop, null, 404)
        expect("GET", "/api/salesman/roles", viewer, null, 403)

        val r = adminOf("/api/salesman/roles", mapOf("distributor_id" to 2, "role_name" to "收银岗", "remark" to "本案新建"), "POST")
        assertContract(r, SalesmanContract.ROLE_ROW, "SM-A6 新建出参")
        newRole = r.path("role_id").asLong()
        assertEquals(1, r.path("status").asInt())
        assertEquals("启用", r.path("status_text").asText())
        assertEquals("demo 旗舰店", r.path("scope_text").asText(), "本店的岗回显店名")
        expect("POST", "/api/salesman/roles", admin, mapOf("distributor_id" to 2, "role_name" to "收银岗"), 422)
        expect("POST", "/api/salesman/roles", admin, mapOf("distributor_id" to 2, "role_name" to ""), 400)

        // 平台通用岗：admin 建得了（distributor_id=0），店铺账号建不了 —— 与自提点的通用点同一条口径
        val pr = adminOf("/api/salesman/roles", mapOf("distributor_id" to 0, "role_name" to "平台巡店"), "POST")
        platformRole = pr.path("role_id").asLong()
        assertEquals("全平台通用", pr.path("scope_text").asText())
        expect("POST", "/api/salesman/roles", shop, mapOf("distributor_id" to 0, "role_name" to "店铺建的平台岗"), 404)

        assertEquals("收银（改名）", adminOf("/api/salesman/roles/$newRole", mapOf("role_name" to "收银（改名）"), "PUT").path("role_name").asText())
        assertEquals(0, adminOf("/api/salesman/roles/$newRole", mapOf("status" to 0), "PUT").path("status").asInt())
        expect("PUT", "/api/salesman/roles/9999", admin, mapOf("role_name" to "不存在"), 404)
        expect("PUT", "/api/salesman/roles/abc", admin, mapOf("role_name" to "不是数字"), 404)

        // 删除守卫：岗 1 下面还挂着人（seed 的小李、本案的 cross），先调岗才能删
        val used = expect("DELETE", "/api/salesman/roles/1", admin, null, 422)
        assertTrue(used.path("message").asText().contains("导购"), "报错要说清是谁挡着：" + used.path("message").asText())
        assertEquals(1L, longOf("SELECT COUNT(*) FROM distribution_distributor_salesman_role WHERE role_id = 1"))
        // 空岗删得掉（真删行：岗名不是任何历史事实的载体，`salesman.role_id` 才是）
        assertContract(adminOf("/api/salesman/roles/$platformRole", null, "DELETE"), SalesmanContract.ROLE_DELETED, "SM-A6 删除出参")
        assertEquals(0L, longOf("SELECT COUNT(*) FROM distribution_distributor_salesman_role WHERE role_id = ?", platformRole))
        expect("DELETE", "/api/salesman/roles/$platformRole", admin, null, 404)
        // 无权限码 403；店铺账号删自己店的空岗是 200（归属对上、码也有）
        expect("DELETE", "/api/salesman/roles/$newRole", viewer, null, 403)
        assertEquals(newRole, shopOf("/api/salesman/roles/$newRole", null, "DELETE").path("role_id").asLong())
    }

    // ===================== 会员面 SM-F：绑定与下单回落 =====================

    @Test @Order(9) fun `绑定导购后自单自动带归属`() {
        jdbc.update("UPDATE items SET rebate = ? WHERE item_id = 3", rebateUnit)

        // 参数面与存在性
        expect("POST", "/api/h5app/wxapp/member/bindSalesperson", null, mapOf("salesman_id" to 2), 401)
        expect("POST", "/api/h5app/wxapp/member/bindSalesperson", member, emptyMap(), 400)
        expect("POST", "/api/h5app/wxapp/member/bindSalesperson", member, mapOf("salesman_id" to 999), 404)

        val b = memberOf("/api/h5app/wxapp/member/bindSalesperson", mapOf("salesman_id" to 2))
        assertContract(b, SalesmanContract.BIND, "SM-F2 出参")
        assertEquals(1L, b.path("user_id").asLong(), "user_id 取自令牌而不是入参")
        assertEquals(2L, b.path("salesman_id").asLong())
        assertEquals(2L, b.path("distributor_id").asLong())
        assertTrue(b.path("bind_time").asLong() > 0)

        // C177：会员自己下单、不传 salesman_id → 服务端从 `members.salesman_id` 回落进订单头
        orderBound = createLogistics(2, 1)
        assertEquals(2L, salesmanOf(orderBound))
        pay(orderBound)
        assertEquals(
            2L,
            longOf("SELECT salesman_id FROM distribution_distribute_logs WHERE order_id = ? AND item_id = 3", orderBound),
            "佣金行的归属从订单头抄过来，入账时不重新判断",
        )
        assertEquals(rebateUnit, longOf("SELECT rebate FROM distribution_distribute_logs WHERE order_id = ?", orderBound))
        assertEquals(0L, longOf("SELECT COUNT(*) FROM distribution_distribute_logs WHERE order_id = ? AND is_close = TRUE", orderBound), "7 天延迟期内还没结")

        // 换绑只影响之后的单：历史单的 `salesman_id` 不回溯改（与 D46"改费率不回溯"同族）
        memberOf("/api/h5app/wxapp/member/bindSalesperson", mapOf("salesman_id" to 1))
        orderRebind = createLogistics(2, 1)
        assertEquals(1L, salesmanOf(orderRebind))
        assertEquals(2L, salesmanOf(orderBound), "上一单的归属没被这次换绑带走")
        pay(orderRebind)

        // 前台显式给了 salesman_id 就用给的（回落只在没给时发生）
        orderExplicit = createLogistics(2, 1, salesmanId = 2)
        assertEquals(2L, salesmanOf(orderExplicit))
        pay(orderExplicit)
    }

    // ===================== 员工面 SO-E：下单与查单 =====================

    @Test @Order(10) fun `代客下单三条强制`() {
        // ① salesman_id 由会话强制：入参传 99 也落 2
        // ② distributor_id 折到本人所在店：给了店 1 是 403
        // ③ 被代下单的会员必须真实存在且属本租户
        orderStaff = staffOrder(1, 2, num = 2, extra = mapOf("salesman_id" to 99))
        assertContract(
            staffOf("/api/admin/wxapp/order/getlist?order_id=$orderStaff"),
            SalesmanContract.LIST_ENVELOPE, "SO-E2 单查外层",
        )
        assertEquals(2L, salesmanOf(orderStaff), "会话里的岗，不是入参里那个 99")
        assertEquals(1L, longOf("SELECT user_id FROM orders_normal_orders WHERE order_id = ?", orderStaff), "单落在被代下单的会员名下")
        assertEquals(2L, longOf("SELECT distributor_id FROM orders_normal_orders WHERE order_id = ?", orderStaff))
        assertEquals(rebateUnit * 2, longOf("SELECT total_rebate FROM orders_normal_orders WHERE order_id = ?", orderStaff))

        val otherShop = expectStaff("POST", "/api/admin/wxapp/order/create", staff, soBody(1, 1), 403)
        assertEquals("只能为本人所属店铺开单", otherShop.path("message").asText(),
            "写入目标越店：明说只能开本店的单，比说这店不存在更有用")

        expectStaff("POST", "/api/admin/wxapp/order/create", staff, soBody(999, 2), 404)
        // 散客（`user_id=0`）没有购物车 → 422 空车，这是期望行为不是缺陷（文档 §22 记这条）
        expectStaff("POST", "/api/admin/wxapp/order/create", staff, soBody(0, 2), 422)
    }

    @Test @Order(11) fun `只出他开的单且详情带归属人`() {
        val mine = idsOf(
            "SELECT order_id FROM orders_normal_orders WHERE company_id = 1 AND salesman_id = 2 AND distributor_id = 2 AND order_status <> 'CANCEL'",
        )
        val page = staffOf("/api/admin/wxapp/order/getlist?pageSize=50")
        assertContract(page, SalesmanContract.LIST_ENVELOPE, "SO-E2 外层")
        assertEquals(mine.size.toLong(), page.path("total_count").asLong(), "他开的单一条不多一条不少")
        val shown = page.path("list").map { it.path("order_id").asLong() }
        assertTrue(shown.toSet() == mine.toSet(), "列表集合与库里的归属集合一致：$shown vs $mine")
        page.path("list").forEach { assertContract(it, OrderContract.ROW, "SO-E2 行（复用 ORDER-4 装配）") }

        // 入参里的 salesman_id / distributor_id / user_id 一律丢弃，不做"取交集"那种要多一层解释的合并
        assertEquals(mine.size.toLong(), staffOf("/api/admin/wxapp/order/getlist?salesman_id=1&pageSize=50").path("total_count").asLong())
        assertEquals(mine.size.toLong(), staffOf("/api/admin/wxapp/order/getlist?distributor_id=1&user_id=1&pageSize=50").path("total_count").asLong())

        val d = staffOf("/api/admin/wxapp/order/getinfo?order_id=$orderStaff")
        assertContract(d, OrderContract.DETAIL, "SO-E3 = ORDER-5 字段集")
        assertContract(d.path("salesman"), SalesmanContract.STAFF_SALESMAN_BLOCK, "SO-E3 多的那一块")
        assertEquals(2L, d.path("salesman").path("salesman_id").asLong())
        assertEquals("小李（门店导购）", d.path("salesman").path("salesman_name").asText())
        assertContract(d.path("orderInfo"), OrderContract.ORDER_INFO, "SO-E3 orderInfo")
        assertEquals(2L, d.path("orderInfo").path("salesman_id").asLong())

        // 不是他开的单（用例 9 换绑后那一单归 salesman 1）→ 404，不给"这单存在"的信息
        expectStaff("GET", "/api/admin/wxapp/order/getinfo?order_id=$orderRebind", staff, null, 404)
        expectStaff("GET", "/api/admin/wxapp/order/getinfo?order_id=999999999", staff, null, 404)
        expectStaff("GET", "/api/admin/wxapp/order/getinfo", staff, null, 404)
        expectStaff("GET", "/api/admin/wxapp/order/getinfo?order_id=abc", staff, null, 404)

        // 结清之后这张代客单才进佣金账
        pay(orderStaff)
    }

    @Test @Order(12) fun `统计随单变动而明细只出自己的`() {
        val before = staffOf("/api/admin/wxapp/salespersonCount")
        assertContract(before, SalesmanContract.STAFF_COUNT, "ST-E4 出参")
        assertEquals(
            longOf("SELECT COUNT(*) FROM orders_normal_orders WHERE salesman_id = 2 AND order_status <> 'CANCEL'"),
            before.path("order_count").asLong(),
        )
        assertEquals(before.path("order_count").asLong(), before.path("today_count").asLong(), "本案的单都是今天建的")
        assertEquals(0L, before.path("pending_count").asLong(), "三单都结清了，没有待收口的")
        assertEquals(rebateUnit * 4, before.path("commission_open").asLong(), "500 + 500 + 1000：本岗名下单子的未结算佣金")
        assertEquals(0L, before.path("commission_closed").asLong(), "延迟结算期内都是未结")
        assertEquals(0L, before.path("balance").asLong(), "余额只认已结算那部分")

        // ST-E5：salesman_id 由会话强制，入参带别人的 id 查不出别人的账
        val fee = staffOf("/api/admin/wxapp/salespersonFee?pageSize=50")
        assertContract(fee, SalesmanContract.LIST_ENVELOPE, "ST-E5 外层")
        fee.path("list").forEach { assertContract(it, CommissionContract.LOG_ROW + "salesman_id", "ST-E5 行") }
        val logs = longOf("SELECT COUNT(*) FROM distribution_distribute_logs WHERE company_id = 1 AND salesman_id = 2")
        assertEquals(logs, fee.path("total_count").asLong())
        fee.path("list").forEach { assertEquals(2L, it.path("salesman_id").asLong()) }
        assertEquals(logs, staffOf("/api/admin/wxapp/salespersonFee?salesman_id=1&pageSize=50").path("total_count").asLong())
        assertEquals(logs, staffOf("/api/admin/wxapp/salespersonFee?distributor_id=1&pageSize=50").path("total_count").asLong(), "入参店被丢弃：还是他自己的账，不漏也不串")

        // 后台同一口径能按岗筛（DL-A1 与 ST-E5 共用一条列表，只是数据来源不同）
        assertEquals(logs, adminOf("/api/distribution/logs?salesman_id=2&pageSize=50").path("total_count").asLong())

        // 再下一张未付款的自提单 → 三个单数当场跟着变
        orderZiti = staffOrder(1, 2, extra = mapOf("receipt_type" to "ziti"))
        val after = staffOf("/api/admin/wxapp/salespersonCount")
        assertEquals(before.path("order_count").asLong() + 1, after.path("order_count").asLong())
        assertEquals(1L, after.path("pending_count").asLong(), "未付未完成的单就是导购要跟的那批")
    }

    @Test @Order(13) fun `员工端核销与后台同一条状态迁移`() {
        val wo = staffOf("/api/admin/wxapp/order/getinfo?order_id=$orderZiti").path("orderInfo")
        assertEquals("ziti", wo.path("receipt_type").asText())
        val code = codeOf(orderZiti)
        assertEquals(6, code.length, "6 位取货码建单时就发在单上")

        // 闸门②：未付款先挡住
        expectStaff("POST", "/api/admin/wxapp/order/ziti", staff, mapOf("order_id" to orderZiti, "ziti_code" to code), 422)
        // 缺参是 400（参数面）；空串不当"没传"，它进逐字比对，结果是"码不符"422
        expectStaff("POST", "/api/admin/wxapp/order/ziti", staff, mapOf("order_id" to orderZiti), 400)
        expectStaff("POST", "/api/admin/wxapp/order/ziti", staff, mapOf("order_id" to orderZiti, "ziti_code" to ""), 422)

        pay(orderZiti)
        expectStaff("POST", "/api/admin/wxapp/order/ziti", staff,
            mapOf("order_id" to orderZiti, "ziti_code" to wrongCodeOf(orderZiti)), 422)

        val done = staffOf("/api/admin/wxapp/order/ziti", mapOf("order_id" to orderZiti, "ziti_code" to code), "POST")
        assertContract(done, PickupContract.WRITE_OFF_ORDER, "SO-E4 出参（与 WO-A2 同一个装配）")
        assertEquals("true", done.path("ziti_status").asText())
        assertEquals("DONE", done.path("order_status").asText())
        assertEquals(
            "DONE",
            strOf("SELECT delivery_status FROM orders_normal_orders WHERE order_id = ?", orderZiti),
            "自提没有物流：核销即把收货态一起置 DONE",
        )
        // 留痕必须指认得出来是谁点的这一下（署名来自会话，不是来自入参）
        assertEquals("salesman:2", strOf("SELECT operator FROM orders_operate_logs WHERE order_id = ? AND behavior = 'writeoff'", orderZiti))
        assertEquals("salesman", strOf("SELECT operator_role FROM orders_operate_logs WHERE order_id = ? AND behavior = 'writeoff'", orderZiti))
        assertEquals(2L, longOf("SELECT operator_id FROM orders_operate_logs WHERE order_id = ? AND behavior = 'writeoff'", orderZiti))
        assertEquals(1L, longOf("SELECT COUNT(*) FROM orders_operate_logs WHERE order_id = ? AND behavior = 'writeoff'", orderZiti))

        // 重复核销：条件更新 `WHERE ziti_status='false'` 受影响 0 行 → 422
        expectStaff("POST", "/api/admin/wxapp/order/ziti", staff, mapOf("order_id" to orderZiti, "ziti_code" to code), 422)

        // 数据权限：店 1 的自提单对他不存在（`load` 判在付款闸门之前，所以未付款也同样是 404）
        orderOtherShop = createZiti(1, 1)
        expectStaff("POST", "/api/admin/wxapp/order/ziti", staff, mapOf("order_id" to orderOtherShop, "ziti_code" to codeOf(orderOtherShop)), 404)
        expectStaff("GET", "/api/admin/wxapp/order/getinfo?order_id=$orderOtherShop", staff, null, 404)

        // 后台那条 WO-A1 看的是同一张单：核销完只剩"已核销"这一句，两处状态是同一份数据
        assertEquals("true", adminOf("/api/writeoff/$orderZiti").path("ziti_status").asText())
    }

    // ===================== 导购提现 CW-F3/F4 =====================

    @Test @Order(14) fun `提现先认在册岗再认余额与在途`() {
        // 本案造一个不属于任何店的会员：403 的判据必须是"没有在册岗"，而不是"没登录"
        jdbc.update(
            "INSERT INTO members (user_id, company_id, grade_id, mobile, user_card_code, created, updated) " +
                "VALUES (3, 1, 1, '13800000003', '80000003', 0, 0)",
        )
        jdbc.update("INSERT INTO members_info (user_id, username, name, sex) VALUES (3, '路人', '王五', 1)")
        stranger = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000003", "check_type" to "mobile", "vcode" to "8888",
        )), "stranger").path("token").asText()
        assertTrue(stranger.isNotBlank())

        expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", null, mapOf("money" to 100), 401)
        val notSalesman = expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", stranger, mapOf("money" to 100), 403)
        assertTrue(notSalesman.path("message").asText().contains("在册导购"), notSalesman.path("message").asText())

        expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", member2, mapOf("money" to 0), 422)
        expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", member2, mapOf("money" to -1), 422)
        // 同一个会员在两家店各有岗 → 必须指明提到哪家（服务层不替他挑）
        val ambiguous = expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", member2, mapOf("money" to 100), 422)
        assertTrue(ambiguous.path("message").asText().contains("请指明"), ambiguous.path("message").asText())
        expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", member2, mapOf("money" to 100, "distributor_id" to 999), 422)

        // 未结算的钱提不了：账上有 open，但 closed 与店池余额都还是 0
        val short = expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", member2, mapOf("money" to 100, "distributor_id" to 2), 422)
        assertTrue(short.path("errors").has("money"), "余额不足要落到字段错误：" + short.path("message").asText())

        // 结算之后（DL-A3 把到期行一把置已结）才有可提余额
        val closed = adminOf("/api/distribution/logs/close", mapOf("distributor_id" to 2, "to_time" to TimeSec.now() + 30 * 86400), "POST")
        assertContract(closed, CommissionContract.CLOSE_RESULT, "DL-A3 出参")
        assertTrue(closed.path("closed_count").asLong() > 0)
        assertEquals(
            closedOf("salesman_id", 2L),
            staffOf("/api/admin/wxapp/salespersonCount").path("balance").asLong(),
            "本岗已结算的钱此时全额可提：店池还没被任何人提过，min 取到自己那一份",
        )
        assertTrue(closedOf("salesman_id", 2L) > 0)
    }

    @Test @Order(15) fun `一份池子两个口径且记录分两栏`() {
        val salesmanBalance = staffOf("/api/admin/wxapp/salespersonCount").path("balance").asLong()
        assertTrue(salesmanBalance > 0)
        val shopBalance = adminOf("/api/distribution/count?distributor_id=2").path("balance").asLong()
        assertTrue(shopBalance >= salesmanBalance, "店池含本岗那一份：$shopBalance >= $salesmanBalance")

        // 先让店铺老板提走"不属于任何岗"的那一份（CW-F1：行上 `salesman_id=0`）
        val own = closedOf("salesman_id", 1L)
        assertEquals(own, memberOf("/api/h5app/wxapp/cash_withdrawal", mapOf("distributor_id" to 2, "money" to own)).path("money").asLong())
        val shopRow = jdbc.queryForList("SELECT salesman_id FROM distribution_cash_withdrawal WHERE distributor_id = 2 AND salesman_id = 0")
        assertEquals(1L, shopRow.size.toLong(), "店那侧的行不带岗")

        // 再让导购提他自己那一份：店池被上面那一笔扣掉之后仍然够（min 生效但不为 0）
        val w = member2Of("/api/h5app/wxapp/salesman/applyCashWithdrawal", mapOf("money" to salesmanBalance, "distributor_id" to 2))
        assertContract(w, CommissionContract.WITHDRAW_ROW + "salesman_id", "CW-F3 出参")
        assertEquals(2L, w.path("salesman_id").asLong(), "岗那侧的行带 salesman_id")
        assertEquals(salesmanBalance, w.path("money").asLong())
        assertEquals("applied", w.path("status").asText())

        // 在途唯一按岗挡（同店两个导购各排各的队）
        val again = expect("POST", "/api/h5app/wxapp/salesman/applyCashWithdrawal", member2,
            mapOf("money" to 1, "distributor_id" to 2), 422)
        assertTrue(again.path("message").asText().contains("待处理"))
        // 同一口池：店那侧也因为已经有一笔在途而被挡住（钱的出口只有一个）
        expect("POST", "/api/h5app/wxapp/cash_withdrawal", member, mapOf("distributor_id" to 2, "money" to 1), 422)

        // 提完之后本岗余额回落为 0，占用额等于他提的那笔（C149 同一条公式）
        val after = staffOf("/api/admin/wxapp/salespersonCount")
        assertEquals(0L, after.path("balance").asLong(), "全额提走后本岗可提余额归零，不会反弹")
        assertEquals(salesmanBalance, after.path("withdraw_occupying").asLong())

        // 四个列表交叉读，证明"谁也没看到对方的记录"（C176：账本分列）
        assertEquals(1L, member2Of("/api/h5app/wxapp/salesman/getCashWithdrawalList", null, "GET").path("total_count").asLong(), "CW-F4：小李只有一条岗上的")
        assertEquals(0L, member2Of("/api/h5app/wxapp/cash_withdrawals", null, "GET").path("total_count").asLong(), "CW-F2：他没替店提过钱，那侧 0 行")
        assertEquals(0L, memberOf("/api/h5app/wxapp/salesman/getCashWithdrawalList", null, "GET").path("total_count").asLong(), "CW-F4：老板没有岗上的申请")
        assertEquals(1L, memberOf("/api/h5app/wxapp/cash_withdrawals", null, "GET").path("total_count").asLong(), "CW-F2：老板那条店上的申请在这里")
        assertEquals(2L, adminOf("/api/distribution/cash_withdrawals?pageSize=50").path("total_count").asLong(), "后台两份都看得到")

        // 后台按岗筛（CW-A1 追加键）：`salesman_id=2` 只出岗那一条
        assertEquals(1L, adminOf("/api/distribution/cash_withdrawals?salesman_id=2").path("total_count").asLong())
        // 无权限码 → 403
        expect("GET", "/api/distribution/cash_withdrawals?salesman_id=2", viewer, null, 403)
    }
}
