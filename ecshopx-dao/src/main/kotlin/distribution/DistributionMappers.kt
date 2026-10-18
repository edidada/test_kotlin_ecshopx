package com.ecshopx.kotlin.dao.distribution

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-4 店铺与分销 DAO（`distribution_distributor` + 佣金聚合）。
 *
 * 三列状态各有归宿，混用就是本轮 D42 要避免的那种账目漂移：
 *  - `review_status`（BOOLEAN）＝**审核位**，只有 DS-A5 改；
 *  - `is_valid`（'true'/'false'/'closed'）＝**运营开关**，只有 DS-A6 改，`closed` 是终态关店；
 *  - `payment_subject` + `rate`＝收款主体与平台服务费率，只有 DS-A7 改，且只影响**之后**的佣金。
 *
 * 前台取数三筛（与营销域 C115 同一条纪律）：`company_id + is_valid='true' + review_status=TRUE`，
 * 筛子与配置台读的是同一份列，不会出现"页面列出了这家店、下单却说店铺未启用"。
 */

/** `is_valid`（源系统字符串枚举，照抄不重命名） */
object DistributorValid {
    const val TRUE = "true"
    const val FALSE = "false"
    const val CLOSED = "closed"
}

/** `source_from`：1 管理端创建 / 2 小程序申请 / 3 开放接口 */
object DistributorSource {
    const val ADMIN = 1
    const val MINIAPP = 2
    const val OPENAPI = 3
}

/** `distribution_type`：0 自营 / 1 加盟（建后不可改，D42） */
object DistributionType {
    const val SELF = 0
    const val FRANCHISE = 1
}

/** `payment_subject`：0 平台收款 / 1 店铺收款 */
object PaymentSubject {
    const val PLATFORM = 0
    const val SHOP = 1
}

class DistributorDO : Stampable {
    var distributorId: Long = 0
    var companyId: Long = 0

    /** 全场唯一的商城主店铺位（seed 里 distributor_id=1） */
    var isDistributor: Boolean = false
    var name: String = ""
    var mobile: String? = null
    var province: String? = null
    var city: String? = null
    var area: String? = null
    var address: String? = null
    var lng: String? = null
    var lat: String? = null
    var logo: String? = null
    var banner: String? = null
    var introduce: String? = null
    var isValid: String = DistributorValid.TRUE
    var reviewStatus: Boolean = false
    var sourceFrom: Int = DistributorSource.ADMIN
    var shopId: Long = 0
    var distributionType: Int = DistributionType.SELF
    var autoSyncGoods: Boolean = false
    var isZiti: Boolean = false
    var isDelivery: Boolean = true
    var isSelfDelivery: Boolean = false
    var paymentSubject: Int = PaymentSubject.PLATFORM

    /** 平台服务费率，**万分比**（0..10000）；出参按百分比文案给前台 */
    var rate: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

/** DS-A2/DS-A8 的订单侧聚合行（一次 GROUP BY，不给每店补一条 COUNT） */
class DistributorOrderStat {
    var distributorId: Long = 0
    var orderCount: Long = 0
    var payFee: Long = 0
    var refundFee: Long = 0
}

/** 佣金聚合行（DL-A2 与 DS-A8 共用；未结算/已结算两栏 + 在途提现） */
class DistributorCommissionStat {
    var distributorId: Long = 0
    var openFee: Long = 0
    var closedFee: Long = 0
    var withdrawingFee: Long = 0
}

private const val D_COLS =
    "distributor_id, company_id, is_distributor, name, mobile, province, city, area, address, lng, lat, logo, banner, " +
        "introduce, is_valid, review_status, source_from, shop_id, distribution_type, auto_sync_goods, is_ziti, " +
        "is_delivery, is_self_delivery, payment_subject, rate, created, updated"

/**
 * 列表筛选字典（与商品域 `ItemQuery` 同一写法：判据在 SQL 里，归一化在服务层）。
 * `valid`/`reviewStatus`/`type`/`sourceFrom` 都可空 —— 缺省即不筛，配置台默认要看全量。
 */
private const val D_FILTER_SQL = """
    <if test="f.companyId != null"> AND company_id = #{f.companyId}</if>
    <if test="f.valid != null"> AND is_valid = #{f.valid}</if>
    <if test="f.reviewStatus != null"> AND review_status = #{f.reviewStatus}</if>
    <if test="f.type != null"> AND distribution_type = #{f.type}</if>
    <if test="f.sourceFrom != null"> AND source_from = #{f.sourceFrom}</if>
    <if test="f.name != null"> AND name LIKE CONCAT('%', #{f.name}, '%')</if>
    <if test="f.ids != null"> AND distributor_id IN <foreach item="d" collection="f.ids" open="(" separator="," close=")">#{d}</foreach></if>
"""

@Mapper
interface DistributorMapper {

    @Insert(
        "INSERT INTO distribution_distributor(company_id, is_distributor, name, mobile, province, city, area, address, " +
            "lng, lat, logo, banner, introduce, is_valid, review_status, source_from, shop_id, distribution_type, " +
            "auto_sync_goods, is_ziti, is_delivery, is_self_delivery, payment_subject, rate, created, updated) " +
            "VALUES(#{companyId}, #{isDistributor}, #{name}, #{mobile}, #{province}, #{city}, #{area}, #{address}, " +
            "#{lng}, #{lat}, #{logo}, #{banner}, #{introduce}, #{isValid}, #{reviewStatus}, #{sourceFrom}, #{shopId}, " +
            "#{distributionType}, #{autoSyncGoods}, #{isZiti}, #{isDelivery}, #{isSelfDelivery}, #{paymentSubject}, " +
            "#{rate}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "distributorId", keyColumn = "distributor_id")
    fun insert(d: DistributorDO): Int

    @Select("SELECT $D_COLS FROM distribution_distributor WHERE distributor_id = #{id} AND company_id = #{companyId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): DistributorDO?

    /** 编辑（DS-A4）：**不碰** review_status / is_distributor / company_id / distribution_type（D42 的落点） */
    @Update(
        "UPDATE distribution_distributor SET name = #{name}, mobile = #{mobile}, province = #{province}, city = #{city}, " +
            "area = #{area}, address = #{address}, lng = #{lng}, lat = #{lat}, logo = #{logo}, banner = #{banner}, " +
            "introduce = #{introduce}, is_ziti = #{isZiti}, is_delivery = #{isDelivery}, is_self_delivery = #{isSelfDelivery}, " +
            "auto_sync_goods = #{autoSyncGoods}, shop_id = #{shopId}, updated = #{updated} " +
            "WHERE distributor_id = #{distributorId} AND company_id = #{companyId}",
    )
    fun update(d: DistributorDO): Int

    /** 审核位单独一条：`review_status <> #{to}` 让重复审核返回 0 行，服务层据此报 422 */
    @Update(
        "UPDATE distribution_distributor SET review_status = #{to}, updated = #{updated} " +
            "WHERE distributor_id = #{id} AND company_id = #{companyId} AND review_status <> #{to}",
    )
    fun review(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("to") to: Boolean,
        @Param("updated") updated: Long,
    ): Int

    /** 运营开关：`from` 给了就是条件迁移（closed 只能从 'true'/'false' 进，不能再从 closed 出来） */
    @Update(
        "<script>UPDATE distribution_distributor SET is_valid = #{to}, updated = #{updated} " +
            "WHERE distributor_id = #{id} AND company_id = #{companyId}" +
            "<if test=\"from != null\"> AND is_valid = #{from}</if></script>",
    )
    fun updateValid(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("to") to: String,
        @Param("from") from: String?,
        @Param("updated") updated: Long,
    ): Int

    @Update(
        "UPDATE distribution_distributor SET payment_subject = #{subject}, rate = #{rate}, updated = #{updated} " +
            "WHERE distributor_id = #{id} AND company_id = #{companyId}",
    )
    fun updateSubject(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("subject") subject: Int,
        @Param("rate") rate: Int,
        @Param("updated") updated: Long,
    ): Int

    @Select("<script>SELECT COUNT(*) FROM distribution_distributor WHERE 1=1$D_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select("<script>SELECT $D_COLS FROM distribution_distributor WHERE 1=1$D_FILTER_SQL ORDER BY distributor_id LIMIT #{limit} OFFSET #{offset}</script>")
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<DistributorDO>

    /** 前台/算价用：营业中的店（三筛），ids 给了就"只看这几家"，keyword 是店名模糊 */
    @Select(
        "<script>SELECT $D_COLS FROM distribution_distributor WHERE company_id = #{companyId} " +
            "AND is_valid = 'true' AND review_status = TRUE" +
            "<if test=\"ids != null and ids.size() > 0\"> AND distributor_id IN " +
            "<foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach></if>" +
            "<if test=\"keyword != null and keyword != ''\"> AND name LIKE CONCAT('%', #{keyword}, '%')</if>" +
            " ORDER BY distributor_id LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun listOpen(
        @Param("companyId") companyId: Long,
        @Param("ids") ids: List<Long>?,
        @Param("keyword") keyword: String?,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<DistributorDO>

    /** 前台列表的总数：与 [listOpen] 同一条判据（三筛 + 同一个 keyword），分页框才不会自相矛盾 */
    @Select(
        "<script>SELECT COUNT(*) FROM distribution_distributor WHERE company_id = #{companyId} " +
            "AND is_valid = 'true' AND review_status = TRUE" +
            "<if test=\"keyword != null and keyword != ''\"> AND name LIKE CONCAT('%', #{keyword}, '%')</if></script>",
    )
    fun countOpen(
        @Param("companyId") companyId: Long,
        @Param("keyword") keyword: String?,
    ): Long

    /** 自营主店铺是否已存在（全场唯一，再建第二条自营 → 422） */
    @Select("SELECT COUNT(*) FROM distribution_distributor WHERE company_id = #{companyId} AND is_distributor = TRUE")
    fun countSelf(@Param("companyId") companyId: Long): Long

    /**
     * 订单聚合（DS-A2/DS-A8）：一次 GROUP BY 出两数，`pay_status='PAYED'` 才算成交额。
     *
     * 退款额**不在这条 SQL 里**：`orders_normal_orders` 没有 refund_fee 列，已退的钱在
     * `trade.refunded_fee`（它自己就带 `distributor_id`），跨表 JOIN 要在 H2/MySQL 两种方言下
     * 各写一遍 `CAST(order_id AS CHAR)` —— 拆成两条 GROUP BY 更贵不了多少，却不会漂。
     */
    @Select(
        "<script>SELECT distributor_id AS distributorId, COUNT(*) AS orderCount, " +
            "SUM(CASE WHEN pay_status = 'PAYED' THEN total_fee ELSE 0 END) AS payFee " +
            "FROM orders_normal_orders WHERE company_id = #{companyId} AND distributor_id IN " +
            "<foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach> " +
            "GROUP BY distributor_id</script>",
    )
    fun orderStats(@Param("companyId") companyId: Long, @Param("ids") ids: List<Long>): List<DistributorOrderStat>

    /** 已退金额（按店铺）：只认有钱进过的交易；`CLOSED` 的 refunded_fee 恒 0，不筛也不亏 */
    @Select(
        "<script>SELECT distributor_id AS distributorId, SUM(refunded_fee) AS refundFee " +
            "FROM trade WHERE company_id = #{companyId} AND distributor_id IN " +
            "<foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach> " +
            "GROUP BY distributor_id</script>",
    )
    fun refundStats(@Param("companyId") companyId: Long, @Param("ids") ids: List<Long>): List<DistributorOrderStat>

    /**
     * 佣金聚合（DS-A6 的"先结后关"闸门与 DS-A8 的 balance）。
     *
     * 两栏按 `is_close` 分（未结算/已结算）。退款冲销直接减在 `rebate` 上（§18.5），
     * 所以这里不需要第三种状态，也不需要在 SQL 里认"已冲销"。
     */
    @Select(
        "<script>SELECT distributor_id AS distributorId, " +
            "SUM(CASE WHEN is_close = FALSE THEN rebate ELSE 0 END) AS openFee, " +
            "SUM(CASE WHEN is_close = TRUE THEN rebate ELSE 0 END) AS closedFee " +
            "FROM distribution_distribute_logs WHERE company_id = #{companyId} AND distributor_id IN " +
            "<foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach> " +
            "GROUP BY distributor_id</script>",
    )
    fun commissionStats(@Param("companyId") companyId: Long, @Param("ids") ids: List<Long>): List<DistributorCommissionStat>

    /**
     * 提现占用额（§18.5 余额公式的后半段，与 DS-A8 的 balance 同源）。
     *
     * 数 `applied/confirmed/paid` 三态 —— **`paid` 也占**。`closedFee` 是"累计已结算佣金"，
     * 是一笔只增不减的总数，已打款的那部分如果不扣，同一笔结算佣金就能被反复提空
     * （源系统的 `balance` 列是增量维护的，本项目改成纯派生，派生式就必须含终态）。
     * `refused` 不占：那笔钱从未离开过余额，所以整个公式对拒绝申请天然可重算。
     */
    @Select(
        "<script>SELECT distributor_id AS distributorId, SUM(money) AS withdrawingFee " +
            "FROM distribution_cash_withdrawal WHERE company_id = #{companyId} AND status IN ('applied','confirmed','paid') " +
            "AND distributor_id IN <foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach> " +
            "GROUP BY distributor_id</script>",
    )
    fun withdrawingStats(@Param("companyId") companyId: Long, @Param("ids") ids: List<Long>): List<DistributorCommissionStat>
}
