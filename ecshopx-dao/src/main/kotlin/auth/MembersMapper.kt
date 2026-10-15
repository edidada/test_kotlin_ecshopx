package com.ecshopx.kotlin.dao.auth

import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

interface MembersMapper {

    @Select("SELECT * FROM members WHERE mobile = #{mobile} LIMIT 1")
    fun findByMobile(@Param("mobile") mobile: String): MemberDO?

    @Select("SELECT * FROM members WHERE user_id = #{userId}")
    fun findById(@Param("userId") userId: Long): MemberDO?

    @Insert("INSERT INTO members(company_id, grade_id, mobile, source_from, created, updated) " +
        "VALUES(#{companyId}, #{gradeId}, #{mobile}, #{sourceFrom}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "userId")
    fun insert(m: MemberDO): Int

    /**
     * MEM-3 换绑手机：条件更新（WHERE mobile = 旧值）——
     * 并发重复提交时只有第一次能改成功，第二次影响 0 行由服务层报"验证码已失效"。
     * 同租户手机号有唯一索引 uk_members_mobile，撞号会抛 DuplicateKey，服务层先查再改给出人话错误。
     */
    @Update("UPDATE members SET mobile = #{newMobile}, updated = #{updated} " +
        "WHERE user_id = #{userId} AND company_id = #{companyId} AND mobile = #{oldMobile}")
    fun updateMobile(
        @Param("userId") userId: Long,
        @Param("companyId") companyId: Long,
        @Param("oldMobile") oldMobile: String?,
        @Param("newMobile") newMobile: String,
        @Param("updated") updated: Long,
    ): Int

    /** 等级变更（成长值达标升级；M0 只在读路径算等级，此方法留给 M1 定时升等） */
    @Update("UPDATE members SET grade_id = #{gradeId}, updated = #{updated} WHERE user_id = #{userId}")
    fun updateGrade(@Param("userId") userId: Long, @Param("gradeId") gradeId: Long, @Param("updated") updated: Long): Int
}

interface MembersAssociationsMapper {

    @Select("SELECT * FROM members_associations WHERE open_id = #{openId} AND user_type = 'wechat' LIMIT 1")
    fun findByOpenId(@Param("openId") openId: String): AssocDO?

    @Insert("INSERT INTO members_associations(user_id, company_id, unionid, open_id, user_type) " +
        "VALUES(#{userId}, #{companyId}, #{unionid}, #{openId}, 'wechat')")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(a: AssocDO): Int
}

/** 会员等级读取（算价链路用） */
interface MembersGradeMapper {

    @Select("SELECT grade_id, company_id, grade_name, level, growth_value, discount FROM members_grade WHERE grade_id = #{gradeId}")
    fun findById(@Param("gradeId") gradeId: Long): MemberGradeDO?

    @Select("SELECT grade_id, company_id, grade_name, level, growth_value, discount FROM members_grade WHERE company_id = #{companyId} ORDER BY level")
    fun listByCompany(@Param("companyId") companyId: Long): List<MemberGradeDO>
}
