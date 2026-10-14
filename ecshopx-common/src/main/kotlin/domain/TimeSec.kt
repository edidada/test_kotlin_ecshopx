package com.ecshopx.kotlin.common.domain

import java.time.Instant
import java.time.ZoneId

/**
 * 时间列一律 Unix 秒（PHP Gedmo Timestampable integer 列）。
 * created/updated 由 dao 层 StampInterceptor 统一填充（详细设计 §8）。
 */
object TimeSec {
    val TZ: ZoneId = ZoneId.of("Asia/Shanghai")

    fun now(): Long = Instant.now().epochSecond

    /** 号段用 14 位时间前缀 yyMMddHHmmss（§5 SeqGenerator） */
    fun nowStamp14(): Long {
        val d = java.time.LocalDateTime.now(TZ)
        return (d.year % 100) * 10_000_000_000L +
            d.monthValue * 100_000_000L +
            d.dayOfMonth * 1_000_000L +
            d.hour * 10_000L +
            d.minute * 100L +
            d.second
    }

    fun of(epochSec: Long): Instant = Instant.ofEpochSecond(epochSec)
}
