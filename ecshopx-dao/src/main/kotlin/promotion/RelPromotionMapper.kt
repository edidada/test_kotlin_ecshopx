package com.ecshopx.kotlin.dao.promotion

import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * `orders_rel_promotions` —— 订单优惠快照（docs/03 §7.1「每步产出 discount_info 与订单快照落 orders_rel_promotions」）。
 *
 * M0 建了这张表但没人写：算价链里满减/券都是空规则，没有可记的东西。
 * M1-3 起每笔生效的优惠落一行，于是订单能回答两个退款与对账都要问的问题：
 *  「这单便宜在哪个活动上」以及「那张券当时抵了多少」——活动模板后面会被改被删，快照不会。
 *
 * `moid` 是主订单号（本系统一单一店，暂等于 order_id），`coid` 留给 M1-4 拆单。
 */
class RelPromotionDO {
    var id: Long = 0

    /** 主订单号 */
    var moid: Long = 0
    var coid: Long = 0
    var userId: Long = 0
    var orderType: String? = null
    var itemId: Long = 0
    var itemName: String? = null
    var itemType: String? = null

    /** 活动 id：满减=marketing_id，秒杀=seckill_id，券=coupon_id */
    var activityId: Long = 0
    var activityName: String? = null

    /** full_minus/full_discount/seckill/coupon/point —— 与 discount_info[].type 同词表 */
    var activityType: String? = null
    var discountFee: Long = 0
    var couponId: Long = 0
    var couponCode: String? = null
    var status: String = "valid"
    var created: Long = 0
    var updated: Long = 0
}

@Mapper
interface RelPromotionMapper {

    @Insert(
        "INSERT INTO orders_rel_promotions(moid, coid, user_id, order_type, item_id, item_name, item_type, activity_id, " +
            "activity_name, activity_type, discount_fee, coupon_id, coupon_code, status, created, updated) " +
            "VALUES(#{moid}, #{coid}, #{userId}, #{orderType}, #{itemId}, #{itemName}, #{itemType}, #{activityId}, " +
            "#{activityName}, #{activityType}, #{discountFee}, #{couponId}, #{couponCode}, #{status}, #{created}, #{updated})",
    )
    fun insert(p: RelPromotionDO): Int

    /** 订单详情用：这笔单的优惠构成（ORDER-5 的 discount_info 从这里回读，而不是重算） */
    @Select("SELECT * FROM orders_rel_promotions WHERE moid = #{orderId} AND status = 'valid' ORDER BY id")
    fun listByOrder(@Param("orderId") orderId: Long): List<RelPromotionDO>

    /** 这单是否用了券（取消回退券时先问它，省一次全表扫） */
    @Select("SELECT COUNT(*) FROM orders_rel_promotions WHERE moid = #{orderId} AND activity_type = 'coupon' AND status = 'valid'")
    fun countCouponOf(@Param("orderId") orderId: Long): Long

    /** 关单/取消后作废（快照不删，只翻状态：留"当时确有此优惠"的痕迹供对账复现） */
    @Update("UPDATE orders_rel_promotions SET status = 'invalid', updated = #{now} WHERE moid = #{orderId} AND status = 'valid'")
    fun invalidate(@Param("orderId") orderId: Long, @Param("now") now: Long): Int
}
