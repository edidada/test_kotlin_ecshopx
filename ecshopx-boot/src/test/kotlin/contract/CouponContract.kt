package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §14.1 / §14.2 抄录的优惠券字段清单（M1-3 CP-*）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object CouponContract {

    /** CP-F1 可领券列表行 */
    val OPEN_ROW = setOf(
        "coupon_id", "coupon_name", "coupon_type", "discount_fee", "min_order_fee",
        "valid_text", "total_num", "sent_num", "received_num", "can_receive", "reason",
    )

    /** CP-F2 领取出参 */
    val RECEIVE = setOf("coupon_user_id", "coupon_code", "expire_time")

    /** CP-F3 我的券行（券实例快照） */
    val MY_ROW = setOf(
        "coupon_user_id", "coupon_id", "coupon_code", "coupon_name", "coupon_type",
        "discount_fee", "min_order_fee", "threshold_fee", "status", "status_msg",
        "source", "receive_time", "use_time", "lock_time", "expire_time",
    )

    /** CP-F4 出参外层 */
    val USABLE_ENVELOPE = setOf("usable", "unusable", "cart_total_price", "shop_count")

    /** CP-F4 usable[] 行 */
    val USABLE_ROW = setOf(
        "coupon_user_id", "coupon_code", "coupon_name", "discount_fee", "threshold_fee", "deduct_fee",
    )

    /** CP-A2/A3 券模板行 */
    val TEMPLATE_ROW = setOf(
        "coupon_id", "coupon_type", "coupon_name", "discount_fee", "min_order_fee",
        "total_num", "sent_num", "get_limit", "valid_type", "status", "valid_text",
        "received", "locked", "used", "expired", "created", "updated",
    )

    /** CP-A3 详情在模板行之外追加的有效期明细 */
    val TEMPLATE_DETAIL = TEMPLATE_ROW + setOf("instruction", "begin_time", "end_time", "valid_days")

    /** CP-A7 记录行：券实例 + 归属会员 + 昵称 + 用在哪一单 */
    val RECORD_ROW = MY_ROW + setOf("user_id", "user_nick", "order_id")

    /** CP-A8 发券出参 */
    val SEND = setOf("total", "sent_num", "sent", "failed")
}
