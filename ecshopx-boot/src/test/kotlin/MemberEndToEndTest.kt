package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.MemberContract
import com.ecshopx.kotlin.boot.contract.PayContract
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M0-8 门槛：MEM-1..8 会员面端到端（docs/04 §2.6）。
 *
 * 独立内存库（exccmember），只依赖 seed：会员 1（普通等、积分 1000）、会员 2（金卡）、
 * 收藏快照 2 条（item 3 快照价 9900，实时价 8900）、浏览足迹 2 条、地址 1 条。
 * 关单定时推到 1 小时后，避免测试期间订单被回收影响 orderscount 口径。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccmember;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.upload.root=target/test-member-storage",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MemberEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var tok = ""
    private var other = ""

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

    private fun ok(url: String, body: Map<String, Any?>? = null, m: String = "GET", asMember: String = tok) =
        data(call(m, url, asMember, body), "$m $url")

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

    private fun col(sql: String): Long = jdbc.queryForObject(sql, Long::class.java)!!

    @Test @Order(1) fun `会员与后台令牌就绪`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        other = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "other").path("token").asText()
        assertTrue(tok.isNotBlank() && other.isNotBlank())
    }

    @Test @Order(2) fun `MEM-1 我的信息 契约与脱敏`() {
        val p = ok("/api/h5app/wxapp/member")
        assertContract(p, MemberContract.PROFILE, "MEM-1")
        assertEquals("138****0001", p.path("mobile").asText(), "手机号只出掩码")
        assertContract(p.path("gradeInfo"), MemberContract.GRADE_INFO, "MEM-1 gradeInfo")
        assertContract(p.path("deposit"), PayContract.DEPOSIT_VIEW, "MEM-1 deposit")
        assertEquals(1L, p.path("user_id").asLong())
        assertEquals(1000L, p.path("point").asLong(), "seed 积分 1000")
        assertEquals(1, p.path("is_new").asInt(), "无已付订单即新客")
        // 普通等 level=1，下一档是银卡（level 2，门槛 10000 分）
        assertEquals("普通会员", p.path("gradeInfo").path("grade_name").asText())
        assertEquals("银卡会员", p.path("nextGradeInfo").path("grade_name").asText())
        assertEquals(10000L, p.path("nextGradeInfo").path("need_growth").asLong(), "差额 = 门槛 - 累计实付")
        // 金卡会员（grade_id=3）已是最高档，nextGradeInfo 为空
        val g = ok("/api/h5app/wxapp/member", m = "GET", asMember = other)
        assertEquals("金卡会员", g.path("gradeInfo").path("grade_name").asText())
        assertTrue(g.path("nextGradeInfo").isNull(), "顶级无下一档")
        // 详情页会员价与结算价同口径：金卡 95 折，199900 - 199900*5/100 = 189905
        val gold = ok("/api/h5app/wxapp/goods/items/2", asMember = other)
        assertEquals(189905L, gold.path("member_price").asLong(), "会员等级价 = 售价 - 折让")
        assertEquals(199900L, ok("/api/h5app/wxapp/goods/items/2").path("member_price").asLong(), "普通会员无折扣")
    }

    @Test @Order(3) fun `MEM-2 改资料 白名单生效且禁改登录凭证`() {
        val r = ok("/api/h5app/wxapp/member", mapOf(
            "username" to "改名后的演示用户", "sex" to 2, "birthday" to "1992-02-02",
            "habbit" to listOf(mapOf("name" to "露营", "ischecked" to 1)),
        ), m = "PUT")
        assertContract(r, MemberContract.PROFILE, "MEM-2 回显")
        assertEquals("改名后的演示用户", r.path("nickname").asText())
        assertEquals(2, r.path("sex").asInt())
        assertEquals("露营", r.path("habbit")[0].path("name").asText(), "爱好数组写进 JSON 列并原样读回")
        // 掩码回显是个人中心保存的常态：忽略，不报错也不改号
        val masked = ok("/api/h5app/wxapp/member", mapOf("mobile" to "138****0001", "username" to "掩码回显保存"), m = "PUT")
        assertEquals("掩码回显保存", masked.path("nickname").asText())
        assertEquals(13800000001L, col("SELECT CAST(mobile AS BIGINT) FROM members WHERE user_id = 1"), "库里号码未变")
        // 真实改号 / 改租户归属：显式拒绝，错误体四件套齐全（校验类错误走 400，与 Dingo 参数错一致）
        val bad = expect("PUT", "/api/h5app/wxapp/member", mapOf("mobile" to "13700000007"), 400)
        assertEquals("该字段不允许修改", bad.path("message").asText())
        assertTrue(bad.path("errors").has("mobile"))
        assertEquals(400, bad.path("status_code").asInt())
        expect("PUT", "/api/h5app/wxapp/member", mapOf("company_id" to 2), 400)
        expect("PUT", "/api/h5app/wxapp/member", mapOf("user_id" to 9), 400)
    }

    @Test @Order(4) fun `MEM-8 验证码 mock 校验入参`() {
        val v = ok("/api/h5app/vcode", mapOf("mobile" to "13800000001", "type" to "rebind_mobile"), m = "POST")
        assertContract(v, MemberContract.VCODE, "MEM-8")
        assertEquals("138****0001", v.path("mobile").asText(), "回执也只出掩码")
        assertEquals(1, v.path("status").asInt())
        // 匿名可用（登录前就要发码）
        expect("POST", "/api/h5app/vcode", mapOf("mobile" to "13900000001", "type" to "login"), 200, token = null)
        expect("POST", "/api/h5app/vcode", mapOf("mobile" to "12345", "type" to "login"), 400, token = null)
        val t = expect("POST", "/api/h5app/vcode", mapOf("mobile" to "13800000001", "type" to "whatever"), 400, token = null)
        assertTrue(t.path("errors").has("type"))
    }

    @Test @Order(5) fun `MEM-3 换绑手机 验码与占用`() {
        expect("PUT", "/api/h5app/wxapp/member/mobile", mapOf("mobile" to "13900000009", "vcode" to "0000"), 400)
        expect("PUT", "/api/h5app/wxapp/member/mobile", mapOf("mobile" to "139", "vcode" to "8888"), 400)
        expect("PUT", "/api/h5app/wxapp/member/mobile", mapOf("mobile" to "13800000002", "vcode" to "8888"), 422)
        expect("PUT", "/api/h5app/wxapp/member/mobile", mapOf("mobile" to "13800000001", "vcode" to "8888"), 422)
        val r = ok("/api/h5app/wxapp/member/mobile", mapOf("mobile" to "13900000009", "vcode" to "8888"), m = "PUT")
        assertEquals("139****0009", r.path("mobile").asText())
        assertEquals(13900000009L, col("SELECT CAST(mobile AS BIGINT) FROM members WHERE user_id = 1"))
        // 换绑后旧号即失效，新号可登录
        val oldLogin = expect("POST", "/api/h5app/wxapp/new_login", mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888", "auto_register" to 0,
        ), 200, token = null)
        assertEquals(0, oldLogin.path("data").path("status").asInt(), "旧号登录必须失败")
        val newTok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13900000009", "check_type" to "mobile", "vcode" to "8888", "auto_register" to 0,
        )), "new login").path("token").asText()
        assertTrue(newTok.isNotBlank())
        // 还原，避免影响后续用例与 seed 语义
        ok("/api/h5app/wxapp/member/mobile", mapOf("mobile" to "13800000001", "vcode" to "8888"), m = "PUT")
        assertEquals(13800000001L, col("SELECT CAST(mobile AS BIGINT) FROM members WHERE user_id = 1"))
    }

    @Test @Order(6) fun `MEM-4 地址簿 校验 默认互斥 越权`() {
        val list = ok("/api/h5app/wxapp/addresslist")
        assertEquals(1L, list.path("total_count").asLong(), "seed 一条")
        assertContract(list.path("list")[0], MemberContract.ADDRESS_ROW, "MEM-4 地址行")
        assertEquals(1, list.path("list")[0].path("is_def").asInt())
        assertTrue(list.path("list")[0].path("receiver_address").asText().contains("科技园"), "省市区+详址拼成整串")

        val bad = expect("POST", "/api/h5app/wxapp/addresslist", mapOf("username" to "", "telephone" to "139"), 400)
        assertEquals(
            setOf("username", "telephone", "adrdetail"),
            bad.path("errors").fieldNames().asSequence().toSet(),
            "收货人/手机号/详址三处必填各自报错，不合并成一条",
        )

        val created = ok("/api/h5app/wxapp/addresslist", mapOf(
            "username" to "张三", "telephone" to "13800000001", "province" to "广东省", "city" to "广州市",
            "county" to "天河区", "adrdetail" to "天河路 1 号", "postal_code" to "510000", "is_def" to 1,
        ), m = "POST")
        assertContract(created, MemberContract.ADDRESS_ROW, "MEM-4 新增回显")
        val newId = created.path("address_id").asLong()
        assertEquals(1, created.path("is_def").asInt(), "新地址声明默认即生效")
        assertEquals(2L, col("SELECT COUNT(*) FROM members_address WHERE user_id = 1"))
        assertEquals(1L, col("SELECT COUNT(*) FROM members_address WHERE user_id = 1 AND is_def = TRUE"), "默认地址全局至多一条")
        assertEquals(0L, col("SELECT COUNT(*) FROM members_address WHERE address_id = 1 AND is_def = TRUE"), "原默认被顶掉")

        val edited = ok("/api/h5app/wxapp/addresslist/$newId", mapOf(
            "username" to "张三丰", "telephone" to "13800000001", "province" to "广东省", "city" to "深圳市",
            "county" to "南山区", "adrdetail" to "科苑路 2 号",
        ), m = "PUT")
        assertEquals("张三丰", edited.path("username").asText())
        assertEquals(1, edited.path("is_def").asInt(), "未传 is_def 时保持原默认状态")

        // 越权：会员 2 既改不到也删不掉别人的地址，统一 404（不暴露"存在但非你的"）
        expect("PUT", "/api/h5app/wxapp/addresslist/1", mapOf("username" to "劫持", "telephone" to "13800000002"), 404, token = other)
        expect("DELETE", "/api/h5app/wxapp/addresslist/1", null, 404, token = other)

        ok("/api/h5app/wxapp/addresslist", mapOf("address_id" to newId), m = "DELETE")
        assertEquals(1L, col("SELECT COUNT(*) FROM members_address WHERE user_id = 1"))
        assertEquals(1L, col("SELECT COUNT(*) FROM members_address WHERE user_id = 1 AND is_def = TRUE"), "删默认后自动顶一条")

        // 第一条无条件成为默认（新会员地址簿为空的分支）
        jdbc.update("DELETE FROM members_address WHERE user_id = 2")
        val first = ok("/api/h5app/wxapp/addresslist", mapOf(
            "username" to "李四", "telephone" to "13800000002", "adrdetail" to "望京 1 号",
        ), m = "POST", asMember = other)
        assertEquals(1, first.path("is_def").asInt(), "地址簿首条无条件默认")
    }

    @Test @Order(7) fun `MEM-5 MEM-6 收藏 实时价与幂等`() {
        val list = ok("/api/h5app/wxapp/member/collect/items")
        assertEquals(2L, list.path("total_count").asLong(), "seed 两条收藏")
        val nut = list.path("list").first { it.path("item_id").asLong() == 3L }
        assertContract(nut, MemberContract.FAV_ROW, "MEM-5 收藏行")
        assertEquals(8900L, nut.path("item_price").asLong(), "列表出实时价")
        assertEquals(9900L, nut.path("fav_price").asLong(), "快照价另存，前端据此提示降价")
        assertEquals("每日坚果礼盒", nut.path("item_name").asText(), "名称以商品表为准，快照只在商品消失时兜底")
        assertEquals(1, nut.path("is_onsale").asInt())

        val added = ok("/api/h5app/wxapp/member/collect/items/4", m = "POST")
        assertContract(added, MemberContract.FAV_ROW, "MEM-6 收藏回显")
        assertEquals(1, added.path("is_collected").asInt())
        val again = ok("/api/h5app/wxapp/member/collect/items/4", m = "POST")
        assertEquals(added.path("fav_id").asLong(), again.path("fav_id").asLong(), "重复收藏幂等：不产生第二行")
        assertEquals(3L, col("SELECT COUNT(*) FROM members_items_fav WHERE user_id = 1"))

        ok("/api/h5app/wxapp/member/collect/items/4", m = "DELETE")
        assertEquals(2L, ok("/api/h5app/wxapp/member/collect/items").path("total_count").asLong())
        assertEquals(200, call("DELETE", "/api/h5app/wxapp/member/collect/items/99", tok, null).response.status, "取消未收藏项不报错")

        val filtered = ok("/api/h5app/wxapp/member/collect/items?keyword=坚果")
        assertEquals(1L, filtered.path("total_count").asLong(), "keyword 命中快照名")
        // 商品下架/删除不静默过滤：给用户看到"收藏的东西没了"
        jdbc.update("UPDATE items SET approve_status = 'instock' WHERE item_id = 1")
        val off = ok("/api/h5app/wxapp/member/collect/items").path("list").first { it.path("item_id").asLong() == 1L }
        assertEquals(0, off.path("is_onsale").asInt())
        jdbc.update("UPDATE items SET approve_status = 'onsale' WHERE item_id = 1")
    }

    @Test @Order(8) fun `浏览足迹 记录 分页 删除 清空`() {
        ok("/api/h5app/wxapp/goods/items/2")
        val list = ok("/api/h5app/wxapp/member/browse/items")
        assertTrue(list.path("total_count").asLong() >= 3, "seed 两条 + 本次浏览：${list.path("total_count")}")
        assertContract(list.path("list")[0], MemberContract.BROWSE_ROW, "浏览行")
        // 同一商品重复浏览只留一条（先删后插）
        ok("/api/h5app/wxapp/goods/items/2")
        assertEquals(list.path("total_count").asLong(), ok("/api/h5app/wxapp/member/browse/items").path("total_count").asLong())
        ok("/api/h5app/wxapp/member/browse/items/2", m = "DELETE")
        val after = ok("/api/h5app/wxapp/member/browse/items")
        assertEquals(list.path("total_count").asLong() - 1, after.path("total_count").asLong())
        ok("/api/h5app/wxapp/member/browse/items", m = "DELETE")
        assertEquals(0L, ok("/api/h5app/wxapp/member/browse/items").path("total_count").asLong(), "清空")
        assertEquals(0L, col("SELECT COUNT(*) FROM members_browse_history WHERE user_id = 1"))
        // 匿名详情不记足迹，也不因缺会员而报错
        expect("GET", "/api/h5app/wxapp/goods/items/2", null, 200, token = null)
        assertEquals(0L, col("SELECT COUNT(*) FROM members_browse_history WHERE user_id = 1"))
    }

    @Test @Order(9) fun `MEM-7 资产与角标`() {
        val s = ok("/api/h5app/wxapp/member/statistical")
        assertContract(s, MemberContract.STATISTICAL, "MEM-7")
        assertContract(s.path("orderscount"), MemberContract.ORDERSCOUNT, "MEM-7 orderscount")
        assertContract(s.path("deposit"), PayContract.DEPOSIT_VIEW, "MEM-7 deposit")
        assertEquals(1000L, s.path("point").asLong())
        assertEquals(0L, s.path("totalConsumption").asLong(), "本用例集未支付，累计实付仍为 0")
        // 角标与订单表实际行数一致（MEM-7 复用 ORDER-8 口径，不另算一套）
        assertEquals(col("SELECT COUNT(*) FROM orders_normal_orders WHERE user_id = 1"), s.path("orderscount").path("total").asLong())
    }

    @Test @Order(10) fun `会员面鉴权 未登录 401 跨号 404`() {
        expect("GET", "/api/h5app/wxapp/member", null, 401, token = null)
        expect("PUT", "/api/h5app/wxapp/member", mapOf("username" to "x"), 401, token = null)
        expect("GET", "/api/h5app/wxapp/addresslist", null, 401, token = null)
        expect("GET", "/api/h5app/wxapp/member/collect/items", null, 401, token = null)
        expect("GET", "/api/h5app/wxapp/member/statistical", null, 401, token = null)
        assertNotEquals(tok, other)
        // 会员 2 看不到会员 1 的收藏（列表按令牌 user_id 过滤）
        val o = ok("/api/h5app/wxapp/member/collect/items", m = "GET", asMember = other)
        assertEquals(0L, o.path("total_count").asLong(), "会员 2 未收藏")
        assertNotEquals(0L, ok("/api/h5app/wxapp/member/collect/items").path("total_count").asLong())
    }
}
