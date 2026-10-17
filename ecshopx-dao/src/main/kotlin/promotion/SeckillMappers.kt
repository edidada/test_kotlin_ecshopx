package com.ecshopx.kotlin.dao.promotion

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-3 秒杀 DAO（`promotions_seckill_activity` + `_rel_goods`）。
 *
 * 库存模型（docs/04 D24）：源系统是 Redis 池 + Lua + 延迟任务，本仓库没有 Redis，
 * 改成同一行上的三列 —— `activity_store` 总量、`sales_store` 已售（已付）、`hold_store` 下单未付占用，
 * **可购量 = 总 − 已售 − 占用**，三次动作都是条件更新：
 *  - 下单占：`WHERE activity_store - sales_store - hold_store >= #{num}`（抢不到 0 行 = 卖完）；
 *  - 结清转：`hold_store -> sales_store`（同一行、同一语句，两个数一起动，不会出现"占用清了但销量没加"）；
 *  - 取消放：`hold_store` 回落（`GREATEST` 兜住永不为负）。
 * 与 M0-6 的商品库存 CAS 完全同一套语义，超卖在数据库层不可能发生。
 */

/** `seckill_type`（源系统两种；本项目按同一套库存模型处理） */
object SeckillType {
    const val NORMAL = "normal"
    const val LIMITED_TIME_SALE = "limited_time_sale"
}

class SeckillActivityDO : Stampable {
    var seckillId: Long = 0
    var companyId: Long = 0
    var activityName: String = ""
    var adPic: String? = null
    var activityStartTime: Long = 0
    var activityEndTime: Long = 0
    var releaseTime: Long = 0

    /** 逗号分隔的参与店铺（源系统就是字符串列，不建关联表） */
    var distributorId: String? = null
    var seckillType: String = SeckillType.NORMAL

    /** 每人单笔限额（分），0=不限 */
    var limitMoney: Long = 0

    /** 下单未付保留分钟（源系统同名列）：订单 auto_cancel_time = min(now + 此值, 活动结束) */
    var validityPeriod: Int = 0
    var disabled: Boolean = false
    override var created: Long = 0
    override var updated: Long = 0
}

class SeckillGoodsDO {
    var id: Long = 0
    var seckillId: Long = 0
    var itemId: Long = 0
    var itemTitle: String? = null
    var pic: String? = null

    /** 秒杀价（分） */
    var activityPrice: Long = 0

    /** 活动总量 */
    var activityStore: Long = 0

    /** 已售（付款确认后才加） */
    var salesStore: Long = 0

    /** 下单未付占用 */
    var holdStore: Long = 0

    /** 每人限购件数，0=不限 */
    var limitNum: Int = 0
    var isShow: Boolean = true
    var disabled: Boolean = false
}

/** 本人已购（含在途占用，限购判定用） */
class SeckillBoughtRow {
    var itemId: Long = 0
    var bought: Long = 0
}

private const val S_COLS =
    "seckill_id, company_id, activity_name, ad_pic, activity_start_time, activity_end_time, release_time, " +
        "distributor_id, seckill_type, limit_money, validity_period, disabled, created, updated"

private const val SG_COLS =
    "id, seckill_id, item_id, item_title, pic, activity_price, activity_store, sales_store, hold_store, limit_num, is_show, disabled"

private const val S_FILTER_SQL = """
    <if test="f.companyId != null"> AND company_id = #{f.companyId}</if>
    <if test="f.disabled != null"> AND disabled = #{f.disabled}</if>
    <if test="f.dateStatus == 'pending'"> AND activity_start_time &gt; #{f.now}</if>
    <if test="f.dateStatus == 'doing'"> AND activity_start_time &lt;= #{f.now} AND activity_end_time &gt;= #{f.now}</if>
    <if test="f.dateStatus == 'end'"> AND activity_end_time &lt; #{f.now}</if>
"""

@Mapper
interface SeckillActivityMapper {

    @Insert(
        "INSERT INTO promotions_seckill_activity(company_id, activity_name, ad_pic, activity_start_time, activity_end_time, " +
            "release_time, distributor_id, seckill_type, limit_money, validity_period, disabled, created, updated) " +
            "VALUES(#{companyId}, #{activityName}, #{adPic}, #{activityStartTime}, #{activityEndTime}, #{releaseTime}, " +
            "#{distributorId}, #{seckillType}, #{limitMoney}, #{validityPeriod}, #{disabled}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "seckillId", keyColumn = "seckill_id")
    fun insert(s: SeckillActivityDO): Int

    @Select("SELECT $S_COLS FROM promotions_seckill_activity WHERE seckill_id = #{id} AND company_id = #{companyId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): SeckillActivityDO?

    @Update(
        "UPDATE promotions_seckill_activity SET activity_name = #{activityName}, ad_pic = #{adPic}, " +
            "activity_start_time = #{activityStartTime}, activity_end_time = #{activityEndTime}, release_time = #{releaseTime}, " +
            "distributor_id = #{distributorId}, seckill_type = #{seckillType}, limit_money = #{limitMoney}, " +
            "validity_period = #{validityPeriod}, updated = #{updated} WHERE seckill_id = #{seckillId} AND company_id = #{companyId}",
    )
    fun update(s: SeckillActivityDO): Int

    @Update("UPDATE promotions_seckill_activity SET disabled = #{disabled}, updated = #{updated} WHERE seckill_id = #{id} AND company_id = #{companyId}")
    fun updateDisabled(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("disabled") disabled: Boolean,
        @Param("updated") updated: Long,
    ): Int

    @Select("<script>SELECT COUNT(*) FROM promotions_seckill_activity WHERE 1=1$S_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $S_COLS FROM promotions_seckill_activity WHERE 1=1$S_FILTER_SQL" +
            " ORDER BY seckill_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<SeckillActivityDO>

    /**
     * 算价/前台取数：进行中的未停用场次。
     * `now` 由调用方给（`CartService.buildContext` 里取一次），保持规则链不取时间的不变式。
     */
    @Select(
        "SELECT $S_COLS FROM promotions_seckill_activity WHERE company_id = #{companyId} AND disabled = FALSE " +
            "AND activity_start_time <= #{now} AND activity_end_time >= #{now}",
    )
    fun listRunning(@Param("companyId") companyId: Long, @Param("now") now: Long): List<SeckillActivityDO>
}

@Mapper
interface SeckillGoodsMapper {

    @Insert(
        "INSERT INTO promotions_seckill_rel_goods(seckill_id, item_id, item_title, pic, activity_price, activity_store, " +
            "sales_store, hold_store, limit_num, is_show, disabled) " +
            "VALUES(#{seckillId}, #{itemId}, #{itemTitle}, #{pic}, #{activityPrice}, #{activityStore}, 0, 0, " +
            "#{limitNum}, #{isShow}, #{disabled})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(g: SeckillGoodsDO): Int

    @Select("SELECT $SG_COLS FROM promotions_seckill_rel_goods WHERE id = #{id}")
    fun findById(@Param("id") id: Long): SeckillGoodsDO?

    @Select("SELECT $SG_COLS FROM promotions_seckill_rel_goods WHERE seckill_id = #{seckillId} AND item_id = #{itemId}")
    fun find(@Param("seckillId") seckillId: Long, @Param("itemId") itemId: Long): SeckillGoodsDO?

    @Select("SELECT $SG_COLS FROM promotions_seckill_rel_goods WHERE seckill_id = #{seckillId} ORDER BY id")
    fun listBySeckill(@Param("seckillId") seckillId: Long): List<SeckillGoodsDO>

    /** 算价预取：本单涉及的这些商品，在跑动场次里的秒杀行（服务层再按场次时间窗筛） */
    @Select(
        "<script>SELECT $SG_COLS FROM promotions_seckill_rel_goods g WHERE g.disabled = FALSE AND g.is_show = TRUE " +
            "AND g.item_id IN <foreach collection=\"itemIds\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach> " +
            "AND g.seckill_id IN <foreach collection=\"seckillIds\" item=\"s\" open=\"(\" separator=\",\" close=\")\">#{s}</foreach></script>",
    )
    fun listOffer(@Param("seckillIds") seckillIds: List<Long>, @Param("itemIds") itemIds: List<Long>): List<SeckillGoodsDO>

    /** 配置台改行（价格/展示/限购可改；`activity_store` 只能加，走 [addStore]） */
    @Update(
        "UPDATE promotions_seckill_rel_goods SET item_title = #{itemTitle}, pic = #{pic}, activity_price = #{activityPrice}, " +
            "limit_num = #{limitNum}, is_show = #{isShow}, disabled = #{disabled} WHERE id = #{id}",
    )
    fun update(g: SeckillGoodsDO): Int

    /** SK-A6 加库存：已产生销量的行只允许加总量（减到低于已售会让 available 变负） */
    @Update("UPDATE promotions_seckill_rel_goods SET activity_store = activity_store + #{num} WHERE id = #{id} AND #{num} > 0")
    fun addStore(@Param("id") id: Long, @Param("num") num: Long): Int

    /** 下单占位（CAS）：0 行 = 秒完/并发抢输 */
    @Update(
        "UPDATE promotions_seckill_rel_goods SET hold_store = hold_store + #{num} " +
            "WHERE seckill_id = #{seckillId} AND item_id = #{itemId} AND disabled = FALSE " +
            "AND activity_store - sales_store - hold_store >= #{num}",
    )
    fun hold(
        @Param("seckillId") seckillId: Long,
        @Param("itemId") itemId: Long,
        @Param("num") num: Long,
    ): Int

    /** 释放占用（取消/关单）：`hold_store > 0` 守卫，0 行 = 已经放过（重复调用安全） */
    @Update(
        "UPDATE promotions_seckill_rel_goods SET hold_store = GREATEST(hold_store - #{num}, 0) " +
            "WHERE seckill_id = #{seckillId} AND item_id = #{itemId} AND hold_store >= #{num}",
    )
    fun release(
        @Param("seckillId") seckillId: Long,
        @Param("itemId") itemId: Long,
        @Param("num") num: Long,
    ): Int

    /**
     * 结清转化（CAS）：占用 → 已售，一条语句同时动两个数。
     * `hold_store >= num` 是守卫：重复回调时第二次拿 0 行，销量不会加两次（与 trade 结清 CAS 同一层幂等）。
     */
    @Update(
        "UPDATE promotions_seckill_rel_goods SET hold_store = hold_store - #{num}, sales_store = sales_store + #{num} " +
            "WHERE seckill_id = #{seckillId} AND item_id = #{itemId} AND hold_store >= #{num}",
    )
    fun settle(
        @Param("seckillId") seckillId: Long,
        @Param("itemId") itemId: Long,
        @Param("num") num: Long,
    ): Int

    /**
     * 本人已购件数（限购判定）：统计**未取消**的秒杀单行，
     * 含未付款在途单 —— 否则用户可以下 5 笔待付款单绕过 `limit_num`。
     */
    @Select(
        "SELECT COALESCE(SUM(i.num), 0) FROM orders_normal_orders_items i " +
            "JOIN orders_normal_orders o ON o.order_id = i.order_id " +
            "WHERE o.company_id = #{companyId} AND o.user_id = #{userId} AND o.order_class = 'seckill' " +
            "AND o.act_id = #{seckillId} AND i.item_id = #{itemId} AND o.order_status <> 'CANCEL'",
    )
    fun boughtByUser(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("seckillId") seckillId: Long,
        @Param("itemId") itemId: Long,
    ): Long

    /** 报表：某场次各行的三个库存数（SK-A7） */
    @Select("SELECT $SG_COLS FROM promotions_seckill_rel_goods WHERE seckill_id = #{seckillId} ORDER BY sales_store DESC, id")
    fun report(@Param("seckillId") seckillId: Long): List<SeckillGoodsDO>
}
