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
 * M2-3 人群折扣 DAO（docs/04 §29.3：`crowd_discount` / `crowd_discount_log`）。
 *
 * 两条设计注记：
 *  1. `crowd_discount.discount` 与 members_grade.discount **同一口径**：百分比，85 = 打 8.5 折、
 *     100 = 无折扣。两种"折扣上限不同"的表达在算价链里就是同一个减法，配一套口径省一半解释；
 *  2. 优惠日志（`crowd_discount_log`）**在下单事务里写**（§29.3）：它记录的是"这笔折让进了哪一单"，
 *     事后补写必然和订单对不上账，所以它不是运营动作的留痕、而是订单事实的一部分。
 */

class CrowdDiscountDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var name: String = ""
    /** 折扣百分比，85=8.5 折（同 members_grade.discount） */
    var discount: Int = 100
    /** all / grade / new_user */
    var crowdType: String = "all"
    /** JSON：grade→{"grade_ids":[1,2]}；new_user→{"days":7}；all→空 */
    var crowdValue: String = ""
    /** 0 全场 / 1 指定商品（item_ids） */
    var useBound: Int = 0
    /** 圈品集合（逗号分隔 item_id） */
    var itemIds: String = ""
    var disabled: Boolean = false
    var startTime: Long = 0
    var endTime: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

class CrowdDiscountLogDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var discountId: Long = 0
    var orderId: Long = 0
    var userId: Long = 0
    var fee: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface CrowdDiscountMapper {

    @Select("SELECT id, company_id, name, discount, crowd_type, crowd_value, use_bound, item_ids, disabled, " +
        "start_time, end_time, created, updated FROM crowd_discount WHERE company_id = #{companyId} AND id = #{id}")
    fun find(companyId: Long, id: Long): CrowdDiscountDO?

    @Select("SELECT id, company_id, name, discount, crowd_type, crowd_value, use_bound, item_ids, disabled, " +
        "start_time, end_time, created, updated FROM crowd_discount WHERE company_id = #{companyId} " +
        "ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun page(companyId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<CrowdDiscountDO>

    @Select("SELECT COUNT(*) FROM crowd_discount WHERE company_id = #{companyId}")
    fun count(companyId: Long): Long

    /** 算价取数（唯一入口）：启用 + 在窗。`now` 由调用方传，注释同 MarketingActivityMapper.listPricable */
    @Select("SELECT id, company_id, name, discount, crowd_type, crowd_value, use_bound, item_ids, disabled, " +
        "start_time, end_time, created, updated FROM crowd_discount WHERE company_id = #{companyId} " +
        "AND disabled = false AND start_time <= #{now} AND (end_time = 0 OR end_time >= #{now})")
    fun listActive(companyId: Long, @Param("now") now: Long): List<CrowdDiscountDO>

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("INSERT INTO crowd_discount(company_id, name, discount, crowd_type, crowd_value, use_bound, item_ids, " +
        "disabled, start_time, end_time, created, updated) VALUES(#{companyId}, #{name}, #{discount}, #{crowdType}, " +
        "#{crowdValue}, #{useBound}, #{itemIds}, #{disabled}, #{startTime}, #{endTime}, #{created}, #{updated})")
    fun insert(r: CrowdDiscountDO): Int

    @Update("UPDATE crowd_discount SET name = #{name}, discount = #{discount}, crowd_type = #{crowdType}, " +
        "crowd_value = #{crowdValue}, use_bound = #{useBound}, item_ids = #{itemIds}, disabled = #{disabled}, " +
        "start_time = #{startTime}, end_time = #{endTime}, updated = #{updated} " +
        "WHERE id = #{id} AND company_id = #{companyId}")
    fun update(r: CrowdDiscountDO): Int

    @Delete("DELETE FROM crowd_discount WHERE id = #{id} AND company_id = #{companyId}")
    fun delete(@Param("companyId") companyId: Long, @Param("id") id: Long): Int
}

@Mapper
interface CrowdDiscountLogMapper {

    @Insert("INSERT INTO crowd_discount_log(company_id, discount_id, order_id, user_id, fee, created, updated) " +
        "VALUES(#{companyId}, #{discountId}, #{orderId}, #{userId}, #{fee}, #{created}, #{updated})")
    fun insert(r: CrowdDiscountLogDO): Int

    @Select("SELECT id, company_id, discount_id, order_id, user_id, fee, created, updated FROM crowd_discount_log " +
        "WHERE company_id = #{companyId} ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun page(companyId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<CrowdDiscountLogDO>

    @Select("SELECT COUNT(*) FROM crowd_discount_log WHERE company_id = #{companyId}")
    fun count(companyId: Long): Long

    @Select("SELECT id, company_id, discount_id, order_id, user_id, fee, created, updated FROM crowd_discount_log " +
        "WHERE company_id = #{companyId} AND order_id = #{orderId}")
    fun listByOrder(companyId: Long, orderId: Long): List<CrowdDiscountLogDO>
}
