package com.ecshopx.kotlin.service.openapi

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.dao.openapi.OpenapiCallStatsMapper
import com.ecshopx.kotlin.dao.openapi.OpenapiDeveloperDO
import com.ecshopx.kotlin.dao.openapi.OpenapiDeveloperMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.webhook.WebhookDeliveryMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import java.net.URI
import org.springframework.stereotype.Service
import com.ecshopx.kotlin.service.tx.Db

/** OP-A2 新建 / OP-A3 改档案的入参形状。 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OpenapiDeveloperForm(
    val name: String? = null,
    val remark: String? = null,
    val externalBaseUri: String? = null,
    val externalAppKey: String? = null,
    val externalAppSecret: String? = null,
    /** 三态：null = 不改（A3）/ 新建默认 1；0 = 停用即刻 `E4003` */
    val status: Int? = null,
) {
    companion object {
        const val MAX_NAME = 64
        const val MAX_REMARK = 255
        const val MAX_URL = 255
    }
}

/**
 * 开放平台开发者档案（M2-1 §25.3 OP-A1..A6，后台面）。
 *
 * 三条判据决定了这一族的形状，都不是"顺手"能改的：
 *
 * 1. **`app_secret` 只进不出**。列表行连 DAO 的列清单里都没有它（`OpenapiMappers` 那两条 select
 *    是分开的），只有 A2 新建与 A4 重置这两处把明文放进响应体各一次。
 *    密钥一旦出现在列表接口的响应里，它就进了对接方的日志、缓存与前端 store ——
 *    那些地方没有"吊销"这件事。
 * 2. **`app_key` 不可改**（A3 的表单里根本没有这一项）。改身份等于把历史投递记录的归属换人，
 *    而 `webhook_delivery_log` 是对接方"我收到了"的证据。
 * 3. **没有 DELETE**。源系统只能删行，删行会把它名下所有出站配置与投递历史一起抹掉；
 *    本项目补了 `status` 之后，"吊销"就是 `status=0` —— 即刻对所有请求生效（网关第 5 道闸门），
 *    而历史留着。
 */
@Service
class OpenapiDeveloperService(
    private val developers: OpenapiDeveloperMapper,
    private val deliveries: WebhookDeliveryMapper,
    private val registry: OpenapiMethodRegistry,
    private val metrics: OpenapiMetrics,
    private val callStats: OpenapiCallStatsMapper,
    private val db: Db,
) {

    /** OP-A1 列表：`keyword?`（名称或 app_key）+ `status?`（三态） */
    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val f = linkedMapOf<String, Any?>("companyId" to companyId)
        raw["keyword"]?.trim()?.takeIf { it.isNotEmpty() }?.let { f["keyword"] = it }
        // 三态在这里必须保住：`status=false` 与"没传 status"是两件事（C194 那一课）
        raw["status"]?.trim()?.let { s ->
            if (s.isNotEmpty()) f["status"] = when (s) {
                "1", "true" -> 1
                "0", "false" -> 0
                else -> throw BizException(ErrorCode.VALIDATION, "status 只能是 0 或 1")
            }
        }
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        return PageHelper.paginate(
            pq,
            count = { developers.count(f) },
            query = { offset, limit -> developers.page(f, offset, limit) },
            map = { rows -> rows.map { row(it) } },
        )
    }

    /**
     * OP-A2 新建。
     *
     * **只有这一条（和 A4）的响应用 `data` 带出 `app_secret`**：密钥由服务端生成、当场给一次、
     * 之后任何接口都不再回显。这一条写在这里是因为它是那种" reviewer 一眼看不出来、
     * 但半年后被某次重构悄悄破坏"的判据。
     */
    fun create(companyId: Long, form: OpenapiDeveloperForm): Map<String, Any?> {
        val name = requireText(form.name, "name")
        val remark = trimToNull(form.remark)
        val externalUri = validateOutboundUri(form.externalBaseUri)
        val d = OpenapiDeveloperDO().apply {
            this.companyId = companyId
            appKey = OpenapiSign.newAppKey()
            appSecret = OpenapiSign.newAppSecret()
            this.name = name
            this.remark = remark
            // 新建即启用：源系统没有 status 列，默认"能用"；要吊销是 A3 的显式动作
            status = 1
            externalBaseUri = externalUri
            externalAppKey = trimToNull(form.externalAppKey)
            externalAppSecret = trimToNull(form.externalAppSecret)
        }
        return db.transactional {
            developers.insert(d)
            // 响应里带一次明文，是这一条接口存在的全部理由；其余读路一律走 page()（不含 secret 列）
            linkedMapOf(
                "developer_id" to d.developerId,
                "company_id" to companyId,
                "app_key" to d.appKey,
                "app_secret" to d.appSecret,
                "name" to d.name,
                "remark" to d.remark,
                "status" to d.status,
                "external_base_uri" to d.externalBaseUri,
                "external_app_key" to d.externalAppKey,
            )
        }
    }

    /** OP-A3 改档案与出站三列 + 启停。`app_key` 不接受入参（类注释第 2 条） */
    fun update(companyId: Long, developerId: Long, form: OpenapiDeveloperForm): Map<String, Any?> {
        val cur = need(companyId, developerId)
        val next = OpenapiDeveloperDO().apply {
            this.developerId = cur.developerId
            this.companyId = companyId
            appKey = cur.appKey
            appSecret = cur.appSecret
            name = form.name?.let { requireText(it, "name") } ?: cur.name
            remark = if (form.remark != null) trimToNull(form.remark) else cur.remark
            // `status` 三态：没传 = 不改。把"没传"当成"停用"会让一次普通改名顺手关掉一个密钥
            status = form.status ?: cur.status
            externalBaseUri = if (form.externalBaseUri != null) validateOutboundUri(form.externalBaseUri) else cur.externalBaseUri
            externalAppKey = if (form.externalAppKey != null) trimToNull(form.externalAppKey) else cur.externalAppKey
            // 出站密钥留空 = 不改（不是"清空"）：清空会让所有在路上的订阅失去签名密钥
            externalAppSecret = if (!form.externalAppSecret.isNullOrBlank()) form.externalAppSecret else cur.externalAppSecret
        }
        when (next.status) {
            0, 1 -> Unit
            else -> throw BizException(ErrorCode.VALIDATION, "status 只能是 0 或 1")
        }
        return db.transactional {
            developers.updateProfile(next)
            rowOf(next)
        }
    }

    /**
     * OP-A4 重置入向密钥。
     *
     * **没有宽限期**：新值当场生效，旧值下一个请求即 `E4004`。
     * 密钥泄露的处置要的是"立刻"，"给对接方 24 小时切换"是运营上的幻觉 ——
     * 泄露的密钥在窗口期内依然能用，而这正是这一条接口存在的原因。
     */
    fun resetSecret(companyId: Long, developerId: Long): Map<String, Any?> {
        need(companyId, developerId)
        val secret = OpenapiSign.newAppSecret()
        return db.transactional {
            developers.resetSecret(developerId, companyId, secret, com.ecshopx.kotlin.common.domain.TimeSec.now())
            linkedMapOf("developer_id" to developerId, "app_secret" to secret)
        }
    }

    /** OP-A5 已注册 method 目录：来自注册表，不来自库（§25.3 那条"文档面从代码出"的纪律） */
    fun methods(): Map<String, Any?> = linkedMapOf(
        "versions" to registry.versions.sorted(),
        "list" to registry.catalog(),
    )

    /**
     * OP-A6 概况。
     *
     * 网关那两位来自 [OpenapiMetrics]（**进程内、重启归零**，§25.4 末条的裁定），
     * 出站那三位来自 `webhook_delivery_log`（表里有什么就是什么），
     * 聚合那位来自 `openapi_call_stats`（批F §29.6，任务每分钟并入的租户-日账）。
     * 三个数字**不同源**这件事在出参里写成三个键组，不混成一个"总调用数"——
     * 混了就没人知道哪一半是估的。
     */
    fun stats(companyId: Long, developerId: Long): Map<String, Any?> {
        val d = need(companyId, developerId)
        val gw = metrics.snapshot(developerId)
        val byStatus = deliveries.statsByDeveloper(companyId, developerId).associate { (it.status ?: "") to it.cnt }
        val today = com.ecshopx.kotlin.common.domain.TimeSec.TZ.let { z ->
            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(com.ecshopx.kotlin.common.domain.TimeSec.now()), z)
        }.let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
        val agg = callStats.find(companyId, today)
        return linkedMapOf(
            "developer_id" to d.developerId,
            "app_key" to d.appKey,
            "gateway" to linkedMapOf("total" to gw["total"], "success" to gw["success"], "fail" to gw["fail"]),
            "delivery" to linkedMapOf(
                "pending" to (byStatus["pending"] ?: 0),
                "running" to (byStatus["running"] ?: 0),
                "success" to (byStatus["success"] ?: 0),
                "fail" to (byStatus["fail"] ?: 0),
            ),
            "aggregate" to linkedMapOf(
                "day" to today,
                "calls" to (agg?.calls ?: 0L),
                "success" to (agg?.success ?: 0L),
                "fails" to (agg?.fails ?: 0L),
            ),
            "note" to "gateway 计数在本进程内、重启归零；delivery 计数来自 webhook_delivery_log；" +
                "aggregate 来自 openapi_call_stats（每分钟并入的租户-日聚合，是账）",
        )
    }

    private fun need(companyId: Long, developerId: Long): OpenapiDeveloperDO =
        developers.find(developerId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "开发者不存在")

    private fun rowOf(d: OpenapiDeveloperDO): Map<String, Any?> = linkedMapOf(
        "developer_id" to d.developerId,
        "company_id" to d.companyId,
        "app_key" to d.appKey,
        "name" to d.name,
        "remark" to d.remark,
        "status" to d.status,
        // 出站地址在后台列表也只出到 host：这一列常常是对端内网地址，进日志的人不该看见它
        "external_base_uri" to hostOnly(d.externalBaseUri),
        "external_app_key" to d.externalAppKey,
        // **出站密钥永不出**：连改档案之后也不回显（A3 的响应里没有这一键）
        "created" to d.created,
        "updated" to d.updated,
    )

    private fun row(r: com.ecshopx.kotlin.dao.openapi.OpenapiDeveloperRow): Map<String, Any?> = linkedMapOf(
        "developer_id" to r.developerId,
        "company_id" to r.companyId,
        "app_key" to r.appKey,
        "name" to r.name,
        "remark" to r.remark,
        "status" to r.status,
        "external_base_uri" to hostOnly(r.externalBaseUri),
        "external_app_key" to r.externalAppKey,
        "created" to r.created,
        "updated" to r.updated,
    )

    /** 脱敏到 host：`https://erp.example.com/openapi/receive?k=1` → `erp.example.com`；解析不出来就整体打星 */
    private fun hostOnly(v: String?): String? {
        val text = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val host = runCatching { URI(text).host }.getOrNull()?.takeIf { it.isNotEmpty() }
        return host ?: "*".repeat(text.length.coerceAtMost(12))
    }

    /** 出站地址必须带 scheme 与 host：没有它就没有能签名投递的目标（WH-A2 同一条判据） */
    private fun validateOutboundUri(v: String?): String? {
        val text = trimToNull(v) ?: return null
        val u = runCatching { URI(text) }.getOrNull()
        val scheme = u?.scheme?.lowercase()
        if (scheme == null || u.host.isNullOrBlank()) throw BizException(ErrorCode.VALIDATION, "external_base_uri 必须是带 host 的 http/https 地址")
        if (scheme != "http" && scheme != "https") throw BizException(ErrorCode.VALIDATION, "external_base_uri 只支持 http/https")
        if (text.length > OpenapiDeveloperForm.MAX_URL) throw BizException(ErrorCode.VALIDATION, "external_base_uri 超长")
        return text
    }

    private fun requireText(v: String?, field: String): String {
        val t = trimToNull(v) ?: throw BizException(ErrorCode.VALIDATION, "$field 必填", mapOf(field to listOf("不能为空")))
        if (t.length > OpenapiDeveloperForm.MAX_NAME) throw BizException(ErrorCode.VALIDATION, "$field 超长")
        return t
    }

    private fun trimToNull(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() }
}
