package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §14.1 / §14.2 / §14.5 抄录的满减满折字段清单（M1-3 MK-*）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object MarketingContract {

    /** MK-A2 活动行（后台配置面：规则原样回 + 解析后的阶梯 + 派生 date_status） */
    val ACTIVITY_ROW = setOf(
        "marketing_id", "company_id", "marketing_type", "marketing_name", "ad_pic",
        "start_time", "end_time", "release_time", "use_bound", "use_shop", "shop_ids",
        "condition_type", "condition_value", "steps", "canjoin_repeat", "join_limit",
        "free_postage", "promotion_tag", "check_status", "status", "date_status",
        "item_count", "created", "updated",
    )

    /** MK-A3 详情在活动行之外追加圈定商品 */
    val ACTIVITY_DETAIL = ACTIVITY_ROW + setOf("items")

    /** steps[] 单档（分 + 展示文案；`rule` 让后台不用自己拼"满 100 减 20"） */
    val STEP_ROW = setOf("full", "minus", "discount", "rule")

    /** items[] 圈品行 */
    val BOUND_ITEM_ROW = setOf(
        "id", "marketing_id", "item_id", "goods_id", "item_name", "price",
        "promotion_tag", "start_time", "end_time", "status", "is_show",
    )

    /** MK-A5 圈品保存出参 */
    val ITEMS_SAVED = setOf("marketing_id", "items")

    /** MK-A7 删除出参 */
    val DELETED = setOf("marketing_id", "deleted")

    /** MK-F1/F2 前台活动行（只给"看得懂 + 买得到"的字段） */
    val FRONT_ROW = setOf(
        "marketing_id", "marketing_type", "marketing_name", "ad_pic", "promotion_tag",
        "condition_type", "condition_text", "start_time", "end_time", "use_bound",
        "free_postage", "date_status",
    )

    /** MK-F2 详情 */
    val FRONT_DETAIL = FRONT_ROW + setOf("items")

    /** MK-F3 单商品可享的全部营销（`seckill` 键位属 #57，先占住） */
    val ITEM_PROMOTIONS = setOf("item_id", "marketing", "tags", "seckill")

    /** ORDER-1/CART-2 `used_activity[]`：组级命中（含上不封顶倍数与包邮标记） */
    val USED_ACTIVITY = setOf(
        "id", "marketing_id", "type", "name", "rule", "condition_type",
        "discount_fee", "free_postage", "times",
    )

    /** 购物车行 / 预览行 `activity_info[]`：这一行的便宜是谁给的 */
    val LINE_ACTIVITY = setOf("id", "marketing_id", "type", "name", "rule", "discount_fee")

    /** ORDER-1 `discount_info[]` 每项（docs/04 §14.5：type 取 full_minus/full_discount/seckill/coupon/point） */
    val DISCOUNT_INFO = setOf("type", "rule", "discount_fee")

    /** ORDER-1 `coupon_info[]` 每项（§14.5 点名的六个键 + 券实例自身列） */
    val COUPON_INFO = setOf("id", "coupon_code", "info", "type", "rule", "discount_fee")

    /** ORDER-1 里 M1-3 新增/落实的抵扣字段（原有键见 [CartContract.PREVIEW]） */
    val PREVIEW_MARKETING = setOf(
        "coupon_discount", "coupon_info", "coupon_reason", "goods_discount",
        "point_fee", "user_point", "max_point", "limit_point", "point_use",
    )
}
