package com.ecshopx.kotlin.dao.promotion

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-3 拼团 DAO（`promotion_groups_activity` / `_team` / `_team_member`，注意源系统前缀是**单数** promotion）。
 *
 * 两处对源系统的行为修复（docs/04 D25/D29）：
 *  1. **超员在结构上不可能发生**：PHP `updateNum()` 是 findOneBy + `+1` 的读—改—写，并发两笔能把 2 人团
 *     坐成 3 人，只能靠"已成团再来就立刻退款"兜底。本项目参团走
 *     `WHERE team_status = 1 AND join_person_num < #{personNum}` 条件更新，抢不到名额直接失败；
 *     成员行 `(team_id, user_id)` 唯一索引钉死"一人一团一名额"。
 *  2. **团号用号段**：PHP 是「距 2012 天数 + 90 秒槽 + Redis 计数器 + user_id%10000」16 位拼串，
 *     依赖 Redis 且同秒并发靠运气；这里 `team_id = "GT" + SeqGenerator(group_team_id)`。
 *
 * 机器人成团行用**负数** user_id（源系统用 0）：唯一索引下 0 只能坐一行，凑不齐 person_num-1 个空位，
 * 出参按 `user_id < 0` 报"虚拟成员"。
 */

/** `team_status`（源系统整数码，照抄） */
object TeamStatus {
    const val DOING = 1
    const val SUCCESS = 2
    const val FAIL = 3
}

class GroupsActivityDO : Stampable {
    var groupsActivityId: Long = 0
    var companyId: Long = 0
    var actName: String = ""
    var goodsId: Long = 0
    var actPrice: Long = 0
    var personNum: Int = 2
    var store: Long = 0
    var limitBuyNum: Int = 0

    /** 成团时效（小时）：团 end_time = min(now + 此值, 活动 end_time) */
    var limitTime: Int = 24
    var beginTime: Long = 0
    var endTime: Long = 0

    /** 虚拟成团：临近结束仍未满员时补机器人行 */
    var robot: Boolean = false
    var instruction: String? = null
    var disabled: Boolean = false
    override var created: Long = 0
    override var updated: Long = 0
}

class GroupsTeamDO {
    var id: Long = 0
    var teamId: String = ""
    var actId: Long = 0
    var headUserId: Long = 0
    var joinPersonNum: Int = 1
    var teamStatus: Int = TeamStatus.DOING
    var beginTime: Long = 0
    var endTime: Long = 0
    var robotNum: Int = 0
    var created: Long = 0
}

class GroupsMemberDO {
    var id: Long = 0
    var teamId: String = ""
    var actId: Long = 0
    var userId: Long = 0

    /** 参团单号（字符串列沿用源系统：团表与订单表之间不做外键） */
    var orderId: String? = null
    var joinTime: Long = 0

    /** 未付不占成团人数，结清才置真 */
    var paid: Boolean = false
    var userNick: String? = null
}

/** 我的团（GP-F4）：团 + 我的那一行 */
class MyTeamRow {
    var teamId: String = ""
    var actId: Long = 0
    var teamStatus: Int = 0
    var joinPersonNum: Int = 0
    var endTime: Long = 0
    var headUserId: Long = 0
    var orderId: String? = null
    var paid: Boolean = false
    var joinTime: Long = 0
}

private const val GA_COLS =
    "groups_activity_id, company_id, act_name, goods_id, act_price, person_num, store, limit_buy_num, limit_time, " +
        "begin_time, end_time, robot, instruction, disabled, created, updated"

private const val GT_COLS = "id, team_id, act_id, head_user_id, join_person_num, team_status, begin_time, end_time, robot_num, created"

private const val GM_COLS = "id, team_id, act_id, user_id, order_id, join_time, paid, user_nick"

private const val GA_FILTER_SQL = """
    <if test="f.companyId != null"> AND company_id = #{f.companyId}</if>
    <if test="f.disabled != null"> AND disabled = #{f.disabled}</if>
    <if test="f.goodsId != null"> AND goods_id = #{f.goodsId}</if>
    <if test="f.dateStatus == 'pending'"> AND begin_time &gt; #{f.now}</if>
    <if test="f.dateStatus == 'doing'"> AND begin_time &lt;= #{f.now} AND end_time &gt;= #{f.now}</if>
    <if test="f.dateStatus == 'end'"> AND end_time &lt; #{f.now}</if>
"""

@Mapper
interface GroupsActivityMapper {

    @Insert(
        "INSERT INTO promotion_groups_activity(company_id, act_name, goods_id, act_price, person_num, store, limit_buy_num, " +
            "limit_time, begin_time, end_time, robot, instruction, disabled, created, updated) " +
            "VALUES(#{companyId}, #{actName}, #{goodsId}, #{actPrice}, #{personNum}, #{store}, #{limitBuyNum}, " +
            "#{limitTime}, #{beginTime}, #{endTime}, #{robot}, #{instruction}, #{disabled}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "groupsActivityId", keyColumn = "groups_activity_id")
    fun insert(a: GroupsActivityDO): Int

    @Select("SELECT $GA_COLS FROM promotion_groups_activity WHERE groups_activity_id = #{id} AND company_id = #{companyId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): GroupsActivityDO?

    /**
     * 定时结算用：不带租户过滤按活动 id 取。
     * 团是跨租户扫出来的（`listDue`/`listNearEndForRobot` 只有 act_id），
     * 回填租户再查一次等于把"活动不存在"变成"查不到租户"，反而更难判断。
     */
    @Select("SELECT $GA_COLS FROM promotion_groups_activity WHERE groups_activity_id = #{id}")
    fun findAnyById(@Param("id") id: Long): GroupsActivityDO?

    @Update(
        "UPDATE promotion_groups_activity SET act_name = #{actName}, goods_id = #{goodsId}, act_price = #{actPrice}, " +
            "person_num = #{personNum}, limit_buy_num = #{limitBuyNum}, limit_time = #{limitTime}, begin_time = #{beginTime}, " +
            "end_time = #{endTime}, robot = #{robot}, instruction = #{instruction}, updated = #{updated} " +
            "WHERE groups_activity_id = #{groupsActivityId} AND company_id = #{companyId}",
    )
    fun update(a: GroupsActivityDO): Int

    @Update("UPDATE promotion_groups_activity SET disabled = #{disabled}, updated = #{updated} WHERE groups_activity_id = #{id} AND company_id = #{companyId}")
    fun updateDisabled(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("disabled") disabled: Boolean,
        @Param("updated") updated: Long,
    ): Int

    /** GP-A4/GP-A5：`store` 只允许加（减到低于已成团人数会退不回来） */
    @Update("UPDATE promotion_groups_activity SET store = store + #{num}, updated = #{updated} WHERE groups_activity_id = #{id} AND company_id = #{companyId} AND #{num} > 0")
    fun addStore(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("num") num: Long,
        @Param("updated") updated: Long,
    ): Int

    @Select("<script>SELECT COUNT(*) FROM promotion_groups_activity WHERE 1=1$GA_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $GA_COLS FROM promotion_groups_activity WHERE 1=1$GA_FILTER_SQL" +
            " ORDER BY groups_activity_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<GroupsActivityDO>

    @Select(
        "SELECT $GA_COLS FROM promotion_groups_activity WHERE company_id = #{companyId} AND disabled = FALSE " +
            "AND begin_time <= #{now} AND end_time >= #{now}",
    )
    fun listRunning(@Param("companyId") companyId: Long, @Param("now") now: Long): List<GroupsActivityDO>

    /**
     * 下单占库存（CAS）：与商品库存、秒杀池同一套语义 —— 受影响行数 0 就是抢输了。
     * `store` 是活动总量，成团失败与关单都走 [restoreStore] 回补。
     */
    @Update("UPDATE promotion_groups_activity SET store = store - #{num} WHERE groups_activity_id = #{id} AND company_id = #{companyId} AND store >= #{num}")
    fun deductStore(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("num") num: Long,
    ): Int

    /** 回补（取消/关单/成团失败）。`store < 原总量` 的钳制由"只按已占件数回"在服务层保证 */
    @Update("UPDATE promotion_groups_activity SET store = store + #{num} WHERE groups_activity_id = #{id} AND company_id = #{companyId}")
    fun restoreStore(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("num") num: Long,
    ): Int
}

@Mapper
interface GroupsTeamMapper {

    @Insert(
        "INSERT INTO promotion_groups_team(team_id, act_id, head_user_id, join_person_num, team_status, begin_time, end_time, " +
            "robot_num, created) VALUES(#{teamId}, #{actId}, #{headUserId}, #{joinPersonNum}, #{teamStatus}, #{beginTime}, " +
            "#{endTime}, #{robotNum}, #{created})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(t: GroupsTeamDO): Int

    @Select("SELECT $GT_COLS FROM promotion_groups_team WHERE team_id = #{teamId}")
    fun findByTeamId(@Param("teamId") teamId: String): GroupsTeamDO?

    @Select("SELECT $GT_COLS FROM promotion_groups_team WHERE act_id = #{actId} AND team_status = #{status} ORDER BY id DESC LIMIT 50")
    fun listByActAndStatus(@Param("actId") actId: Long, @Param("status") status: Int): List<GroupsTeamDO>

    @Select("SELECT $GT_COLS FROM promotion_groups_team WHERE act_id = #{actId} ORDER BY id DESC LIMIT 200")
    fun listByAct(@Param("actId") actId: Long): List<GroupsTeamDO>

    @Select(
        "<script>SELECT COUNT(*) FROM promotion_groups_team WHERE 1=1" +
            "<if test=\"f.actId != null\"> AND act_id = #{f.actId}</if>" +
            "<if test=\"f.status != null\"> AND team_status = #{f.status}</if></script>",
    )
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $GT_COLS FROM promotion_groups_team WHERE 1=1" +
            "<if test=\"f.actId != null\"> AND act_id = #{f.actId}</if>" +
            "<if test=\"f.status != null\"> AND team_status = #{f.status}</if>" +
            " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<GroupsTeamDO>

    /**
     * 参团占位（CAS，D25）：只有"进行中 + 还差人"的团能占住名额，0 行就是满员/已解散/已失败。
     * `personNum` 由调用方读活动后传入 —— 不把人数写进团行，改活动人数即时生效于新加入者。
     */
    @Update(
        "UPDATE promotion_groups_team SET join_person_num = join_person_num + 1 " +
            "WHERE team_id = #{teamId} AND team_status = 1 AND join_person_num < #{personNum}",
    )
    fun joinSeat(
        @Param("teamId") teamId: String,
        @Param("personNum") personNum: Int,
    ): Int

    /** 未付取消/超时关单回退名额（只在进行中的团上回退，成团/失败团不动） */
    @Update(
        "UPDATE promotion_groups_team SET join_person_num = GREATEST(join_person_num - 1, 0) " +
            "WHERE team_id = #{teamId} AND team_status = 1",
    )
    fun leaveSeat(@Param("teamId") teamId: String): Int

    /**
     * 成团（CAS）：`team_status = 1` 是守卫，并发两笔结清只有一笔能把它推到 2，
     * 失败的那笔读到 0 行就知道"团已经被别人结过了"，直接按已成团分支走。
     */
    @Update("UPDATE promotion_groups_team SET team_status = #{to} WHERE team_id = #{teamId} AND team_status = #{from}")
    fun transit(
        @Param("teamId") teamId: String,
        @Param("from") from: Int,
        @Param("to") to: Int,
    ): Int

    /** 机器人凑团：一次补 n 个空位（同一事务里逐行插成员 + 这里加人数与 robot_num） */
    @Update(
        "UPDATE promotion_groups_team SET join_person_num = join_person_num + #{num}, robot_num = robot_num + #{num} " +
            "WHERE team_id = #{teamId} AND team_status = 1",
    )
    fun addRobots(
        @Param("teamId") teamId: String,
        @Param("num") num: Int,
    ): Int

    /** 到期失败候选（GroupsSettleJob 第一条腿） */
    @Select("SELECT $GT_COLS FROM promotion_groups_team WHERE team_status = 1 AND end_time > 0 AND end_time <= #{now} ORDER BY id LIMIT #{limit}")
    fun listDue(
        @Param("now") now: Long,
        @Param("limit") limit: Int,
    ): List<GroupsTeamDO>

    /** 临近结束且允许机器人的团（第二条腿）：窗口是 `now < end_time <= now + horizon`，
     *  `horizon` 是**提前量**（秒）不是绝对时刻，直接和 `end_time` 比会一条也扫不出来 */
    @Select(
        "SELECT t.id, t.team_id, t.act_id, t.head_user_id, t.join_person_num, t.team_status, t.begin_time, " +
            "t.end_time, t.robot_num, t.created FROM promotion_groups_team t " +
            "JOIN promotion_groups_activity a ON a.groups_activity_id = t.act_id " +
            "WHERE t.team_status = 1 AND t.end_time > #{now} AND t.end_time <= #{now} + #{horizon} " +
            "AND a.robot = TRUE AND a.disabled = FALSE " +
            "ORDER BY t.id LIMIT #{limit}",
    )
    fun listNearEndForRobot(
        @Param("now") now: Long,
        @Param("horizon") horizon: Long,
        @Param("limit") limit: Int,
    ): List<GroupsTeamDO>
}

@Mapper
interface GroupsMemberMapper {

    @Insert(
        "INSERT INTO promotion_groups_team_member(team_id, act_id, user_id, order_id, join_time, paid, user_nick) " +
            "VALUES(#{teamId}, #{actId}, #{userId}, #{orderId}, #{joinTime}, #{paid}, #{userNick})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(m: GroupsMemberDO): Int

    @Select("SELECT $GM_COLS FROM promotion_groups_team_member WHERE team_id = #{teamId} ORDER BY id")
    fun listByTeam(@Param("teamId") teamId: String): List<GroupsMemberDO>

    /** 一人一团一行（uk_team_member 的读侧）：真人用 user_id，机器人是负数 */
    @Select("SELECT $GM_COLS FROM promotion_groups_team_member WHERE team_id = #{teamId} AND user_id = #{userId}")
    fun find(@Param("teamId") teamId: String, @Param("userId") userId: Long): GroupsMemberDO?

    @Select("SELECT COUNT(*) FROM promotion_groups_team_member WHERE team_id = #{teamId} AND paid = TRUE")
    fun countPaid(@Param("teamId") teamId: String): Long

    /** 结清确认（CAS）：只从未付推进，重复回调第二次拿 0 行，成团计数不会被加两次 */
    @Update(
        "UPDATE promotion_groups_team_member SET paid = TRUE, order_id = #{orderId} " +
            "WHERE team_id = #{teamId} AND user_id = #{userId} AND paid = FALSE",
    )
    fun markPaid(
        @Param("teamId") teamId: String,
        @Param("userId") userId: Long,
        @Param("orderId") orderId: String,
    ): Int

    /** 未付退团（取消/关单）：删行 + 名额回退由 [GroupsTeamMapper.leaveSeat] 同事务完成 */
    @Delete("DELETE FROM promotion_groups_team_member WHERE team_id = #{teamId} AND user_id = #{userId} AND paid = FALSE")
    fun dropUnpaid(@Param("teamId") teamId: String, @Param("userId") userId: Long): Int

    /** GP-F4 我的团 */
    @Select(
        "SELECT t.team_id, t.act_id, t.team_status, t.join_person_num, t.end_time, t.head_user_id, " +
            "m.order_id, m.paid, m.join_time FROM promotion_groups_team t " +
            "JOIN promotion_groups_team_member m ON m.team_id = t.team_id " +
            "WHERE m.user_id = #{userId} AND t.act_id IN " +
            "(SELECT groups_activity_id FROM promotion_groups_activity WHERE company_id = #{companyId}) " +
            "ORDER BY t.id DESC LIMIT 50",
    )
    fun listMyTeams(@Param("companyId") companyId: Long, @Param("userId") userId: Long): List<MyTeamRow>

    /** 失败团要退的人（GroupsSettleJob）：机器人行 user_id<0 不在退款名单里 */
    @Select("SELECT $GM_COLS FROM promotion_groups_team_member WHERE team_id = #{teamId} AND user_id > 0 AND paid = TRUE")
    fun listPaidHumans(@Param("teamId") teamId: String): List<GroupsMemberDO>

    @Select("SELECT $GM_COLS FROM promotion_groups_team_member WHERE order_id = #{orderId} LIMIT 1")
    fun findByOrder(@Param("orderId") orderId: String): GroupsMemberDO?

    /**
     * 本人在该活动下的**在途未付**参团行数（源系统同口径："同一会员同一活动只允许一笔待成团订单"）。
     * 只看进行中的团：失败团由结算清理，成功团的成员已付，本来就不算"待成团"。
     */
    @Select(
        "SELECT COUNT(*) FROM promotion_groups_team_member m JOIN promotion_groups_team t ON t.team_id = m.team_id " +
            "WHERE t.act_id = #{actId} AND m.user_id = #{userId} AND m.paid = FALSE AND t.team_status = 1",
    )
    fun countUnpaidInAct(
        @Param("actId") actId: Long,
        @Param("userId") userId: Long,
    ): Long

    /** 限购口径：本人在该活动下"失败团以外"的参团次数（已付 + 在途都算，堵住反复下单） */
    @Select(
        "SELECT COUNT(*) FROM promotion_groups_team_member m JOIN promotion_groups_team t ON t.team_id = m.team_id " +
            "WHERE t.act_id = #{actId} AND m.user_id = #{userId} AND t.team_status <> 3",
    )
    fun countJoinedInAct(
        @Param("actId") actId: Long,
        @Param("userId") userId: Long,
    ): Long

    /** 团里已付的真人成员（发货卡口与"团内还差谁"都用它） */
    @Select("SELECT COUNT(*) FROM promotion_groups_team_member WHERE team_id = #{teamId} AND user_id > 0")
    fun countHumans(@Param("teamId") teamId: String): Long
}
