package com.ecshopx.kotlin.boot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

/**
 * M2-3 互动域 e2e（docs/04 §29.1 门槛①..⑥，独立 H2 `exccm23`）。
 *
 * 六道门槛全部离线钉死：
 *  1. **签到幂等**：同天双签回业务错"今日已签到"；送分行落 `point_member_log`（`remark=签到`）；
 *  2. **别名一致性**：`/promotion/checkin/create` 与 `/sign` 同判据、`checkin/getlist` 与 `weekly/list` 同形状；
 *  3. **连签加成**：铺 6 天 → 第 7 天 `point=base+bonus`、`continuous=7`、周面板 7 格全亮；
 *  4. **抽奖链**：未开启 422 → 开启（单奖品独占权重=确定性抽取）→ 中积分奖（流水行 + `remark=抽奖` 送分行）→ 次数耗尽 422；
 *  5. **每日加次幂等**：首 +N、二刷不加不报错，加次把当日"用完"的转盘救活一抽；
 *  6. **后台留痕**：`getturntable_log/byid` / `getturntable_count/byid` 三数与流水一致；下架后抽取 422 且配置不丢；
 *     附 **积分对账恒等式**（基线 1000 + Σ change_point == point_member.point）与 role 外 403（viewer）。
 *
 * 跨日竞态说明：全部用例同日跑断言（基准日由服务层同源 `Asia/Shanghai` 日界推导），
 * 只有在进程恰好跨上半夜时才需要重跑——与既有日期敏感用例同一风险位，不为其造假时钟。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccm23;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.webhook-deliver-initial-delay-ms=3600000",
        "ecx.schedule.webhook-deliver-ms=3600000",
        "ecx.pay.active-channel-profile=mock",
        "ecx.wx.component-token=wxcomp_token_23",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class M23EndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var tok = ""
    private var tok2 = ""
    private var admin = ""
    private var viewer = ""

    // ================= 门槛①②（签到幂等 + 别名一致性 + 周面板） =================

    @Test @Order(1) fun `令牌就绪`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null, mapOf(
            "username" to "admin", "password" to "admin" + "123",
        )), "admin").path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null, mapOf(
            "username" to "viewer", "password" to "viewer" + "123",
        )), "viewer").path("token").asText()
        tok2 = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "member2").path("token").asText()
        assertTrue(tok.isNotBlank() && admin.isNotBlank() && viewer.isNotBlank() && tok2.isNotBlank())
    }

    @Test @Order(2) fun `门槛一 签到幂等与送分留痕`() {
        val d = ok("POST", "/api/h5app/wxapp/sign")
        assertEquals(10L, d.path("point").asLong(), "缺省每日 base=10")
        assertEquals(1, d.path("continuous").asInt())
        assertEquals(1010L, d.path("after_point").asLong(), "seed 基线 1000 + 本次 10")

        // 送分行落 `point_member_log`：remark=签到（M1-5 对账靠 remark 认源）
        val logs = jdbc.queryForList(
            "SELECT change_point, remark FROM point_member_log WHERE company_id = 1 AND user_id = 1 AND remark = ?",
            "签到",
        ).map { r -> r["change_point"] }
        assertTrue(logs.isNotEmpty(), "签到送分必有一行 remark=签到")
        assertEquals(10L, (logs.first() as Number).toLong())

        // 双签幂等：第二次是业务错（422），不是静默成功
        expect("POST", "/api/h5app/wxapp/sign", null, 422, tok).let { b ->
            assertTrue(b.path("message").asText().contains("今日已签到"), "幂等语义=" + b.path("message").asText())
        }
    }

    @Test @Order(3) fun `门槛二 别名一致与周面板`() {
        // 别名口与主口同一幂等判据：主口已签，别名口回同一个业务错
        expect("POST", "/api/h5app/wxapp/promotion/checkin/create", null, 422, tok)
        // 周面板：今天 1 格；别名口与主口同形状（缺失即失败、出参键同集合）
        val w = ok("GET", "/api/h5app/wxapp/sign/weekly/list")
        assertEquals(1L, w.path("signed_days").asLong())
        assertTrue(w.path("today_signed").asBoolean())
        assertEquals(7, w.path("days").size(), "周面板恒 7 格")
        val alias = ok("GET", "/api/h5app/wxapp/promotion/checkin/getlist")
        assertEquals(
            w.fieldNames().asSequence().sorted().toList(),
            alias.fieldNames().asSequence().sorted().toList(),
            "别名口出键与主口一致",
        )
    }

    @Test @Order(4) fun `门槛三 连签七天加成`() {
        // 铺昨天起倒退 6 天：continuous=6..1、point=10（今天尚未签，user 2 独立走这条链——
        // user 1 今天已被门槛①签掉，同一天不可能第二次签）
        val now = System.currentTimeMillis() / 1000
        (5L downTo 0L).forEach { i ->
            jdbc.update(
                "INSERT INTO user_checkin(company_id, user_id, day, continuous, point, created, updated) " +
                    "VALUES(1, 2, ?, ?, 10, ?, ?)",
                ymd(-i - 1), (6L - i).toInt(), now, now,
            )
        }
        val d = ok("POST", "/api/h5app/wxapp/sign", body = null, asWho = tok2)
        assertEquals(7, d.path("continuous").asInt(), "昨天那行 continuous=6 + 1")
        assertEquals(30L, d.path("point").asLong(), "base 10 + 第 7 天加成 20")
        assertEquals(30L, d.path("after_point").asLong(), "user 2 新开户：0 + 30")
        // 送分行与行在同一事务：这一行 remark=签到、change=30
        assertEquals(1L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_member_log WHERE company_id = 1 AND user_id = 2 AND remark = ? AND change_point = 30",
            Long::class.java, "签到",
        ))
        // 周面板：7 格全亮、本周送分 90（6*10 + 30）
        val w = ok("GET", "/api/h5app/wxapp/sign/weekly/list", body = null, asWho = tok2)
        assertEquals(7L, w.path("signed_days").asLong())
        assertEquals(90L, w.path("week_point").asLong())
        assertEquals(7, w.path("continuous").asInt())

        // 后台记录可见（权限码 78）：user1 一行 + user2 七行
        val adminRows = okGet("/api/promotions/checkin/getlist", mapOf("page" to 1, "page_size" to 10), admin)
        assertTrue(adminRows.path("total_count").asLong() >= 8L, "后台翻页能看到两个会员的签到行")
        assertTrue(jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_member_log WHERE company_id = 1 AND remark = ?",
            Long::class.java, "签到",
        ) >= 2L, "两次签到两行送分留痕")
    }

    // ================= 门槛④⑤⑥（抽奖链 + 每日加次 + 后台留痕） =================

    /** 单奖品独占权重 1 = 确定性抽取（§29.1 的种子规则只在多奖品时引入抖动） */
    private fun prize() = mapOf<String, Any?>("id" to "p1", "name" to "积分50", "type" to "point", "point" to 50, "weight" to 1)

    private fun accountPoint(): Long = jdbc.queryForObject(
        "SELECT point FROM point_member WHERE company_id = 1 AND user_id = 1", Long::class.java,
    ) ?: -1L

    @Test @Order(5) fun `门槛四 未开启抽取与开启后第一抽`() {
        // 初始（无配置）：状态页 enable=false；抽取 422
        val cfg = ok("GET", "/api/h5app/wxapp/promotion/turntableconfig")
        assertFalse(cfg.path("enable").asBoolean())
        expect("GET", "/api/h5app/wxapp/promotion/turntable", null, 422, tok)
        // 后台写配置：base_times=2、login_add_times=1
        val saved = expect("POST", "/api/promotions/turntableconfig", mapOf(
            "enable" to true, "name" to "幸运大转盘23",
            "base_times" to 2, "login_add_times" to 1,
            "prizes" to listOf(prize()),
        ), 200).path("data")
        assertTrue(saved.path("enable").asBoolean(), "写后视图立即开启")
        val data = ok("GET", "/api/h5app/wxapp/promotion/getLuckyDrawData")
        assertEquals(2, data.path("residue").asInt(), "base 2、无领取、无流水")
        // 一抽：中积分奖（单奖品必中）→ 流水行 + 送分行 + 剩余减一
        val draw1 = ok("GET", "/api/h5app/wxapp/promotion/turntable")
        assertEquals("point", draw1.path("prize_type").asText())
        assertEquals(50L, draw1.path("point").asLong())
        assertEquals("积分50", draw1.path("prize_name").asText())
        assertEquals(1, draw1.path("residue").asInt())
        assertEquals(1060L, accountPoint(), "1010（基线+签到）+ 中奖 50")
        assertEquals(1L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_lottery_log WHERE company_id = 1 AND user_id = 1", Long::class.java,
        ))
        assertTrue(jdbc.queryForObject(
            "SELECT COUNT(*) FROM point_member_log WHERE remark = ? AND change_point = 50", Long::class.java,
            "抽奖",
        ) == 1L, "中奖积分走 remark=抽奖 的动账口留痕")
    }

    @Test @Order(6) fun `门槛四 次数耗尽与我的流水`() {
        // 残 1 抽：抽走 base 的第二次
        val draw2 = ok("GET", "/api/h5app/wxapp/promotion/turntable")
        assertEquals(0, draw2.path("residue").asInt())
        expect("GET", "/api/h5app/wxapp/promotion/turntable", null, 422, tok).let { b ->
            assertTrue(b.path("message").asText().contains("次数已用完"), b.path("message").asText())
        }
        // 我的流水：两抽两行，翻页参数收窄可见
        val log = okGet("/api/h5app/wxapp/promotion/getLuckyDrawLog", mapOf("page" to 1, "page_size" to 1))
        assertEquals(2L, log.path("total_count").asLong())
        assertEquals(1, log.path("list").size())
    }

    @Test @Order(7) fun `门槛五 每日加次幂等与救活一抽`() {
        val add1 = ok("GET", "/api/h5app/wxapp/promotion/loginaddtimes")
        assertTrue(add1.path("claimed").asBoolean())
        assertEquals(1, add1.path("add_times").asInt())
        assertEquals(1, add1.path("residue").asInt(), "base 2 + add 1 - drawn 2 = 1")
        // 二刷：不加次、不报错（同一状态）
        val add2 = ok("GET", "/api/h5app/wxapp/promotion/loginaddtimes")
        assertEquals(1, add2.path("add_times").asInt(), "每日只发一次（uk 冲突即放弃）")
        assertEquals(1, add2.path("residue").asInt())
        // 被救活的这一抽（当日第 3 抽）：再 +50
        ok("GET", "/api/h5app/wxapp/promotion/turntable")
        assertEquals(1110L, accountPoint(), "1060 + 50")
        // 当日用尽（add 已领、base+add 全花）→ 422
        expect("GET", "/api/h5app/wxapp/promotion/turntable", null, 422, tok)
    }

    @Test @Order(8) fun `门槛六 后台留痕下架与对账恒等式`() {
        // 全量流水翻页（权限码 79）
        val pages = okGet("/api/promotions/getturntable_log/byid", mapOf("page" to 1, "page_size" to 10), admin)
        assertEquals(3L, pages.path("total_count").asLong(), "三抽三行")
        // 汇总三数与流水一致
        val cnt = okGet("/api/promotions/getturntable_count/byid", emptyMap(), admin)
        assertEquals(3L, cnt.path("log_total").asLong())
        assertEquals(150L, cnt.path("point_total").asLong())
        assertEquals(1L, cnt.path("drawn_users").asLong())
        // 列表恒一行快照（C222）；详情 byid=1，其它的不存在
        val list = okGet("/api/promotions/getturntableList", emptyMap(), admin)
        assertEquals(1L, list.path("total_count").asLong())
        assertEquals(1L, list.path("list").get(0).path("activity_id").asLong())
        okGet("/api/promotions/getturntable", mapOf("id" to 1), admin)
        expect("GET", "/api/promotions/getturntable", null, 404, admin, mapOf("id" to 2))
        // 下架（80，GET 写动作照抄源 C221）：关开关、其余字段不丢
        val down = okGet("/api/promotions/down_lucky_draw/byid", mapOf("id" to 1), admin)
        assertFalse(down.path("enable").asBoolean())
        assertEquals(1, down.path("base_times").asInt())
        assertEquals(1, down.path("prizes").size(), "下架不动奖品池")
        // 下架后前台抽取 422（"未开启"）
        expect("GET", "/api/h5app/wxapp/promotion/turntable", null, 422, tok)
        // 权限码段：未挂角色的 viewer 403
        expect("GET", "/api/promotions/checkin/getlist", null, 403, viewer)
        expect("GET", "/api/promotions/getturntable_log/byid", null, 403, viewer)

        // 终局对账恒等式（user 1）：基线 1000 + Σ change_point（签到 10、抽奖 50*3）== 账头 1160
        val delta = jdbc.queryForObject(
            "SELECT COALESCE(SUM(change_point), 0) FROM point_member_log WHERE company_id = 1 AND user_id = 1",
            Long::class.java,
        ) ?: -1L
        assertEquals(160L, delta, "签到 10 与 抽奖三笔 50 = 160")
        assertEquals(1000L + delta, accountPoint(), "Σ change_point == point（对账恒等式）")
        // user 2 同恒等式（新开户：30 = 一次连签送分）
        val delta2 = jdbc.queryForObject(
            "SELECT COALESCE(SUM(change_point), 0) FROM point_member_log WHERE company_id = 1 AND user_id = 2",
            Long::class.java,
        ) ?: -1L
        assertEquals(30L, delta2)
        assertEquals(30L, jdbc.queryForObject(
            "SELECT point FROM point_member WHERE company_id = 1 AND user_id = 2", Long::class.java,
        ), "user 2 的账实恒等式")
    }

    // ================= 调用件 =================

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>? = null): MvcResult {
        val req = if (method == "GET") get(url) else post(url)
        token?.let { req.header("Authorization", "Bearer $it") }
        body?.let { req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(it)) }
        return mvc.perform(req).andReturn()
    }

    /** GET 携参一律 .param（MockMvc 不做 query 百分号解码，C209 那条纪律） */
    private fun callGet(url: String, params: Map<String, Any?>, token: String?): MvcResult {
        val req = get(url).also { q -> params.forEach { (k, v) -> q.param(k, v.toString()) } }
        token?.let { req.header("Authorization", "Bearer $it") }
        return mvc.perform(req).andReturn()
    }

    private fun bodyOf(res: MvcResult): JsonNode = mapper.readTree(utf8(res.response.contentAsByteArray))

    private fun data(res: MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label -> " + utf8(res.response.contentAsByteArray))
        return bodyOf(res).path("data")
    }

    private fun ok(verb: String, url: String, body: Map<String, Any?>? = null, asWho: String = tok): JsonNode =
        data(call(verb, url, asWho, body), url)

    private fun okGet(url: String, params: Map<String, Any?> = emptyMap(), asWho: String = tok): JsonNode =
        data(callGet(url, params, asWho), url)

    private fun expect(m: String, url: String, body: Map<String, Any?>?, code: Int, token: String = admin): JsonNode {
        val res = call(m, url, token, body)
        assertEquals(code, res.response.status, "$m $url -> " + utf8(res.response.contentAsByteArray))
        return bodyOf(res)
    }

    /** GET 期望非 200 的口（携参仍走 .param） */
    private fun expect(m: String, url: String, body: Map<String, Any?>?, code: Int, token: String, params: Map<String, Any?>): JsonNode {
        val res = callGet(url, params, token)
        assertEquals(code, res.response.status, "$m $url -> " + utf8(res.response.contentAsByteArray))
        return bodyOf(res)
    }

    /** ymd 整数（Asia/Shanghai 业务日界），与服务层同一推导，铺数/断言共用 */
    private fun ymd(dayOffset: Long): Long {
        val d = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(dayOffset)
        return (d.year * 10_000L) + (d.monthValue * 100L) + d.dayOfMonth
    }
}
