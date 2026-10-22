package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.systemlink.SystemLinkInboundService
import com.ecshopx.kotlin.service.systemlink.SystemLinkSettingForm
import com.ecshopx.kotlin.service.systemlink.SystemLinkSettingService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * SystemLink 入站与对接设置（M2-2 §27.1/27.3）。
 *
 * 与 `OpenapiGatewayController` 同一类东西：入站那三条是**机器对机器的匿名面**，
 * 认的是签名（两条链两支语义），不是任何人的令牌；后台那四条在 `/api/setting/systemlink`
 * 与 `/api/systemlink/logs` 下，走运营 JWT。
 *
 * 安全链只放行**入站那三个路径形态**（`/api/systemlink/openapi/{method}`、
 * `/api/systemlink/ome`、`/api/systemlink/ome/{method}`），不是整个
 * `/api/systemlink` 前缀 —— 因为 `SL-A3` 的
 * `/api/systemlink/logs` 就长在同一个前缀下。放行整段前缀等于把调用留痕（里面是
 * 对端发来的全部业务参数）挂到公网上，而症状是"列表页 200 但数据不对"，没人会往安全配置上想。
 *
 * 出参是裸 `Map`：信封由服务拼（`{rsp, code, err_msg, data}`，`data` 是 JSON **字符串**），
 * 不能被站内的 `{data:…}` 包络再套一层 —— `ApiEnvelopeAdvice` 只替换 `null`，非 null 原样透出。
 */
@RestController
class SystemLinkController(
    private val inbound: SystemLinkInboundService,
    private val settings: SystemLinkSettingService,
    private val mapper: ObjectMapper,
) {

    /** SL-G1：站点开通/关闭/续费/查询（签名 A） */
    @PostMapping("/api/systemlink/openapi/{method}")
    fun openapi(
        @PathVariable("method") method: String,
        @RequestParam params: Map<String, String>,
        @RequestBody(required = false) body: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> = inbound.openapi(method, merge(params, body), ipOf(request))

    /**
     * SL-G2 / SL-G3：OME 入站（签名 B + IP 白名单）。
     * 两条路径一个方法：源系统就是 `POST /ome` 与 `POST /ome/{method}` 两条注册、
     * 同一个 `Verify@omeApi` 分发函数 —— 写两套迟早漂开，而漂移的症状是"换个写法就报签名错误"。
     */
    @RequestMapping(value = ["/api/systemlink/ome", "/api/systemlink/ome/{method}"], method = [org.springframework.web.bind.annotation.RequestMethod.POST])
    fun ome(
        @PathVariable(name = "method", required = false) pathMethod: String?,
        @RequestParam params: Map<String, String>,
        @RequestBody(required = false) body: String?,
        request: HttpServletRequest,
    ): Map<String, Any?> = inbound.ome(pathMethod, merge(params, body), ipOf(request))

    /** SL-A1 对接设置（两把 token 恒掩码） */
    @RequiresResource("systemlink.lists")
    @GetMapping("/api/setting/systemlink")
    fun getSetting(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(settings.get(cid(p)))

    /** SL-A2 保存设置；token 传空 = 不改（掩码出参没法被抄回去再存一遍） */
    @Audited(route = "systemlink.setting.save")
    @RequiresResource("systemlink.update")
    @PutMapping("/api/setting/systemlink")
    fun saveSetting(
        @RequestBody form: SystemLinkSettingForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(settings.save(cid(p), form))

    /** SL-A3 调用留痕列表：**不出** `params`/`result` 两个 CLOB */
    @RequiresResource("systemlink.lists")
    @GetMapping("/api/systemlink/logs")
    fun logs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(inbound.listLogs(cid(p), raw))

    /** SL-A4 留痕详情：带原文（排障只有这一条路） */
    @RequiresResource("systemlink.lists")
    @GetMapping("/api/systemlink/log/{id}")
    fun logDetail(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(inbound.logDetail(cid(p), id))

    // ===================== 入参归一 =====================

    /**
     * query ∪ body 的扁平全集（源系统 `$request->toArray()` 就是这个形状）。
     *
     * 数字节点保留**字面量文本**（`1` 与 `1.0` 是两个不同的串，PHP 侧全程按字符串拼签），
     * 布尔保留布尔（两支 `assemble` 对它的处理不同，折成字符串就再也分不出来了）。
     */
    private fun merge(query: Map<String, String>, body: String?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        query.forEach { (k, v) -> out[k] = v }
        if (!body.isNullOrBlank()) {
            val node = runCatching { mapper.readTree(body) }.getOrNull()
            if (node != null && node.isObject) node.fields().forEach { (k, v) -> out[k] = plain(v) }
        }
        return out
    }

    private fun plain(n: JsonNode): Any? = when {
        n.isNull -> null
        n.isObject -> n.fields().asSequence().associate { (k, v) -> k to plain(v) }
        n.isArray -> n.map { plain(it) }
        n.isBoolean -> n.asBoolean()
        n.isNumber -> n.asText()
        else -> n.asText()
    }

    /** 取真实来源 IP（`X-Forwarded-For` 首个，网关/反代场景；没有就 socket 地址） */
    private fun ipOf(request: HttpServletRequest): String? {
        val fwd = request.getHeader("X-Forwarded-For")
        if (!fwd.isNullOrBlank()) return fwd.split(',')[0].trim()
        return request.remoteAddr
    }

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
