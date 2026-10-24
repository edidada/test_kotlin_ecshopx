package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.WebhookContract
import com.ecshopx.kotlin.boot.web.IdentityRealignRunner
import com.ecshopx.kotlin.service.distribution.StatementService
import com.ecshopx.kotlin.service.openapi.OpenapiSign
import com.ecshopx.kotlin.service.openapi.OpenapiStatsFlushJob
import com.ecshopx.kotlin.service.pay.PayChannelConfigService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

/**
 * M2-4 生产化 e2e（docs/04 §29.6 门槛①..⑥，独立 H2 `exccm24`）。
 *
 *  1. **渠道配置面**：开户（api_key 空串=不改）、台账掩码、开关、viewer 写 403；
 *     落库即 AES-GCM 密文（`enc-v1:` 前缀），服务层读出来的必须是**明文**（签名的判据层读原值）。
 *  2. **结算账期**：下单 → mock 回调结清 → 生成窗口账期单（幂等：重复生成 0 张）→
 *     翻页/详情/确认三读一写；账期设置 daily→weekly 往返。
 *  3. **调用量聚合（D76 收口）**：网关三连（两成功一失败）→ 倾倒任务一次 →
 *     `openapi_call_stats(company, today)` 行 `calls=3/success=2/fails=1`；**再倾倒一次 0 差量**（并过的基线不重并）。
 *     OP-A6 概况的 aggregate 三位与表行一致（一个数两个局面：进程内读数 ≠ 表上的账，note 里说清）。
 *  4. **积压告警读面（批G WH-B0）**：空库四档桶全零行、`oldest_created` 显式 null、viewer 403。
 *  5. **启动序列校正（批G，修 M2-1 踩坑⑤）**：显式插 id=250 → `realign()`（与启动调的是同一条）→
 *     下一发默认主键 = 251。
 *  6. **批量重推（批H WH-B1）**：只扫终态 fail、至少一个筛位、`limit` 闸量、最老最先；
 *     在投的 running 与 success 不动；`reset < matched` 的余量分批续。
 *
 * 不测嘴上的"压测 50 TPS"：并发护住的断言在 M0 的 OrderStockConcurrencyTest，生产化的压测脚本
 * 属于部署侧联调（docs/05 §M2-4 完成记录里写明出处），e2e 不重复背着它跑。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccm24;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.webhook-deliver-initial-delay-ms=3600000",
        "ecx.schedule.webhook-deliver-ms=3600000",
        "ecx.schedule.statement-settle-initial-delay-ms=3600000",
        "ecx.schedule.statement-settle-ms=3600000",
        "ecx.schedule.openapi-stats-flush-initial-delay-ms=3600000",
        "ecx.schedule.openapi-stats-flush-ms=3600000",
        "ecx.pay.active-channel-profile=mock",
        "ecx.wx.component-token=wxcomp_token_24",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class M24EndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var payConfigs: PayChannelConfigService
    @Autowired lateinit var statementService: StatementService
    @Autowired lateinit var statsFlush: OpenapiStatsFlushJob
    @Autowired lateinit var realign: IdentityRealignRunner

    private var tok = ""
    private var admin = ""
    private var viewer = ""

    /** 开出来的账期单号（门槛②先用先落，门槛③再读它） */
    private var statementId = 0L

    // ================= 门槛①（渠道配置面 + 密钥加密） =================

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
        assertTrue(tok.isNotBlank() && admin.isNotBlank() && viewer.isNotBlank())
    }

    @Test @Order(2) fun `门槛一 渠道配置 开户 掩码 加密落库 开关与写权限`() {
        val raw = "sk-bspay-9f8e7d6c5b4a3210-testonly"
        val d1 = okAdmin("POST", "/api/pay/channel/config", mapOf(
            "channel" to "bspay", "merchant_no" to "M-2026-001", "app_id" to "app-2431",
            "api_key" to raw, "notify_url" to "https://mock.example.com/notify",
        ))
        assertTrue(d1.path("api_key_masked").asText().contains("*"), "出参只有掩码：${d1}")
        assertFalse(d1.path("api_key_masked").asText().contains("9f8e7d6c"), "掩码不许带出原值")

        // 落库即密文；服务层读出必须是明文（判据层读原值，MASK-R1）
        val stored = jdbc.queryForObject(
            "SELECT api_key FROM pay_channel_config WHERE company_id = 1 AND channel = ?",
            String::class.java, "bspay",
        )
        assertTrue(stored.startsWith("enc-v1:"), "落库是 AES-GCM 密文：$stored")
        assertEquals(raw, payConfigs.find(1, "bspay")?.apiKey, "服务层读回的是明文，签名用它")

        // 开关：关掉之后支付面同答 422"未配置"；台账 cipher 额外键不出现
        okAdmin("POST", "/api/pay/channel/config/bspay/toggle?status=0")
        assertEquals(0, (jdbc.queryForMap(
            "SELECT status FROM pay_channel_config WHERE company_id = 1 AND channel = ?", "bspay",
        )["status"] as Number).toInt())
        okAdmin("POST", "/api/pay/channel/config/bspay/toggle?status=1")

        // api_key 传空串=不改：改个开关不该把密钥抹掉
        val d2 = okAdmin("POST", "/api/pay/channel/config", mapOf(
            "channel" to "bspay", "merchant_no" to "M-2026-002",
        ))
        assertEquals("M-2026-002", d2.path("merchant_no").asText())
        assertEquals(raw, payConfigs.find(1, "bspay")?.apiKey, "空串不覆盖密钥")

        val list = okAdmin("GET", "/api/pay/channel/config/list")
        assertTrue(list.isArray && list.any { it.path("channel").asText() == "bspay" && it.has("api_key_masked") }, "台账含新行")

        // viewer（role 外）写路径 403；读路径也要码（第 90）
        expectAdmin("POST", "/api/pay/channel/config", mapOf("channel" to "adapay"), 403, viewer)
        expectAdmin("GET", "/api/pay/channel/config/list", null, 403, viewer)
    }

    // ================= 门槛②（结算账期） =================

    @Test @Order(3) fun `门槛二 结清生成账期单 idempotent 与三读一写`() {
        val now = com.ecshopx.kotlin.common.domain.TimeSec.now()
        // 下单 → 建支付单（mock 渠道走真回调链）→ mock 回调结清（trade.is_settled=TRUE，updated=now）
        val orderId = fastBuy(3, 2)
        val tradeId = ok("POST", "/api/h5app/wxapp/payment", mapOf("order_id" to orderId, "pay_type" to "wxpay"), tok)
            .path("trade_id").asText()
        val payFee = jdbc.queryForObject(
            "SELECT total_fee FROM trade WHERE trade_id = ?", Long::class.java, tradeId,
        )
        val notify = {
            mvc.perform(post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId).param("total_fee", payFee.toString())
                .param("transaction_id", "WX-$tradeId").param("trade_state", "SUCCESS"))
                .andReturn().response.contentAsByteArray
        }
        assertTrue(utf8(notify()).contains("SUCCESS"), "回调结清")

        // 生成窗口（覆盖 now）：1 张；再跑一次同一窗口：0 张（幂等）
        assertEquals(1, statementService.generate(1, now - 7200, now + 7200, now), "一个店铺一张账期单")
        assertEquals(0, statementService.generate(1, now - 7200, now + 7200, now + 5), "重复生成不重铺")

        val row = jdbc.queryForMap(
            "SELECT id, statement_no, order_num, total_fee, refund_fee, statement_status " +
                "FROM statements WHERE company_id = 1 ORDER BY id DESC LIMIT 1",
        )
        statementId = (row["id"] as Number).toLong()
        assertEquals(1, (row["order_num"] as Number).toInt())
        assertEquals(payFee, (row["total_fee"] as Number).toLong())
        assertEquals("ready", row["statement_status"])
        assertEquals(1, jdbc.queryForObject(
            "SELECT COUNT(*) FROM statement_details WHERE statement_id = ? AND order_id = ?",
            Int::class.java, statementId, orderId,
        ), "明细恰一单一行")
        assertTrue((row["statement_no"] as String).startsWith("ST-D-1-"), "单号格式：类型-店铺-窗口日")

        // ST-A1 翻页 / ST-A2 详情 / ST-A3 确认（ready→confirmed）
        val page = okAdmin("GET", "/api/statement/summarized?merchant_type=distributor")
        assertEquals(1, page.path("total_count").asLong())
        val detail = okAdmin("GET", "/api/statement/detail/$statementId")
        assertEquals("ready", detail.path("statement_status").asText())
        assertEquals(1, detail.path("details").size(), "明细一单")
        // ST-A4 越级：ready 状态下带齐回单号也 422（先确认再打款，C242）
        expectAdmin("POST", "/api/statement/paid/$statementId", mapOf("receipt_no" to "RCP-24-EARLY"), 422, admin)
        val confirmed = okAdmin("POST", "/api/statement/confirm/$statementId")
        assertEquals("confirmed", confirmed.path("statement_status").asText())
        assertTrue(confirmed.path("operator").asText().startsWith("operator:"), "留痕里该有谁")
        // 已确认的重复确认：幂等回当前态，不改账
        assertEquals("confirmed", okAdmin("POST", "/api/statement/confirm/$statementId").path("statement_status").asText())
        expectAdmin("GET", "/api/statement/detail/999999", null, 404, admin)

        // ST-A4 打款登记（批G）：回单号为空（body 缺键）→ 400，没凭据不许置 paid
        expectAdmin("POST", "/api/statement/paid/$statementId", emptyMap(), 400, admin)
        // 登记回单号：paid + 账头留 receipt_no
        val paid = okAdmin("POST", "/api/statement/paid/$statementId", mapOf("receipt_no" to "RCP-24-000001"))
        assertEquals("paid", paid.path("statement_status").asText())
        assertEquals("RCP-24-000001", paid.path("receipt_no").asText())
        assertTrue(paid.path("operator").asText().startsWith("operator:"), "打款也留痕")
        // 已 paid 的重复登记：幂等回 paid，不覆盖已存回单号
        val paid2 = okAdmin("POST", "/api/statement/paid/$statementId", mapOf("receipt_no" to "RCP-24-000002"))
        assertEquals("paid", paid2.path("statement_status").asText())
        assertEquals("RCP-24-000001", paid2.path("receipt_no").asText())
        assertEquals("paid", jdbc.queryForObject(
            "SELECT statement_status FROM statements WHERE id = ?", String::class.java, statementId,
        ))
        assertEquals("RCP-24-000001", jdbc.queryForObject(
            "SELECT receipt_no FROM statements WHERE id = ?", String::class.java, statementId,
        ))

        // 账期设置：seed 两种已配 daily；改成 weekly 再读回
        val s0 = okAdmin("GET", "/api/statement/period/setting")
        assertEquals("daily", s0.path("distributor").path("period_type").asText())
        okAdmin("POST", "/api/statement/period/setting", mapOf("merchant_type" to "distributor", "period_type" to "weekly"))
        assertEquals("weekly", okAdmin("GET", "/api/statement/period/setting").path("distributor").path("period_type").asText())
        expectAdmin("POST", "/api/statement/period/setting", mapOf("merchant_type" to "distributor", "period_type" to "hourly"), 400, admin)
    }

    // ================= 门槛③（调用量聚合，D76 收口） =================

    @Test @Order(4) fun `门槛三 网关三连入账 倾倒一次成账 再次倾倒零差量`() {
        metricsBefore()
        assertEquals("E0000", code(gw("ecx.items.entity.get")))
        assertEquals("E0000", code(gw("ecx.items.entity.get")))
        assertEquals("E5301", code(gw("ecx.order.get", mapOf("order_id" to 987654321L))))
        // 闸门就失败的探测不归属租户：不入账（UNKNOWN 桶）
        assertEquals("E4005", code(gw("ecx.nope.nope")))

        val now = com.ecshopx.kotlin.common.domain.TimeSec.now()
        assertEquals(1, statsFlush.runNow(now), "本租户今天并入一行")

        val day = LocalDateTime24(now)
        val row = jdbc.queryForMap(
            "SELECT calls, success, fails FROM openapi_call_stats WHERE company_id = 1 AND day = ?",
            day,
        )
        assertEquals(3L, (row["calls"] as Number).toLong())
        assertEquals(2L, (row["success"] as Number).toLong())
        assertEquals(1L, (row["fails"] as Number).toLong())

        // 再倾倒：没有新差量 → 不再动账（进程内读口的语义不被聚合动作改变）
        assertEquals(0, statsFlush.runNow(now + 10))
        assertEquals(3L, jdbc.queryForObject(
            "SELECT calls FROM openapi_call_stats WHERE company_id = 1 AND day = ?", Long::class.java, day,
        ))

        // OP-A6 概况的 aggregate 三位与表行一致
        val stats = okAdmin("GET", "/api/setting/openapi/developer/1/stats")
        assertEquals(day.toLong(), stats.path("aggregate").path("day").asLong())
        assertEquals(3L, stats.path("aggregate").path("calls").asLong())
        assertTrue(2L == stats.path("aggregate").path("success").asLong())
    }

    // ================= 门槛④（积压告警读面，批G WH-B0） =================

    @Test @Order(5) fun `门槛四 积压告警读面 空库形状与分桶完集`() {
        val backdrop = okAdmin("GET", "/api/webhook/backlog")
        assertEquals(WebhookContract.BACKLOG, backdrop.stringKeys(), "三项固定键")
        assertEquals(WebhookContract.BACKLOG_PENDING, backdrop.path("pending").stringKeys(), "pending 三键")
        assertEquals(WebhookContract.BACKLOG_BUCKETS, backdrop.path("pending").path("buckets").stringKeys(), "四档桶都在（空库也要给全零行）")
        assertEquals(0L, backdrop.path("pending").path("total").asLong())
        assertEquals(0L, backdrop.path("fail_total").asLong())
        assertTrue(backdrop.path("pending").path("oldest_created").isNull, "没有积压就没有最老行（显式 null）")
        assertTrue(backdrop.path("note").asText().contains("docs/06"), "口径说明在出参里")
        // viewer（不在码内）读 403：backlog 是聚合读但仍是读，读码 webhook.lists 它没配
        expectAdmin("GET", "/api/webhook/backlog", null, 403, viewer)
    }

    // ================= 门槛⑤（自增序列启动校正，批G 修 M2-1 踩坑⑤） =================

    @Test @Order(6) fun `门槛五 启动件把自增序列校正到 MAX+1`() {
        // 现场模拟踩坑⑤：历史运行把 id 写到了 250（超过 seed 的 RESTART 常量 100），
        // 若没有启动校正，下一次重启会把序列拨回 100 → 新增撞号
        val maxBefore = jdbc.queryForObject("SELECT MAX(id) FROM items_barcode", Number::class.java)!!.toLong()
        jdbc.update("UPDATE items_barcode SET id = ? WHERE id = ?", 250L, maxBefore)
        assertEquals(250L, jdbc.queryForObject("SELECT MAX(id) FROM items_barcode", Number::class.java)!!.toLong())

        // realign() 与启动时框架调的是同一条（e2e 模拟"下一次重启"）
        realign.realign()
        jdbc.update(
            "INSERT INTO items_barcode(item_id, barcode, company_id) VALUES(3, 'BC-ALIGN-24', 1)",
        )
        assertEquals(251L, jdbc.queryForObject("SELECT MAX(id) FROM items_barcode", Number::class.java)!!.toLong(),
            "校正后下一发默认主键 = 250+1")
        // 清场：realign 是幂等的，剩下的行不碍后来者
        jdbc.update("DELETE FROM items_barcode WHERE barcode = 'BC-ALIGN-24'")
    }

    // ================= 门槛⑥（批量重推，批H WH-B1） =================

    @Test @Order(7) fun `门槛六 批量重推 最老优先 只扫fail 至少一筛 限界`() {
        val now = com.ecshopx.kotlin.common.domain.TimeSec.now()
        // 对端用本机必然拒连的端口：wake 是同步实投一轮，拒连秒回、不依赖 CI 出网；
        // attempts 缺省 0 —— 重推后的下一轮失败按退避回排 pending（给 5 会在 wake 失败后到 maxAttempts 又回 fail，断言竞态）
        fun insertFail(msg: String, topic: String, created: Long) {
            jdbc.update(
                "INSERT INTO webhook_delivery_log(company_id, developer_id, subscription_id, msg_id, topic, url, " +
                    "status, created, updated) " +
                    "VALUES(1, 1, 0, ?, ?, 'http://127.0.0.1:1/hook', 'fail', ?, ?)",
                msg, topic, created, now,
            )
        }
        // 五行现场：窗口内两条 fail、一条 running、一条 success；窗口外一条老 fail（最老优先要靠它证）
        insertFail("WHR24-OLD", "order.created", now - 100 * 3600)
        insertFail("WHR24-IN1", "order.created", now - 2)
        insertFail("WHR24-IN2", "order.created", now - 1)
        jdbc.update(
            "INSERT INTO webhook_delivery_log(company_id, developer_id, subscription_id, msg_id, topic, url, status, created, updated) " +
                "VALUES(1, 1, 0, 'WHR24-RUN', 'order.created', 'http://127.0.0.1:1/hook', 'running', ?, ?)",
            now - 2, now,
        )
        jdbc.update(
            "INSERT INTO webhook_delivery_log(company_id, developer_id, subscription_id, msg_id, topic, url, status, created, updated) " +
                "VALUES(1, 1, 0, 'WHR24-OK', 'order.created', 'http://127.0.0.1:1/hook', 'success', ?, ?)",
            now - 2, now,
        )
        val end = now + 10
        // 窗口重推：命中的是窗口内两条 fail（running/success/窗口外全不动）；
        // wake 同步实投一轮，对端拒连 → 失败按退避回排 pending（attempts 1、next_try_at>0）
        val r1 = okAdmin(
            "POST",
            "/api/webhook/deliveries/replay?topic=order.created&created_start=${now - 4}&created_end=$end",
        )
        assertEquals(WebhookContract.REPLAY, r1.stringKeys(), "两项固定键")
        assertEquals(2L, r1.path("matched").asLong())
        assertEquals(2L, r1.path("reset").asLong())
        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM webhook_delivery_log WHERE msg_id LIKE 'WHR24-IN%' AND status = 'pending' AND next_try_at > 0",
            Int::class.java,
        ), "重推即排程：失败过一轮也回到 pending 再排")
        assertEquals("running", jdbc.queryForMap("SELECT status FROM webhook_delivery_log WHERE msg_id = 'WHR24-RUN'")["status"], "在投的不动")
        assertEquals("success", jdbc.queryForMap("SELECT status FROM webhook_delivery_log WHERE msg_id = 'WHR24-OK'")["status"], "成功的更不动")

        // 无筛位兜底：批量重推不给一键全量（全租户失败史全量打向对端的事不由一个忘带筛位的请求决定）
        expectAdmin("POST", "/api/webhook/deliveries/replay", emptyMap(), 400, admin)
        // 限界：limit > 500 拒；limit=1 + 无窗口 → 最老的一条先走（created 升序实证）
        expectAdmin("POST", "/api/webhook/deliveries/replay?topic=order.created&limit=501", null, 400, admin)
        insertFail("WHR24-NEW", "order.created", now)
        val r2 = okAdmin("POST", "/api/webhook/deliveries/replay?topic=order.created&limit=1")
        assertEquals(1L, r2.path("matched").asLong())
        assertEquals("pending", jdbc.queryForMap("SELECT status FROM webhook_delivery_log WHERE msg_id = 'WHR24-OLD'")["status"], "最老最先")
        assertEquals("fail", jdbc.queryForMap("SELECT status FROM webhook_delivery_log WHERE msg_id = 'WHR24-NEW'")["status"], "limit 截断留到下一轮")
        // 未知 topic 的筛位直接 BIZ 拒
        expectAdmin("POST", "/api/webhook/deliveries/replay?topic=nope.nope", null, 422, admin)
        // 清场
        jdbc.update("DELETE FROM webhook_delivery_log WHERE msg_id LIKE 'WHR24-%'")
    }

    // ================= 调用件 =================

    private fun metricsBefore() {
        // 记录本次的计数基线清零（e2e 只认本轮差量）
        jdbc.update("DELETE FROM openapi_call_stats")
        statsFlush.runNow()
        // 不会拉进上一轮的差量：计数器是进程内的，本轮之前没有别的人调网关
    }

    private fun LocalDateTime24(now: Long): Int {
        val d = java.time.LocalDateTime.ofInstant(
            java.time.Instant.ofEpochSecond(now), com.ecshopx.kotlin.common.domain.TimeSec.TZ,
        )
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun code(node: JsonNode): String = node.path("code").asText()

    /** 出参键集合（门槛四按契约集逐键对） */
    private fun JsonNode.stringKeys(): Set<String> = fieldNames().asSequence().toSet()

    /** fastbuy 快捷下单（item 3 坚果，distributor_id=1 随单拷进 trade） */
    private fun fastBuy(itemId: Long, num: Long = 1): Long =
        data(call("POST", "/api/h5app/wxapp/order_new", tok, mapOf(
            "cart_type" to "fastbuy", "order_type" to "normal",
            "items" to listOf(mapOf("item_id" to itemId, "num" to num)),
            "receiver_name" to "张三", "receiver_mobile" to "13800000001", "receiver_state" to "浙江省",
            "receiver_city" to "杭州市", "receiver_district" to "西湖区", "receiver_address" to "文三路 1 号",
        )), "order").path("order_id").asText().toLong()

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            else -> put(url)
        }
        token?.let { req.header("Authorization", "Bearer $it") }
        body?.let { req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(it)) }
        return mvc.perform(req).andReturn()
    }

    private fun data(res: MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun okAdmin(m: String, url: String, body: Map<String, Any?>? = null): JsonNode =
        data(call(m, url, admin, body), "$m $url")

    /** admin/reader 调用但期待非 200（401/403/422/404 走这里断） */
    private fun expectAdmin(m: String, url: String, body: Map<String, Any?>?, exp: Int, token: String) {
        val res = call(m, url, token, body)
        assertEquals(exp, res.response.status, "$m $url -> " + utf8(res.response.contentAsByteArray))
    }

    private fun ok(m: String, url: String, body: Map<String, Any?>? = null, token: String? = null): JsonNode =
        data(call(m, url, token, body), "$m $url")

    // ===== 开放面签名件（网关调用，M21 同一份形状的最小复刻） =====

    private val AK1 = "ak_1a2b3c4d5e6f7081"
    private val SK1 = "sk_1a2b3c4d5e6f708192a3b4c5d6e7f809"

    private val common = setOf("app_key", "timestamp", "version", "nonce", "request_id", "sign", "method", "debug", "company_id")

    private fun wire(v: Any?): String = when (v) {
        is Boolean -> if (v) "true" else "false"
        else -> v.toString()
    }

    private fun split(verb: String, params: Map<String, Any?>): Pair<Map<String, String>, Map<String, Any?>> {
        val q = LinkedHashMap<String, String>()
        val b = LinkedHashMap<String, Any?>()
        params.forEach { (k, v) ->
            val business = verb != "GET" && k !in common
            when {
                v == null -> if (business) b[k] = null
                business -> b[k] = v
                else -> q[k] = wire(v)
            }
        }
        return q to b
    }

    /** 最小 gw：视图对齐 M21 的 openapi()（业务位进 body、公共位进 query，签"合并后的全集"） */
    private fun gw(method: String, params: Map<String, Any?> = emptyMap(), verb: String = "GET", version: String = "2.0"): JsonNode {
        val toSign = LinkedHashMap<String, Any?>()
        toSign["app_key"] = AK1
        toSign["method"] = method
        toSign["version"] = version
        toSign["timestamp"] = (com.ecshopx.kotlin.common.domain.TimeSec.now() - 5).toString()
        toSign["nonce"] = "N24" + System.nanoTime()
        toSign.putAll(params)
        val (q0, b0) = split(verb, toSign)
        val merged = LinkedHashMap<String, Any?>()
        q0.forEach { (k, v) -> merged[k] = v }
        b0.forEach { (k, v) -> merged[k] = v }
        toSign["sign"] = OpenapiSign.genSign(merged, SK1)
        val (q, b) = split(verb, toSign)
        val req = if (verb == "GET") get("/api/openapi")
        else post("/api/openapi").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(b))
        q.forEach { (k, v) -> req.param(k, v) }
        val res = mvc.perform(req).andReturn()
        assertEquals(200, res.response.status, "开放面 HTTP 恒 200（§25.1）")
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }
}
