package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §2.3/§2.4 抄录的购物车与结算预览字段清单（M0-5）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object CartContract {

    /** CART-2 出参 data 外层 */
    val CART_DATA = setOf("valid_cart", "invalid_cart", "is_check_store")

    /** valid_cart / invalid_cart 元素（店铺分组外壳） */
    val SHOP_GROUP = setOf(
        "shop_id", "shop_name", "address", "mobile", "lat", "lng", "hour", "is_ziti", "is_delivery",
        "cart_total_price", "item_fee", "freight", "discount_fee", "total_fee",
        "cart_total_num", "cart_total_count", "member_discount",
        "used_activity", "used_activity_ids", "gift_activity", "plus_buy", "list",
    )

    /** 购物车行（有效与失效同构） */
    val CART_LINE = setOf(
        "cart_id", "company_id", "user_id", "shop_type", "shop_id", "activity_type", "activity_id",
        "item_type", "item_id", "items_id", "item_name", "pics", "price", "num", "is_checked",
        "is_plus_buy", "is_last_price", "discount_fee", "total_fee", "store", "market_price", "brief",
        "approve_status", "item_spec_desc", "parent_id", "goods_id", "item_category", "type",
        "grade_name", "member_discount", "activity_info",
    )

    /** CART-6 角标 */
    val CART_COUNT = setOf("count", "num")

    /** ORDER-1 出参（getOrderFreightFeeInfo 同构） */
    val PREVIEW = setOf(
        "item_fee", "freight_fee", "discount_fee", "discount_info", "coupon_discount", "coupon_info",
        "member_discount", "goods_discount", "point", "point_use", "point_fee", "get_points",
        "bonus_points", "user_point", "max_point", "limit_point", "total_fee", "total_rebate",
        "totalItemNum", "cost_fee", "fee_type", "fee_rate", "fee_symbol", "auto_cancel_time",
        "items", "distributor_id", "pack", "is_logistics",
    )

    /** ORDER-1 items[] 行 */
    val PREVIEW_ITEM = setOf(
        "item_id", "item_bn", "item_name", "pic", "num", "price", "item_fee", "total_fee",
        "discount_fee", "member_discount", "cart_id", "is_checked", "store", "item_spec_desc",
        "approve_status",
    )
}
