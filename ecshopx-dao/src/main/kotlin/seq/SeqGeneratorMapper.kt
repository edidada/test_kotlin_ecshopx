package com.ecshopx.kotlin.dao.seq

import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 号段表 ecx.seq_generator（sql/schema.sql 末段，seq_name 小写行：order_no/trade_no/refund_no/aftersales_no）。
 * 取段两步 UPDATE→SELECT 必须在同一事务内（SeqGenerator 用 REQUIRES_NEW 包裹）：
 * UPDATE 持有行锁到提交，并发取段互斥，读回值即本段上界；MySQL/H2 语义一致（H2 无 UPDATE…RETURNING）。
 */
interface SeqGeneratorMapper {

    @Update(
        "UPDATE seq_generator SET current_value = current_value + #{step}, version = version + 1 " +
            "WHERE seq_name = #{seqName}",
    )
    fun takeSegment(
        @Param("seqName") seqName: String,
        @Param("step") step: Int,
    ): Int

    @Select("SELECT current_value FROM seq_generator WHERE seq_name = #{seqName}")
    fun currentValue(@Param("seqName") seqName: String): Long
}
