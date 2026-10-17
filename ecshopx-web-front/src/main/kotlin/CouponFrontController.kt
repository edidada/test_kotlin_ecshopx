package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.cart.PreviewForm
import com.ecshopx.kotlin.service.promotion.CouponReceiveForm
import com.ecshopx.kotlin.service.promotion.CouponService
import com.ecshopx.kotlin.service.promotion.CouponUsableService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * CP-F1..F4 会员面优惠券（docs/04 §14.1，前缀 `/api/h5app/wxapp`，鉴权=会员 JWT）。
 *
 * 三条口径：
 *  1. `user_id`/`company_id` 一律取自令牌：券实例的归属写在行上，
 *     别人的券码在 DAO 层就是 0 行（[CouponService.byCodeAndUser]），不泄露券码是否存在；
 *  2. 领取失败的原因与列表上的 `can_receive/reason` **同一份判据**（服务层 `receiveBlock`），
 *     前端照着列表渲染，不会出现"显示可领、点了报限领"；
 *  3. CP-F4 可用券与 ORDER-1 走同一条算价链取"组内应付"，
 *     门槛判定因此与真实下单完全一致（§14.5）。
 */
@RestController
class CouponFrontController(
    private val coupons: CouponService,
    private val usable: CouponUsableService,
) {

    /** CP-F1 可领券列表 */
    @GetMapping("/api/h5app/wxapp/coupons")
    fun open(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(coupons.openList(userId(p), cid(p), raw))

    /** CP-F2 领取 */
    @PostMapping("/api/h5app/wxapp/coupons/receive")
    fun receive(
        @RequestBody form: CouponReceiveForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val id = form.couponId ?: throw BizException(ErrorCode.VALIDATION, "coupon_id 必填", mapOf("coupon_id" to listOf("不能为空")))
        return ApiResponse.ok(coupons.receive(userId(p), cid(p), id))
    }

    /** CP-F3 我的券 */
    @GetMapping("/api/h5app/wxapp/coupons/my")
    fun mine(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(coupons.myList(userId(p), cid(p), raw))

    /** CP-F4 结算页可用券（入参与 ORDER-1 同） */
    @GetMapping("/api/h5app/wxapp/coupons/usable")
    fun usable(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(usable.usable(userId(p), cid(p), formOf(raw)))

    /**
     * GET 查询串 → [PreviewForm]。字段与 ORDER-1 同名同语义，
     * `items` 用 `item_id:num` 逗号串传（结算页可用券是轻量查询，不开 JSON body）。
     */
    private fun formOf(raw: Map<String, String>): PreviewForm {
        val items = raw["items"]?.split(',')?.mapNotNull { s ->
            val parts = s.trim().split(':')
            val id = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            com.ecshopx.kotlin.service.cart.CartItemNum(id, parts.getOrNull(1)?.toLongOrNull() ?: 1)
        }
        return PreviewForm(
            cartType = raw["cart_type"]?.takeIf { it.isNotBlank() },
            items = items?.takeIf { it.isNotEmpty() },
            itemId = raw["item_id"]?.toLongOrNull(),
            itemNum = raw["item_num"]?.toLongOrNull(),
            shopId = raw["shop_id"]?.toLongOrNull(),
            distributorId = raw["distributor_id"]?.toLongOrNull(),
        )
    }

    private fun userId(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
