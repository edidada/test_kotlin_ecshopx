package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.dao.promotion.CouponStatus
import com.ecshopx.kotlin.dao.promotion.CouponUserDO

/**
 * 券能不能用、能用多少 —— **纯判据**（不查库、不取时间之外的任何输入）。
 *
 * 抽成对象而不是留在 `CouponService` 里，是因为 M1-3 之后有两个调用方：
 *  - CP-F4 结算页可用券列表（`CouponUsableService`）；
 *  - 算价责任链里的 `CouponRule`（它必须是纯函数，见 docs/04 §14.5）。
 * 判据写两份迟早会漂成"列表说能用、下单说不能用"，所以两边都走这里。
 *
 * 两条口径：
 *  1. 门槛比的是**组内商品应付**（会员折让、满减之后，券与积分之前）；运费不参与门槛；
 *  2. 抵扣额 `min(面额, 应付)`：券面额大于应付时按应付抵，不产生负数应付。
 */
object CouponCheck {

    /** 可用性判定，返回 null 表示可以用；否则返回给人看的拒绝原因 */
    fun usable(c: CouponUserDO, groupPayFee: Long, now: Long): String? = when {
        c.status != CouponStatus.UNUSED -> if (c.status == CouponStatus.LOCKED) "该券已被其他订单占用" else "该券当前状态不可用"
        c.expireTime in 1..now -> "该券已过期"
        groupPayFee <= 0 -> "没有可抵扣的商品金额"
        c.minOrderFee > 0 && groupPayFee < c.minOrderFee -> "满 ${yuan(c.minOrderFee)} 元可用，当前 ${yuan(groupPayFee)} 元"
        else -> null
    }

    /** 抵扣额（分）：绝不超过本组应付 */
    fun deductOf(c: CouponUserDO, groupPayFee: Long): Long = c.discountFee.coerceAtMost(groupPayFee.coerceAtLeast(0L))

    /** 档位文案（`coupon_info[].rule` / `discount_info[]` 要说清这张券怎么减的） */
    fun ruleOf(c: CouponUserDO): String =
        if (c.minOrderFee > 0) "满 ${yuan(c.minOrderFee)} 元减 ${yuan(c.discountFee)}" else "无门槛减 ${yuan(c.discountFee)}"

    /**
     * 券实例的展示面（CP-F4 usable[] / 算价出参 coupon_info[] 同一形状）。
     *
     * `id/info/type/rule` 四个键是 docs/04 §14.5 给 ORDER-1 `coupon_info[]` 点名的字段名，
     * 与 CP-F4 的 `coupon_user_id/coupon_name` 指同一件事：结算页两种叫法都要认，
     * 少任何一个都会让前端为同一个值写两处取值代码。
     */
    fun viewOf(c: CouponUserDO, deduct: Long): Map<String, Any?> = linkedMapOf(
        "id" to c.id,
        "coupon_user_id" to c.id,
        "coupon_id" to c.couponId,
        "coupon_code" to c.couponCode,
        "coupon_name" to c.couponName,
        "info" to c.couponName,
        "coupon_type" to c.couponType,
        "type" to "coupon",
        "rule" to ruleOf(c),
        "discount_fee" to c.discountFee,
        "threshold_fee" to c.minOrderFee,
        "min_order_fee" to c.minOrderFee,
        "deduct_fee" to deduct,
        "expire_time" to c.expireTime,
    )
}
