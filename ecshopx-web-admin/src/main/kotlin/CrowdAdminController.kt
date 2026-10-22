package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.CrowdDiscountService
import com.ecshopx.kotlin.service.promotion.CrowdForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * CD-A 人群折扣后台（docs/04 §29.3，前缀 `/api/specific/crowd`，运营 JWT + 权限码 83/84）。
 *
 * 路由按附录照抄（源 `Action\SpecificCrowdDiscount`）：CRUD 四条 + 优惠日志一条。
 * 折扣折让折的是真金白银（改一行配置就改变整条结算链），写路径两面闸：
 * `@Audited` + 只给 `crowd.update` 权限的角色（role 2 不配，§29.3 划分）。
 */
@RestController
class CrowdAdminController(private val crowd: CrowdDiscountService) {

    /** CD-A1 创建配置 */
    @Audited(route = ActivityCodes.CROWD_UPDATE)
    @RequiresResource(ActivityCodes.CROWD_UPDATE)
    @PostMapping("/api/specific/crowd/discount")
    fun create(
        @RequestBody body: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(crowd.save(cid(p), form(body)))

    /** CD-A2 更新配置（编辑不改 id、不重启窗口审计口径） */
    @Audited(route = ActivityCodes.CROWD_UPDATE)
    @RequiresResource(ActivityCodes.CROWD_UPDATE)
    @PutMapping("/api/specific/crowd/discount")
    fun update(
        @RequestBody body: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<String> {
        val id = (body["discount_id"] ?: body["id"])?.toString()?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.VALIDATION, "参数不合法", mapOf("discount_id" to listOf("必填")))
        crowd.update(cid(p), id, form(body))
        return ApiResponse.ok("updated")
    }

    /** CD-A3 配置翻页（`disabled`/`crowd_type` 过滤在服务层入参留给 §30 精读，本批按源系统"全量翻页"） */
    @RequiresResource(ActivityCodes.CROWD_LISTS)
    @GetMapping("/api/specific/crowd/discountList")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> =
        ApiResponse.ok(crowd.adminPage(cid(p), PageQuery.of(raw["page"]?.toIntOrNull(), raw["page_size"]?.toIntOrNull())))

    /** CD-A4 配置详情 */
    @RequiresResource(ActivityCodes.CROWD_LISTS)
    @GetMapping("/api/specific/crowd/discountInfo")
    fun detail(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val id = raw["id"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: raw["discount_id"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.NOT_FOUND, "人群折扣配置不存在")
        return ApiResponse.ok(crowd.adminDetail(cid(p), id))
    }

    /** CD-A5 优惠日志翻页（§29.3：下单事务里落的行为事实，只读） */
    @RequiresResource(ActivityCodes.CROWD_LISTS)
    @GetMapping("/api/specific/crowd/discountLogList")
    fun logs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> =
        ApiResponse.ok(crowd.adminLogs(cid(p), PageQuery.of(raw["page"]?.toIntOrNull(), raw["page_size"]?.toIntOrNull())))

    /** 表单字段全走 `Any?` 宽收：toDo 里逐个窄化，非法值在 validate 里报人话 */
    private fun form(body: Map<String, Any?>): CrowdForm = CrowdForm().apply {
        name = body["name"]
        discount = body["discount"]
        crowdType = body["crowd_type"]
        crowdValue = body["crowd_value"]
        useBound = body["use_bound"]
        itemIds = (body["item_ids"] as? List<*>) ?: emptyList()
        disabled = body["disabled"] as? Boolean
        startTime = (body["start_time"] as? Number)?.toLong() ?: body["start_time"]?.toString()?.toLongOrNull()
        endTime = (body["end_time"] as? Number)?.toLong() ?: body["end_time"]?.toString()?.toLongOrNull()
    }

    private fun cid(p: Principal?): Long =
        (p as? Principal)?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
