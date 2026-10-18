package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.GroupsForm
import com.ecshopx.kotlin.service.promotion.GroupsService
import com.ecshopx.kotlin.service.promotion.GroupsStatusForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * GP-A1..A6 后台拼团（docs/04 §14.2，前缀 `/api`，运营 JWT + `@RequiresResource`，写路径 `@Audited`）。
 *
 * 两条口径：
 *  1. **停用与强制结束是一条端点两个开关**（GP-A5）：`disabled` 只关门（在途的团继续按时间结算），
 *     `end=true` 立即把进行中的团全部判失败并走 M1-1 退款链退钱（PHP `finish/{id}` 的语义）；
 *  2. **活动库存只加不减**（GP-A4）：减下来等于把已成团的货退没，运营要收回额度请停用活动而不是改小 `store`。
 */
@RestController
class GroupsAdminController(private val groups: GroupsService) {

    /** GP-A1 建拼团活动 */
    @Audited(route = "promotions.groups.update")
    @RequiresResource("promotions.groups.update")
    @PostMapping("/api/groups/activities")
    fun create(
        @RequestBody form: GroupsForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(groups.save(cid(p), form.copy(groupsActivityId = null)))

    /** GP-A2 活动列表 */
    @RequiresResource("promotions.groups.lists")
    @GetMapping("/api/groups/activities")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(groups.list(cid(p), raw))

    /** GP-A3 详情 + 团统计（开了几团、成了几团、砸了几团） */
    @RequiresResource("promotions.groups.lists")
    @GetMapping("/api/groups/activities/{groups_activity_id}")
    fun detail(
        @PathVariable("groups_activity_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(groups.detail(cid(p), id(id)))

    /** GP-A4 编辑（`store` 只允许加） */
    @Audited(route = "promotions.groups.update")
    @RequiresResource("promotions.groups.update")
    @PutMapping("/api/groups/activities/{groups_activity_id}")
    fun update(
        @PathVariable("groups_activity_id") id: String,
        @RequestBody form: GroupsForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(groups.save(cid(p), form.copy(groupsActivityId = id(id))))

    /** GP-A5 停用 / 强制结束（`end=true` = 未成团队立即失败退款） */
    @Audited(route = "promotions.groups.update")
    @RequiresResource("promotions.groups.update")
    @PutMapping("/api/groups/activities/{groups_activity_id}/status")
    fun status(
        @PathVariable("groups_activity_id") id: String,
        @RequestBody form: GroupsStatusForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        if (form.disabled == null && form.end != true) {
            throw BizException(ErrorCode.VALIDATION, "disabled / end 至少要给一个", mapOf("status" to listOf("disabled 与 end 不能同时为空")))
        }
        return ApiResponse.ok(groups.status(cid(p), id(id), form))
    }

    /** GP-A6 团列表（`act_id` / `team_status` 过滤，分页） */
    @RequiresResource("promotions.groups.lists")
    @GetMapping("/api/groups/teams")
    fun teams(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(groups.teamList(cid(p), raw))

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "拼团活动不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
