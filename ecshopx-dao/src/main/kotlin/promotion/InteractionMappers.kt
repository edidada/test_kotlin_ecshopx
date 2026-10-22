package com.ecshopx.kotlin.dao.promotion

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/**
 * M2-3 互动域 DAO（docs/04 §29.1：`user_checkin` / `user_lottery_times` / `user_lottery_log`）。
 *
 * 三条设计注记：
 *  1. `day` 列是 **yyyymmdd 整数**（Asia/Shanghai 时区），不是 Unix 秒 —— 签到/抽奖的"一天"
 *     是业务日界（0 点翻篇），秒值没法直接做唯一键；整数比字符串小、跨月减一天也能直接比大小；
 *  2. `user_checkin` 的 `uk(company_id, user_id, day)` 就是"同天只能签一次"的数据库级幂等担保——
 *     并发双击签到时后到的 INSERT 以重复键失败，服务层据此回同一条业务错，不是靠先查后插的假幂等；
 *  3. `user_lottery_times`（每日次数状态行，uk 同上）与 `user_lottery_log`（每抽一条的流水）
 *     分开的理由与积分域同构：**可剩余次数是派生值**（base + add − 当日流水数），不落列，
 *     落列就会出现"抽完了但列没扣"这种要人修的脏状态。
 *
 * 后台翻页过滤的约定：`dayStart`/`dayEnd` 传 0 表示"不设界"（`#{dayStart} = 0 OR` 那半边恒真），
 * 避免动态 SQL 脚本；admin 面的行沿用这条纪律（Filter 传值统一由服务层归一）。
 */

class UserCheckinDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var day: Long = 0
    var point: Long = 0
    var continuous: Int = 1
    override var created: Long = 0
    override var updated: Long = 0
}

class UserLotteryTimesDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var day: Long = 0

    /** 当日已通过 loginaddtimes 领走的加次（每日发一次，uk(g,u,day) 保证只发一回） */
    var addTimes: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

class UserLotteryLogDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var activityId: Long = 1
    var prizeId: String = ""
    var prizeName: String = ""
    var prizeType: String = ""
    var point: Long = 0
    var day: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface UserCheckinMapper {

    @Select("SELECT id, company_id, user_id, day, point, continuous, created, updated FROM user_checkin " +
        "WHERE company_id = #{companyId} AND user_id = #{userId} AND day = #{day}")
    fun find(companyId: Long, userId: Long, day: Long): UserCheckinDO?

    @Insert("INSERT INTO user_checkin(company_id, user_id, day, point, continuous, created, updated) " +
        "VALUES(#{companyId}, #{userId}, #{day}, #{point}, #{continuous}, #{created}, #{updated})")
    fun insert(r: UserCheckinDO): Int

    @Select("SELECT COUNT(*) FROM user_checkin WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun countByUser(companyId: Long, userId: Long): Long

    @Select("SELECT COALESCE(SUM(point), 0) FROM user_checkin WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun pointSumByUser(companyId: Long, userId: Long): Long

    /** 闭区间 [dayStart, dayEnd]，整数 yyyymmdd 直接比大小 */
    @Select("SELECT COUNT(*) FROM user_checkin WHERE company_id = #{companyId} AND user_id = #{userId} " +
        "AND day BETWEEN #{dayStart} AND #{dayEnd}")
    fun countBetween(companyId: Long, userId: Long, dayStart: Long, dayEnd: Long): Long

    @Select("SELECT COALESCE(SUM(point), 0) FROM user_checkin WHERE company_id = #{companyId} AND user_id = #{userId} " +
        "AND day BETWEEN #{dayStart} AND #{dayEnd}")
    fun pointSumBetween(companyId: Long, userId: Long, dayStart: Long, dayEnd: Long): Long

    @Select("SELECT id, company_id, user_id, day, point, continuous, created, updated FROM user_checkin " +
        "WHERE company_id = #{companyId} AND user_id = #{userId} AND day BETWEEN #{dayStart} AND #{dayEnd} " +
        "ORDER BY day ASC")
    fun rowsBetween(companyId: Long, userId: Long, dayStart: Long, dayEnd: Long): List<UserCheckinDO>

    @Select("SELECT id, company_id, user_id, day, point, continuous, created, updated FROM user_checkin " +
        "WHERE company_id = #{companyId} " +
        "AND (#{userId} = 0 OR user_id = #{userId}) " +
        "AND (#{dayStart} = 0 OR day >= #{dayStart}) " +
        "AND (#{dayEnd} = 0 OR day <= #{dayEnd}) " +
        "ORDER BY day DESC, id DESC LIMIT #{offset}, #{limit}")
    fun pageAdmin(
        companyId: Long,
        userId: Long,
        dayStart: Long,
        dayEnd: Long,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<UserCheckinDO>

    @Select("SELECT COUNT(*) FROM user_checkin WHERE company_id = #{companyId} " +
        "AND (#{userId} = 0 OR user_id = #{userId}) " +
        "AND (#{dayStart} = 0 OR day >= #{dayStart}) " +
        "AND (#{dayEnd} = 0 OR day <= #{dayEnd})")
    fun countAdmin(companyId: Long, userId: Long, dayStart: Long, dayEnd: Long): Long
}

@Mapper
interface UserLotteryTimesMapper {

    @Select("SELECT id, company_id, user_id, day, add_times, created, updated FROM user_lottery_times " +
        "WHERE company_id = #{companyId} AND user_id = #{userId} AND day = #{day}")
    fun find(companyId: Long, userId: Long, day: Long): UserLotteryTimesDO?

    /** 只在首次（uk 冲突则不动）把当日的加次落下行 —— 每日发一次的幂等靠它，不加更新、加了不再加 */
    @Insert("INSERT INTO user_lottery_times(company_id, user_id, day, add_times, created, updated) " +
        "VALUES(#{companyId}, #{userId}, #{day}, #{addTimes}, #{created}, #{updated}) " +
        "ON DUPLICATE KEY UPDATE id = id")
    fun claim(r: UserLotteryTimesDO): Int

    @Select("SELECT COUNT(*) FROM user_lottery_times WHERE company_id = #{companyId} AND day = #{day}")
    fun countClaimedByDay(companyId: Long, day: Long): Long
}

@Mapper
interface UserLotteryLogMapper {

    @Insert("INSERT INTO user_lottery_log(company_id, user_id, activity_id, prize_id, prize_name, prize_type, " +
        "point, day, created, updated) VALUES(#{companyId}, #{userId}, #{activityId}, #{prizeId}, #{prizeName}, " +
        "#{prizeType}, #{point}, #{day}, #{created}, #{updated})")
    fun insert(r: UserLotteryLogDO): Int

    @Select("SELECT COUNT(*) FROM user_lottery_log WHERE company_id = #{companyId} AND user_id = #{userId} AND day = #{day}")
    fun countByDay(companyId: Long, userId: Long, day: Long): Long

    @Select("SELECT id, company_id, user_id, activity_id, prize_id, prize_name, prize_type, point, day, created, updated " +
        "FROM user_lottery_log WHERE company_id = #{companyId} AND user_id = #{userId} ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageUser(companyId: Long, userId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UserLotteryLogDO>

    @Select("SELECT COUNT(*) FROM user_lottery_log WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun countUser(companyId: Long, userId: Long): Long

    @Select("SELECT id, company_id, user_id, activity_id, prize_id, prize_name, prize_type, point, day, created, updated " +
        "FROM user_lottery_log WHERE company_id = #{companyId} " +
        "AND (#{userId} = 0 OR user_id = #{userId}) ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageUserAll(companyId: Long, userId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UserLotteryLogDO>

    @Select("SELECT COUNT(*) FROM user_lottery_log WHERE company_id = #{companyId} AND (#{userId} = 0 OR user_id = #{userId})")
    fun countAll(companyId: Long, userId: Long): Long

    @Select("SELECT COALESCE(SUM(point), 0) FROM user_lottery_log WHERE company_id = #{companyId} AND (#{userId} = 0 OR user_id = #{userId})")
    fun pointSum(companyId: Long, userId: Long): Long

    @Select("SELECT COUNT(DISTINCT user_id) FROM user_lottery_log WHERE company_id = #{companyId}")
    fun countUsers(companyId: Long): Long

    @Select("SELECT MAX(created) FROM user_lottery_log WHERE company_id = #{companyId}")
    fun lastDrawAt(companyId: Long): Long?
}
