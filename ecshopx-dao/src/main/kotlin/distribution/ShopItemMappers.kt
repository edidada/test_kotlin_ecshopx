package com.ecshopx.kotlin.dao.distribution

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-4 店铺商品 DAO（`distribution_distributor_items`）—— §18.4「双模」的唯一写口。
 *
 * 两个维度各自独立，混起来就是"店铺卖光了、总部库存纹丝不动"那类账目漂：
 *  - `price` 决定**卖多少钱**（0/未挂品 = 回落 `items.price`）；
 *  - `is_total_store` 决定**从哪个池子扣**：TRUE 扣 `items.store`（总部池），
 *    FALSE 扣本表 `store`（店铺池，`deductShop` 那条 CAS 是唯一防线）。
 *  `is_show`/`is_can_sale` 是上下架两位：前者给前台列表看，后者进购物车判 invalid。
 *
 * 本表**没有** created/updated 列（schema §8 原样沿用源系统），所以 DO 不实现 `Stampable` ——
 * M0-4 记过的那条：照抄 Stampable 会让 seed 与 INSERT 一起报 `Column "created" not found`。
 */
class ShopItemDO {
    var id: Long = 0
    var distributorId: Long = 0
    var itemId: Long = 0
    var defaultItemId: Long = 0
    var goodsId: Long = 0
    var shopId: Long = 0

    /** 店铺库存（`is_total_store=FALSE` 时才是真池子） */
    var store: Long = 0

    /** 店铺价（分）；0 = 跟随总部现价 */
    var price: Long = 0
    var isShow: Boolean = true
    var isCanSale: Boolean = true
    var isTotalStore: Boolean = true
    var sales: Long = 0
}

/** SI-A2 的行：店铺态与总部态**同时**给，运营才看得出"这家店挂的是 59 元、总部是 89 元" */
class ShopItemRow {
    var id: Long = 0
    var distributorId: Long = 0
    var itemId: Long = 0
    var defaultItemId: Long = 0
    var goodsId: Long = 0
    var itemName: String? = null
    var itemBn: String? = null
    var pics: String? = null
    var shopPrice: Long = 0
    var shopStore: Long = 0
    var shopSales: Long = 0
    var price: Long = 0
    var store: Long = 0
    var isShow: Boolean = true
    var isCanSale: Boolean = true
    var isTotalStore: Boolean = true
    var approveStatus: String? = null
    var templatesId: Long = 0
    var itemDistributorId: Long = 0
    var companyId: Long = 0
}

private const val DI_COLS =
    "id, distributor_id, item_id, default_item_id, goods_id, shop_id, store, price, is_show, is_can_sale, is_total_store, sales"

/** 双态列表行：本表列一律 `AS shop_*`，items 侧给原价与总店库存 */
private const val ROW_COLS =
    "di.id, di.distributor_id, di.item_id, di.default_item_id, di.goods_id, di.store AS shop_store, " +
        "di.price AS shop_price, di.sales AS shop_sales, di.is_show, di.is_can_sale, di.is_total_store, " +
        "i.item_name, i.item_bn, i.pics, i.price, i.store, i.approve_status, i.templates_id, " +
        "i.distributor_id AS item_distributor_id, i.company_id"

@Mapper
interface ShopItemMapper {

    @Insert(
        "INSERT INTO distribution_distributor_items(distributor_id, item_id, default_item_id, goods_id, shop_id, store, price, " +
            "is_show, is_can_sale, is_total_store, sales) " +
            "VALUES(#{distributorId}, #{itemId}, #{defaultItemId}, #{goodsId}, #{shopId}, #{store}, #{price}, " +
            "#{isShow}, #{isCanSale}, #{isTotalStore}, #{sales})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(i: ShopItemDO): Int

    /** SI-A3 的写口：五个可改字段一次给全（服务层读旧行后合并，不做部分更新，避免"改了价把上架位清掉"） */
    @Update(
        "UPDATE distribution_distributor_items SET store = #{store}, price = #{price}, is_show = #{isShow}, " +
            "is_can_sale = #{isCanSale}, is_total_store = #{isTotalStore}, shop_id = #{shopId} " +
            "WHERE distributor_id = #{distributorId} AND item_id = #{itemId}",
    )
    fun updateConfig(i: ShopItemDO): Int

    @Select("SELECT $DI_COLS FROM distribution_distributor_items WHERE distributor_id = #{distributorId} AND item_id = #{itemId}")
    fun findByKey(@Param("distributorId") distributorId: Long, @Param("itemId") itemId: Long): ShopItemDO?

    /**
     * 算价预取（§18.4 唯一取数口）：按 (店, 商品) 批量读，调用方是 `CartService.buildContext`。
     *
     * 空集合就是"这些店都没挂这些品" —— 服务层按**回落总部态**处理，不是报错：
     * `auto_sync_goods` 的常态就是店里没有独立行，把"没挂品"判成不可售会让整个演示链当场死。
     */
    @Select(
        "<script>SELECT $DI_COLS FROM distribution_distributor_items WHERE distributor_id IN " +
            "<foreach item=\"d\" collection=\"shopIds\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach> " +
            "AND item_id IN <foreach item=\"i\" collection=\"itemIds\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>",
    )
    fun listForPrice(@Param("shopIds") shopIds: List<Long>, @Param("itemIds") itemIds: List<Long>): List<ShopItemDO>

    @Select(
        "<script>SELECT COUNT(*) FROM distribution_distributor_items di JOIN items i ON i.item_id = di.item_id " +
            "WHERE di.distributor_id = #{f.distributorId}" +
            "<if test=\"f.show != null\"> AND di.is_show = #{f.show}</if>" +
            "<if test=\"f.canSale != null\"> AND di.is_can_sale = #{f.canSale}</if>" +
            "<if test=\"f.keyword != null\"> AND i.item_name LIKE CONCAT('%', #{f.keyword}, '%')</if></script>",
    )
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $ROW_COLS FROM distribution_distributor_items di JOIN items i ON i.item_id = di.item_id " +
            "WHERE di.distributor_id = #{f.distributorId}" +
            "<if test=\"f.show != null\"> AND di.is_show = #{f.show}</if>" +
            "<if test=\"f.canSale != null\"> AND di.is_can_sale = #{f.canSale}</if>" +
            "<if test=\"f.keyword != null\"> AND i.item_name LIKE CONCAT('%', #{f.keyword}, '%')</if>" +
            " ORDER BY di.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<ShopItemRow>

    /** SI-F1 前台店铺商品：只出「上架 + 可售」的行，总部态下架的商品一并过滤掉 */
    @Select(
        "<script>SELECT $ROW_COLS FROM distribution_distributor_items di JOIN items i ON i.item_id = di.item_id " +
            "WHERE di.distributor_id = #{distributorId} AND di.is_show = TRUE AND di.is_can_sale = TRUE " +
            "AND i.approve_status = 'onsale' AND i.company_id = #{companyId}" +
            "<if test=\"keyword != null and keyword != ''\"> AND i.item_name LIKE CONCAT('%', #{keyword}, '%')</if>" +
            " ORDER BY di.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun listShopOnSale(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long,
        @Param("keyword") keyword: String?,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<ShopItemRow>

    @Select(
        "<script>SELECT COUNT(*) FROM distribution_distributor_items di JOIN items i ON i.item_id = di.item_id " +
            "WHERE di.distributor_id = #{distributorId} AND di.is_show = TRUE AND di.is_can_sale = TRUE " +
            "AND i.approve_status = 'onsale' AND i.company_id = #{companyId}" +
            "<if test=\"keyword != null and keyword != ''\"> AND i.item_name LIKE CONCAT('%', #{keyword}, '%')</if></script>",
    )
    fun countShopOnSale(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long,
        @Param("keyword") keyword: String?,
    ): Long

    @Delete(
        "<script>DELETE FROM distribution_distributor_items WHERE distributor_id = #{distributorId} AND item_id IN " +
            "<foreach item=\"i\" collection=\"itemIds\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>",
    )
    fun deleteByKeys(@Param("distributorId") distributorId: Long, @Param("itemIds") itemIds: List<Long>): Int

    /**
     * 店铺池扣减（§18.4 的第 ②条腿）：`store >= num` 写进 WHERE，返回 0 即店铺库存不足。
     * 与 `ItemsMapper.deductStock` 同一条 CAS 纪律 —— 服务层不做"读—判—写"。
     */
    @Update(
        "UPDATE distribution_distributor_items SET store = store - #{num}, sales = sales + #{num} " +
            "WHERE distributor_id = #{distributorId} AND item_id = #{itemId} AND store >= #{num}",
    )
    fun deductShop(
        @Param("distributorId") distributorId: Long,
        @Param("itemId") itemId: Long,
        @Param("num") num: Long,
    ): Int

    /**
     * 回补店铺池：`sales >= num` 兜底防重复回补把销量打成负数，`store` 不设上限 ——
     * 调用方必须先做订单状态 CAS（与商品池那条注释同一句告诫）。
     */
    @Update(
        "UPDATE distribution_distributor_items SET store = store + #{num}, " +
            "sales = CASE WHEN sales >= #{num} THEN sales - #{num} ELSE 0 END " +
            "WHERE distributor_id = #{distributorId} AND item_id = #{itemId}",
    )
    fun restoreShop(
        @Param("distributorId") distributorId: Long,
        @Param("itemId") itemId: Long,
        @Param("num") num: Long,
    ): Int

    /** 店铺挂品数（DS-A8 的一个展示数，也用来判"这家店是不是空店"） */
    @Select("SELECT COUNT(*) FROM distribution_distributor_items WHERE distributor_id = #{distributorId}")
    fun countByShop(@Param("distributorId") distributorId: Long): Long
}
