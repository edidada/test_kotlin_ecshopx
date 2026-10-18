package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §23.1/§23.2 抄录的评价字段清单（M1-5 RATE 族）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 *
 * 这一族值得钉住的是**同一张表在两面上少了几列**：
 *  - 会员面（[RATE_FRONT_ROW]）没有 `mobile`、没有 `disabled`、没有店铺归属三列 ——
 *    逛商品页的人不需要知道谁的电话，也不需要知道一条评价是被商家下架的；
 *  - 后台面（[RATE_ADMIN_ROW]）反过来必须带 `shop_name/item_name`，
 *    否则客服只拿得到一串 id，没法回答"哪家店的哪个商品被差评了"；
 *  - `order_id` 在会员面上**只对本人那一行出**（别人的订单号 = 泄露他买过什么），
 *    契约测试在这里验的是"键在不在"，值是不是 null 由端到端用例分别验两种身份。
 */
object RateContract {

    /** 分页信封（PageResult 只有这两键） */
    val LIST_ENVELOPE = setOf("total_count", "list")

    /** RATE-F1 提交评价（一次提交可以覆盖多行，`rate_count` 回真正落了几行） */
    val RATE_SUBMIT = setOf("order_id", "rate_count", "is_rate")

    /** RATE-F2/F3/F4 会员面评价行 */
    val RATE_FRONT_ROW = setOf(
        "rate_id", "item_id", "goods_id", "user_id", "star", "content", "rate_pic",
        "anonymous", "is_reply", "nickname", "created", "seller_reply", "order_id",
    )

    /** RATE-A1 后台面评价行 = 会员面的身份/归属/可见位补齐 */
    val RATE_ADMIN_ROW = setOf(
        "rate_id", "order_id", "item_id", "goods_id", "user_id", "nickname",
        "mobile", "mobile_masked", "star", "content", "rate_pic", "anonymous",
        "is_reply", "disabled", "distributor_id", "shop_id", "shop_name", "item_name",
        "created", "seller_reply",
    )

    /** 商家回复子对象（会员面与后台面同一个形状） */
    val SELLER_REPLY = setOf("content", "created")

    /** RATE-F3 评价详情：评价本体 + 全部回复 */
    val RATE_DETAIL = setOf("rate", "replies")

    /** RATE-F3 回复行 */
    val RATE_REPLY_ROW = setOf("role", "content", "created")

    /** RATE-F5 待评价清单：整单已评时 `list` 直接空（不是缺集还挂着） */
    val RATE_PENDING = setOf("order_id", "is_rate", "list")

    /** RATE-F5 待评价行（来自订单行，字段名按商品侧惯例：`goods_name` 而不是 `item_name`） */
    val RATE_PENDING_ROW = setOf(
        "order_id", "id", "item_id", "goods_id", "goods_name", "pics", "spec_desc", "num",
    )

    /** RATE-A2 商家回复出参 */
    val RATE_REPLY_OUT = setOf("rate_id", "is_reply", "content", "created")

    /** RATE-A3 上下架出参 */
    val RATE_DISABLE_OUT = setOf("rate_id", "disabled")
}
