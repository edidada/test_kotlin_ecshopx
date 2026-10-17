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
 * M1-3 满减/满折 DAO（`promotions_marketing_activity` + `_items`）。
 *
 * 规则文本落在 **`condition_value`**（源系统同列，阶梯数组、金额单位是「元」字符串）；
 * `promotions_activity.discount_config` 是另一件事（会员定向自动营销），docs/05 那句
 * "兼容 PHP `discount_config`" 的列名在源系统里其实指 `condition_value` —— 两个形态都由
 * `MarketingRuleParser` 解析，见 service 层（docs/04 §14.0/§14.4）。
 *
 * 参与算价的条件只有一条 SQL：`status=TRUE AND check_status='reviewed' AND 时间在窗`，
 * 未审核的活动**永远不进算价链**（审核 MK-A6 是唯一让活动生效的开关）。
 */

/** `check_status`（源系统字符串枚举，照抄不重命名） */
object MarketingCheckStatus {
    const val NON_REVIEWED = "non-reviewed"
    const val REVIEWED = "reviewed"
    const val UNREVIEWED = "unreviewed"
}

/** `marketing_type`：前两种参与算价，后三种只解析、不出金额（P2） */
object MarketingType {
    const val FULL_MINUS = "full_minus"
    const val FULL_DISCOUNT = "full_discount"
    const val PLUS_PRICE_BUY = "plus_price_buy"
    const val FULL_GIFT = "full_gift"
    const val SINGLE_GIFT = "single_gift"

    /** 算价链认识的类型白名单 —— 白名单外一律不进 ctx，脏数据与新型活动都不会算错 */
    const val PRICABLE_IN = "full_minus, full_discount"
}

/** `use_bound`：0 全场 / 1 指定商品 / 2 分类（P2） */
object MarketingBound {
    const val ALL = 0
    const val ITEMS = 1
    const val CATEGORY = 2
}

class MarketingActivityDO : Stampable {
    var marketingId: Long = 0
    var companyId: Long = 0
    var marketingType: String = MarketingType.FULL_MINUS
    var marketingName: String = ""
    var adPic: String? = null
    var startTime: Long = 0
    var endTime: Long = 0
    var releaseTime: Long = 0

    /** 0 全场 / 1 商品 / 2 分类 */
    var useBound: Int = MarketingBound.ALL
    var useShop: Int = 0
    var shopIds: String? = null
    var conditionType: String? = null
    var conditionValue: String? = null

    /** 上不封顶 */
    var canjoinRepeat: Boolean = false

    /** 每人可参与次数，0=不限（join_limit 语义在源系统只影响前台展示，本项目算价不裁剪） */
    var joinLimit: Int = 0

    /** 满后包邮：命中则本组运费置 0（FreightRule 读它） */
    var freePostage: Boolean = false
    var promotionTag: String? = null
    var checkStatus: String = MarketingCheckStatus.NON_REVIEWED
    var status: Boolean = true
    override var created: Long = 0
    override var updated: Long = 0
}

class MarketingItemDO {
    var id: Long = 0
    var marketingId: Long = 0
    var itemId: Long = 0
    var goodsId: Long = 0
    var marketingType: String? = null
    var itemName: String? = null
    var price: Long = 0
    var promotionTag: String? = null
    var startTime: Long = 0
    var endTime: Long = 0
    var status: Boolean = true
    var isShow: Boolean = true
}

private const val M_COLS =
    "marketing_id, company_id, marketing_type, marketing_name, ad_pic, start_time, end_time, release_time, " +
        "use_bound, use_shop, shop_ids, condition_type, condition_value, canjoin_repeat, join_limit, free_postage, " +
        "promotion_tag, check_status, status, created, updated"

private const val MI_COLS =
    "id, marketing_id, item_id, goods_id, marketing_type, item_name, price, promotion_tag, start_time, end_time, status, is_show"

/** date_status 派生条件（不落列，按时间算）：pending 未开始 / doing 进行中 / end 已结束 */
private const val DATE_STATUS_SQL = """
    <if test="f.dateStatus == 'pending'"> AND start_time &gt; #{f.now}</if>
    <if test="f.dateStatus == 'doing'"> AND start_time &lt;= #{f.now} AND (end_time = 0 OR end_time &gt;= #{f.now})</if>
    <if test="f.dateStatus == 'end'"> AND end_time &gt; 0 AND end_time &lt; #{f.now}</if>
"""

private const val M_FILTER_SQL = """
    <if test="f.companyId != null"> AND company_id = #{f.companyId}</if>
    <if test="f.type != null"> AND marketing_type = #{f.type}</if>
    <if test="f.checkStatus != null"> AND check_status = #{f.checkStatus}</if>
    <if test="f.status != null"> AND status = #{f.status}</if>
    <if test="f.name != null"> AND marketing_name LIKE CONCAT('%', #{f.name}, '%')</if>
"""

@Mapper
interface MarketingActivityMapper {

    @Insert(
        "INSERT INTO promotions_marketing_activity(company_id, marketing_type, marketing_name, ad_pic, start_time, end_time, " +
            "release_time, use_bound, use_shop, shop_ids, condition_type, condition_value, canjoin_repeat, join_limit, " +
            "free_postage, promotion_tag, check_status, status, created, updated) " +
            "VALUES(#{companyId}, #{marketingType}, #{marketingName}, #{adPic}, #{startTime}, #{endTime}, #{releaseTime}, " +
            "#{useBound}, #{useShop}, #{shopIds}, #{conditionType}, #{conditionValue}, #{canjoinRepeat}, #{joinLimit}, " +
            "#{freePostage}, #{promotionTag}, #{checkStatus}, #{status}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "marketingId", keyColumn = "marketing_id")
    fun insert(m: MarketingActivityDO): Int

    @Select("SELECT $M_COLS FROM promotions_marketing_activity WHERE marketing_id = #{id} AND company_id = #{companyId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): MarketingActivityDO?

    @Update(
        "UPDATE promotions_marketing_activity SET marketing_type = #{marketingType}, marketing_name = #{marketingName}, " +
            "ad_pic = #{adPic}, start_time = #{startTime}, end_time = #{endTime}, release_time = #{releaseTime}, " +
            "use_bound = #{useBound}, use_shop = #{useShop}, shop_ids = #{shopIds}, condition_type = #{conditionType}, " +
            "condition_value = #{conditionValue}, canjoin_repeat = #{canjoinRepeat}, join_limit = #{joinLimit}, " +
            "free_postage = #{freePostage}, promotion_tag = #{promotionTag}, status = #{status}, updated = #{updated} " +
            "WHERE marketing_id = #{marketingId} AND company_id = #{companyId}",
    )
    fun update(m: MarketingActivityDO): Int

    /** 审核（MK-A6）：只有 reviewed 参与算价；重新编辑会由服务层打回 non-reviewed */
    @Update(
        "UPDATE promotions_marketing_activity SET check_status = #{to}, updated = #{updated} " +
            "WHERE marketing_id = #{id} AND company_id = #{companyId} AND check_status <> #{to}",
    )
    fun check(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("to") to: String,
        @Param("updated") updated: Long,
    ): Int

    @Update("UPDATE promotions_marketing_activity SET status = #{status}, updated = #{updated} WHERE marketing_id = #{id} AND company_id = #{companyId}")
    fun updateStatus(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("status") status: Boolean,
        @Param("updated") updated: Long,
    ): Int

    @Delete("DELETE FROM promotions_marketing_activity WHERE marketing_id = #{id} AND company_id = #{companyId}")
    fun delete(@Param("id") id: Long, @Param("companyId") companyId: Long): Int

    @Select("<script>SELECT COUNT(*) FROM promotions_marketing_activity WHERE 1=1$M_FILTER_SQL$DATE_STATUS_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $M_COLS FROM promotions_marketing_activity WHERE 1=1$M_FILTER_SQL$DATE_STATUS_SQL" +
            " ORDER BY marketing_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<MarketingActivityDO>

    /**
     * 算价取数（唯一的算价入口查询）：已审核 + 启用 + 在窗 + 认识的类型。
     * `now` 由调用方传入（`CartService.buildContext` 取一次），规则链本身仍然不取时间。
     */
    @Select(
        "SELECT $M_COLS FROM promotions_marketing_activity WHERE company_id = #{companyId} AND status = TRUE " +
            "AND check_status = 'reviewed' AND start_time <= #{now} AND (end_time = 0 OR end_time >= #{now}) " +
            "AND marketing_type IN (" + MarketingType.PRICABLE_IN + ")",
    )
    fun listPricable(@Param("companyId") companyId: Long, @Param("now") now: Long): List<MarketingActivityDO>
}

@Mapper
interface MarketingItemsMapper {

    @Insert(
        "INSERT INTO promotions_marketing_activity_items(marketing_id, item_id, goods_id, marketing_type, item_name, price, " +
            "promotion_tag, start_time, end_time, status, is_show) " +
            "VALUES(#{marketingId}, #{itemId}, #{goodsId}, #{marketingType}, #{itemName}, #{price}, #{promotionTag}, " +
            "#{startTime}, #{endTime}, #{status}, #{isShow})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(i: MarketingItemDO): Int

    @Select("SELECT $MI_COLS FROM promotions_marketing_activity_items WHERE marketing_id = #{marketingId} ORDER BY id")
    fun listByMarketing(@Param("marketingId") marketingId: Long): List<MarketingItemDO>

    @Delete("DELETE FROM promotions_marketing_activity_items WHERE marketing_id = #{marketingId}")
    fun deleteByMarketing(@Param("marketingId") marketingId: Long): Int

    /** 圈品命中判定（一次批量读）：返回 (marketing_id, item_id) 对，服务层据此筛活动 */
    @Select(
        "<script>SELECT $MI_COLS FROM promotions_marketing_activity_items WHERE status = TRUE AND is_show = TRUE " +
            "AND marketing_id IN <foreach collection=\"ids\" item=\"m\" open=\"(\" separator=\",\" close=\")\">#{m}</foreach> " +
            "AND item_id IN <foreach collection=\"itemIds\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>",
    )
    fun listHit(@Param("ids") ids: List<Long>, @Param("itemIds") itemIds: List<Long>): List<MarketingItemDO>
}
