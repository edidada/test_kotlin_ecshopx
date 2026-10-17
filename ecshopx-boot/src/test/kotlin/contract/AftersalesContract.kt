package com.ecshopx.kotlin.boot.contract

/**
 * M1-1 售后/退款域出参字段集（抄自 docs/04 §11.1/§11.2/§11.3）。
 * 判定沿用 ContractDiff：缺失即失败，多出仅回显。
 */
object AftersalesContract {

    /** §11.3 列表与详情共用的售后行字段 */
    val AF_ROW = setOf(
        "aftersales_bn", "order_id", "item_id", "item_name", "item_bn", "pic", "num",
        "refund_fee", "refund_point", "aftersales_type", "aftersales_status", "progress",
        "status_msg", "progress_msg", "type_msg", "reason", "description", "refuse_reason", "memo",
        "evidence_pic", "return_type", "contact", "mobile", "freight", "company_id", "user_id",
        "distributor_id", "can_apply", "created", "updated", "sendback_data",
    )

    /** AF-F3 / AF-A2 详情：行字段 + 明细数组 + 退款单 + 订单摘要（+ 后台 buttons） */
    val FRONT_DETAIL = AF_ROW + setOf("detail", "refund_info", "order_info")
    val ADMIN_DETAIL = AF_ROW + setOf("detail", "refund_info", "order_info", "buttons")

    /** aftersales_detail 行（§11.3 detail[]） */
    val DETAIL_ROW = setOf(
        "detail_id", "item_id", "item_name", "pic", "num", "refunded_num", "refund_fee",
        "aftersales_status", "progress",
    )

    /** order_info（§11.3） */
    val ORDER_INFO = setOf("order_id", "order_status", "pay_status", "total_fee", "pay_fee", "item_fee", "freight_fee")

    /** AF-F6 单件可退额（源系统只有 price，本项目把守恒读口径一并给出） */
    val ITEM_PRICE = setOf("price", "num", "applied_num", "can_num", "can_fee")

    /** AF-A1/A8 列表信封 */
    val LIST = setOf("total_count", "list")

    /** §11.3 refund_info（aftersales_refund 行） */
    val REFUND_ROW = setOf(
        "refund_bn", "aftersales_bn", "order_id", "trade_id", "item_id", "company_id",
        "refund_type", "refund_channel", "refund_status", "status_msg",
        "order_fee", "refund_fee", "refunded_fee", "refund_point", "refunded_point", "return_freight",
        "pay_type", "refund_id", "refund_time", "refund_success_time",
        "payee_name", "bank_name", "bank_account", "bank_branch", "bank_province", "bank_city",
        "memo", "created", "updated",
    )

    /** AF-A9 详情：退款行 + 三个守恒读口径 + 售后摘要 */
    val REFUND_DETAIL = REFUND_ROW + setOf("trade_can_refund", "order_refunded_fee", "order_refunded_point", "aftersales")

    /** AF-F1 申请成功的最小断言集（文档列举字段，实际返回整行） */
    val APPLY_MIN = setOf("aftersales_bn", "aftersales_status", "progress", "refund_fee", "refund_point", "num", "item_name", "can_apply")
}
