package com.ecshopx.kotlin.service.cart

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/** CART-1 单件/批量共用的入参体（docs/04 §2.3） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CartItemNum(
    val itemId: Long? = null,
    val num: Long? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CartAddForm(
    val itemId: Long? = null,
    val num: Long? = null,
    val items: List<CartItemNum>? = null,
    val shopId: Long? = null,
    val shopType: String? = null,
    val activityType: String? = null,
    val activityId: Long? = null,
    val cartType: String? = null,
    /** PHP 侧就是 camelCase，这里双名兼容 */
    @JsonAlias("isAccumulate") val isAccumulate: Boolean? = null,
    @JsonAlias("iscrossborder") val isCrossBorder: Boolean? = null,
    val promoterUserId: Long? = null,
    val distributorId: Long? = null,
    val itemsId: List<Long>? = null,
    val isPlusBuy: Boolean? = null,
) {
    /** 单件与批量归一成一个列表 */
    fun entries(): List<CartItemNum> =
        items?.filter { it.itemId != null && it.itemId > 0 }
            ?: listOfNotNull(itemId?.let { CartItemNum(it, num ?: 1) })
}

/** CART-4 改数量 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CartNumForm(
    val cartId: Long? = null,
    val num: Long? = null,
)

/** CART-5 勾选/取消：cart_ids 缺省且 all=true 时全车改；is_checked 必给 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CartCheckForm(
    val cartId: Long? = null,
    val cartIds: List<Long>? = null,
    val isChecked: Boolean? = null,
    val all: Boolean? = null,
)

/** CART-3 删除：cart_id / cart_ids 两种写法都收 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CartDelForm(
    val cartId: Long? = null,
    val cartIds: List<Long>? = null,
)

/** ORDER-1 预览入参（docs/04 §2.4 公共入参的 M0 子集） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PreviewForm(
    val cartType: String? = null,
    val orderType: String? = null,
    val items: List<CartItemNum>? = null,
    val itemId: Long? = null,
    val itemNum: Long? = null,
    val shopId: Long? = null,
    val distributorId: Long? = null,
    /** 券开关位，M0 不生效 */
    @JsonAlias("coupon_code") val couponCode: String? = null,
    val usePoint: Boolean? = null,
) {
    fun entries(): List<CartItemNum> =
        items?.filter { it.itemId != null && it.itemId > 0 }
            ?: listOfNotNull(itemId?.let { CartItemNum(it, itemNum ?: 1) })
}
