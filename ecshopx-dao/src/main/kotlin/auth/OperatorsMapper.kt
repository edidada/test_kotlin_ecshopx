package com.ecshopx.kotlin.dao.auth

import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

interface OperatorsMapper {

    @Select(
        "SELECT operator_id, company_id, username, password, mobile, operator_type, distributor_ids, " +
            "disabled, last_login_time, created, updated FROM operators WHERE username = #{username}",
    )
    fun findByUsername(@Param("username") username: String): OperatorDO?

    @Select(
        "SELECT operator_id, company_id, username, password, mobile, operator_type, distributor_ids, " +
            "disabled, last_login_time, created, updated FROM operators WHERE operator_id = #{id}",
    )
    fun findById(@Param("id") id: Long): OperatorDO?

    @Update("UPDATE operators SET last_login_time = #{now}, updated = #{now} WHERE operator_id = #{id}")
    fun touchLastLogin(@Param("id") id: Long, @Param("now") now: Long): Int
}
