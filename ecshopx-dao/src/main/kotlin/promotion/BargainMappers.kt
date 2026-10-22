package com.ecshopx.kotlin.dao.promotion

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/**
 * M2-3 砍价域 DAO（docs/04 §29.2：`bargain_promotions` / `user_bargain` / `user_bargain_log`）。
 *
 * 三条设计注记：
 *  1. **价格一律分**（与全仓价格列同一单位），`current_price` 是刀动到哪的账头 ——
 *     剩余刀数不落列，由 `user_bargain_log` 行数派生（与互动域"次数是派生值"同一条纪律）；
 *  2. `user_bargain` 的 `uk(company, bargain, user)` = "同一活动同一会员一份刀"；
 *     参与失败以重复键回收成业务错，不是先查后插；
 *  3. 成单链路（按底价下单）**本批不接 cart/order**（§29.2 差异 C224）：
 *     `order_class='bargain'` 已在 schema 预留（M0-6 那批），接入就是把快照参数送进下单链的一跳，
 *     单独成批而不是夹在本批里让砍价表背依赖。
 */

class BargainPromotionDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var itemId: Long = 0
    var itemPrice: Long = 0
    var floorPrice: Long = 0
    var steps: Int = 10
    var disabled: Boolean = false
    override var created: Long = 0
    override var updated: Long = 0
}

class UserBargainDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var bargainId: Long = 0
    var currentPrice: Long = 0
    var status: String = "going"   // going / reached
    override var created: Long = 0
    override var updated: Long = 0
}

class UserBargainLogDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var userBargainId: Long = 0
    var userId: Long = 0
    var cutFee: Long = 0
    var beforePrice: Long = 0
    var afterPrice: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface BargainPromotionMapper {

    @Select("SELECT id, company_id, item_id, item_price, floor_price, steps, disabled, created, updated " +
        "FROM bargain_promotions WHERE company_id = #{companyId} AND id = #{id}")
    fun find(companyId: Long, id: Long): BargainPromotionDO?

    @Select("SELECT id, company_id, item_id, item_price, floor_price, steps, disabled, created, updated " +
        "FROM bargain_promotions WHERE company_id = #{companyId} ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun page(companyId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<BargainPromotionDO>

    @Select("SELECT COUNT(*) FROM bargain_promotions WHERE company_id = #{companyId}")
    fun count(companyId: Long): Long

    @Select("SELECT COUNT(*) FROM bargain_promotions WHERE company_id = #{companyId} AND disabled = false")
    fun countActive(companyId: Long): Long

    @Select("SELECT id, company_id, item_id, item_price, floor_price, steps, disabled, created, updated " +
        "FROM bargain_promotions WHERE company_id = #{companyId} AND disabled = false ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageActive(companyId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<BargainPromotionDO>

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("INSERT INTO bargain_promotions(company_id, item_id, item_price, floor_price, steps, disabled, created, updated) " +
        "VALUES(#{companyId}, #{itemId}, #{itemPrice}, #{floorPrice}, #{steps}, #{disabled}, #{created}, #{updated})")
    fun insert(r: BargainPromotionDO): Int

    @org.apache.ibatis.annotations.Update(
        "UPDATE bargain_promotions SET item_id = #{itemId}, item_price = #{itemPrice}, floor_price = #{floorPrice}, " +
            "steps = #{steps}, disabled = #{disabled}, updated = #{updated} WHERE id = #{id} AND company_id = #{companyId}",
    )
    fun update(r: BargainPromotionDO): Int

    @org.apache.ibatis.annotations.Update(
        "UPDATE bargain_promotions SET disabled = 1, updated = #{now} WHERE id = #{id} AND company_id = #{companyId}",
    )
    fun terminate(@Param("id") id: Long, @Param("companyId") companyId: Long, @Param("now") now: Long): Int

    @Delete("DELETE FROM bargain_promotions WHERE id = #{id} AND company_id = #{companyId}")
    fun delete(@Param("id") id: Long, @Param("companyId") companyId: Long): Int
}

@Mapper
interface UserBargainMapper {

    @Select("SELECT id, company_id, user_id, bargain_id, current_price, status, created, updated " +
        "FROM user_bargain WHERE company_id = #{companyId} AND id = #{id}")
    fun find(companyId: Long, id: Long): UserBargainDO?

    @Select("SELECT id, company_id, user_id, bargain_id, current_price, status, created, updated " +
        "FROM user_bargain WHERE company_id = #{companyId} AND bargain_id = #{bargainId} AND user_id = #{userId}")
    fun findByUser(companyId: Long, bargainId: Long, userId: Long): UserBargainDO?

    @Select("SELECT id, company_id, user_id, bargain_id, current_price, status, created, updated " +
        "FROM user_bargain WHERE company_id = #{companyId} AND bargain_id = #{bargainId}")
    fun pageByBargain(companyId: Long, bargainId: Long): List<UserBargainDO>

    @Select("SELECT COUNT(*) FROM user_bargain WHERE company_id = #{companyId} AND bargain_id = #{bargainId}")
    fun countByBargain(companyId: Long, bargainId: Long): Long

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("INSERT INTO user_bargain(company_id, user_id, bargain_id, current_price, status, created, updated) " +
        "VALUES(#{companyId}, #{userId}, #{bargainId}, #{currentPrice}, #{status}, #{created}, #{updated})")
    fun insert(r: UserBargainDO): Int

    @org.apache.ibatis.annotations.Update(
        "UPDATE user_bargain SET current_price = #{currentPrice}, status = #{status}, updated = #{updated} " +
            "WHERE id = #{id} AND company_id = #{companyId}",
    )
    fun update(r: UserBargainDO): Int
}

@Mapper
interface UserBargainLogMapper {

    @Insert("INSERT INTO user_bargain_log(company_id, user_bargain_id, user_id, cut_fee, before_price, after_price, " +
        "created, updated) VALUES(#{companyId}, #{userBargainId}, #{userId}, #{cutFee}, #{beforePrice}, #{afterPrice}, " +
        "#{created}, #{updated})")
    fun insert(r: UserBargainLogDO): Int

    @Select("SELECT COUNT(*) FROM user_bargain_log WHERE company_id = #{companyId} AND user_bargain_id = #{userBargainId}")
    fun countByUbg(companyId: Long, userBargainId: Long): Long

    @Select("SELECT id, company_id, user_bargain_id, user_id, cut_fee, before_price, after_price, created, updated " +
        "FROM user_bargain_log WHERE company_id = #{companyId} AND user_bargain_id = #{userBargainId} ORDER BY id ASC")
    fun listByUbg(companyId: Long, userBargainId: Long): List<UserBargainLogDO>
}
