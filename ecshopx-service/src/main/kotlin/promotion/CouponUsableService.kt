package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.service.cart.CartService
import com.ecshopx.kotlin.service.cart.PreviewForm
import org.springframework.stereotype.Service

/**
 * CP-F4 结算页可用券（docs/04 §14.1）。
 *
 * 单独成类而不是塞进 [CouponService]，是为了保住依赖方向：
 * 本类要算价（`CartService`），而 `CartService` 的算价链又要读券（#56 的 CouponRule）。
 * 两者互相依赖会让 Spring 起不来，所以"用券判定"这层单向站在购物车之上，
 * 判据本身（门槛/过期/抵扣额）仍然只有 [CouponService.usable] 一份。
 *
 * 两条口径：
 *  1. 门槛比的是**组内商品应付**（`cart_total_price`，会员折让与满减之后、券之前），运费不参与（§14.5）；
 *  2. 一单多店铺时全部券都不可用并给出原因 —— M0-6 的 `groupCount>1` 直接拒单，
 *     这里提前在结算页说清楚，而不是等用户点"提交"才吃 422。
 */
@Service
class CouponUsableService(
    private val carts: CartService,
    private val coupons: CouponService,
) {

    fun usable(userId: Long, companyId: Long, form: PreviewForm): Map<String, Any?> {
        val q = if (form.cartType == "fastbuy" || form.cartType == "offline") {
            carts.quoteFromEntries(userId, companyId, form.entries(), null, false)
        } else {
            carts.quoteFromCart(userId, companyId, form.shopId ?: form.distributorId, null, false)
        }
        val now = TimeSec.now()
        val groups = q.ctx.groups.filter { it.cartTotalCount > 0 }
        val multiShop = groups.size > 1
        val hold = coupons.available(companyId, userId, now)

        val usable = mutableListOf<Map<String, Any?>>()
        val unusable = mutableListOf<Map<String, Any?>>()
        hold.forEach { c ->
            // 一单一店才有"这张券抵多少"的确定答案；多店时不猜店，直接判不可用
            val g = if (multiShop) null else groups.firstOrNull()
            val payFee = g?.cartTotalPrice ?: 0L
            val reason = if (multiShop) "本单包含多个店铺，优惠券只支持单店铺结算" else CouponCheck.usable(c, payFee, now)
            if (reason == null) {
                usable += CouponCheck.viewOf(c, CouponCheck.deductOf(c, payFee))
            } else {
                unusable += LinkedHashMap(CouponCheck.viewOf(c, 0L)).apply { put("reason", reason) }
            }
        }
        return linkedMapOf(
            "usable" to usable,
            "unusable" to unusable,
            "cart_total_price" to payFeeOf(groups, multiShop),
            "shop_count" to groups.size,
        )
    }

    private fun payFeeOf(groups: List<com.ecshopx.kotlin.service.cart.PriceGroup>, multiShop: Boolean): Long =
        if (multiShop) 0L else groups.firstOrNull()?.cartTotalPrice ?: 0L
}
