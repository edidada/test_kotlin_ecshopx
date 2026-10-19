package com.ecshopx.kotlin.service.webhook

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.openapi.OpenapiDeveloperMapper
import com.ecshopx.kotlin.dao.webhook.WebhookAttempt
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryDO
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryMapper
import com.ecshopx.kotlin.service.openapi.OpenapiSign
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Webhook 投递（M2-1 §25.4 的执行腿）。
 *
 * 与源系统 `MarketingCenter\Request::call()` 的三处不同，全都是"它在那里踩过的坑"：
 * 1. 它同步发、`catch(\Exception){ return []; }` —— 本项目**每次投递都留一行状态**（`success`/`pending`/`fail`），
 *    失败不再是静默，而是 `attempts` 加一、排到下一次；
 * 2. 它零重试 —— 本项目按 `min(2^attempts, 32) × backoff_base` 指数退避，
 *    到 `max_attempts` 收敛成终态 `fail`（不再自动排，只能 WH-A7 手动重推）。
 *    **上限 32 倍**是刻意的：不设上限的退避会在第五六次排到几天之后，那种"还在重试"的记录等于没有；
 * 3. 它只用一种签名 —— 本项目两层（§25.4），现代接收端验 HMAC 头，PHP 形态的接收端验体内 `sign`，
 *    两支共用 [OpenapiSign] 那一支函数（入向验签、出向包裹同一实现，不让"嵌套数组怎么折"在两处漂开）。
 *
 * 两条判据值得单独说明：
 * - **HTTP 200 但响应体不是 `success` 不算失败**：对端的应答格式不由本项目裁定，
 *   能判的只有状态码。把"我不认识你的 body"当失败，会让一个正常的对端被无限重投。
 * - **密钥在发送时刻现读**（`findOutboundConfig`）：重置出站密钥之后重推历史记录，用的必须是新密钥，
 *   否则对端永远验不过；而 `url` 用的是 outbox 里那一行抄下的旧值（WH-A3 改址不追改历史）。
 */
@Service
class WebhookDeliveryService(
    private val deliveries: WebhookDeliveryMapper,
    private val developers: OpenapiDeveloperMapper,
    private val mapper: ObjectMapper,

    @Value("\${ecx.webhook.enabled:true}") private val enabled: Boolean,
    @Value("\${ecx.webhook.timeout-ms:2000}") private val timeoutMs: Long,
    @Value("\${ecx.webhook.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${ecx.webhook.backoff-base-seconds:30}") private val backoffBase: Long,
    @Value("\${ecx.webhook.batch-limit:20}") private val batchLimit: Int,
) {

    /**
     * 连接超时用 `timeoutMs`、请求超时也用 `timeoutMs`（照抄源系统 Guzzle 只有一个 `timeout=>2` 的形状）。
     * 客户端是无状态可共享的：`HttpClient` 自带连接池，每批 new 一个会把"对端慢"变成"我方线程堆积"。
     */
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    /** 提交后唤醒：只催一次、绝不把异常抛回业务线程（对接方挂了不能拖累下单） */
    fun wakeNow() {
        if (!enabled) return
        runCatching { deliverDue(limit = WAKE_LIMIT) }
    }

    /**
     * 投一批到期记录。
     *
     * @return 本轮真正发出去的行数（`claim` 没抢到的不算 —— 那说明别的线程/实例已经在投这一条）
     */
    fun deliverDue(limit: Int = batchLimit): Int {
        if (!enabled) return 0
        val now = TimeSec.now()
        var sent = 0
        deliveries.listDue(now, limit.coerceIn(1, HARD_LIMIT)).forEach { row ->
            // CAS 抢占：0 行 = 这一条已被别人拿走，本轮直接跳过（至多一次投递的防线，§25.4）
            if (deliveries.claim(row.id, now) == 0) return@forEach
            sent++
            deliver(row, now)
        }
        return sent
    }

    /**
     * 立刻投某一条（WH-A9 的测试事件、WH-A7 的手动重推都走这里）。
     *
     * 仍然先 `claim`：手动重推点两下、或者重推正好撞上作业的轮次，靠的都是这同一个 CAS 位，
     * 不给"同一条并发发两次"留口子（§25.4 至多一次）。抢不到就返回 false，让调用方知道"有人在投了"。
     */
    fun deliverNow(row: WebhookDeliveryDO): Boolean {
        val now = TimeSec.now()
        if (!enabled) return false
        if (deliveries.claim(row.id, now) == 0) return false
        deliver(row, now)
        return true
    }

    /**
     * 单条投递。`row` 是 `claim` 之前读到的快照，只有 `attempts` 要自己 +1
     * （`claim` 在库里加，内存里这一位决定"这次算第几次、失败后排多久"）。
     */
    private fun deliver(row: WebhookDeliveryDO, now: Long) {
        val attempt = row.attempts + 1
        val secret = developers.findOutboundConfig(row.developerId, row.companyId)
        val started = System.nanoTime()
        // 没有出站密钥 = 这个开发者根本不具备被推送的能力。这种行直接进终态，不占重试额度：
        // 配好密钥之后由 WH-A7 手动重推，比"自动重试五次然后静默失败"更容易被发现
        if (secret?.externalAppSecret.isNullOrBlank()) {
            deliveries.markFail(row.id, WebhookAttempt().apply { error = ERR_NO_SECRET }, now)
            return
        }
        val appSecret = secret!!.externalAppSecret!!
        val body = buildBody(row, appSecret)
            ?: run {
                deliveries.markFail(row.id, WebhookAttempt().apply { error = ERR_BAD_PAYLOAD }, now)
                return
            }
        // 时间戳在这里定一次、发一次、留一次：它同时是 HMAC 的输入与 `X-Ecx-Timestamp` 的值。
        // 发完再取当前秒会让留下的那个数对不上真正发出去的那个（§25.3 WH-A6 要的就是"当次值"）
        val ts = (System.currentTimeMillis() / 1000).toString()
        val sign = "sha256=" + OpenapiSign.hmacSha256Hex(appSecret, ts + "\n" + body)
        val sent = post(row, body, secret.externalAppKey.orEmpty(), ts, sign)
        val a = WebhookAttempt().apply {
            httpStatus = sent.status
            error = sent.error.truncate(200)
            responseBody = sent.body.truncate()
            signature = sign
            this.ts = ts.toLongOrNull() ?: 0L
            runtime = elapsed(started)
        }
        when {
            sent.status in 200..299 -> deliveries.markSuccess(row.id, a, now)
            // 还有额度就排下一次；额度用尽就是终态 `fail`
            attempt < maxAttempts -> deliveries.markRetry(row.id, a.apply { nextTryAt = nextTryAt(now, attempt) }, now)
            else -> deliveries.markFail(row.id, a, now)
        }
    }

    /** 一次 HTTP 尝试的结果。`error` 只放一句人能读的话（进 `last_error`，长度有列宽限制） */
    private data class Sent(val status: Int, val body: String?, val error: String?)

    /** `ts` 与 `sign` 由 [deliver] 传进来而不是在这里现算 —— 同一对值要同时用于发出与留痕，两处各算必然不同 */
    private fun post(row: WebhookDeliveryDO, body: String, appKey: String, ts: String, sign: String): Sent {
        val req = try {
            HttpRequest.newBuilder(URI.create(row.url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("X-Ecx-Signature", sign)
                .header("X-Ecx-Timestamp", ts)
                .header("X-Ecx-Msg-Id", row.msgId)
                .header("X-Ecx-Event", row.topic)
                .header("X-Ecx-App-Key", appKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
        } catch (e: IllegalArgumentException) {
            return Sent(0, null, "推送地址不合法：${e.message}")
        }
        return try {
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            Sent(res.statusCode(), res.body(), if (res.statusCode() in 200..299) null else "HTTP ${res.statusCode()}")
        } catch (e: Exception) {
            // 超时、连接失败、对端 reset 全都算**可重试失败**（§25.4），状态位写 0 = 根本没收到应答
            Sent(0, null, "${e::class.simpleName}: ${e.message}")
        }
    }

    /**
     * 组装发给对端的请求体：库里的信封 + 体内 `sign`。
     *
     * 顺序不能反：**先算签、再序列化**。签名覆盖的是"去掉 `sign` 之后的信封参数"，
     * 而 JSON 序列化不稳定（键序、空格、转义），只要让签覆盖"重新序列化后的串"，
     * 对端就永远验不过 —— 这也是 HMAC 那一层覆盖原始字节而不是覆盖解析结果的原因。
     */
    private fun buildBody(row: WebhookDeliveryDO, secret: String): String? {
        val payload = row.payload ?: return null
        val node = runCatching { mapper.readTree(payload) }.getOrNull() as? ObjectNode ?: return null
        @Suppress("UNCHECKED_CAST")
        val params = mapper.convertValue(node, Map::class.java) as Map<String, Any?>
        val sign = OpenapiSign.genSign(params, secret)
        // `sign` 追加在末尾：它是唯一一个"发送侧现算"的字段，放在信封结构之后最不容易被误当业务字段
        node.put("sign", sign)
        return node.toString()
    }

    /** `min(2^attempts, 32) × backoff_base`：attempts 是本次**已经试过**的次数（claim 之后） */
    private fun nextTryAt(now: Long, attempt: Int): Long =
        now + (1L shl attempt.coerceAtMost(5)) * backoffBase

    private fun elapsed(started: Long): Int = ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(0)

    private fun String?.truncate(n: Int = 500): String? = this?.take(n)

    private companion object {
        /** 唤醒只做小批量：它的目的是"让对接方少等一会儿"，不是把作业抢跑 */
        const val WAKE_LIMIT = 5

        /** 单轮硬上限：配置写错（比如 batch-limit 填一百万）时不让一次调度拖死线程 */
        const val HARD_LIMIT = 200
        const val ERR_NO_SECRET = "开发者未配置出站密钥（external_app_secret）"
        const val ERR_BAD_PAYLOAD = "outbox 正文不是合法信封 JSON"
    }
}
