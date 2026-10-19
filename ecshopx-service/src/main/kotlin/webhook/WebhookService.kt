package com.ecshopx.kotlin.service.webhook

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.openapi.OpenapiDeveloperMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryDO
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryMapper
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryRow
import com.ecshopx.kotlin.dao.webhook.WebhookSubscriptionDO
import com.ecshopx.kotlin.dao.webhook.WebhookSubscriptionMapper
import com.ecshopx.kotlin.service.openapi.EventPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.net.URI
import org.springframework.stereotype.Service
import com.ecshopx.kotlin.service.tx.Db

/** WH-A2 新建 / WH-A3 改订阅的入参形状。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WebhookSubscriptionForm(
    /** A2 必填；A3 不接受（改订阅者等于把事件送给另一家，那要先建一条新订阅） */
    val developerId: Long? = null,
    val topic: String? = null,
    val url: String? = null,
    /** 三态：null = 不改 / 新建默认 1；`status=0` 即刻断推（连 `pending` 都不生成） */
    val status: Int? = null,
)

/**
 * Webhook 订阅与投递记录（M2-1 §25.3 WH-A1..A9，后台面）。
 *
 * 这个类里没有一条"发 HTTP"的代码 —— 投递归 [WebhookDeliveryService]，落 outbox 归 [EventPublisher]。
 * 分成三处不是为了好看：订阅是**配置**（可以直接删）、outbox 是**事件的存放处**（不能删，它是对接方
 * "我收到了"的证据）、投递是**动作**（可以随时重来）。三件事的生命周期不同，混在一个类里迟早会让人
 * 在删订阅的时候顺手删了投递记录。
 *
 * 建订阅的两道 422（§25.3 WH-A2）都是"这一条订阅注定投不成"的提前判定：
 * - **没有 `external_app_secret` 就拦下**：没出站密钥就没有能验得通的推送，
 *   让它建成，事件会在 outbox 里堆成一排终态 `fail`，运营看到的是"系统坏了"，真实原因是密钥没配；
 * - **非 https 且非本机回环就拦下**：明文推 HMAC 载荷等于把签名白送 ——
 *   中间人拿到 body 与 `X-Ecx-Signature` 之后，它不需要知道密钥就能重放（回环例外是因为本地开发与 e2e
 *   都跑在 `127.0.0.1`，那条路上没有中间人）。
 */
@Service
class WebhookService(
    private val subs: WebhookSubscriptionMapper,
    private val deliveries: WebhookDeliveryMapper,
    private val developers: OpenapiDeveloperMapper,
    private val publisher: EventPublisher,
    private val sender: WebhookDeliveryService,
    private val db: Db,
    private val mapper: ObjectMapper,
) {

    /** WH-A1 订阅列表：`developer_id?` / `topic?` / `status?`（三态，C194 那条纪律在每一张表上都成立） */
    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val f = filter(companyId)
        triState(raw["status"])?.let { f["status"] = it }
        raw["topic"]?.trim()?.takeIf { it.isNotEmpty() }?.let { f["topic"] = it }
        raw["developer_id"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            f["developerId"] = it.toLongOrNull() ?: throw BizException(ErrorCode.VALIDATION, "developer_id 必须是数字")
        }
        return PageHelper.paginate(
            pageQuery(raw),
            count = { subs.count(f) },
            query = { offset, limit -> subs.page(f, offset, limit) },
            map = { rows -> rows.map { subRow(it) } },
        )
    }

    /**
     * WH-A2 新建订阅。
     *
     * 三条判据按"最可能是原因的那条先报"：开发者不存在 → 404，topic 不认识 → 422，
     * 没出站密钥 → 422，地址不合式 → 422，重复 → 422。
     * `topic` 必须来自 [WebhookTopics]：订阅一个不存在的 topic 能建成功，
     * 但它永远不会来事件 —— 那种"配了却没反应"的对接受不了，运营也查不出来。
     */
    fun create(companyId: Long, form: WebhookSubscriptionForm): Map<String, Any?> {
        val developerId = form.developerId ?: throw BizException(ErrorCode.VALIDATION, "developer_id 必填")
        val topic = requireTopic(form.topic)
        val url = requireUrl(form.url)
        val d = developers.find(developerId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "开发者不存在")
        if (d.status != 1) throw BizException(ErrorCode.BIZ, "开发者已停用，不能建订阅")
        if (d.externalAppSecret.isNullOrBlank()) {
            throw BizException(ErrorCode.BIZ, "该开发者未配置出站密钥（external_app_secret），推送过去对方验不过")
        }
        if (subs.countSame(developerId, topic, url) > 0) {
            throw BizException(ErrorCode.BIZ, "同一开发者、同一事件、同一地址的订阅已存在")
        }
        val row = WebhookSubscriptionDO().apply {
            this.companyId = companyId
            this.developerId = developerId
            this.topic = topic
            this.url = url
            // 建了就是要用：默认启用。要暂停是 A3 的显式动作（与开发者档案同一惯例）
            status = form.status ?: 1
        }
        return db.transactional {
            subs.insert(row)
            subRowOf(row)
        }
    }

    /**
     * WH-A3 改订阅。`developer_id` 不接受入参（同 `app_key` 那条纪律：改归属会让历史投递记录的接收方换人）。
     *
     * **改址不追改历史记录**：已经落进 outbox 的那些行，`url` 是发布那一刻抄进去的旧值，
     * 这一条不靠"记得别去 UPDATE 历史"来保证，是靠 [EventPublisher] 抄值这个动作保证的。
     */
    fun update(companyId: Long, id: Long, form: WebhookSubscriptionForm): Map<String, Any?> {
        val cur = subs.find(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "订阅不存在")
        val topic = if (form.topic != null) requireTopic(form.topic) else cur.topic
        val url = if (form.url != null) requireUrl(form.url) else cur.url
        val status = form.status ?: cur.status
        if (status != 0 && status != 1) throw BizException(ErrorCode.VALIDATION, "status 只能是 0 或 1")
        // 只有真的换了 (topic, url) 才查重：把"提交内容等于当前值"的保存当成重复，会让人改不动一条订阅
        if ((topic != cur.topic || url != cur.url) && subs.countSame(cur.developerId, topic, url) > 0) {
            throw BizException(ErrorCode.BIZ, "同一开发者、同一事件、同一地址的订阅已存在")
        }
        return db.transactional {
            subs.update(id, companyId, topic, url, status, TimeSec.now())
            linkedMapOf(
                "id" to id,
                "company_id" to companyId,
                "developer_id" to cur.developerId,
                "topic" to topic,
                "url" to url,
                "status" to status,
            )
        }
    }

    /**
     * WH-A4 删订阅：**直接删**（订阅是配置不是账）。
     * 名下 `webhook_delivery_log` 的行**一条都不动** —— 它们是对接方"我收到了"的证据，
     * 跟着配置一起消失的话，事后就没法回答"那笔事件到底推没推过"。
     */
    fun delete(companyId: Long, id: Long): Map<String, Any?> {
        subs.find(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "订阅不存在")
        return db.transactional {
            val left = subs.delete(id, companyId)
            linkedMapOf("id" to id, "deleted" to (left > 0), "delivery_log_kept" to true)
        }
    }

    /** WH-A5 投递记录列表（不含 `payload`：那一列只在详情里出，理由见 `WebhookMappers.L_HEAVY_COLS`） */
    fun deliveries(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val f = filter(companyId)
        raw["topic"]?.trim()?.takeIf { it.isNotEmpty() }?.let { f["topic"] = it }
        raw["status"]?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            if (s !in DELIVERY_STATUS) throw BizException(ErrorCode.VALIDATION, "status 只能是 $DELIVERY_STATUS 之一")
            f["status"] = s
        }
        raw["msg_id"]?.trim()?.takeIf { it.isNotEmpty() }?.let { f["msgId"] = it }
        raw["url"]?.trim()?.takeIf { it.isNotEmpty() }?.let { f["url"] = it }
        raw["subscription_id"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            f["subscriptionId"] = it.toLongOrNull() ?: throw BizException(ErrorCode.VALIDATION, "subscription_id 必须是数字")
        }
        raw["developer_id"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            f["developerId"] = it.toLongOrNull() ?: throw BizException(ErrorCode.VALIDATION, "developer_id 必须是数字")
        }
        timeRange(raw["created_start"], "created_start")?.let { f["createdStart"] = it }
        timeRange(raw["created_end"], "created_end")?.let { f["createdEnd"] = it }
        return PageHelper.paginate(
            pageQuery(raw),
            count = { deliveries.count(f) },
            query = { offset, limit -> deliveries.page(f, offset, limit) },
            map = { rows -> rows.map { deliveryRow(it) } },
        )
    }

    /**
     * WH-A6 投递详情。
     *
     * `signature` 出的是**当次实际发出的那个头值**（库里留的 `last_signature`），
     * 不是拿当前密钥重算的：重算值会随时间戳和密钥一起变，而对端手里只有它收到的那一个。
     * 对接方说"验不过"的时候，只有这一份能和它的日志逐字对上 —— 这就是这两个字段的唯一用途。
     */
    fun delivery(companyId: Long, id: Long): Map<String, Any?> {
        val d = deliveries.find(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "投递记录不存在")
        return linkedMapOf(
            "id" to d.id,
            "company_id" to d.companyId,
            "developer_id" to d.developerId,
            "subscription_id" to d.subscriptionId,
            "msg_id" to d.msgId,
            "topic" to d.topic,
            "url" to d.url,
            "status" to d.status,
            "attempts" to d.attempts,
            "next_try_at" to d.nextTryAt,
            "last_http_status" to d.lastHttpStatus,
            "last_error" to d.lastError,
            "runtime" to d.runtime,
            "created" to d.created,
            "updated" to d.updated,
            // 信封在库里是不含 `sign` 的形态（签要发送时刻现算），所以这一份与对端收到的 body 只差那一个字段
            "payload" to parseOrNull(d.payload),
            "response_body" to d.responseBody,
            "signature" to d.lastSignature,
            "signature_ts" to d.lastTs,
        )
    }

    /**
     * WH-A7 手动重推。
     *
     * 走 [WebhookDeliveryMapper.resetForRetry] 而不是直接发：那一条 SQL 的 `status IN ('fail','running')`
     * 就是"这一条该不该重推"的判据所在处（`success` 的重推只会让对端收到两份，`pending` 的会排队两次）。
     * 命中 0 行 → 422，让点按钮的人立刻知道为什么没动，而不是"提交了但什么都没发生"。
     * 重推**不换 `msg_id`、不清 `attempts`**（§25.4）：前者让对端能判重，后者让"试过几次"这件事继续有意义。
     */
    fun retry(companyId: Long, id: Long): Map<String, Any?> {
        val cur = deliveries.find(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "投递记录不存在")
        val now = TimeSec.now()
        if (deliveries.resetForRetry(id, companyId, now) == 0) {
            throw BizException(ErrorCode.BIZ, "当前状态 ${cur.status} 不需要或不能重推（只有 fail/running 可以）")
        }
        val row = deliveries.find(id, companyId)!!
        // 立刻投一次：手动点这一条的人就在等结果，让作业再等 30 秒是把"我帮你重试了"变成一句空话。
        // 抢不到（别的实例正好在投）不报错，返回 `dispatched=false` —— 那说明这一条正在路上
        val dispatched = runCatching { sender.deliverNow(row) }.getOrDefault(false)
        val after = deliveries.find(id, companyId)
        return linkedMapOf(
            "id" to id,
            "msg_id" to cur.msgId,
            "dispatched" to dispatched,
            "status" to (after?.status ?: "pending"),
            "attempts" to (after?.attempts ?: cur.attempts),
            "last_http_status" to (after?.lastHttpStatus ?: 0),
            "last_error" to after?.lastError,
        )
    }

    /** WH-A8 事件目录：来自 [WebhookTopics]，不来自库（与 OP-A5 同一个纪律 —— 文档面从代码出） */
    fun topics(): List<Map<String, Any?>> = WebhookTopics.catalog()

    /**
     * WH-A9 测试事件。
     *
     * 它与正式推送**同一套签名、同一条投递代码**（[WebhookDeliveryService.deliverNow]），
     * 区别只在目标：这一条的地址是入参给的、不等任何订阅存在。
     * 仍然落一行 `delivery_log`（`subscription_id = 0` 表示"不是订阅带出来的"）——
     * 对接方拿"测试通了"当验收证据时，那一条记录就是证据本身；不落它，这句"通了"就成了口头话。
     *
     * 这里**不开事务**：插完 outbox 行就要能被投递侧读到（`deliverNow` 在同一个方法里发 HTTP），
     * 而 HTTP 绝不能包在事务里 —— 对端 2 秒不响应，这条连接上的事务就挂着 2 秒。
     * 单条 INSERT 由 autocommit 落定，仍然满足"落库先于投递"。
     */
    fun testEvent(companyId: Long, raw: Map<String, Any?>): Map<String, Any?> {
        val developerId = (raw["developer_id"] ?: raw["developerId"])?.toString()?.trim()?.toLongOrNull()
            ?: throw BizException(ErrorCode.VALIDATION, "developer_id 必填")
        val url = requireUrl(raw["url"]?.toString())
        val topic = raw["topic"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: WebhookTopics.TEST
        if (!WebhookTopics.isKnown(topic)) throw BizException(ErrorCode.BIZ, "未知事件：$topic")
        val d = developers.find(developerId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "开发者不存在")
        if (d.externalAppSecret.isNullOrBlank()) {
            throw BizException(ErrorCode.BIZ, "该开发者未配置出站密钥（external_app_secret），推送过去对方验不过")
        }
        val id = publisher.publishDirect(companyId, developerId, 0L, topic, url, linkedMapOf("echo" to raw, "sent_at" to TimeSec.now()))
        val row = deliveries.find(id, companyId)!!
        val dispatched = runCatching { sender.deliverNow(row) }.getOrDefault(false)
        val after = deliveries.find(id, companyId)
        return linkedMapOf(
            "id" to id,
            "msg_id" to row.msgId,
            "topic" to topic,
            "url" to url,
            "dispatched" to dispatched,
            "status" to (after?.status ?: "pending"),
            "last_http_status" to (after?.lastHttpStatus ?: 0),
            "last_error" to after?.lastError,
        )
    }

    /** 概况里那一位：`WH-A1` 的页脚用（"还有多少在路上"），不是筛选条件 */
    fun inFlight(companyId: Long): Long = deliveries.countInFlight(companyId)

    // ---- 校验 ----

    private fun requireTopic(v: String?): String {
        val t = trimToNull(v) ?: throw BizException(ErrorCode.VALIDATION, "topic 必填")
        if (!WebhookTopics.isKnown(t)) throw BizException(ErrorCode.BIZ, "未知事件：$t")
        return t
    }

    /**
     * `url` 的三条判据：可解析、http/https、**https 或本机回环**。
     * 列宽 255 也要管：超长会在 INSERT 时才报错，那时人已经点过提交了。
     */
    private fun requireUrl(v: String?): String {
        val text = trimToNull(v) ?: throw BizException(ErrorCode.VALIDATION, "url 必填")
        if (text.length > MAX_URL) throw BizException(ErrorCode.VALIDATION, "url 超长（最多 $MAX_URL）")
        val u = runCatching { URI(text) }.getOrNull()
            ?: throw BizException(ErrorCode.BIZ, "url 不是合法地址")
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw BizException(ErrorCode.BIZ, "url 只支持 http/https")
        val host = u.host
        if (host.isNullOrBlank()) throw BizException(ErrorCode.BIZ, "url 必须有主机名")
        if (scheme == "http" && !isLoopback(host)) {
            throw BizException(ErrorCode.BIZ, "生产环境的推送地址必须用 https：明文携带 HMAC 载荷等于把签名白送")
        }
        return text
    }

    /** 本机回环：本地开发与 e2e 的假接收服务器都跑在这几个名字上，那条路上没有中间人 */
    private fun isLoopback(host: String): Boolean {
        val h = host.lowercase().removeSurrounding("[", "]")
        return h == "localhost" || h == "::1" || h.endsWith(".localhost") ||
            (h.startsWith("127.") && h.split(".").size == 4)
    }

    private fun filter(companyId: Long): LinkedHashMap<String, Any?> = linkedMapOf("companyId" to companyId)

    private fun triState(v: String?): Int? {
        val s = v?.trim() ?: return null
        if (s.isEmpty()) return null
        return when (s) {
            "1", "true" -> 1
            "0", "false" -> 0
            else -> throw BizException(ErrorCode.VALIDATION, "status 只能是 0 或 1")
        }
    }

    private fun pageQuery(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())

    /** 时间段接受 unix 秒或 `yyyy-MM-dd[ HH:mm:ss]`（与网关 `timestamp` 同一套宽容读法，运营不会两头记） */
    private fun timeRange(v: String?, field: String): Long? {
        val s = trimToNull(v) ?: return null
        s.toLongOrNull()?.let { return it }
        val zone = java.time.ZoneId.systemDefault()
        runCatching { java.time.LocalDateTime.parse(s.replace(' ', 'T')).toEpochSecond(zone.rules.getOffset(java.time.Instant.now())) }
            .getOrNull()?.let { return it }
        runCatching { java.time.LocalDate.parse(s).atStartOfDay(zone).toEpochSecond() }
            .getOrNull()?.let { return it }
        throw BizException(ErrorCode.VALIDATION, "$field 要 unix 秒或 yyyy-MM-dd[ HH:mm:ss]")
    }

    private fun parseOrNull(s: String?): Any? =
        s?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    private fun subRow(r: com.ecshopx.kotlin.dao.webhook.WebhookSubscriptionRow): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        "company_id" to r.companyId,
        "developer_id" to r.developerId,
        "topic" to r.topic,
        "url" to r.url,
        "status" to r.status,
        "created" to r.created,
        "updated" to r.updated,
        // 开发者侧的两列：后台看"这条订阅是谁家的"靠它们，只出 developer_id 等于让运营拿着一个数字没法查
        "app_key" to r.appKey,
        "developer_name" to r.developerName,
        "developer_status" to r.developerStatus,
    )

    private fun subRowOf(d: WebhookSubscriptionDO): Map<String, Any?> = linkedMapOf(
        "id" to d.id,
        "company_id" to d.companyId,
        "developer_id" to d.developerId,
        "topic" to d.topic,
        "url" to d.url,
        "status" to d.status,
    )

    private fun deliveryRow(r: WebhookDeliveryRow): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        "company_id" to r.companyId,
        "developer_id" to r.developerId,
        "subscription_id" to r.subscriptionId,
        "msg_id" to r.msgId,
        "topic" to r.topic,
        "url" to r.url,
        "status" to r.status,
        "attempts" to r.attempts,
        "next_try_at" to r.nextTryAt,
        "last_http_status" to r.lastHttpStatus,
        "last_error" to r.lastError,
        "runtime" to r.runtime,
        "created" to r.created,
        "updated" to r.updated,
    )

    private fun trimToNull(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_URL = 255
        val DELIVERY_STATUS = setOf("pending", "running", "success", "fail")
    }
}
