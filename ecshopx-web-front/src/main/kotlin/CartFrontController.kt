package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.cart.CartAddForm
import com.ecshopx.kotlin.service.cart.CartCheckForm
import com.ecshopx.kotlin.service.cart.CartDelForm
import com.ecshopx.kotlin.service.cart.CartNumForm
import com.ecshopx.kotlin.service.cart.CartService
import com.ecshopx.kotlin.service.cart.PreviewForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * CART-1..6 购物车 + ORDER-1 结算预览（M0-5，前台会员面）。
 *
 * 全部端点走 member JWT（SecurityConfig.frontChain 里 h5app 面默认 authenticated），
 * userId/companyId 一律取自令牌，不接受客户端传参（对齐 PHP user() 语义）。
 * 预览与车内取价共用 [CartService.quote]，「预览价==下单价」在这里是同一个函数调用。
 */
@RestController
class CartFrontController(private val carts: CartService) {

    /** CART-1 加购（单件 item_id+num 或批量 items[]），出参 = CART-2 全量购物车 */
    @PostMapping("/api/h5app/wxapp/cart")
    fun add(@RequestBody form: CartAddForm, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(carts.add(userId(p), cid(p), form))

    /** CART-2 购物车列表；shop_id 可选（PHP 侧按店铺分屏时传） */
    @GetMapping("/api/h5app/wxapp/cart")
    fun view(
        @RequestParam(value = "shop_id", required = false) shopId: Long?,
        @RequestParam(value = "cart_type", required = false) cartType: String?,
        @RequestParam(value = "items", required = false) items: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val u = userId(p)
        val c = cid(p)
        // fastbuy：临时行不落库，只按传入 item_id:num 算一版购物车（与 PHP CartServices 同义）
        if (cartType == "fastbuy" && !items.isNullOrBlank()) {
            return ApiResponse.ok(carts.viewFastbuy(u, c, parseFastbuy(items)))
        }
        return ApiResponse.ok(carts.view(u, c, shopId))
    }

    /** CART-3 删除行（cart_id / cart_ids），返回删除后的全量购物车 */
    @DeleteMapping("/api/h5app/wxapp/cartdel")
    fun delete(@RequestBody form: CartDelForm, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(carts.delete(userId(p), cid(p), form))

    /** CART-4 改数量 */
    @PutMapping("/api/h5app/wxapp/cartupdate/num")
    fun num(@RequestBody form: CartNumForm, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> {
        val n = form.num
            ?: throw BizException(ErrorCode.VALIDATION, "num 必填", mapOf("num" to listOf("不能为空")))
        val cartId = form.cartId
            ?: throw BizException(ErrorCode.VALIDATION, "cart_id 必填", mapOf("cart_id" to listOf("不能为空")))
        return ApiResponse.ok(carts.updateNum(userId(p), cid(p), cartId, n))
    }

    /** CART-5 勾选/取消（cart_id | cart_ids | all=true 全车） */
    @PutMapping("/api/h5app/wxapp/cartupdate/checkstatus")
    fun check(@RequestBody form: CartCheckForm, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(carts.updateCheck(userId(p), cid(p), form))

    /** CART-6 角标数量 */
    @GetMapping("/api/h5app/wxapp/cartcount")
    fun count(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(carts.count(userId(p), cid(p)))

    /** ORDER-1 结算预览：cart 模式读车内勾选项，fastbuy/offline 用传入行 */
    @PostMapping("/api/h5app/wxapp/getFreightFee")
    fun preview(@RequestBody form: PreviewForm, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(carts.preview(userId(p), cid(p), form))

    /** "item_id:num,item_id:num"（PHP 前台 fastbuy 传法）→ 行列表 */
    private fun parseFastbuy(raw: String): List<com.ecshopx.kotlin.service.cart.CartItemNum> =
        raw.split(',').mapNotNull { part ->
            val seg = part.trim().split(':')
            val id = seg.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            com.ecshopx.kotlin.service.cart.CartItemNum(id, seg.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: 1)
        }

    private fun userId(p: Principal?): Long =
        p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.", emptyMap())

    private fun cid(p: Principal?): Long =
        p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.", emptyMap())
}
