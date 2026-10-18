package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.DistributorForm
import com.ecshopx.kotlin.service.distribution.DistributorReviewForm
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.distribution.DistributorService
import com.ecshopx.kotlin.service.distribution.DistributorStatusForm
import com.ecshopx.kotlin.service.distribution.DistributorSubjectForm
import com.ecshopx.kotlin.service.distribution.ShopItemConfigForm
import com.ecshopx.kotlin.service.distribution.ShopItemDeleteForm
import com.ecshopx.kotlin.service.distribution.ShopItemsForm
import com.ecshopx.kotlin.service.distribution.ShopItemService
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
 * DS-A1..A8 + SI-A1..A4 后台店铺与店铺商品（docs/04 §18.2，前缀 `/api`）。
 *
 * 权限码分四条（`lists`/`update`/`review`/`status`）不是形式主义：
 * "能改档案"不等于"能自己过审"，"能过审"也不等于"能关店"（§18.7）。
 * 关店（DS-A6）与结算同理，各自一条码。
 *
 * 每一条都带 [DistributorScope]：admin 看全场，分销商账号只看绑定的店，
 * 越界的显式 id 直接 404（§18.8 门槛用例②）。
 */
@RestController
class DistributorAdminController(private val distributors: DistributorService) {

    /** DS-A1 建店 */
    @Audited(route = "distributor.update")
    @RequiresResource("distributor.update")
    @PostMapping("/api/distributor")
    fun create(
        @RequestBody form: DistributorForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(distributors.create(cid(p), form))

    /** DS-A2 店铺列表（行带订单数/未结佣金/可提现余额） */
    @RequiresResource("distributor.lists")
    @GetMapping("/api/distributors")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(distributors.list(cid(p), raw, scope(p)))

    /** DS-A3 店铺详情（`?distributor_id=`，与源系统同一形态：id 走查询串不走路径） */
    @RequiresResource("distributor.lists")
    @GetMapping("/api/distributors/info")
    fun info(
        @RequestParam(required = false, name = "distributor_id") distributorId: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(distributors.detail(cid(p), id(distributorId), scope(p)))

    /** DS-A4 更新档案（审核位/类型/租户一列都不碰） */
    @Audited(route = "distributor.update")
    @RequiresResource("distributor.update")
    @PutMapping("/api/distributor/{distributor_id}")
    fun update(
        @PathVariable("distributor_id") distributorId: String,
        @RequestBody form: DistributorForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(distributors.update(cid(p), id(distributorId), form, scope(p)))

    /** DS-A5 审核 */
    @Audited(route = "distributor.review")
    @RequiresResource("distributor.review")
    @PutMapping("/api/distributor/{distributor_id}/review")
    fun review(
        @PathVariable("distributor_id") distributorId: String,
        @RequestBody form: DistributorReviewForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val v = form.reviewStatus
            ?: throw BizException(ErrorCode.VALIDATION, "review_status 必填", mapOf("review_status" to listOf("不能为空")))
        return ApiResponse.ok(distributors.review(cid(p), id(distributorId), v, scope(p)))
    }

    /** DS-A6 运营开关（true/false/closed，closed 要过"先结后关"） */
    @Audited(route = "distributor.status")
    @RequiresResource("distributor.status")
    @PutMapping("/api/distributor/{distributor_id}/status")
    fun status(
        @PathVariable("distributor_id") distributorId: String,
        @RequestBody form: DistributorStatusForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val v = form.isValid
            ?: throw BizException(ErrorCode.VALIDATION, "is_valid 必填", mapOf("is_valid" to listOf("不能为空")))
        return ApiResponse.ok(distributors.updateValid(cid(p), id(distributorId), v, scope(p)))
    }

    /** DS-A7 收款主体与平台服务费率 */
    @Audited(route = "distributor.update")
    @RequiresResource("distributor.update")
    @PutMapping("/api/distributor/{distributor_id}/payment-subject")
    fun subject(
        @PathVariable("distributor_id") distributorId: String,
        @RequestBody form: DistributorSubjectForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(distributors.updateSubject(cid(p), id(distributorId), form, scope(p)))

    /** DS-A8 店铺统计 */
    @RequiresResource("distributor.lists")
    @GetMapping("/api/distributor/count/{distributor_id}")
    fun count(
        @PathVariable("distributor_id") distributorId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(distributors.stats(cid(p), id(distributorId), scope(p)))

    private fun id(raw: String?): Long =
        raw?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}

/**
 * SI-A1..A4 店铺商品（挂品 / 双态列表 / 配置 / 取消挂品）。
 *
 * 与店铺档案分成两个权限码（`items_lists`/`items_update`）：挂品改价是店铺运营的活，
 * 开店过审是平台招商的活，源系统也确实是两个岗。
 */
@RestController
class ShopItemAdminController(private val shopItems: ShopItemService) {

    /** SI-A1 挂品（按 (店, 商品) upsert） */
    @Audited(route = "distributor.items_update")
    @RequiresResource("distributor.items_update")
    @PostMapping("/api/distributor/items")
    fun save(
        @RequestBody form: ShopItemsForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(shopItems.saveItems(cid(p), form, scope(p)))

    /** SI-A2 店铺商品列表（店铺态 + 总部态两组） */
    @RequiresResource("distributor.items_lists")
    @GetMapping("/api/distributor/items")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(shopItems.list(cid(p), raw, scope(p)))

    /** SI-A3 配置价格/库存/上下架 */
    @Audited(route = "distributor.items_update")
    @RequiresResource("distributor.items_update")
    @PutMapping("/api/distributors/item")
    fun config(
        @RequestBody form: ShopItemConfigForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(shopItems.updateConfig(cid(p), form, scope(p)))

    /** SI-A4 取消挂品 */
    @Audited(route = "distributor.items_update")
    @RequiresResource("distributor.items_update")
    @DeleteMapping("/api/distributor/items")
    fun delete(
        @RequestBody form: ShopItemDeleteForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(shopItems.delete(cid(p), form, scope(p)))

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
