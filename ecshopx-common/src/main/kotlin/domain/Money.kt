package com.ecshopx.kotlin.common.domain

/**
 * 金额一律整数分（与 ECShopX items.price / *_fee 列一致，见 docs/01 §2 契约表）。
 * 展示层转换走本类，禁止在 service 出现裸的 *100 / /100。
 */
@JvmInline
value class MoneyFen(val fen: Long) : Comparable<MoneyFen> {
    operator fun plus(other: MoneyFen) = MoneyFen(fen + other.fen)
    operator fun minus(other: MoneyFen) = MoneyFen(fen - other.fen)
    fun times(num: Long) = MoneyFen(fen * num)
    override fun compareTo(other: MoneyFen) = fen.compareTo(other.fen)

    companion object {
        val ZERO = MoneyFen(0)
        fun fromYuanText(yuan: String): MoneyFen =
            MoneyFen(java.math.BigDecimal(yuan).movePointRight(2).longValueExact())
    }
}

fun MoneyFen.toYuanText(): String =
    java.math.BigDecimal(fen).movePointLeft(2).toPlainString()
