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

    /**
     * 出站消息号（M2-1 §25.4）：一个业务事件 × 一个订阅一个号，
     * 重试与手动重推**沿用同一个号**（对端靠它判重，换号等于让同一件事被收两次且无从 dedupe）。
     * schema 里必须同时有 `('webhook_msg', 0)` 那一行，缺行时 SeqGenerator 抛 500（C166）。
     */
    WEBHOOK_MSG("webhook_msg", "WH"),

    /**
     * SystemLink 调用留痕号（M2-2 §27.5）：一次入站调用一个号，request 与 response 两行**共用**它。
     * 两行各自发号的话，"这两行是同一次调用"就只能靠时间戳猜，而对端并发推单时猜不出来。
     * schema 里必须同时有 `('oms_msg', 0)` 那一行，缺行时 SeqGenerator 抛 500（C166）。
     */
    OMS_MSG("oms_msg", "OMS"),
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

/**
 * `orders_invoice.invoice_status`（M1-5 `INV-R1`，docs/04 §23.3 第 8 条）。
 *
 * 小写词表是这张表的原状（列默认值就写着 `'pending'`），与上面那几族大写态不同；
 * `orders.invoice_status` 存的是同一套值（申请单状态的镜像，`INV-R2`）。
 * `pending` 可反复改（upsert），`issued`/`rejected` 是终态 —— 再改回 422。
 */
object InvoiceApplyStatus {
    const val PENDING = "pending"
    const val ISSUED = "issued"
    const val REJECTED = "rejected"

    val KNOWN = setOf(PENDING, ISSUED, REJECTED)
}

/**
 * 抬头词表的**唯一映射处**（`INV-R3`）：两张表两套值，本批不合并、不迁移。
 *
 * `members_invoices.invoices_type` 用 `personal/corporate`（抬头本，源系统原状），
 * `orders_invoice.invoice_type` 用 `enterprise/individual`（申请单，源系统原状）。
 * 对外只暴露各自列的词表，混用的代价是历史数据要迁；
 * 在 Service 层做一次映射，DAO 与库里都不出现第三套值。
 */
object InvoiceHeadType {
    const val PERSONAL = "personal"
    const val CORPORATE = "corporate"
    const val ENTERPRISE = "enterprise"
    const val INDIVIDUAL = "individual"

    /** 抬头本侧值域（`INV-F5` 的入参判据） */
    val HEAD_VALUES = setOf(PERSONAL, CORPORATE)

    /** 申请单侧值域（`INV-F1` 的入参判据） */
    val APPLY_VALUES = setOf(ENTERPRISE, INDIVIDUAL)

    /** 申请单侧：`personal → individual`、`corporate → enterprise` */
    fun toApply(v: String): String = if (v == CORPORATE) ENTERPRISE else INDIVIDUAL

    /** 抬头本侧：`enterprise → corporate`、其余按 `personal`（含未知值） */
    fun toHead(v: String?): String = if (v == ENTERPRISE) CORPORATE else PERSONAL
}
