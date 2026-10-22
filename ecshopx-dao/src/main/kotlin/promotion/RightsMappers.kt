package com.ecshopx.kotlin.dao.promotion

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M2-3 批D 次卡（rights）DAO（docs/04 §29.4：`user_rights` / `user_rights_log`）。
 *
 * 三条设计注记：
 *  1. **次数与有效期的事实源是商品侧的 `items_rel_type`**（`num` 次数 / `is_not_limit_num` 不限 /
 *     `limit_time` 有效天数，M1 就随商品标签入库了），`user_rights` 只是**账头**：
 *     `total_count=0` 即不限次、`end_time=0` 即长期有效 —— 表里不存"还剩几次"的独立视图；
 *  2. **核销配额走 CAS 列**：`used = used_count` 由一条条件 UPDATE 保证（不限次数的行不参与配额），
 *     派生纪律不破——`used_count` 与 `behavior='consume'` 的日志行数恒等（同事务各写一行一次），
 *     e2e 里以"两处相等"钉死；
 *  3. **转赠改归属不改行**：转赠就是 `user_id` 换人（事实跟着人走），流水用 `user_id=转出人`
 *     + `to_user_id=收件人` 记一条 `behavior='transfer'`，"我的转赠记录"两腿都从这张日志出。
 *
 * 四种 `behavior`：`grant`（发放，来自结清或后台手工）/ `consume`（核销，每次一行 num=1）/
 * `transfer`（转出）/ `delay`（延期，num=0）。
 */

class UserRightsDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    /** 当前持有人（转赠会换人） */
    var userId: Long = 0
    var itemId: Long = 0
    /** 发放来源（结清的单或 0=手工/无单） */
    var orderId: Long = 0
    var labelId: Long = 0
    var rightsName: String = ""
    /** 总次数，0=不限次 */
    var totalCount: Int = 1
    /** 已核销次数（CAS 列，恒等于 consume 日志行数） */
    var usedCount: Int = 0
    /** 失效时刻（秒），0=长期有效 */
    var endTime: Long = 0
    var disabled: Boolean = false
    override var created: Long = 0
    override var updated: Long = 0
}

class UserRightsLogDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var rightId: Long = 0
    /** 操作那一刻的持有人 */
    var userId: Long = 0
    /** 转赠收件人（grant/consume/delay 行为恒为 0） */
    var toUserId: Long = 0
    var behavior: String = "consume"
    /** 动账次数：consume=1，transfer/delay 无动账记 0 */
    var num: Int = 0
    /** 员工核销人 / 后台发放操作者；0=会员本人在前台自办 */
    var operatorId: Long = 0
    var remark: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface UserRightsMapper {

    @Select("SELECT id, company_id, user_id, item_id, order_id, label_id, rights_name, total_count, used_count, " +
        "end_time, disabled, created, updated FROM user_rights WHERE company_id = #{companyId} AND id = #{id}")
    fun find(companyId: Long, id: Long): UserRightsDO?

    @Select("SELECT id, company_id, user_id, item_id, order_id, label_id, rights_name, total_count, used_count, " +
        "end_time, disabled, created, updated FROM user_rights WHERE company_id = #{companyId} AND user_id = #{userId} " +
        "AND disabled = FALSE ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageBelong(companyId: Long, @Param("userId") userId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UserRightsDO>

    @Select("SELECT COUNT(*) FROM user_rights WHERE company_id = #{companyId} AND user_id = #{userId} AND disabled = FALSE")
    fun countBelong(companyId: Long, userId: Long): Long

    /** 后台账账（全量翻页，含已停用行） */
    @Select("SELECT id, company_id, user_id, item_id, order_id, label_id, rights_name, total_count, used_count, " +
        "end_time, disabled, created, updated FROM user_rights WHERE company_id = #{companyId} " +
        "ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageAll(companyId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UserRightsDO>

    @Select("SELECT COUNT(*) FROM user_rights WHERE company_id = #{companyId}")
    fun countAll(companyId: Long): Long

    @Insert("INSERT INTO user_rights(company_id, user_id, item_id, order_id, label_id, rights_name, total_count, " +
        "used_count, end_time, disabled, created, updated) VALUES(#{companyId}, #{userId}, #{itemId}, #{orderId}, " +
        "#{labelId}, #{rightsName}, #{totalCount}, #{usedCount}, #{endTime}, #{disabled}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(r: UserRightsDO): Int

    /** 转赠改归属（CAS：还是我持有时才换），0 行=已不归属或被停用 */
    @Update("UPDATE user_rights SET user_id = #{toUserId}, updated = #{now} " +
        "WHERE id = #{id} AND company_id = #{companyId} AND user_id = #{fromUserId} AND disabled = FALSE")
    fun transfer(
        @Param("companyId") companyId: Long,
        @Param("id") id: Long,
        @Param("fromUserId") fromUserId: Long,
        @Param("toUserId") toUserId: Long,
        @Param("now") now: Long,
    ): Int

    /**
     * 核销一次的配额 CAS：`0 行` = 已停用/已过期/次数耗尽（调用方先查行拿人话文案，这里只跟并发竞争）。
     * 不限次数的行（total_count=0）同样走这条 UPDATE（条件恒真），保证"用过没用过"在行上可读。
     */
    @Update("UPDATE user_rights SET used_count = used_count + 1, updated = #{now} " +
        "WHERE id = #{id} AND company_id = #{companyId} AND disabled = FALSE " +
        "AND (total_count = 0 OR used_count &lt; total_count) AND (end_time = 0 OR end_time &gt; #{now})")
    fun consumeCas(
        @Param("companyId") companyId: Long,
        @Param("id") id: Long,
        @Param("now") now: Long,
    ): Int

    /** 延期：只动有期限的卡（ end_time>0），永久卡不延迟（业务语义：无到期日可延无意义），逻辑在 Service 判 */
    @Update("UPDATE user_rights SET end_time = end_time + #{days}, updated = #{now} " +
        "WHERE id = #{id} AND company_id = #{companyId} AND end_time &gt; 0")
    fun delay(
        @Param("companyId") companyId: Long,
        @Param("id") id: Long,
        @Param("days") days: Long,
        @Param("now") now: Long,
    ): Int
}

@Mapper
interface UserRightsLogMapper {

    @Insert("INSERT INTO user_rights_log(company_id, right_id, user_id, to_user_id, behavior, num, operator_id, " +
        "remark, created, updated) VALUES(#{companyId}, #{rightId}, #{userId}, #{toUserId}, #{behavior}, #{num}, " +
        "#{operatorId}, #{remark}, #{created}, #{updated})")
    fun insert(l: UserRightsLogDO): Int

    /** 一张卡的全部动账史（详情页回显） */
    @Select("SELECT id, company_id, right_id, user_id, to_user_id, behavior, num, operator_id, remark, created, updated " +
        "FROM user_rights_log WHERE company_id = #{companyId} AND right_id = #{rightId} ORDER BY id DESC")
    fun listByRight(companyId: Long, @Param("rightId") rightId: Long): List<UserRightsLogDO>

    @Select("SELECT id, company_id, right_id, user_id, to_user_id, behavior, num, operator_id, remark, created, updated " +
        "FROM user_rights_log WHERE company_id = #{companyId} AND (user_id = #{userId} OR to_user_id = #{userId}) " +
        "ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageBelong(companyId: Long, @Param("userId") userId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UserRightsLogDO>

    @Select("SELECT COUNT(*) FROM user_rights_log WHERE company_id = #{companyId} AND (user_id = #{userId} OR to_user_id = #{userId})")
    fun countBelong(companyId: Long, userId: Long): Long

    /** 我的转赠记录（只取 transfer 行；id DESC 翻页） */
    @Select("SELECT id, company_id, right_id, user_id, to_user_id, behavior, num, operator_id, remark, created, updated " +
        "FROM user_rights_log WHERE company_id = #{companyId} AND behavior = 'transfer' " +
        "AND (user_id = #{userId} OR to_user_id = #{userId}) ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageTransfers(companyId: Long, @Param("userId") userId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UserRightsLogDO>

    @Select("SELECT COUNT(*) FROM user_rights_log WHERE company_id = #{companyId} AND behavior = 'transfer' " +
        "AND (user_id = #{userId} OR to_user_id = #{userId})")
    fun countTransfers(companyId: Long, userId: Long): Long

    @Select("SELECT id, company_id, right_id, user_id, to_user_id, behavior, num, operator_id, remark, created, updated " +
        "FROM user_rights_log WHERE company_id = #{companyId} AND right_id = #{rightId} AND behavior = 'consume'")
    fun listConsumeByRight(companyId: Long, @Param("rightId") rightId: Long): List<UserRightsLogDO>

    @Select("SELECT id, company_id, right_id, user_id, to_user_id, behavior, num, operator_id, remark, created, updated " +
        "FROM user_rights_log WHERE company_id = #{companyId} ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun page(companyId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UserRightsLogDO>

    @Select("SELECT COUNT(*) FROM user_rights_log WHERE company_id = #{companyId}")
    fun count(companyId: Long): Long
}
