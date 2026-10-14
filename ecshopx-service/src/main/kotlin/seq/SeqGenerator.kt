package com.ecshopx.kotlin.service.seq

import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.seq.SeqGeneratorMapper
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * 业务单号生成器（详细设计 §5）—— 号段步长 100 + JVM 内游标。
 * 数值形状与 PHP 对齐：16 位 = yyMMddHHmmss(12) + 段内序列低 4 位，按时间可排序。
 * 前缀：trade=T、refund=R、aftersales=AS（nextBn），order 裸数字。
 * 取段走 REQUIRES_NEW：外层业务回滚不回拨号段（与 PHP 行为一致，单号允许跳号）。
 */
@Component
class SeqGenerator(
    private val mapper: SeqGeneratorMapper,
    private val db: Db,
    // 不给 step 加 Kotlin 默认值：否则生成多重构造器，Spring 无法选择注入构造器
    @Value("\${ecx.seq.step:100}") private val step: Int,
) {
    private class Segment(var cursor: Long = 0L, var ceiling: Long = -1L)

    private val segments = ConcurrentHashMap<SeqName, Segment>()

    fun next(name: SeqName): Long = TimeSec.nowStamp14() * 10_000L + take(name) % 10_000L

    /** 单号字符串（trade_id/refund_bn/aftersales_bn 等，含前缀） */
    fun nextBn(name: SeqName): String = name.prefix + next(name)

    private fun take(name: SeqName): Long = synchronized(segments.computeIfAbsent(name) { Segment() }) {
        val seg = segments[name]!!
        if (seg.cursor >= seg.ceiling) {
            // 同一 REQUIRES_NEW 事务内 UPDATE(行锁)→SELECT：外层业务回滚不回拨号段，单号允许跳号
            val newBase = db.transactional(TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
                val touched = mapper.takeSegment(name.dbValue, step)
                check(touched == 1) { "seq_generator row missing: ${name.dbValue}" }
                mapper.currentValue(name.dbValue)
            }
            seg.cursor = newBase - step
            seg.ceiling = newBase
        }
        ++seg.cursor
    }
}
