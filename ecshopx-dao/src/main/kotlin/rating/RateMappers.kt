package com.ecshopx.kotlin.dao.rating

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 评价族（M1-5 `RATE-F1..F5` / `RATE-A1..A3`，docs/04 §23）。
 *
 * 三件事决定这一族的形状：
 * 1. **按行落、一单一提交**（RATE-R1）：`trade_rate` 一行 = 订单的一行商品，
 *    所以"这单评过没有"看 `orders_normal_orders.is_rate`（CAS 位），"还有哪些行没评"看这张表的缺集；
 * 2. **归属在落评价那刻定死**（C177 同一条纪律）：`distributor_id/shop_id` 从订单行带下来，
 *    不在读时 join 商品现挂哪家店 —— 加盟体系里"这条差评是谁家的"必须能追溯；
 * 3. **`nickname` 是快照**：会员改名或注销之后，评价仍要显示"当时那个名字"，
 *    所以它落在行上而不是每次 join `members_info`（匿名只在会员面对非本人隐藏，RATE-R3）。
 */
class TradeRateDO : Stampable {
    var rateId: Long = 0
    var companyId: Long = 0
    /** 订单号在评价表是字符串（与源系统一致），服务层两种形态都拿着 */
    var orderId: String = ""
    var itemId: Long = 0
    var goodsId: Long = 0
    var userId: Long = 0
    var star: Int = 1
    var content: String? = null
    /** JSON 数组串（与 `items.pics` 同一惯例），出参用 jsonList 解析 */
    var ratePic: String? = null
    var anonymous: Boolean = false
    var isReply: Boolean = false
    var disabled: Boolean = false
    var distributorId: Long = 0
    var shopId: Long = 0
    var nickname: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/**
 * 回复表只有 `created`、没有 `updated`（源系统形状）。
 * 商家回复"一行一条最新"（RATE-R4）因此靠覆盖那一行的 content/created 实现，
 * 不是插新行再取最新 —— 插新行的话 `is_reply` 与"回复面只出一条"两处都要额外解释。
 */
class TradeRateReplyDO {
    var replyId: Long = 0
    var rateId: Long = 0
    /** seller 商家回复 / buyer 买家追评 */
    var role: String = "seller"
    var content: String? = null
    var created: Long = 0
}

/** 列表行：评价本体 + 会员手机号（datapass 判的正是这一列）+ 店铺名 + 商家回复 */
class RateRow {
    var rateId: Long = 0
    var companyId: Long = 0
    var orderId: String? = null
    var itemId: Long = 0
    var goodsId: Long = 0
    var userId: Long = 0
    var star: Int = 1
    var content: String? = null
    var ratePic: String? = null
    var anonymous: Boolean = false
    var isReply: Boolean = false
    var disabled: Boolean = false
    var distributorId: Long = 0
    var shopId: Long = 0
    var nickname: String? = null
    var created: Long = 0
    var updated: Long = 0
    var mobile: String? = null
    var shopName: String? = null
    var itemName: String? = null
    var replyContent: String? = null
    var replyCreated: Long = 0
}

/** 待评价行（RATE-F5）：订单行的快照字段，评价要按行提交，前台必须先知道"还缺哪几行" */
class PendingRateRow {
    var id: Long = 0
    var itemId: Long = 0
    var goodsId: Long = 0
    var itemName: String? = null
    var pic: String? = null
    var itemSpecDesc: String? = null
    var num: Long = 0
}

/**
 * 商品评价聚合（`RATE-F2` 卡头 / 详情页"x 条评价"）。
 * 返回 DO 而不是 Map：H2 `DATABASE_TO_LOWER` 会把结果集标签折成小写，
 * Map 取键时大小写一错就静默拿到 null（聚合本仓一律走 DO，与 `CommissionSum` 同一做法）。
 */
class RateStatsDO {
    var cnt: Long = 0
    var avgStar: Int = 0
    var picCnt: Long = 0
}

private const val R_COLS =
    "rate_id, company_id, order_id, item_id, goods_id, user_id, star, content, rate_pic, " +
        "anonymous, is_reply, disabled, distributor_id, shop_id, nickname, created, updated"

/**
 * 筛选字典（服务层归一化后传进来，SQL 只管等值与 IN）：
 * `hasPic` 是"只看有图"这一筛，`isReply` 给后台"待回复"页签，
 * `ids` 是店铺运营的数据权限（`DistributorScope.narrow` 那条，越界时塞 UNWATCHABLE 让它自然 0 行）。
 *
 * `includeDisabled` 这条**必须留在字典里而不是只挂在取数那一段**：
 * 它是可见性判据，`count` 与 `page` 必须共用同一道筛 —— 挂在 `page` 上时
 * `total_count` 会把下架的行算进去、列表却不给，分页器按总数发页码，最后一页就成了空页
 * （实跑冒烟 `tools/m15_reconcile_smoke.py` 的下架那一条抓到的就是这个形状）。
 * 缺键 = 不传 = 隐藏下架行；显式 `true`（`RATE-F4` 与 `RATE-A1?include_disabled=1`）= 全出；
 * 显式传了 `disabled` 筛选位则以筛选为准，两条不同时叠加（否则 `disabled=true` 会与自己打起来）。
 */
private const val R_FILTER_SQL = """
    <if test="f.companyId != null"> AND r.company_id = #{f.companyId}</if>
    <if test="f.orderId != null"> AND r.order_id = #{f.orderId}</if>
    <if test="f.itemId != null"> AND r.item_id = #{f.itemId}</if>
    <if test="f.userId != null"> AND r.user_id = #{f.userId}</if>
    <if test="f.distributorId != null"> AND r.distributor_id = #{f.distributorId}</if>
    <if test="f.star != null"> AND r.star = #{f.star}</if>
    <if test="f.isReply != null"> AND r.is_reply = #{f.isReply}</if>
    <if test="f.hasPic != null and f.hasPic"> AND r.rate_pic IS NOT NULL AND r.rate_pic NOT IN ('', '[]')</if>
    <if test="f.disabled != null"> AND r.disabled = #{f.disabled}</if>
    <if test="f.includeDisabled == null and f.disabled == null"> AND r.disabled = FALSE</if>
    <if test="f.ids != null"> AND r.distributor_id IN <foreach item="d" collection="f.ids" open="(" separator="," close=")">#{d}</foreach></if>
"""

@Mapper
interface TradeRateMapper {

    @Insert(
        "INSERT INTO trade_rate(company_id, order_id, item_id, goods_id, user_id, star, content, rate_pic, " +
            "anonymous, is_reply, disabled, distributor_id, shop_id, nickname, created, updated) " +
            "VALUES(#{companyId}, #{orderId}, #{itemId}, #{goodsId}, #{userId}, #{star}, #{content}, #{ratePic}, " +
            "#{anonymous}, #{isReply}, #{disabled}, #{distributorId}, #{shopId}, #{nickname}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "rateId", keyColumn = "rate_id")
    fun insert(r: TradeRateDO): Int

    @Select("SELECT $R_COLS FROM trade_rate WHERE rate_id = #{rateId}")
    fun find(@Param("rateId") rateId: Long): TradeRateDO?

    /** 详情越权判据（RATE-F3 非本人/不存在同回 404）：一条 SQL 把"是不是你的"也判掉 */
    @Select("SELECT $R_COLS FROM trade_rate WHERE rate_id = #{rateId} AND company_id = #{companyId} AND user_id = #{userId}")
    fun findMine(
        @Param("rateId") rateId: Long,
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
    ): TradeRateDO?

    @Select("SELECT $R_COLS FROM trade_rate WHERE order_id = #{orderId} ORDER BY rate_id")
    fun listByOrder(@Param("orderId") orderId: String): List<TradeRateDO>

    @Select("SELECT COUNT(*) FROM trade_rate WHERE order_id = #{orderId}")
    fun countByOrder(@Param("orderId") orderId: String): Int

    /** 这一单的这几行是否已有评价（RATE-R1 闸门③：给了不在本单里的 item_id 要 422，不静默丢弃） */
    @Select("SELECT COUNT(*) FROM trade_rate WHERE order_id = #{orderId} AND item_id = #{itemId}")
    fun countByOrderItem(@Param("orderId") orderId: String, @Param("itemId") itemId: Long): Int

    /**
     * 下架/上架（RATE-A3）：只翻可见位。
     * **不删行、不动 `is_rate`** —— 评价撤回后订单仍算已评价，否则用户可以拿同一条无限重评。
     */
    @Update("UPDATE trade_rate SET disabled = #{disabled}, updated = #{updated} WHERE rate_id = #{rateId} AND company_id = #{companyId}")
    fun transitDisabled(
        @Param("rateId") rateId: Long,
        @Param("companyId") companyId: Long,
        @Param("disabled") disabled: Boolean,
        @Param("updated") updated: Long,
    ): Int

    /** 回复完把"有没有回复过"这位翻上去（后台"待回复"页签读它） */
    @Update("UPDATE trade_rate SET is_reply = TRUE, updated = #{updated} WHERE rate_id = #{rateId}")
    fun markReplied(@Param("rateId") rateId: Long, @Param("updated") updated: Long): Int

    @Select("<script>SELECT COUNT(*) FROM trade_rate r WHERE 1=1$R_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    /**
     * 列表取数。三张 JOIN 各有用处、全部 `LEFT`：
     * `members` 出手机号（datapass 判的就是这一列），`distribution_distributor` 出店铺名，
     * `items` 出商品名（评价行只存 `item_id`）。
     * 商家回复用相关子查询取"那条唯一的 seller 行"，不是 JOIN ——
     * JOIN 上买家追评会让一条评价在列表里出现两次（RATE-R4 一行的回复面只出一条）。
     *
     * 别名一律下划线式：H2 `DATABASE_TO_LOWER` 把结果集标签折成小写，
     * 靠 `map-underscore-to-camel-case` 还原成 DO 属性（本仓聚合与 join 别名的统一做法，
     * 见 `OrdersMapper` 里那处"驼峰别名会丢"的教训）。
     */
    @Select(
        "<script>SELECT r.rate_id, r.company_id, r.order_id, r.item_id, r.goods_id, r.user_id, r.star, r.content, " +
            "r.rate_pic, r.anonymous, r.is_reply, r.disabled, r.distributor_id, r.shop_id, " +
            "r.nickname, r.created, r.updated, m.mobile AS mobile, d.name AS shop_name, i.item_name AS item_name, " +
            "(SELECT sr.content FROM trade_rate_reply sr WHERE sr.rate_id = r.rate_id AND sr.role = 'seller' " +
            " ORDER BY sr.created DESC LIMIT 1) AS reply_content, " +
            "(SELECT MAX(sr2.created) FROM trade_rate_reply sr2 WHERE sr2.rate_id = r.rate_id AND sr2.role = 'seller') AS reply_created " +
            "FROM trade_rate r " +
            "LEFT JOIN members m ON m.user_id = r.user_id " +
            "LEFT JOIN distribution_distributor d ON d.distributor_id = r.distributor_id " +
            "LEFT JOIN items i ON i.item_id = r.item_id " +
            "WHERE 1=1$R_FILTER_SQL " +
            " ORDER BY r.rate_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<RateRow>

    /**
     * 待评价行（RATE-F5）：这一单的订单行里，还没在 `trade_rate` 出现过的那些。
     * 用 `NOT IN` 子查询而不是服务层两趟读，是因为"整单已评"时这里必须自然回空集 ——
     * 回空集前台才知道显示"已评价"，两趟读法容易把已评的行也列出来让人重复提交。
     * `orders.order_id` 是 BIGINT，故入参用 Long（评价表的字符串单号在服务层转换）。
     */
    @Select(
        "SELECT id, item_id, goods_id, item_name, pic, item_spec_desc, num " +
            "FROM orders_normal_orders_items " +
            "WHERE order_id = #{orderId} AND item_id NOT IN " +
            "(SELECT item_id FROM trade_rate WHERE order_id = #{orderIdStr}) ORDER BY id",
    )
    fun pendingItems(@Param("orderId") orderId: Long, @Param("orderIdStr") orderIdStr: String): List<PendingRateRow>

    /** 商品评价聚合（详情页与 `RATE-F2` 的卡头用；下架的行同样不计入，与列表同一口径） */
    @Select(
        "SELECT COUNT(*) AS cnt, COALESCE(ROUND(AVG(star), 0), 0) AS avg_star, " +
            "SUM(CASE WHEN rate_pic IS NOT NULL AND rate_pic NOT IN ('', '[]') THEN 1 ELSE 0 END) AS pic_cnt " +
            "FROM trade_rate WHERE company_id = #{companyId} AND item_id = #{itemId} AND disabled = FALSE",
    )
    fun statsByItem(@Param("companyId") companyId: Long, @Param("itemId") itemId: Long): RateStatsDO?
}

@Mapper
interface TradeRateReplyMapper {

    @Insert("INSERT INTO trade_rate_reply(rate_id, role, content, created) VALUES(#{rateId}, #{role}, #{content}, #{created})")
    @Options(useGeneratedKeys = true, keyProperty = "replyId", keyColumn = "reply_id")
    fun insert(r: TradeRateReplyDO): Int

    /** 覆盖商家回复那一条（RATE-R4）：0 行 = 还没回复过，调用方接着 insert */
    @Update("UPDATE trade_rate_reply SET content = #{content}, created = #{created} " +
        "WHERE rate_id = #{rateId} AND role = 'seller'")
    fun overwriteSeller(@Param("rateId") rateId: Long, @Param("content") content: String, @Param("created") created: Long): Int

    @Select("SELECT reply_id AS replyId, rate_id AS rateId, role, content, created FROM trade_rate_reply " +
        "WHERE rate_id = #{rateId} ORDER BY created, reply_id")
    fun listByRate(@Param("rateId") rateId: Long): List<TradeRateReplyDO>
}
