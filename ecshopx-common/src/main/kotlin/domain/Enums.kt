package com.ecshopx.kotlin.common.domain

/** 业务单号种类；值必须与 seq_generator.seq_name 一致（sql/schema.sql） */
enum class SeqName { ORDER_NO, TRADE_NO, REFUND_NO, AFTERSALES_NO }

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
