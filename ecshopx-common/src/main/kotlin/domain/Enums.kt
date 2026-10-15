package com.ecshopx.kotlin.common.domain

/**
 * 业务单号种类；dbValue 必须与 seq_generator.seq_name 一致（sql/schema.sql 小写行），
 * prefix 为源系统单号前缀（order 裸数字 / trade T / refund R / aftersales AS）。
 */
enum class SeqName(val dbValue: String, val prefix: String) {
    ORDER_NO("order_no", ""),
    TRADE_NO("trade_no", "T"),
    REFUND_NO("refund_no", "R"),
    AFTERSALES_NO("aftersales_no", "AS"),
}

/** 订单主状态（大写态，与 PHP order_status/pay_status 字符串枚举一致，概要设计 §3） */
object OrderStatus {
    const val NOTPAY = "NOTPAY"
    const val PAYED = "PAYED"
    const val PACKING = "PACKING"
    const val WAIT_BUYER_CONFIRM = "WAIT_BUYER_CONFIRM"
    const val DONE = "DONE"
    const val CANCEL = "CANCEL"
}

object PayStatus {
    const val NOTPAY = "NOTPAY"
    const val PAYED = "PAYED"
    const val REFUND_PART = "REFUND_PART"
    const val REFUND = "REFUND"
}

object DeliveryStatus {
    const val PENDING = "PENDING"
    const val PART_DONE = "PART_DONE"
    const val PACKED = "PACKED"
    const val DONE = "DONE"
}

object CancelStatus {
    const val NOCANCEL = "NOCANCEL"
    const val CANCEL_APPLY = "CANCEL_APPLY"
    const val CANCEL_AGREE = "CANCEL_AGREE"
    const val REFUSE_CANCEL = "REFUSE_CANCEL"
}
