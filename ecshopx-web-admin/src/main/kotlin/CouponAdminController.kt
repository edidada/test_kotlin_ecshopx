package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.CouponForm
import com.ecshopx.kotlin.service.promotion.CouponSendForm
import com.ecshopx.kotlin.service.promotion.CouponService
import com.ecshopx.kotlin.service.promotion.CouponStatusForm
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
 * CP-A1..A8 后台优惠券（docs/04 §14.2，前缀 `/api`，运营 JWT + `@RequiresResource`，写路径 `@Audited`）。
 *
 * 三条口径：
 *  1. **`promotions.coupon.lists` / `.update` / `.send` 三个权限码分开**：能看报表的人不该能建券，
 *     更不该能把券直接发到会员账上（源系统就是三个 route alias，这里原样隔开）；
 *  2. 建券与改券同一个方法、同一个入参体（CP-A1/CP-A4）：差别只在 `coupon_id` 有没有给，
 *     校验只有一份，不会出现"新建校验严、编辑能绕过"的洞；
 *  3. `company_id` 永远取自令牌：券模板是租户资产，跨租户既查不到也发不出。
 */
@RestController
class CouponAdminController(private val coupons: CouponService) {

    /** CP-A1 建券 */
    @Audited(route = "promotions.coupon.update")
    @RequiresResource("promotions.coupon.update")
    @PostMapping("/api/coupons")
    fun create(
        @RequestBody form: CouponForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(coupons.save(cid(p), form.copy(couponId = null)))

    /** CP-A2 券列表（带发放/锁定/已用/过期四计数） */
    @RequiresResource("promotions.coupon.lists")
    @GetMapping("/api/coupons")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(coupons.list(cid(p), raw))

    /** CP-A3 券详情 */
    @RequiresResource("promotions.coupon.lists")
    @GetMapping("/api/coupons/{coupon_id}")
    fun detail(
        @PathVariable("coupon_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(coupons.detail(cid(p), id(id)))

    /** CP-A4 编辑（面额/门槛改动不回溯已发出的券） */
    @Audited(route = "promotions.coupon.update")
    @RequiresResource("promotions.coupon.update")
    @PutMapping("/api/coupons/{coupon_id}")
    fun update(
        @PathVariable("coupon_id") id: String,
        @RequestBody form: CouponForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val cid = cid(p)
        return ApiResponse.ok(coupons.save(cid, form.copy(couponId = id(id))))
    }

    /** CP-A5 停发 / 恢复 */
    @Audited(route = "promotions.coupon.update")
    @RequiresResource("promotions.coupon.update")
    @PutMapping("/api/coupons/{coupon_id}/status")
    fun status(
        @PathVariable("coupon_id") id: String,
        @RequestBody form: CouponStatusForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val v = form.status ?: throw BizException(ErrorCode.VALIDATION, "status 必填", mapOf("status" to listOf("不能为空")))
        return ApiResponse.ok(coupons.setStatus(cid(p), id(id), v))
    }

    /** CP-A6 删除（已发放过的券只能停发） */
    @Audited(route = "promotions.coupon.update")
    @RequiresResource("promotions.coupon.update")
    @DeleteMapping("/api/coupons/{coupon_id}")
    fun delete(
        @PathVariable("coupon_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(coupons.delete(cid(p), id(id)))

    /** CP-A7 领取/使用记录 */
    @RequiresResource("promotions.coupon.lists")
    @GetMapping("/api/coupons/{coupon_id}/records")
    fun records(
        @PathVariable("coupon_id") id: String,
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(coupons.records(cid(p), id(id), raw))

    /** CP-A8 后台发券：点名发 / 按等级发 / 按定向活动的等级→券映射发 */
    @Audited(route = "promotions.coupon.send")
    @RequiresResource("promotions.coupon.send")
    @PostMapping("/api/coupons/send")
    fun send(
        @RequestBody form: CouponSendForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(coupons.send(cid(p), form))

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "优惠券不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
