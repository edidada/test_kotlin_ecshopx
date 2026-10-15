package com.ecshopx.kotlin.dao.cart

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 购物车表（CART-1..6）。同一 (user_id, shop_id, activity_type, activity_id, item_id) 只留一行，
 * 累加/覆盖由 isAccumulate 在 Service 层决定（对齐 PHP CartServices 语义）。
 */
@Mapper
interface OrdersCartMapper {

    companion object {
        const val COLS =
            "cart_id, company_id, user_id, user_ident, shop_type, shop_id, activity_type, activity_id, " +
            "marketing_type, marketing_id, item_type, item_id, items_id, item_name, pics, num, price, " +
            "point, is_checked, is_plus_buy, source_type, promoter_user_id, created, updated"

        /** 同一车内同一 SKU 的唯一键（不含 cart_id） */
        const val UNIQUE_WHERE =
            "company_id = #{companyId} AND user_id = #{userId} AND item_id = #{itemId} " +
            "AND shop_id = #{shopId} AND activity_type = #{activityType} AND activity_id = #{activityId} " +
            "AND is_plus_buy = #{isPlusBuy}"
    }

    @Select("<script>SELECT $COLS FROM orders_cart WHERE company_id = #{companyId} AND user_id = #{userId} " +
        "<if test=\"shopId != null\">AND shop_id = #{shopId}</if> " +
        "ORDER BY shop_id, cart_id</script>")
    fun listByUser(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("shopId") shopId: Long?,
    ): List<OrdersCartDO>

    @Select("SELECT $COLS FROM orders_cart WHERE cart_id = #{cartId}")
    fun findById(@Param("cartId") cartId: Long): OrdersCartDO?

    @Select("SELECT $COLS FROM orders_cart WHERE $UNIQUE_WHERE LIMIT 1")
    fun findByKey(cart: OrdersCartDO): OrdersCartDO?

    @Insert(
        "INSERT INTO orders_cart(company_id, user_id, user_ident, shop_type, shop_id, activity_type, activity_id, " +
            "marketing_type, marketing_id, item_type, item_id, items_id, item_name, pics, num, price, point, " +
            "is_checked, is_plus_buy, source_type, promoter_user_id, created, updated) " +
            "VALUES(#{companyId}, #{userId}, #{userIdent}, #{shopType}, #{shopId}, #{activityType}, #{activityId}, " +
            "#{marketingType}, #{marketingId}, #{itemType}, #{itemId}, #{itemsId}, #{itemName}, #{pics}, #{num}, " +
            "#{price}, #{point}, #{isChecked}, #{isPlusBuy}, #{sourceType}, #{promoterUserId}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "cartId", keyColumn = "cart_id")
    fun insert(cart: OrdersCartDO): Int

    @Update("UPDATE orders_cart SET num = #{num}, updated = #{updated} WHERE cart_id = #{cartId}")
    fun updateNum(@Param("cartId") cartId: Long, @Param("num") num: Long, @Param("updated") updated: Long): Int

    /** 加购命中已有行时的快照刷新（价格/名称/图片以商品主表为准） */
    @Update("UPDATE orders_cart SET num = #{num}, price = #{price}, item_name = #{itemName}, pics = #{pics}, " +
        "is_checked = #{isChecked}, updated = #{updated} WHERE cart_id = #{cartId}")
    fun refreshSnapshot(cart: OrdersCartDO): Int

    @Update("UPDATE orders_cart SET is_checked = #{isChecked}, updated = #{updated} WHERE cart_id = #{cartId}")
    fun updateChecked(@Param("cartId") cartId: Long, @Param("isChecked") isChecked: Boolean, @Param("updated") updated: Long): Int

    @Update("<script>UPDATE orders_cart SET is_checked = #{isChecked}, updated = #{updated} " +
        "WHERE company_id = #{companyId} AND user_id = #{userId} AND cart_id IN " +
        "<foreach item=\"i\" collection=\"cartIds\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun updateCheckedBatch(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("cartIds") cartIds: List<Long>,
        @Param("isChecked") isChecked: Boolean,
        @Param("updated") updated: Long,
    ): Int

    @Delete("<script>DELETE FROM orders_cart WHERE user_id = #{userId} AND cart_id IN " +
        "<foreach item=\"i\" collection=\"cartIds\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun deleteByIds(@Param("userId") userId: Long, @Param("cartIds") cartIds: List<Long>): Int

    @Delete("DELETE FROM orders_cart WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun clearByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): Int

    /** CART-6 角标：全部行数与件数（不区分勾选，与 PHP 一致） */
    @Select("SELECT COUNT(*) AS cnt, COALESCE(SUM(num), 0) AS num FROM orders_cart " +
        "WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun statByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): CartStat?
}

/** CART-6 出参承载体 */
class CartStat {
    var cnt: Long = 0
    var num: Long = 0
}

/** 算价/校验用的商品最小快照读取（不复用商品装配链路，避免购物车依赖前台视图） */
@Mapper
interface CartItemsMapper {

    @Select("<script>SELECT item_id, company_id, goods_id, default_item_id, is_default, item_name, pics, price, " +
        "market_price, brief, store, item_type, item_category, type, point, approve_status, distributor_id, templates_id, " +
        "weight, volume, nospec, item_unit FROM items WHERE item_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun listByIds(@Param("ids") ids: List<Long>): List<CartItemView>
}
