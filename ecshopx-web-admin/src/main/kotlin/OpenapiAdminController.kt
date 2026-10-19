package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.openapi.OpenapiDeveloperForm
import com.ecshopx.kotlin.service.openapi.OpenapiDeveloperService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * OP-A1..A6 开放平台开发者档案（docs/04 §25.3，前缀 `/api/setting/openapi`）。
 *
 * 前缀为什么不是 `/api/openapi`：网关那条链对整个 `/api/openapi` 前缀 `permitAll`（它的鉴权由
 * `app_key + sign` 自己承担），而这一组接口要认运营令牌。两者放在同一个前缀下就必须写豁免表，
 * 而豁免表在安全匹配器里是"链的位次"级别的坑（C174）—— 错开前缀之后，路径本身就是判据。
 *
 * 两条权限码（§25.5）：`openapi.lists` 读，`openapi.update` 写。
 * seed 里 **role 2 一条都不配**：开发者密钥与推送地址是整个租户对外的口子，
 * 店铺运营连"看有哪些密钥"都不该有（D57/C167 那条划分的延伸）。
 */
@RestController
class OpenapiAdminController(private val developers: OpenapiDeveloperService) {

    /** OP-A1 开发者列表（`app_secret` 天生不在列清单里，见 `OpenapiMappers` 第 2 条纪律） */
    @RequiresResource("openapi.lists")
    @GetMapping("/api/setting/openapi/developers")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(developers.list(cid(p), raw))

    /** OP-A2 新建：服务端发 `app_key`/`app_secret`，**只有这一条响应带得出 secret** */
    @Audited(route = "openapi.create")
    @RequiresResource("openapi.update")
    @PostMapping("/api/setting/openapi/developer")
    fun create(
        @RequestBody form: OpenapiDeveloperForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(developers.create(cid(p), form))

    /** OP-A3 改档案与出站三列 + 启停；`app_key` 不可改 */
    @Audited(route = "openapi.update")
    @RequiresResource("openapi.update")
    @PutMapping("/api/setting/openapi/developer/{id}")
    fun update(
        @PathVariable("id") id: Long,
        @RequestBody form: OpenapiDeveloperForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(developers.update(cid(p), id, form))

    /** OP-A4 重置入向密钥：新值当场出一次，旧值下一个请求即 `E4004`（没有宽限期） */
    @Audited(route = "openapi.reset_secret")
    @RequiresResource("openapi.update")
    @PostMapping("/api/setting/openapi/developer/{id}/reset_secret")
    fun resetSecret(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(developers.resetSecret(cid(p), id))

    /** OP-A5 已注册 method 目录（对接文档面）：只读、来自注册表不来自库 */
    @RequiresResource("openapi.lists")
    @GetMapping("/api/setting/openapi/methods")
    fun methods(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(developers.methods())

    /** OP-A6 调用与投递概况：网关计数（进程内）+ 出站三态（来自表） */
    @RequiresResource("openapi.lists")
    @GetMapping("/api/setting/openapi/developer/{id}/stats")
    fun stats(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(developers.stats(cid(p), id))

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
