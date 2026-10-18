package com.ecshopx.kotlin.dao.distribution

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-4 佣金与提现 DAO（`distribution_distribute_logs` + `distribution_cash_withdrawal`，docs/04 §18.5）。
 *
 * 两张表各有各的"只增不减 / 可重算"纪律，写错一处就是账实不符：
 *  - 佣金日志是**事实流水**：入账只在支付结清那一次发生（幂等判据 = 该单已有行就跳过），
 *    冲销**按行减 `rebate`**、减到 0 为止；已经 `is_close` 的行**绝不回改** —— 那笔钱早就进了
 *    可提现余额，回改历史行会让 `balance` 与前端的每一张对账截图同时对不上，
 *    所以改成"插一条负向流水"（D52），聚合式 `SUM(CASE WHEN is_close=TRUE …)` 自然把它抵掉；
 *  - 提现单是**状态机**：`applied → confirmed → paid` / `→ refused`，四条迁移全部走
 *    `WHERE status = #{from}` 的条件更新（0 行即"状态已变"），余额是纯派生数（§18.5 的公式），
 *    所以 `refused` 什么都不用写 —— 少扣一笔这件事，公式自己就会算回来。
 *
 * `distributor_id` 在两张表上都是**数据权限列**：所有列表/聚合查询都必须带
 * [DistributorScope] 收窄后的 ids（§18.8 门槛②），不做"先查全量再在内存里滤"。
 */

/** 提现四态（照抄源系统列值，不重命名） */
object WithdrawalStatus {
    const val APPLIED = "applied"
    const val CONFIRMED = "confirmed"
    const val PAID = "paid"
    const val REFUSED = "refused"

    /** 占用可提现余额的三态 —— `paid` 也占（C149），`refused` 不占 */
    val OCCUPYING = listOf(APPLIED, CONFIRMED, PAID)

    /** 在途（未到终态）：一店同时只许一笔，闸门用这一条 */
    val IN_FLIGHT = listOf(APPLIED, CONFIRMED)
}

/** `distribution_distribute_logs`：一订单行一条流水（负向冲销行是第二条，同 (order_id, item_id)） */
class DistributeLogDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var distributorId: Long = 0
    var orderId: Long = 0
    var itemId: Long = 0
    var userId: Long = 0
    var shopId: Long = 0

    /** 归属导购（3B）：入账时从订单头 `salesman_id` 带过来，ST-E5/CW-F3 按这一列聚合 */
    var salesmanId: Long = 0
    var num: Long = 0

    /** 本行佣金（分）；负向流水行是负数 */
    var rebate: Long = 0

    /** 累计佣金：入账时 == `rebate`，冲销与入账同步增减（源系统语义，本项目不拿它做第二种账） */
    var totalRebate: Long = 0
    var isClose: Boolean = false

    /** 预计结算时刻（秒）；`null` = 不设（负向行、以及 delay=0 的即时结算） */
    var planCloseTime: Long? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/** DL-A1 的展示行：日志表没有商品名与买家昵称，JOIN 出来（掩码在服务层做） */
class DistributeLogRow {
    var id: Long = 0
    var companyId: Long = 0
    var distributorId: Long = 0
    var orderId: Long = 0
    var itemId: Long = 0
    var userId: Long = 0
    var shopId: Long = 0

    /** 归属导购（3B）：入账时从订单头 `salesman_id` 带过来，ST-E5/CW-F3 按这一列聚合 */
    var salesmanId: Long = 0
    var num: Long = 0
    var rebate: Long = 0
    var totalRebate: Long = 0
    var isClose: Boolean = false
    var planCloseTime: Long? = null
    var created: Long = 0
    var updated: Long = 0
    var itemName: String? = null

    /** 下单会员的昵称/手机号原值，服务层掩码后才出参 */
    var nickname: String? = null
    var mobile: String? = null
}

/** DL-A2 的佣金侧聚合（一条 SQL 出三个数，不给每个数补一次往返） */
class CommissionSum {
    var commissionTotal: Long = 0
    var commissionOpen: Long = 0
    var commissionClosed: Long = 0
}

/** DL-A2 的提现侧聚合 */
class WithdrawalSum {
    var withdrawApplied: Long = 0
    var withdrawPaid: Long = 0
    var withdrawRefused: Long = 0
}

/** `distribution_cash_withdrawal`：提现申请单 */
class CashWithdrawalDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var distributorId: Long = 0

    /** 申请人（会员侧 CW-F1 是店铺老板的 user_id；后台代发为 0） */
    var userId: Long = 0
    var shopId: Long = 0

    /** 导购提现行的归属岗（CW-F3/F4）；店铺提现行为 0，两侧互不合并（§22 C176） */
    var salesmanId: Long = 0
    var money: Long = 0
    var status: String = WithdrawalStatus.APPLIED
    var remarks: String? = null

    /**
     * 打款回执时刻（秒）。本表**没有** `paid_time` 列（schema 原样沿用源系统），
     * 所以终态时刻记在 `updated` 上：`refused/confirmed` 也写 `updated`，
     * 出参侧只有在 `status='paid'` 时才把它当 `paid_time` 给（C151）。
     */
    override var created: Long = 0
    override var updated: Long = 0
}

private const val L_COLS =
    "id, company_id, distributor_id, order_id, item_id, user_id, shop_id, salesman_id, num, rebate, total_rebate, " +
        "is_close, plan_close_time, created, updated"

private const val W_COLS =
    "id, company_id, distributor_id, user_id, shop_id, salesman_id, money, status, remarks, created, updated"

/**
 * DL-A1 的筛选字典。
 *
 * `itemIds`/`ids` 之外多一条 `orderIds`：订单号在后台是**输入框**（运营拿着一张单来找佣金），
 * 但 `orders_normal_orders.order_id` 是 BIGINT、页面给的是 16 位单号字符串，
 * 归一化在服务层做完再传进来，SQL 只管 `IN`。
 */
private const val L_FILTER_SQL = """
    <if test="f.companyId != null"> AND l.company_id = #{f.companyId}</if>
    <if test="f.distributorId != null"> AND l.distributor_id = #{f.distributorId}</if>
    <if test="f.orderId != null"> AND l.order_id = #{f.orderId}</if>
    <if test="f.isClose != null"> AND l.is_close = #{f.isClose}</if>
    <if test="f.salesmanId != null"> AND l.salesman_id = #{f.salesmanId}</if>
    <if test="f.beginTime != null"> AND l.created &gt;= #{f.beginTime}</if>
    <if test="f.endTime != null"> AND l.created &lt;= #{f.endTime}</if>
    <if test="f.ids != null"> AND l.distributor_id IN <foreach item="d" collection="f.ids" open="(" separator="," close=")">#{d}</foreach></if>
    <if test="f.itemName != null"> AND i.item_name LIKE CONCAT('%', #{f.itemName}, '%')</if>
"""

@Mapper
interface DistributeLogMapper {

    @Insert(
        "INSERT INTO distribution_distribute_logs(company_id, distributor_id, order_id, item_id, user_id, shop_id, salesman_id, num, " +
            "rebate, total_rebate, is_close, plan_close_time, created, updated) " +
            "VALUES(#{companyId}, #{distributorId}, #{orderId}, #{itemId}, #{userId}, #{shopId}, #{salesmanId}, #{num}, " +
            "#{rebate}, #{totalRebate}, #{isClose}, #{planCloseTime}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(l: DistributeLogDO): Int

    /** 入账幂等判据（§18.5 第③条）：这一单的流水存在过就不再入账，与 M1-2 的 `trade_state` 同族 */
    @Select("SELECT COUNT(*) FROM distribution_distribute_logs WHERE order_id = #{orderId}")
    fun countByOrder(@Param("orderId") orderId: Long): Long

    @Select("SELECT $L_COLS FROM distribution_distribute_logs WHERE order_id = #{orderId} ORDER BY id")
    fun listByOrder(@Param("orderId") orderId: Long): List<DistributeLogDO>

    /**
     * 冲销（§18.5）：只动**未结算且为正**的行，`GREATEST(…, 0)` 兜住"退得比佣多"；
     * 条件里带 `is_close = FALSE` 就是"已结算的行改不动"这条纪律的结构保证 ——
     * 服务层不需要先读后判，0 行即"这行已结算，走负向流水"。
     * `rebate > 0` 是另一半：负向冲销行本身也落在同一 `(order_id, item_id)` 上，
     * 不带这个条件会把上次冲销的痕迹再次减一遍。
     */
    @Update(
        "UPDATE distribution_distribute_logs SET rebate = GREATEST(rebate - #{delta}, 0), " +
            "total_rebate = GREATEST(total_rebate - #{delta}, 0), updated = #{updated} " +
            "WHERE order_id = #{orderId} AND item_id = #{itemId} AND is_close = FALSE AND rebate > 0",
    )
    fun reduce(
        @Param("orderId") orderId: Long,
        @Param("itemId") itemId: Long,
        @Param("delta") delta: Long,
        @Param("updated") updated: Long,
    ): Int

    /** 未结算且为正的佣金合计（服务层用它算"这一刀能从没结的里削掉多少"） */
    @Select(
        "SELECT COALESCE(SUM(rebate), 0) FROM distribution_distribute_logs " +
            "WHERE order_id = #{orderId} AND item_id = #{itemId} AND is_close = FALSE AND rebate > 0",
    )
    fun openRebate(@Param("orderId") orderId: Long, @Param("itemId") itemId: Long): Long

    /** 已结算有多少可冲（服务层据此决定要不要补负向行、补多少 —— 冲销不许把账冲成负的） */
    @Select(
        "SELECT COALESCE(SUM(rebate), 0) FROM distribution_distribute_logs " +
            "WHERE order_id = #{orderId} AND item_id = #{itemId} AND is_close = TRUE AND rebate > 0",
    )
    fun closedRebate(@Param("orderId") orderId: Long, @Param("itemId") itemId: Long): Long

    /**
     * 负向行"抄谁"：取该 (单, 品) 第一条**已结算的正向**行。
     * 冲销行要沿用它的 店铺/买家/门店 三个归属，不能从订单头现读 ——
     * 与 D46 同一族理由：当时的行才是事实，头表可能已经被别的流程改过。
     */
    @Select(
        "SELECT $L_COLS FROM distribution_distribute_logs " +
            "WHERE order_id = #{orderId} AND item_id = #{itemId} AND is_close = TRUE AND rebate > 0 ORDER BY id LIMIT 1",
    )
    fun firstClosed(@Param("orderId") orderId: Long, @Param("itemId") itemId: Long): DistributeLogDO?

    /**
     * 结算（DL-A3 / 定时任务）：把到期未结的行一次性置已结算。
     * `distributor_id` 传 0 表示全场；后台显式结算与定时任务共用这一条，
     * 判据也共用（`plan_close_time <= toTime`），两个入口不会算出两套账。
     */
    @Update(
        "<script>UPDATE distribution_distribute_logs SET is_close = TRUE, updated = #{updated} " +
            "WHERE company_id = #{companyId} AND is_close = FALSE " +
            "<if test=\"toTime != null\"> AND plan_close_time IS NOT NULL AND plan_close_time &lt;= #{toTime}</if>" +
            "<if test=\"distributorId != null\"> AND distributor_id = #{distributorId}</if>" +
            "<if test=\"ids != null\"> AND distributor_id IN <foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach></if></script>",
    )
    fun closeBatch(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long?,
        @Param("ids") ids: List<Long>?,
        @Param("toTime") toTime: Long?,
        @Param("updated") updated: Long,
    ): Int

    /** 结算前的取数：同一批行的条数与金额（`closed_count`/`closed_fee` 出参与 UPDATE 同一判据） */
    @Select(
        "<script>SELECT COUNT(*) AS c, COALESCE(SUM(rebate), 0) AS s FROM distribution_distribute_logs " +
            "WHERE company_id = #{companyId} AND is_close = FALSE " +
            "<if test=\"toTime != null\"> AND plan_close_time IS NOT NULL AND plan_close_time &lt;= #{toTime}</if>" +
            "<if test=\"distributorId != null\"> AND distributor_id = #{distributorId}</if>" +
            "<if test=\"ids != null\"> AND distributor_id IN <foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach></if></script>",
    )
    fun closePreview(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long?,
        @Param("ids") ids: List<Long>?,
        @Param("toTime") toTime: Long?,
    ): Map<String, Any?>

    /** 关掉"即时结算"用的读口：delay=0 时入账就直接 is_close=TRUE，不写 plan_close_time */
    @Select(
        "<script>SELECT $L_COLS FROM distribution_distribute_logs WHERE company_id = #{companyId} AND is_close = FALSE " +
            "AND plan_close_time IS NOT NULL AND plan_close_time &lt;= #{toTime} ORDER BY id LIMIT #{limit}</script>",
    )
    fun listDue(
        @Param("companyId") companyId: Long,
        @Param("toTime") toTime: Long,
        @Param("limit") limit: Int,
    ): List<DistributeLogDO>

    /**
     * 定时任务用的全场结算：与 [closeBatch] **同一条判据**，只是不带 `company_id`。
     *
     * 单列一条而不是把 `companyId` 改成可空，是因为服务层那条 `close` 走的是
     * "当前登录租户"，而 Job 没有租户上下文（多租户共用一个调度线程）。
     * 判据相同 ⇒ 手动结算与定时结算永远不会算出两套账。
     */
    @Update(
        "UPDATE distribution_distribute_logs SET is_close = TRUE, updated = #{updated} " +
            "WHERE is_close = FALSE AND plan_close_time IS NOT NULL AND plan_close_time <= #{toTime}",
    )
    fun closeBatchAll(@Param("toTime") toTime: Long, @Param("updated") updated: Long): Int

    /** 待结算行数（只给巡检/日志看，不参与判定） */
    @Select(
        "SELECT COUNT(*) FROM distribution_distribute_logs " +
            "WHERE is_close = FALSE AND plan_close_time IS NOT NULL AND plan_close_time <= #{toTime}",
    )
    fun countDue(@Param("toTime") toTime: Long): Long

    @Select("<script>SELECT COUNT(*) FROM distribution_distribute_logs l JOIN items i ON i.item_id = l.item_id WHERE 1=1$L_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    /**
     * DL-A1 的分页取数。
     *
     * 两张 JOIN 各有用途：`items` 出商品名（日志表只存 `item_id`，源系统也一样），
     * `members`/`members_info` 出买家（**昵称在 `members_info.username`，手机号在 `members`**，
     * 与售后/拼团那两处列表同一个取法）。`LEFT JOIN` 是刻意的：
     * 会员注销了也要看得到那笔佣金，不能让一条流水因为查不到人而从列表里消失。
     */
    @Select(
        "<script>SELECT l.id, l.company_id, l.distributor_id, l.order_id, l.item_id, l.user_id, l.shop_id, l.salesman_id, l.num, " +
            "l.rebate, l.total_rebate, l.is_close, l.plan_close_time, l.created, l.updated, " +
            "i.item_name AS itemName, mi.username AS nickname, m.mobile AS mobile " +
            "FROM distribution_distribute_logs l JOIN items i ON i.item_id = l.item_id " +
            "LEFT JOIN members m ON m.user_id = l.user_id " +
            "LEFT JOIN members_info mi ON mi.user_id = l.user_id WHERE 1=1$L_FILTER_SQL " +
            "ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<DistributeLogRow>

    /**
     * DL-A2 佣金聚合：`rebate` 的符号已经把负向冲销行算进去了，
     * 所以这里**不需要**"已冲销"这第三种状态（与 [DistributorMapper.commissionStats] 同一口径）。
     */
    @Select(
        "<script>SELECT COALESCE(SUM(rebate), 0) AS commissionTotal, " +
            "SUM(CASE WHEN is_close = FALSE THEN rebate ELSE 0 END) AS commissionOpen, " +
            "SUM(CASE WHEN is_close = TRUE THEN rebate ELSE 0 END) AS commissionClosed " +
            "FROM distribution_distribute_logs WHERE company_id = #{companyId}" +
            "<if test=\"distributorId != null\"> AND distributor_id = #{distributorId}</if>" +
            "<if test=\"ids != null\"> AND distributor_id IN <foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach></if></script>",
    )
    fun sum(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long?,
        @Param("ids") ids: List<Long>?,
    ): CommissionSum

    /**
     * ST-E4/CW-F3 的导购侧聚合：三个数与 [sum] 同一套 `CASE`，只是键换成 `salesman_id`。
     * 单独一条而不是给 [sum] 加参数：两处调用方（店/岗）的鉴权来源完全不同，
     * 合成一条可选参数容易让"店视角的聚合"被误当成"岗视角"用，那正是最容易把两份账并成一份错账的地方。
     */
    @Select(
        "SELECT COALESCE(SUM(rebate), 0) AS commissionTotal, " +
            "SUM(CASE WHEN is_close = FALSE THEN rebate ELSE 0 END) AS commissionOpen, " +
            "SUM(CASE WHEN is_close = TRUE THEN rebate ELSE 0 END) AS commissionClosed " +
            "FROM distribution_distribute_logs WHERE company_id = #{companyId} AND salesman_id = #{salesmanId}",
    )
    fun sumSalesman(
        @Param("companyId") companyId: Long,
        @Param("salesmanId") salesmanId: Long,
    ): CommissionSum
}

@Mapper
interface CashWithdrawalMapper {

    @Insert(
        "INSERT INTO distribution_cash_withdrawal(company_id, distributor_id, user_id, shop_id, salesman_id, money, status, remarks, " +
            "created, updated) VALUES(#{companyId}, #{distributorId}, #{userId}, #{shopId}, #{salesmanId}, #{money}, #{status}, " +
            "#{remarks}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(w: CashWithdrawalDO): Int

    @Select("SELECT $W_COLS FROM distribution_cash_withdrawal WHERE id = #{id} AND company_id = #{companyId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): CashWithdrawalDO?

    /**
     * 导购提现的"在途唯一"闸门（CW-F3）。
     *
     * 判据按 `salesman_id` 而不是 `distributor_id`：同一家店里两个导购各自提钱互不影响，
     * 但同一个人不能同时挂两笔没处理完的申请 —— 店那侧的 CW-F1 是按店挡的，两条各挡各的岗。
     */
    @Select(
        "<script>SELECT COUNT(*) FROM distribution_cash_withdrawal WHERE company_id = #{companyId} " +
            "AND salesman_id = #{salesmanId} AND status IN " +
            "<foreach item=\"s\" collection=\"statuses\" open=\"(\" separator=\",\" close=\")\">#{s}</foreach></script>",
    )
    fun countInFlightBySalesman(
        @Param("companyId") companyId: Long,
        @Param("salesmanId") salesmanId: Long,
        @Param("statuses") statuses: List<String>,
    ): Long

    /** CW-F3 的可提余额：与店那侧同一条公式，只是聚合键换成 `salesman_id`（§22 C176） */
    @Select(
        "SELECT COALESCE(SUM(CASE WHEN status IN ('applied','confirmed') THEN money ELSE 0 END), 0) AS withdrawApplied, " +
            "COALESCE(SUM(CASE WHEN status = 'paid' THEN money ELSE 0 END), 0) AS withdrawPaid, " +
            "COALESCE(SUM(CASE WHEN status = 'refused' THEN money ELSE 0 END), 0) AS withdrawRefused " +
            "FROM distribution_cash_withdrawal WHERE company_id = #{companyId} AND salesman_id = #{salesmanId}",
    )
    fun sumSalesman(
        @Param("companyId") companyId: Long,
        @Param("salesmanId") salesmanId: Long,
    ): WithdrawalSum

    /** 一店一笔在途（申请闸门）：`status IN ('applied','confirmed')` 命中即 422 */
    @Select(
        "<script>SELECT COUNT(*) FROM distribution_cash_withdrawal WHERE company_id = #{companyId} " +
            "AND distributor_id = #{distributorId} AND status IN " +
            "<foreach item=\"s\" collection=\"statuses\" open=\"(\" separator=\",\" close=\")\">#{s}</foreach></script>",
    )
    fun countInFlight(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long,
        @Param("statuses") statuses: List<String>,
    ): Long

    /**
     * 状态迁移（CW-A2）：`from` 进了 WHERE，0 行即"这单已被别人处理过"。
     * `paid_time` 落不到列上（本表无该列），所以终态时刻就是这条 UPDATE 的 `updated`。
     */
    @Update(
        "UPDATE distribution_cash_withdrawal SET status = #{to}, remarks = #{remarks}, updated = #{updated} " +
            "WHERE id = #{id} AND company_id = #{companyId} AND status = #{from}",
    )
    fun transit(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("remarks") remarks: String?,
        @Param("updated") updated: Long,
    ): Int

    @Select(
        "<script>SELECT COUNT(*) FROM distribution_cash_withdrawal WHERE 1=1" +
            "<if test=\"f.companyId != null\"> AND company_id = #{f.companyId}</if>" +
            "<if test=\"f.distributorId != null\"> AND distributor_id = #{f.distributorId}</if>" +
            "<if test=\"f.status != null\"> AND status = #{f.status}</if>" +
            "<if test=\"f.userId != null\"> AND user_id = #{f.userId}</if>" +
            "<if test=\"f.salesmanId != null\"> AND salesman_id = #{f.salesmanId}</if>" +
            "<if test=\"f.salesmanIds != null\"> AND salesman_id IN <foreach item=\"v\" collection=\"f.salesmanIds\" open=\"(\" separator=\",\" close=\")\">#{v}</foreach></if>" +
            "<if test=\"f.onlySalesman == true\"> AND salesman_id &gt; 0</if>" +
            // CW-F2「我的提现」是店铺那侧的账：只出 `salesman_id=0` 的行，
            // 同一个会员替店里提的钱不能混进他自己的店铺列表（§22 C176）
            "<if test=\"f.onlyShop == true\"> AND salesman_id = 0</if>" +
            "<if test=\"f.ids != null\"> AND distributor_id IN <foreach item=\"d\" collection=\"f.ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach></if></script>",
    )
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $W_COLS FROM distribution_cash_withdrawal WHERE 1=1" +
            "<if test=\"f.companyId != null\"> AND company_id = #{f.companyId}</if>" +
            "<if test=\"f.distributorId != null\"> AND distributor_id = #{f.distributorId}</if>" +
            "<if test=\"f.status != null\"> AND status = #{f.status}</if>" +
            "<if test=\"f.userId != null\"> AND user_id = #{f.userId}</if>" +
            "<if test=\"f.salesmanId != null\"> AND salesman_id = #{f.salesmanId}</if>" +
            "<if test=\"f.salesmanIds != null\"> AND salesman_id IN <foreach item=\"v\" collection=\"f.salesmanIds\" open=\"(\" separator=\",\" close=\")\">#{v}</foreach></if>" +
            "<if test=\"f.onlySalesman == true\"> AND salesman_id &gt; 0</if>" +
            // 同上一条：店铺那侧的列表不混岗的钱（`onlyShop` 由服务层在 CW-F2 上置 true）
            "<if test=\"f.onlyShop == true\"> AND salesman_id = 0</if>" +
            "<if test=\"f.ids != null\"> AND distributor_id IN <foreach item=\"d\" collection=\"f.ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach></if>" +
            " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<CashWithdrawalDO>

    /** DL-A2 提现聚合：在途（applied+confirmed）与已打款分开给，`refused` 单列只为展示 */
    @Select(
        "<script>SELECT COALESCE(SUM(CASE WHEN status IN ('applied','confirmed') THEN money ELSE 0 END), 0) AS withdrawApplied, " +
            "COALESCE(SUM(CASE WHEN status = 'paid' THEN money ELSE 0 END), 0) AS withdrawPaid, " +
            "COALESCE(SUM(CASE WHEN status = 'refused' THEN money ELSE 0 END), 0) AS withdrawRefused " +
            "FROM distribution_cash_withdrawal WHERE company_id = #{companyId}" +
            "<if test=\"distributorId != null\"> AND distributor_id = #{distributorId}</if>" +
            "<if test=\"ids != null\"> AND distributor_id IN <foreach item=\"d\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{d}</foreach></if></script>",
    )
    fun sum(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long?,
        @Param("ids") ids: List<Long>?,
    ): WithdrawalSum
}

/**
 * 会员面提现的"这是不是我的店"判据（C152）。
 *
 * `distribution_distributor` 没有归属人列，源系统靠 distributor→user 的关系表；
 * 本项目沿用**在册员工行**（`distribution_distributor_salesman.user_id`）作为唯一可信归属：
 * 店铺老板在这家店里就是这么一条行，`is_valid='true'` 才算在册。
 * 第三批的 SM-A/ST-E 会复用这张表，这里先只开一条读口。
 */
@Mapper
interface SalesmanLinkMapper {

    @Select(
        "SELECT COUNT(*) FROM distribution_distributor_salesman " +
            "WHERE company_id = #{companyId} AND user_id = #{userId} AND distributor_id = #{distributorId} AND is_valid = 'true'",
    )
    fun countActive(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("distributorId") distributorId: Long,
    ): Long
}
