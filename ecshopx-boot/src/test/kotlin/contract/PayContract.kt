package com.ecshopx.kotlin.boot.contract

/**
 * M0-7 支付域出参字段集（抄自 docs/04 §2.5 + §8 实现期补充）。
 * 判定沿用 ContractDiff：缺失即失败，多出仅回显。
 */
object PayContract {

    /** PAY-1 支付配置 */
    val CONFIG = setOf(
        "order_id", "order_type", "title", "total_fee", "pay_fee", "order_status", "pay_status",
        "trade_state", "trade_id", "pay_types", "deposit", "point", "auto_cancel_time",
        "receipt_type", "shop_id", "distributor_id",
    )
    val DEPOSIT_VIEW = setOf("deposit_amount", "gift_amount", "balance")
    val POINT_VIEW = setOf("point", "can_pay")

    /** PAY-2 结清（point/deposit）：渠道参数 + trade_info + ORDER-5 同构详情 */
    val PAY_SETTLED = setOf(
        "order_id", "trade_id", "trade_state", "pay_type", "total_fee", "pay_fee",
        "trade_info", "order_created", "orderInfo", "items", "logistics_items",
    )

    /** PAY-2 预支付（wxpay mock）：ORDER-3 同形 JSAPI 参数 */
    val PAY_JSAPI = setOf("appId", "timeStamp", "nonceStr", "package", "signType", "paySign")

    /** PAY-3 轮询 */
    val TRADE_STATE = setOf("trade_id", "order_id", "trade_state", "pay_fee", "pay_type", "transaction_id")

    /** PAY-4 充值下单 */
    val RECHARGE = setOf(
        "order_id", "trade_id", "amount", "gift_amount", "rule_name", "trade_state", "pay_types",
    )

    /** PAY-2 充值结清 */
    val RECHARGE_SETTLED = setOf("order_id", "trade_id", "trade_state", "amount", "gift_amount", "pay_type", "deposit")
}
