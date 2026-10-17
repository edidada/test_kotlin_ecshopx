package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.MarketingCheckForm
import com.ecshopx.kotlin.service.promotion.MarketingForm
import com.ecshopx.kotlin.service.promotion.MarketingItemsForm
import com.ecshopx.kotlin.service.promotion.MarketingService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * MK-A1..A7 后台满减/满折（docs/04 §14.2，前缀 `/api`，运营 JWT + `@RequiresResource`，写路径 `@Audited`）。
 *
 * 两条口径：
 *  1. **`promotions.marketing.check` 单独一个权限码**：审核是"让活动开始减钱"的动作，
 *     它比建活动本身更需要分离——配活动的人不该同时是批准它生效的人；
 *  2. 建/改同一个方法、同一份校验（MK-A1/MK-A4）：编辑会由服务层把 `check_status` 打回 `non-reviewed`，
 *     所以"改完还在生效"这条后门在结构上不存在。
 */
@RestController
class MarketingAdminController(private val marketing: MarketingService) {

    /** MK-A1 建活动 */
    @Audited(route = "promotions.marketing.update")
    @RequiresResource("promotions.marketing.update")
    @PostMapping("/api/marketing/activities")
    fun create(
        @RequestBody form: MarketingForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(marketing.save(cid(p), form.copy(marketingId = null)))

    /** MK-A2 活动列表（date_status / check_status / marketing_type 过滤） */
    @RequiresResource("promotions.marketing.lists")
    @GetMapping("/api/marketing/activities")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(marketing.list(cid(p), raw))

    /** MK-A3 活动详情（规则原样回 + 解析后的阶梯） */
    @RequiresResource("promotions.marketing.lists")
    @GetMapping("/api/marketing/activities/{marketing_id}")
    fun detail(
        @PathVariable("marketing_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(marketing.detail(cid(p), id(id)))

    /** MK-A4 编辑（必重审） */
    @Audited(route = "promotions.marketing.update")
    @RequiresResource("promotions.marketing.update")
    @PutMapping("/api/marketing/activities/{marketing_id}")
    fun update(
        @PathVariable("marketing_id") id: String,
        @RequestBody form: MarketingForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(marketing.save(cid(p), form.copy(marketingId = id(id))))

    /** MK-A5 圈品：全量覆盖 */
    @Audited(route = "promotions.marketing.update")
    @RequiresResource("promotions.marketing.update")
    @PutMapping("/api/marketing/activities/{marketing_id}/items")
    fun items(
        @PathVariable("marketing_id") id: String,
        @RequestBody form: MarketingItemsForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(marketing.saveItems(cid(p), id(id), form))

    /** MK-A6 审核：只有 reviewed 参与算价 */
    @Audited(route = "promotions.marketing.check")
    @RequiresResource("promotions.marketing.check")
    @PutMapping("/api/marketing/activities/{marketing_id}/check")
    fun check(
        @PathVariable("marketing_id") id: String,
        @RequestBody form: MarketingCheckForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val v = form.checkStatus
            ?: throw BizException(ErrorCode.VALIDATION, "check_status 必填", mapOf("check_status" to listOf("不能为空")))
        return ApiResponse.ok(marketing.check(cid(p), id(id), v))
    }

    /** MK-A7 删除活动 + 圈品行 */
    @Audited(route = "promotions.marketing.update")
    @RequiresResource("promotions.marketing.update")
    @DeleteMapping("/api/marketing/activities/{marketing_id}")
    fun delete(
        @PathVariable("marketing_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(marketing.delete(cid(p), id(id)))

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "营销活动不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
