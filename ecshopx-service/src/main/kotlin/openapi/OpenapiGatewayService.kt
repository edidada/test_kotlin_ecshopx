package com.ecshopx.kotlin.service.openapi

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.openapi.OpenapiDeveloperMapper
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * 开放平台网关（M2-1 §25.1）。单入口、十一条闸门、HTTP 恒 200。
 *
 * 这一支是本项目唯一"自己承担鉴权"的 API 面：它不看 JWT，只看 `app_key + sign`。
 * 闸门顺序照抄源系统 `Middleware/OpenapiCheck.php` —— **顺序即契约**，
 * 重排之后对接方拿着错误码排障会走错方向（先报"app_key 不正确"还是先报"缺少 sign"是有区别的，
 * 而且先查密钥会把"密钥存不存在"泄露给没带签的人）。
 *
 * 三条与站内接口不同的纪律，写在这里而不是靠 reviewer 记住：
 * 1. **异常必须在本类内部消化成信封**。`GlobalExceptionHandler` 一接住漏出去的异常就改成站内那套
 *    `{message,errors,code,status_code}` 并按 ErrorCode 给状态码，开放面对端拿到那种响应
 *    等于拿到一份"这不是我们的 API"的困惑。所以这里 `try` 的范围包住全部闸门与委托。
 * 2. **租户只认密钥行**。入参里出现 `company_id` 且不等于密钥所属租户 → `E4001`（第 9 道闸门，fail-closed）。
 *    源系统 `Filter/BaseFilter::resolveCompanyId` 就是这个判据；入参不传才是正常路径。
 * 3. **防重放挡在委托之前**。第 8 道闸门命中就不执行任何业务动作，所以"同一个 nonce 连打两次"
 *    对发货、核销、动账这类写面也必须只有第一次生效（§25.6 门槛①）。
 */
@Service
class OpenapiGatewayService(
    private val developers: OpenapiDeveloperMapper,
    private val nonceStore: OpenapiNonceStore,
    private val registry: OpenapiMethodRegistry,
    private val mapper: ObjectMapper,
    private val metrics: OpenapiMetrics,

    @Value("\${ecx.openapi.enabled:true}") private val enabled: Boolean,
    @Value("\${ecx.openapi.debug:false}") private val debug: Boolean,
    @Value("\${ecx.openapi.timestamp-window-seconds:600}") private val windowSeconds: Long,
    @Value("\${ecx.openapi.nonce-ttl-seconds:600}") private val nonceTtl: Long,
    @Value("\${ecx.openapi.wrap-response:true}") private val wrapResponse: Boolean,
) {

    /**
     * 一次调用。
     *
     * @param pathMethod OA-G2 的路径末段（`/api/openapi/ecx.order.get`）；OA-G1 传 null，此时 `method` 从参数取
     * @param query      query 参数（表单字段也在这张表里，由 Spring 的合并结果给出）
     * @param body       原始请求体，只在以 `{` 开头时作为 JSON 参与合并
     * @return 信封（`wrap-response=false` 时成功回 `data` 本体），控制器**原样返回、不再包一层**
     */
    fun handle(
        pathMethod: String?,
        verb: String,
        query: Map<String, String>,
        body: String?,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Any? {
        val req = OpenapiRequest(OpenapiParams.merge(query, body, mapper))
        val envelope = try {
            guardThenInvoke(pathMethod, verb, req, nowSeconds)
        } catch (e: OpenapiException) {
            // 闸门 1..10 与"委托主动抛开放面异常"都走这里：码由抛的那一方定
            OpenapiEnvelope.fail(e.code, e.messageOrEmpty())
        } catch (t: Throwable) {
            // 第 11 道闸门：委托的 BizException 一律 E5000 + 原文透出（那些消息本来就是给人看的中文）；
            // 未预期异常连原文都不给——堆栈里的类名与 SQL 片段不属于对外面
            metrics.markError(t)
            OpenapiEnvelope.fail(OpenapiCode.INTERNAL, t.message?.takeIf { t is BizException } ?: "服务异常")
        }
        metrics.record(envelope)
        return if (wrapResponse) envelope
        // `wrap-response=false`：成功直出 data；失败仍是信封，否则对接方无从判断错（§25.1 末段）
        else envelope["code"]?.let { if (it == OpenapiCode.SUCCESS) envelope["data"] else envelope }
            ?: envelope["data"]
    }

    /**
     * 闸门 + 分发。返回**信封**（不是裸 data），这样 `metrics` 与 `wrap-response` 都能在调用点判；
     * 闸门失败用 [OpenapiException] 抛出。
     */
    private fun guardThenInvoke(
        pathMethod: String?,
        verb: String,
        req: OpenapiRequest,
        now: Long,
    ): Map<String, Any?> {
        if (!enabled) throw OpenapiException(OpenapiCode.INTERNAL, "开放接口未启用")

        // ---- 闸门 1：三个必传位（`version` 缺省 1.0，与源系统那条 `?: '1.0'` 同形） ----
        val version = req.str("version")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_VERSION
        val timestamp = req.str("timestamp")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw OpenapiException(OpenapiCode.PARAM, "timestamp必填")
        val appKey = req.str("app_key")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw OpenapiException(OpenapiCode.PARAM, "app_key必填")

        // ---- 闸门 2：debug 跳过 3..8（本地自测与冒烟脚本用；默认必须 false） ----
        // 跳过之后租户取系统默认租户 1 —— 这条也解释了 seed 的演示密钥为什么落在 company_id=1
        var caller = OpenapiCaller(developerId = 0, companyId = DEBUG_COMPANY, appKey = appKey)
        if (!debug) {
            // ---- 闸门 3：时间窗（±600 秒，边界两个方向都算超窗：`> window` 才放行） ----
            val ts = parseTimestamp(timestamp)
                ?: throw OpenapiException(OpenapiCode.TIMESTAMP, "timestamp 不合法")
            if (Math.abs(now - ts) > windowSeconds) {
                throw OpenapiException(OpenapiCode.TIMESTAMP, "timestamp 不合法")
            }

            // ---- 闸门 4：缺 sign（先于查密钥，源系统同序） ----
            val sign = req.str("sign")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw OpenapiException(OpenapiCode.SIGN, "缺少 sign")

            // ---- 闸门 5：按 app_key 取密钥（不存在与已停用同回 E4003） ----
            val d = developers.findActiveByAppKey(appKey)
                ?: throw OpenapiException(OpenapiCode.APP_KEY, "app_key 不正确")
            caller = OpenapiCaller(d.developerId, d.companyId, appKey)

            // ---- 闸门 6：比签。参数集 = query ∪ body 全集，`sign` 自身除外 ----
            val forSign = LinkedHashMap(req.params).apply { remove("sign") }
            val expect = OpenapiSign.genSign(forSign, d.appSecret)
            if (!expect.equals(sign, ignoreCase = true)) {
                throw OpenapiException(OpenapiCode.SIGN, "sign 不合法")
            }

            // ---- 闸门 7/8：nonce 必填且不可复用（挡在委托之前，见类注释第 3 条） ----
            val nonce = req.str("nonce")?.trim()?.takeIf { it.isNotEmpty() }
                ?: req.str("request_id")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw OpenapiException(OpenapiCode.PARAM, "缺少 nonce 或 request_id")
            if (!nonceStore.consume(nonce, nonceTtl, now)) {
                throw OpenapiException(OpenapiCode.TIMESTAMP, "nonce 已使用")
            }
        }

        // ---- 闸门 9：入参自带的 company_id 必须与密钥所属租户一致（fail-closed） ----
        req.requestedCompanyId()?.let {
            if (it != caller.companyId) {
                throw OpenapiException(OpenapiCode.PARAM, "company_id 与密钥所属企业不一致")
            }
        }

        // ---- 闸门 10：版本 / method / verb ----
        if (!registry.versions.contains(version)) {
            throw OpenapiException(OpenapiCode.VERSION, "该版本号不存在API")
        }
        val method = (pathMethod ?: req.str("method"))?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw OpenapiException(OpenapiCode.API_NOT_FOUND, "找不到API")
        val entry = registry.find(version, method)
            ?: throw OpenapiException(OpenapiCode.API_NOT_FOUND, "找不到API")
        // verb 不符在源系统就是 API_NOT_FOUND（不是 405），照抄 —— 改成 405 就漂了
        if (!entry.verb.equals(verb, ignoreCase = true)) {
            throw OpenapiException(OpenapiCode.API_NOT_FOUND, "找不到API")
        }
        registry.checkParams(entry, req)?.let { throw it }

        // 走到这里调用方才算认出来：计数按开发者分桶要靠它
        metrics.bind(caller)

        // ---- 委托执行（第 11 道闸门的映射在这一层做） ----
        val data = try {
            entry.handler(req, caller)
        } catch (e: OpenapiException) {
            throw e
        } catch (e: BizException) {
            throw mapBiz(entry, e)
        }
        return OpenapiEnvelope.ok(maskOutbound(data))
    }

    /**
     * 域错误映射（§25.2 那列「强制筛/口径」的实现处）。
     *
     * `NOT_FOUND` 与 `NO_PERM` **合成同一个回答**：前者是"这一单不存在"，后者是"这一单不是你的"，
     * 在开放面上把两者区分开，就等于允许外部探测"某个单号属于别的租户"（门槛②的判据）。
     * 其余 `BizException`（校验、状态机、余额不足）一律 `E5000` + 原文透出。
     */
    private fun mapBiz(entry: OpenapiMethodRegistry.Entry, e: BizException): OpenapiException {
        val nf = entry.notFound
        return if (nf != null && (e.ec == ErrorCode.NOT_FOUND || e.ec == ErrorCode.NO_PERM)) {
            OpenapiException(nf.code, nf.message)
        } else {
            OpenapiException(OpenapiCode.INTERNAL, e.message ?: "服务异常")
        }
    }

    /**
     * 出境数据的手机号一律掩码（§25.6 门槛②："与 `datapass.pass` 无关"）。
     *
     * 为什么不只在会员装配函数里掩：委托的 19 个出口来自 8 个不同的 service，
     * `MemberService.profile` 确实掩了，但 `ecx.order.list` 的收件人电话来自订单行原列。
     * 出境面没有"看全值"这个权限位，就由网关兜底 —— 漏一个字段就是数据事故，多扫一遍只是 CPU。
     */
    @Suppress("UNCHECKED_CAST")
    private fun maskOutbound(data: Any?): Any? = when (data) {
        is Map<*, *> -> (data as Map<String, Any?>).mapValues { (k, v) ->
            if (k in MOBILE_KEYS) Mask.mobile(v?.toString()) else maskOutbound(v)
        }
        is List<*> -> data.map { maskOutbound(it) }
        else -> data
    }

    /**
     * 时间戳解析。源系统用 `strtotime`，它同时接受 unix 秒与 `2026-10-18 12:00:00`；
     * 这里两种都认（对端两族写法都真实存在），认不出来一律按"不合法"走 `E4002`。
     */
    private fun parseTimestamp(raw: String): Long? {
        raw.trim().toLongOrNull()?.let { return it }
        val text = raw.trim().replace('T', ' ')
        return runCatching {
            val dt = when {
                text.length == 19 -> LocalDateTime.parse(text, SEC_FMT)
                // `2026-10-18` 这种纯日期按当天 00:00 算（strtotime 同语义）
                text.length == 10 -> LocalDateTime.of(
                    text.substring(0, 4).toInt(), text.substring(5, 7).toInt(), text.substring(8, 10).toInt(), 0, 0,
                )
                else -> return null
            }
            dt.atZone(ZoneId.systemDefault()).toEpochSecond()
        }.getOrNull()
    }

    private companion object {
        const val DEFAULT_VERSION = "1.0"

        /** debug 模式下的租户 = 系统默认租户（源系统同形） */
        const val DEBUG_COMPANY = 1L
        val SEC_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** 出境面需要掩码的列名（三个域各自的叫法，都指手机号） */
        val MOBILE_KEYS = setOf("mobile", "phone", "receiver_phone", "receiver_mobile", "contact_mobile")
    }
}

/** 开放面异常的 message 不可能为 null，但 `RuntimeException.message` 是平台类型，这里收口一次 */
private fun OpenapiException.messageOrEmpty(): String = message ?: "服务异常"

/**
 * 网关调用计数（§25.3 OP-A6 的读源）。
 *
 * **进程内计数、不落表**是 §25.4 末条的裁定：每次开放调用都插一行日志，等于把 P2 的对外面变成
 * 写放大源；而 OP-A6 要的只是"这个开发者大概调了多少、失败多少"，内存够。
 * 代价写清楚：重启归零、多实例各自计数 —— 配额与计量属 M2-4，那时要的形状是按天聚合，本来就不同。
 */
@Component
class OpenapiMetrics {

    private val counts = ConcurrentHashMap<Long, Cell>()
    private val errors = ConcurrentHashMap<String, AtomicLong>()

    /** 网关在认出调用方之后绑一次；闸门阶段认不出来时计数落在 [UNKNOWN] 桶 */
    fun bind(caller: OpenapiCaller) {
        current.set(caller.developerId)
    }

    /** 未预期异常的种类计数（只给运维看排障分布，不进任何对外面） */
    fun markError(t: Throwable) {
        errors.computeIfAbsent(t::class.java.simpleName) { AtomicLong() }.incrementAndGet()
    }

    fun record(envelope: Map<String, Any?>) {
        val dev = current.get() ?: UNKNOWN
        val cell = counts.computeIfAbsent(dev) { Cell() }
        if (envelope["code"] == OpenapiCode.SUCCESS) cell.success.incrementAndGet() else cell.fail.incrementAndGet()
        current.remove()
        current.remove()
    }

    /** OP-A6 的读口：某个开发者的调用总数/成功/失败 */
    fun snapshot(developerId: Long): Map<String, Long> {
        val c = counts[developerId] ?: return linkedMapOf("total" to 0L, "success" to 0L, "fail" to 0L)
        val s = c.success.get()
        val f = c.fail.get()
        return linkedMapOf("total" to (s + f), "success" to s, "fail" to f)
    }

    fun errorKinds(): Map<String, Long> = errors.mapValues { it.value.get() }

    /** 测试与运维清零用（e2e 断"这一轮打了三次"必须能从 0 开始数） */
    fun reset() {
        counts.clear()
        errors.clear()
        current.remove()
    }

    private class Cell {
        val success = AtomicLong()
        val fail = AtomicLong()
    }

    private companion object {
        /** 闸门就失败的调用没有开发者身份，统一记在这一桶，不进 OP-A6 的按开发者读口 */
        const val UNKNOWN = -1L

        /**
         * ThreadLocal 而不是把 `caller` 一路传进 `record`：`record` 的调用点在 `handle` 的收尾，
         * 那里已经跨过了"闸门内构造 caller"的作用域，把它提到外面会让"闸门失败时 caller 还不存在"
         * 变成满屏 nullable。`record` 里必定 `remove()`，线程池复用不会串。
         */
        val current = ThreadLocal<Long?>()
    }
}
