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
    /** 储值充值单（PAY-4），出参 order_id 即形如 CZyyMMddHHmmssNNNN */
    RECHARGE_NO("recharge_no", "CZ"),
    /** 拼团团号（M1-3 GP-F/下单开团），D29：代替 PHP 的 Redis 拼串 */
    GROUP_TEAM_ID("group_team_id", "GT"),
    /** 券码（M1-3 CP-F2 领取时发号，uk_coupon_code 唯一索引兜底） */
    COUPON_CODE("coupon_code", "C"),
    /** 自提取货码（M1-4 §18.6）：号段值取末 6 位成码，非随机；同店不重复即可 */
    ZITI_CODE("ziti_code", ""),

    /**
     * 发票申请单号（M1-5 INV-F1）：形状沿用 [RECHARGE_NO] 那条「前缀 + 时间 + 号段尾」，
     * 不照搬 ZITI_CODE 的末 6 位短码 —— 取货码要在小票上手抄，申请单号要进财务系统对账。
     * schema 里必须同时有 `('invoice_apply_bn', 0)` 那一行，缺行时 SeqGenerator 抛 500（C166）。
     */
    INVOICE_APPLY_BN("invoice_apply_bn", "FP"),
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

/**
 * `orders_normal_orders.order_class`（docs/04 §14.3）：订单**业务类别**，与 `order_type` 正交。
 *
 * `order_type` 仍是 M0 的 normal/service 口径（service 一律 501 的守卫不动），
 * 秒杀与拼团不改订单类型，只改类别 —— 与 PHP 同列同值，砍价（bargain）属 P2。
 */
object OrderClass {
    const val NORMAL = "normal"
    const val SECKILL = "seckill"
    const val GROUPS = "groups"

    val KNOWN = setOf(NORMAL, SECKILL, GROUPS)

    /** 入参归一：未知/空值一律按普通单（`order_class`/`activity_type` 都吃这个口径） */
    fun normalize(v: String?): String = if (v != null && v in KNOWN) v else NORMAL
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
