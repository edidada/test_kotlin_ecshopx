package com.ecshopx.kotlin.dao.auth

import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

interface MembersMapper {

    @Select("SELECT * FROM members WHERE mobile = #{mobile} LIMIT 1")
    fun findByMobile(@Param("mobile") mobile: String): MemberDO?

    @Select("SELECT * FROM members WHERE user_id = #{userId}")
    fun findById(@Param("userId") userId: Long): MemberDO?

    @Insert("INSERT INTO members(company_id, grade_id, mobile, source_from, created, updated) " +
        "VALUES(#{companyId}, #{gradeId}, #{mobile}, #{sourceFrom}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "userId")
    fun insert(m: MemberDO): Int
}

interface MembersAssociationsMapper {

    @Select("SELECT * FROM members_associations WHERE open_id = #{openId} AND user_type = 'wechat' LIMIT 1")
    fun findByOpenId(@Param("openId") openId: String): AssocDO?

    @Insert("INSERT INTO members_associations(user_id, company_id, unionid, open_id, user_type) " +
        "VALUES(#{userId}, #{companyId}, #{unionid}, #{openId}, 'wechat')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(a: AssocDO): Int
}
