package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §2.4 抄录的订单出参字段清单（M0-6）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object OrderContract {

    /** ORDER-2 创建订单 data */
    val CREATE = setOf("order_id", "pay_type", "order_type", "team_id", "total_fee")

    /** ORDER-4 列表行：主要列 + distributor{} + items[] */
    val ROW = setOf(
        "order_id", "title", "user_id", "shop_id", "order_status", "order_status_des", "pay_status",
        "delivery_status", "cancel_status", "item_fee", "freight_fee", "discount_fee", "member_discount",
        "total_fee", "pay_type", "mobile", "receiver_name", "receiver_mobile", "create_time",
        "distributor", "items",
    )

    /** 列表行里的 items[] 摘要 */
    val ITEM_BRIEF = setOf(
        "id", "order_id", "item_id", "item_name", "pic", "num", "price", "total_fee",
    )

    val DISTRIBUTOR = setOf("distributor_id", "name", "logo")

    /** ORDER-5 详情 data */
    val DETAIL = setOf(
        "orderInfo", "items", "order_status_des", "order_status_msg",
        "distributor", "tradeInfo", "cancelData", "logistics_items", "operate_logs",
    )

    /** ORDER-5 orderInfo（文档逐列抄录） */
    val ORDER_INFO = setOf(
        "order_id", "title", "user_id", "item_fee", "freight_fee", "total_fee", "pay_fee", "order_status",
        "pay_status", "delivery_status", "cancel_status", "receipt_type", "ziti_code", "ziti_status",
        "receiver_name", "receiver_mobile", "receiver_state", "receiver_city", "receiver_district",
        "receiver_address", "member_discount", "coupon_discount", "discount_fee", "discount_info",
        "point", "point_use", "point_fee", "get_points", "pay_type", "remark", "invoice",
        "fee_type", "fee_rate", "fee_symbol", "auto_cancel_time", "auto_finish_time", "end_time",
        "create_time", "update_time", "type", "order_class", "order_source", "salesman_id",
        "distributor_id", "shop_id", "delivery_corp", "delivery_code", "delivery_img", "delivery_time",
        "is_rate", "team_id",
    )

    /** ORDER-5 items[] 全列 */
    val ITEM_FULL = setOf(
        "id", "order_id", "item_id", "item_bn", "item_name", "pic", "num", "price", "total_fee", "item_fee",
        "cost_fee", "templates_id", "rebate", "item_unit", "member_discount", "coupon_discount",
        "discount_fee", "delivery_status", "aftersales_status", "refunded_fee", "item_spec_desc",
        "order_item_type", "share_points", "point_fee", "get_points",
    )

    /** ORDER-5 logistics_items[] 包裹行 */
    val LOGISTICS = setOf(
        "orders_delivery_id", "order_id", "delivery_corp", "delivery_corp_name", "delivery_code",
        "package_type", "delivery_time", "delivery_pics", "items",
    )

    /** ORDER-8 角标 data */
    val COUNTS = setOf(
        "notpay", "pending_delivery", "pending_receive", "done", "evaluation", "cancel", "total",
    )
}
