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

    /**
     * CP-A8 按等级发券的取人查询。`disabled = FALSE` 是硬条件：给停用会员发券等于凭空造一张
     * 永远用不掉的券（前台鉴权按会员状态拒绝登录），比不发更糟。
     * `limit` 由服务层给（后台单次上限），避免一个等级几万人时把事务拖死。
     */
    @Select("SELECT user_id FROM members WHERE company_id = #{companyId} AND grade_id = #{gradeId} " +
        "AND disabled = FALSE ORDER BY user_id LIMIT #{limit}")
    fun listUserIdsByGrade(
        @Param("companyId") companyId: Long,
        @Param("gradeId") gradeId: Long,
        @Param("limit") limit: Int,
    ): List<Long>

    @Select("<script>SELECT user_id, company_id, grade_id, mobile, disabled FROM members WHERE user_id IN " +
        "<foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun listByIds(@Param("ids") ids: List<Long>): List<MemberDO>

    /**
     * SM-F2 绑定/换绑当前归属导购（§18.1）。
     *
     * 只写 `members.salesman_id` 这一列，`WHERE` 带 `company_id`：跨租户改别人的归属是数据权限问题，
     * 不能靠"反正 user_id 是全局自增"糊过去。传 0 = 解绑。
     */
    @Update("UPDATE members SET salesman_id = #{salesmanId}, updated = #{updated} " +
        "WHERE user_id = #{userId} AND company_id = #{companyId}")
    fun bindSalesman(
        @Param("userId") userId: Long,
        @Param("companyId") companyId: Long,
        @Param("salesmanId") salesmanId: Long,
        @Param("updated") updated: Long,
    ): Int

    /**
     * 下单回落读归属（C177）：会员自己下单时，`salesman_id` 从这条当前归属来。
     *
     * 单独一条取数列而不是给 `findById` 的 DO 加字段：`SELECT *` 在多租户面里被很多地方用着，
     * 加字段本身无害，但"这一列什么时候该读"是订单域的判断，显式一条读口更容易在审查时被看见。
     */
    @Select("SELECT COALESCE(salesman_id, 0) FROM members WHERE user_id = #{userId} AND company_id = #{companyId}")
    fun salesmanIdOf(
        @Param("userId") userId: Long,
        @Param("companyId") companyId: Long,
    ): Long?
}

interface MembersAssociationsMapper {

    @Select("SELECT * FROM members_associations WHERE open_id = #{openId} AND user_type = 'wechat' LIMIT 1")
    fun findByOpenId(@Param("openId") openId: String): AssocDO?

    /**
     * 微信支付 v3 JSAPI 下单必需 `payer.openid`（docs/05 §M1-2）：按租户+会员反查微信绑定。
     * `LIMIT 1` 是刻意的——历史脏数据里同一会员可能有多条 wechat 关联，取到的那条就是当次支付用的那条，
     * 渠道侧不会因此错账（openid 只决定"钱从谁的钱包出"）。
     */
    @Select("SELECT open_id FROM members_associations WHERE company_id = #{companyId} AND user_id = #{userId} " +
        "AND user_type = 'wechat' ORDER BY id DESC LIMIT 1")
    fun findOpenIdByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): String?

    /**
     * 支付宝 `trade.create` 必需 `buyer_id`（2088 开头的支付宝 userId，不是登录号），口径与微信 openid 完全一致：
     * **先按租户+会员反查绑定，查不到才允许用应用级兜底值**——两条真渠道共用"绝不无主支付"这条不变量。
     * `user_type = 'alipay'` 的行由支付宝授权登录写入（属 M2 开放能力），沙箱与本地可由运营先导入。
     */
    @Select("SELECT open_id FROM members_associations WHERE company_id = #{companyId} AND user_id = #{userId} " +
        "AND user_type = 'alipay' ORDER BY id DESC LIMIT 1")
    fun findAlipayUserIdByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): String?

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
