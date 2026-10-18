package com.ecshopx.kotlin.dao.pay

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M0-7 支付域 DAO（trade / point_member / user_deposit / deposit_trade / offline_payment）。
 *
 * 约定与订单域一致：
 *  - 金额一律分；`trade.trade_id` 是应用层号段生成的业务单号（前缀 T / 充值 CZ）
 *  - 所有动账都是条件更新（CAS），返回 0 表示并发已改变余额/状态，调用方据此放弃并回滚
 *  - 结清（settle）幂等：只有 NOTPAY 能变 SUCCESS，重复回调影响 0 行
 */

/** trade：一笔订单的支付单（同一订单可有多笔，只有最后一笔会成功） */
class TradeDO : Stampable {
    var tradeId: String = ""
    var orderId: String = ""
    var companyId: Long = 0
    var userId: String = ""
    var shopId: Long = 0
    var distributorId: Long = 0
    var totalFee: Long = 0
    var discountFee: Long = 0
    var payFee: Long = 0
    var refundedFee: Long = 0
    /** NOTPAY/SUCCESS/CLOSED（REFUND 属 M1 售后） */
    var tradeState: String = TradeState.NOTPAY
    var payType: String? = null
    var payChannel: String? = null
    var transactionId: String? = null
    var body: String? = null
    var timeStart: String? = null
    var timeExpire: String? = null
    var initalRequest: String? = null
    var initalResponse: String? = null
    var isSettled: Boolean = false
    /** M1-2：本地已 CLOSED 的支付单是否已向渠道发过关单（0 待发 / 1 已确认，1 之后不再重试） */
    var channelClosed: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

object TradeState {
    const val NOTPAY = "NOTPAY"
    const val SUCCESS = "SUCCESS"
    const val CLOSED = "CLOSED"
}

@Mapper
interface TradeMapper {

    @Insert("INSERT INTO trade(trade_id, order_id, company_id, user_id, shop_id, distributor_id, total_fee, discount_fee, " +
        "pay_fee, trade_state, pay_type, transaction_id, body, time_start, inital_request, is_settled, created, updated) " +
        "VALUES(#{tradeId}, #{orderId}, #{companyId}, #{userId}, #{shopId}, #{distributorId}, #{totalFee}, #{discountFee}, " +
        "#{payFee}, #{tradeState}, #{payType}, #{transactionId}, #{body}, #{timeStart}, #{initalRequest}, #{isSettled}, #{created}, #{updated})")
    fun insert(trade: TradeDO): Int

    @Select("SELECT * FROM trade WHERE trade_id = #{tradeId}")
    fun findById(@Param("tradeId") tradeId: String): TradeDO?

    /** 未付款的活跃支付单：重复发起支付时复用，避免一个订单挂一串 NOTPAY 单 */
    @Select("SELECT * FROM trade WHERE order_id = #{orderId} AND trade_state = 'NOTPAY' ORDER BY created DESC LIMIT 1")
    fun findActiveByOrder(@Param("orderId") orderId: String): TradeDO?

    @Select("SELECT * FROM trade WHERE order_id = #{orderId} ORDER BY created DESC")
    fun listByOrder(@Param("orderId") orderId: String): List<TradeDO>

    /** 结清：NOTPAY → SUCCESS，返回 0 = 已结清/已关闭（渠道重复通知天然幂等） */
    @Update("UPDATE trade SET trade_state = 'SUCCESS', pay_fee = #{payFee}, pay_type = #{payType}, " +
        "transaction_id = #{transactionId}, is_settled = TRUE, updated = #{updated} " +
        "WHERE trade_id = #{tradeId} AND trade_state = 'NOTPAY'")
    fun settle(
        @Param("tradeId") tradeId: String,
        @Param("payFee") payFee: Long,
        @Param("payType") payType: String?,
        @Param("transactionId") transactionId: String?,
        @Param("updated") updated: Long,
    ): Int

    /** 订单被关闭时收掉未付支付单，防止渠道侧"已付但订单已关"的误判 */
    @Update("UPDATE trade SET trade_state = 'CLOSED', updated = #{updated} WHERE order_id = #{orderId} AND trade_state = 'NOTPAY'")
    fun closeByOrder(@Param("orderId") orderId: String, @Param("updated") updated: Long): Int

    @Update("UPDATE trade SET inital_response = #{body}, updated = #{updated} WHERE trade_id = #{tradeId}")
    fun saveResponse(@Param("tradeId") tradeId: String, @Param("body") body: String?, @Param("updated") updated: Long): Int

    /**
     * 退款取原支付单：只认 SUCCESS（CLOSED/NOTPAY 上没有实付，退不了）。
     * 一单多笔支付时取最后结清的那笔，transaction_id 就是原路退的凭据。
     */
    @Select("SELECT * FROM trade WHERE order_id = #{orderId} AND trade_state = 'SUCCESS' ORDER BY updated DESC LIMIT 1")
    fun findSuccessByOrder(@Param("orderId") orderId: String): TradeDO?

    /**
     * 支付单累计退款（守恒不变式的支付侧，docs/04 §11 D10）：
     *  - **不改 trade_state**（源系统售后只累加 refunded_fee，交易状态归订单域管）
     *  - `AND refunded_fee + ? <= pay_fee` 由数据库兜底，影响 0 行 = 这笔试图退超实付
     */
    @Update("UPDATE trade SET refunded_fee = refunded_fee + #{fee}, updated = #{updated} " +
        "WHERE trade_id = #{tradeId} AND refunded_fee + #{fee} <= pay_fee")
    fun addRefundedFee(@Param("tradeId") tradeId: String, @Param("fee") fee: Long, @Param("updated") updated: Long): Int

    /**
     * 补单作业扫描（docs/05 §M1-2 查单）：本地仍 `NOTPAY`、但已经"过了回调窗口"的外部渠道支付单。
     * 只扫有渠道可查的类型（wxpay/alipay），本地渠道（point/deposit/offline_pay）没有"渠道侧的钱"，
     * 扫它们只会白白打渠道接口；`created <= #{beforeSec}` 给回调留出正常到达的时间。
     */
    @Select("<script>SELECT * FROM trade WHERE trade_state = 'NOTPAY' AND created &lt;= #{beforeSec} " +
        "AND pay_type IN <foreach item='t' collection='payTypes' open='(' separator=',' close=')'>#{t}</foreach> " +
        "ORDER BY created LIMIT #{limit}</script>")
    fun listStaleNotpay(
        @Param("beforeSec") beforeSec: Long,
        @Param("payTypes") payTypes: List<String>,
        @Param("limit") limit: Int,
    ): List<TradeDO>

    /**
     * 待向渠道关单的支付单（M1-2 补单作业第二条腿）。
     *
     * 只看**本地**状态：订单关闭时 `closeByOrder` 已经把 `trade_state` 收掉，
     * 所以"订单已取消 + 渠道仍 NOTPAY"这种条件在库里永远凑不出来 ——
     * 真正没收敛的是"渠道侧那张预支付单还活着"，它由 `channel_closed` 这一位记着。
     * `updated <= #{beforeSec}` 同样是宽限期：刚关掉就发 close，会和同一轮里正在到达的回调抢行。
     */
    @Select("<script>SELECT * FROM trade WHERE trade_state = 'CLOSED' AND channel_closed = 0 " +
        "AND updated &lt;= #{beforeSec} " +
        "AND pay_type IN <foreach item='t' collection='payTypes' open='(' separator=',' close=')'>#{t}</foreach> " +
        "ORDER BY updated LIMIT #{limit}</script>")
    fun listPendingChannelClose(
        @Param("beforeSec") beforeSec: Long,
        @Param("payTypes") payTypes: List<String>,
        @Param("limit") limit: Int,
    ): List<TradeDO>

    /**
     * 关单确认（CAS）：只从 `channel_closed=0` 出发。
     * 影响 0 行 = 并发的另一轮已经确认过，本轮什么都不用做。
     */
    @Update("UPDATE trade SET channel_closed = 1, updated = #{updated} " +
        "WHERE trade_id = #{tradeId} AND channel_closed = 0")
    fun markChannelClosed(@Param("tradeId") tradeId: String, @Param("updated") updated: Long): Int
}

/**
 * 积分流水筛选字典（PT-F2 会员面 + PT-A3 后台面共用）。
 *
 * `remark` 用 LIKE 而不是等值：备注是给人读的中文（"订单支付赠送积分"、"后台调账"），
 * 运营只会输入"调账"两个字，等值匹配等于这个筛选项没用。
 * 时间列是 `created`（这一族用 created/updated，与 `orders_invoice` 的 create_time 不同名同义都别混）。
 */
private const val PT_LOG_WHERE = """
    WHERE 1=1
    <if test="f.companyId != null">AND l.company_id = #{f.companyId}</if>
    <if test="f.userId != null">AND l.user_id = #{f.userId}</if>
    <if test="f.orderId != null">AND l.order_id = #{f.orderId}</if>
    <if test="f.remark != null">AND l.remark LIKE CONCAT('%', #{f.remark}, '%')</if>
    <if test="f.positive == true">AND l.change_point &gt; 0</if>
    <if test="f.negative == true">AND l.change_point &lt; 0</if>
    <if test="f.createdStart != null">AND l.created &gt;= #{f.createdStart}</if>
    <if test="f.createdEnd != null">AND l.created &lt;= #{f.createdEnd}</if>
    <if test="f.distributorIds != null">AND EXISTS (SELECT 1 FROM orders_normal_orders oo
        WHERE oo.user_id = l.user_id AND oo.distributor_id IN
        <foreach item="g" collection="f.distributorIds" open="(" separator="," close=")">#{g}</foreach>)</if>
"""

/**
 * 会员积分列表筛选（PT-A1）：`keyword` 打昵称与手机号两列，datapass 掩码只作用于出参。
 *
 * `distributorIds` 同 [DT_WHERE] 里那条：积分账户表没有店铺归属列，
 * 受限账号按"在本店下过单"收窄，而不是因为没列就放行全场。
 */
private const val PT_ACCOUNT_WHERE = """
    WHERE 1=1
    <if test="f.companyId != null">AND p.company_id = #{f.companyId}</if>
    <if test="f.userId != null">AND p.user_id = #{f.userId}</if>
    <if test="f.keyword != null">AND (mi.username LIKE CONCAT('%', #{f.keyword}, '%')
        OR m.mobile LIKE CONCAT('%', #{f.keyword}, '%'))</if>
    <if test="f.onlyHasPoint == true">AND p.point &gt; 0</if>
    <if test="f.distributorIds != null">AND EXISTS (SELECT 1 FROM orders_normal_orders oo
        WHERE oo.user_id = p.user_id AND oo.distributor_id IN
        <foreach item="g" collection="f.distributorIds" open="(" separator="," close=")">#{g}</foreach>)</if>
"""

/** point_member + point_member_log：积分账户（动账留痕，before/after 便于对账） */
class PointAccountDO {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var point: Long = 0
    var totalPoint: Long = 0
    var updated: Long = 0
}

class PointLogDO {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var orderId: String? = null
    var changePoint: Long = 0
    var beforePoint: Long = 0
    var afterPoint: Long = 0
    var remark: String? = null
    var created: Long = 0
}

@Mapper
interface PointMapper {

    @Select("SELECT * FROM point_member WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun find(@Param("companyId") companyId: Long, @Param("userId") userId: Long): PointAccountDO?

    @Insert("INSERT INTO point_member(company_id, user_id, point, total_point, updated) VALUES(#{companyId}, #{userId}, 0, 0, #{updated})")
    fun insertEmpty(@Param("companyId") companyId: Long, @Param("userId") userId: Long, @Param("updated") updated: Long): Int

    /** 扣分：积分不足影响 0 行 */
    @Update("UPDATE point_member SET point = point - #{point}, updated = #{updated} " +
        "WHERE company_id = #{companyId} AND user_id = #{userId} AND point >= #{point}")
    fun deduct(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("point") point: Long,
        @Param("updated") updated: Long,
    ): Int

    /** 得分：同时累加 total_point（累计获得） */
    @Update("UPDATE point_member SET point = point + #{point}, total_point = total_point + #{point}, updated = #{updated} " +
        "WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun add(@Param("companyId") companyId: Long, @Param("userId") userId: Long, @Param("point") point: Long, @Param("updated") updated: Long): Int

    @Insert("INSERT INTO point_member_log(company_id, user_id, order_id, change_point, before_point, after_point, remark, created) " +
        "VALUES(#{companyId}, #{userId}, #{orderId}, #{changePoint}, #{beforePoint}, #{afterPoint}, #{remark}, #{created})")
    fun insertLog(log: PointLogDO): Int

    /**
     * 退积分（M1-1）：只回补余额，**不动 total_point**。
     * total_point 是"累计获得"口径，退款回补不是获得，照抄源系统。
     */
    @Update("UPDATE point_member SET point = point + #{point}, updated = #{updated} " +
        "WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun refund(@Param("companyId") companyId: Long, @Param("userId") userId: Long, @Param("point") point: Long, @Param("updated") updated: Long): Int

    @Select("SELECT COUNT(*) FROM point_member_log WHERE order_id = #{orderId}")
    fun countLogByOrder(@Param("orderId") orderId: String): Int

    // ===== M1-5 读口（docs/04 §23.1 PT-F1/F2、§23.2 PT-A1/A2/A3）=====

    /**
     * 积分流水分页（PT-F2 只传 userId，PT-A3 传 company/时间段）。
     *
     * 一张 `page`/`count` 同时服务会员面与后台面：会员面由服务层强制塞 `userId`，
     * 后台面不塞。少写一条 SQL 的代价是零，多写一条的代价是两套筛选条件各漂移一次。
     */
    @Select(
        "<script>SELECT l.id, l.company_id, l.user_id, l.order_id, l.change_point, l.before_point, l.after_point, " +
            "l.remark, l.created, mi.username AS nickname, m.mobile AS mobile " +
            "FROM point_member_log l " +
            "LEFT JOIN members m ON m.user_id = l.user_id " +
            "LEFT JOIN members_info mi ON mi.user_id = l.user_id" + PT_LOG_WHERE +
            " ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun logPage(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<PointLogRow>

    @Select("<script>SELECT COUNT(*) FROM point_member_log l" + PT_LOG_WHERE + "</script>")
    fun logCount(@Param("f") f: Map<String, Any?>): Long

    /**
     * 会员积分列表（PT-A1）：账号表在 `point_member`，昵称手机号 join 会员两表。
     *
     * `LEFT JOIN` 是刻意的：注销会员的积分行也要出现在对账列表里，
     * 不然"账上有钱但人没了"这一类差异会从后台视野里消失。
     */
    @Select(
        "<script>SELECT p.id, p.company_id, p.user_id, p.point, p.total_point, p.updated, " +
            "mi.username AS nickname, m.mobile AS mobile " +
            "FROM point_member p " +
            "LEFT JOIN members m ON m.user_id = p.user_id " +
            "LEFT JOIN members_info mi ON mi.user_id = p.user_id" + PT_ACCOUNT_WHERE +
            " ORDER BY p.point DESC, p.user_id LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun accountPage(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<PointAccountRow>

    @Select("<script>SELECT COUNT(*) FROM point_member p " + PT_ACCOUNT_WHERE + "</script>")
    fun accountCount(@Param("f") f: Map<String, Any?>): Long

    /**
     * PT-A1 的卡头三个"现值"：可用合计、累计获得合计、人数。
     *
     * 与 `accountPage`/`accountCount` 共用 [PT_ACCOUNT_WHERE]，所以店铺运营看到的卡头
     * 与他列表里的行是**同一个集合** —— 全场合计配本店明细会让"总数比明细大"成为常态，
     * 那是最容易让人怀疑账错了的组合。
     */
    @Select(
        "<script>SELECT COALESCE(SUM(p.point), 0) AS point_sum, COALESCE(SUM(p.total_point), 0) AS granted_sum, " +
            "COUNT(*) AS member_count FROM point_member p " +
            "LEFT JOIN members m ON m.user_id = p.user_id " +
            "LEFT JOIN members_info mi ON mi.user_id = p.user_id" + PT_ACCOUNT_WHERE + "</script>",
    )
    fun accountStats(@Param("f") f: Map<String, Any?>): PointStatsDO

    /**
     * PT-A1 卡头的"累计扣减"：取日志里负向动账的**绝对值**，不是 `total_point - point`。
     *
     * 后者把"回补"（退款/取消往回加、不动 total_point，见 [refund]）算没了，
     * 两个口径在账对得上时相等，对不上的时候只有本条能暴露差额。
     * 同样走 [PT_LOG_WHERE]（含 `distributorIds` 那条收窄），与上面一条配对。
     *
     * `AND l.change_point < 0` 写死在 SQL 里、不靠 `f.negative`：这一条的调用方是 PT-A1 的卡头，
     * 它的筛选字典（`PointService.filter`）根本没有 `negative` 这一位 —— 挂在入参上等于没挂，
     * 于是 `SUM(-change_point)` 会把正流水也一起翻成负数，实测把"扣了 21100"的账户回成 `-19000`
     * （-19000 = -(100 + 20000 + 20000 - 21100)）。负向口径是这条 SQL 自己的语义，不是查询条件。
     */
    @Select(
        "<script>SELECT COALESCE(SUM(-l.change_point), 0) FROM point_member_log l " +
            "LEFT JOIN members m ON m.user_id = l.user_id " +
            "LEFT JOIN members_info mi ON mi.user_id = l.user_id" + PT_LOG_WHERE +
            " AND l.change_point &lt; 0</script>",
    )
    fun usedSum(@Param("f") f: Map<String, Any?>): Long

    /** 单个会员的累计扣减（PT-F1 的 `user_point`）：与 [usedSum] 同一条负向口径，只是多一个 user_id */
    @Select(
        "SELECT COALESCE(SUM(-change_point), 0) FROM point_member_log " +
            "WHERE company_id = #{companyId} AND user_id = #{userId} AND change_point < 0",
    )
    fun userUsedSum(@Param("companyId") companyId: Long, @Param("userId") userId: Long): Long

    /**
     * PT-A2 调账（CAS）：`point = #{to}` 由服务层算好（负数已钳到 0），
     * `expect` 是服务层读到的原值 —— 期间有别的动账就回 0 行，让调用方重读重算，
     * 而不是把两条并发动账合成一个看不出来的数。
     *
     * `granted` 单独传：正数调账算"获得"（动 total_point），负数与钳位不动它（PT-R2）。
     */
    @Update("UPDATE point_member SET point = #{to}, total_point = total_point + #{granted}, updated = #{updated} " +
        "WHERE id = #{id} AND point = #{expect}")
    fun casAdjust(
        @Param("id") id: Long,
        @Param("to") to: Long,
        @Param("granted") granted: Long,
        @Param("expect") expect: Long,
        @Param("updated") updated: Long,
    ): Int
}

/** PT-A1 列表行（账号 + 会员身份） */
class PointAccountRow {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var point: Long = 0
    var totalPoint: Long = 0
    var updated: Long = 0
    var nickname: String? = null
    var mobile: String? = null
}

/** PT-A3 流水行（后台面多带会员昵称手机号；会员面用同一行，服务层不出这两个键） */
class PointLogRow {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var orderId: String? = null
    var changePoint: Long = 0
    var beforePoint: Long = 0
    var afterPoint: Long = 0
    var remark: String? = null
    var created: Long = 0
    var nickname: String? = null
    var mobile: String? = null
}

/** PT-A1 卡头（聚合结果必须落 DO：H2 小写化后驼峰别名会丢，见 OrdersMapper 同一课） */
class PointStatsDO {
    var pointSum: Long = 0
    var grantedSum: Long = 0
    var memberCount: Long = 0
}

/** user_deposit / deposit_trade / deposit_recharge_rule：储值余额（本金 + 赠金两栏） */
class DepositDO {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var depositAmount: Long = 0
    var giftAmount: Long = 0
    var totalRecharge: Long = 0
    var updated: Long = 0
}

class DepositTradeDO : Stampable {
    var tradeId: String = ""
    var companyId: Long = 0
    var userId: Long = 0
    /** recharge 充值 / consume 消费 / refund 退回 */
    var tradeType: String = "consume"
    var relId: String? = null
    var amount: Long = 0
    var giftAmount: Long = 0
    var beforeBalance: Long = 0
    var afterBalance: Long = 0
    var status: String = TradeState.NOTPAY
    /** 动账理由（CZ-A3 代客充值必填）：账上唯一"没有订单来源"的说明位 */
    var remark: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

class RechargeRuleDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var ruleName: String? = null
    var rechargeAmount: Long = 0
    var giftAmount: Long = 0
    /** 1 上架 / 0 下架：CZ-F3 只出 1，CZ-A2 后台两态都出 */
    var status: Int = 1
    override var created: Long = 0
    override var updated: Long = 0
}

/**
 * 储值流水筛选字典（CZ-F2 会员面 + CZ-A1 后台面共用）。
 *
 * 排序用 `created DESC, trade_id DESC`：表没有自增列（主键是业务单号），
 * 同一秒内的多笔动账只靠 created 排不出稳定序，翻页会跳行 —— 带 trade_id 兜底才有全序。
 *
 * `distributorIds` 那条 EXISTS 是给店铺运营（role 2）收窄用的：
 * `deposit_trade`/`user_deposit` 都没有店铺归属列（储值是平台资产），
 * 但给了他 `deposit.lists` 不等于让他看到**全场**会员的余额与流水，
 * 于是按"这个会员在本店下过单"收 —— 与 [PT_ACCOUNT_WHERE] 同一条口径，两处不各写一套。
 */
private const val DT_WHERE = """
    WHERE 1=1
    <if test="f.companyId != null">AND t.company_id = #{f.companyId}</if>
    <if test="f.userId != null">AND t.user_id = #{f.userId}</if>
    <if test="f.tradeType != null">AND t.trade_type = #{f.tradeType}</if>
    <if test="f.status != null">AND t.status = #{f.status}</if>
    <if test="f.relId != null">AND t.rel_id = #{f.relId}</if>
    <if test="f.keyword != null">AND (mi.username LIKE CONCAT('%', #{f.keyword}, '%')
        OR m.mobile LIKE CONCAT('%', #{f.keyword}, '%')
        OR t.trade_id LIKE CONCAT('%', #{f.keyword}, '%'))</if>
    <if test="f.createdStart != null">AND t.created &gt;= #{f.createdStart}</if>
    <if test="f.createdEnd != null">AND t.created &lt;= #{f.createdEnd}</if>
    <if test="f.distributorIds != null">AND EXISTS (SELECT 1 FROM orders_normal_orders oo
        WHERE oo.user_id = t.user_id AND oo.distributor_id IN
        <foreach item="g" collection="f.distributorIds" open="(" separator="," close=")">#{g}</foreach>)</if>
"""

@Mapper
interface DepositMapper {

    @Select("SELECT * FROM user_deposit WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun find(@Param("companyId") companyId: Long, @Param("userId") userId: Long): DepositDO?

    @Insert("INSERT INTO user_deposit(company_id, user_id, deposit_amount, gift_amount, total_recharge, updated) " +
        "VALUES(#{companyId}, #{userId}, 0, 0, 0, #{updated})")
    fun insertEmpty(@Param("companyId") companyId: Long, @Param("userId") userId: Long, @Param("updated") updated: Long): Int

    /**
     * 乐观扣款：整行余额（本金 + 赠金）都按读到的值做条件更新，
     * 影响 0 行 = 期间有别的动账，调用方重试或直接报"余额已变动"。
     */
    @Update("UPDATE user_deposit SET deposit_amount = #{depositAmount}, gift_amount = #{giftAmount}, updated = #{updated} " +
        "WHERE id = #{id} AND deposit_amount = #{expectDeposit} AND gift_amount = #{expectGift}")
    fun swap(
        @Param("id") id: Long,
        @Param("depositAmount") depositAmount: Long,
        @Param("giftAmount") giftAmount: Long,
        @Param("expectDeposit") expectDeposit: Long,
        @Param("expectGift") expectGift: Long,
        @Param("updated") updated: Long,
    ): Int

    /** 充值到账：本金 + 赠金 + 累计充值一并累加 */
    @Update("UPDATE user_deposit SET deposit_amount = deposit_amount + #{amount}, gift_amount = gift_amount + #{gift}, " +
        "total_recharge = total_recharge + #{amount}, updated = #{updated} WHERE id = #{id}")
    fun recharge(
        @Param("id") id: Long,
        @Param("amount") amount: Long,
        @Param("gift") gift: Long,
        @Param("updated") updated: Long,
    ): Int

    @Insert("INSERT INTO deposit_trade(trade_id, company_id, user_id, trade_type, rel_id, amount, gift_amount, " +
        "before_balance, after_balance, status, remark, created, updated) " +
        "VALUES(#{tradeId}, #{companyId}, #{userId}, #{tradeType}, #{relId}, #{amount}, #{giftAmount}, " +
        "#{beforeBalance}, #{afterBalance}, #{status}, #{remark}, #{created}, #{updated})")
    fun insertTrade(t: DepositTradeDO): Int

    /**
     * 退回储值（M1-1）：本金/赠金**分栏**回补——消费时按什么比例扣的就按什么比例退，
     * 赠金不能退成本金（否则等于白送充值赠金）。
     */
    @Update("UPDATE user_deposit SET deposit_amount = deposit_amount + #{principal}, gift_amount = gift_amount + #{gift}, " +
        "updated = #{updated} WHERE id = #{id}")
    fun refund(
        @Param("id") id: Long,
        @Param("principal") principal: Long,
        @Param("gift") gift: Long,
        @Param("updated") updated: Long,
    ): Int

    @Update("UPDATE deposit_trade SET status = #{status}, updated = #{updated} WHERE trade_id = #{tradeId}")
    fun updateTradeStatus(@Param("tradeId") tradeId: String, @Param("status") status: String, @Param("updated") updated: Long): Int

    @Select("SELECT * FROM deposit_trade WHERE trade_id = #{tradeId}")
    fun findTrade(@Param("tradeId") tradeId: String): DepositTradeDO?

    /**
     * 这笔订单的余额消费留痕（退款按"本金/赠金"原比例回补的唯一依据）。
     * 表没有自增列，主键是业务单号，故按 created 取最近一笔。
     */
    @Select("SELECT * FROM deposit_trade WHERE rel_id = #{orderId} AND trade_type = 'consume' ORDER BY created DESC LIMIT 1")
    fun findConsumeByOrder(@Param("orderId") orderId: String): DepositTradeDO?

    @Select("SELECT * FROM deposit_recharge_rule WHERE company_id = #{companyId} AND status = 1 ORDER BY recharge_amount")
    fun listRules(@Param("companyId") companyId: Long): List<RechargeRuleDO>

    // ===== M1-5 读口与档位 CRUD（docs/04 §23.1 CZ-F1/F2/F3、§23.2 CZ-A1/A2/A4）=====

    /**
     * 储值流水分页（CZ-F2 会员面 + CZ-A1 后台面共用，同 PT 那一条的理由）。
     *
     * **`NOTPAY` 不过滤**：会员刚点充值、支付还没回来时，那条 CZ 单就是他"在等的那笔"，
     * 列表里看不见只会让他再点一次（然后两笔 NOTPAY）。
     */
    @Select(
        "<script>SELECT t.trade_id, t.company_id, t.user_id, t.trade_type, t.rel_id, t.amount, t.gift_amount, " +
            "t.before_balance, t.after_balance, t.status, t.remark, t.created, t.updated, " +
            "mi.username AS nickname, m.mobile AS mobile " +
            "FROM deposit_trade t " +
            "LEFT JOIN members m ON m.user_id = t.user_id " +
            "LEFT JOIN members_info mi ON mi.user_id = t.user_id" + DT_WHERE +
            " ORDER BY t.created DESC, t.trade_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun tradePage(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<DepositTradeRow>

    @Select("<script>SELECT COUNT(*) FROM deposit_trade t" + DT_WHERE + "</script>")
    fun tradeCount(@Param("f") f: Map<String, Any?>): Long

    /**
     * CZ-A4 的两个"现值"：本金与赠金各一栏（`CZ-R1` 说它们在账上分列）。
     *
     * 与 `rechargeSumSince`、`notpayCount` **分成三条查**是刻意的：
     * 一条 SQL 出四个数看着省，但 `user_deposit` 的现值与 `deposit_trade` 的历史
     * 是两个时间轴，拼在一个 `<script>` 里迟早有人以为它们能相互加减。
     */
    @Select("SELECT COALESCE(SUM(deposit_amount), 0) AS principal_sum, COALESCE(SUM(gift_amount), 0) AS gift_sum, " +
        "COUNT(*) AS member_count FROM user_deposit WHERE company_id = #{companyId}")
    fun stats(@Param("companyId") companyId: Long): DepositStatsDO

    /** 待支付的充值单（CZ-A4 的 `notpay_count`，也是巡检"有多少笔卡在支付中"的口径） */
    @Select("SELECT COUNT(*) FROM deposit_trade WHERE company_id = #{companyId} " +
        "AND trade_type = 'recharge' AND status = 'NOTPAY'")
    fun notpayCount(@Param("companyId") companyId: Long): Long

    /** 近 N 秒的充值到账合计（CZ-A4 的 `recharge_sum_7d`；只算 SUCCESS，NOTPAY 不是收入） */
    @Select("SELECT COALESCE(SUM(amount), 0) FROM deposit_trade WHERE company_id = #{companyId} " +
        "AND trade_type = 'recharge' AND status = 'SUCCESS' AND created >= #{since}")
    fun rechargeSumSince(@Param("companyId") companyId: Long, @Param("since") since: Long): Long

    /** CZ-A2 后台档位列表：不带 `status = 1`，关掉的档位运营要看得见才能决定开不开 */
    @Select("<script>SELECT * FROM deposit_recharge_rule WHERE company_id = #{companyId} " +
        "<if test='status != null'>AND status = #{status}</if> ORDER BY recharge_amount</script>")
    fun listRulesAll(@Param("companyId") companyId: Long, @Param("status") status: Int?): List<RechargeRuleDO>

    @Select("SELECT * FROM deposit_recharge_rule WHERE id = #{id} AND company_id = #{companyId}")
    fun findRule(@Param("id") id: Long, @Param("companyId") companyId: Long): RechargeRuleDO?

    @Insert("INSERT INTO deposit_recharge_rule(company_id, rule_name, recharge_amount, gift_amount, status, created, updated) " +
        "VALUES(#{companyId}, #{ruleName}, #{rechargeAmount}, #{giftAmount}, #{status}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insertRule(r: RechargeRuleDO): Int

    @Update("UPDATE deposit_recharge_rule SET rule_name = #{ruleName}, recharge_amount = #{rechargeAmount}, " +
        "gift_amount = #{giftAmount}, status = #{status}, updated = #{updated} WHERE id = #{id} AND company_id = #{companyId}")
    fun updateRule(r: RechargeRuleDO): Int

    /**
     * 删档位不设守卫（CZ-A2）：档位是价目表不是账，
     * 历史 `deposit_trade` 已把**当时**的 amount/gift_amount 写进自己那一行，删规则不动流水。
     */
    @Delete("DELETE FROM deposit_recharge_rule WHERE id = #{id} AND company_id = #{companyId}")
    fun deleteRule(@Param("id") id: Long, @Param("companyId") companyId: Long): Int

    /**
     * CZ-A3 代客充值后的"这笔会员动了哪些账"（冒烟脚本按 trade_id 回查，不靠内存）。
     * `trade_id` 是主键，单条查走主键索引，无需另建。
     */
    @Select("SELECT t.trade_id, t.company_id, t.user_id, t.trade_type, t.rel_id, t.amount, t.gift_amount, " +
        "t.before_balance, t.after_balance, t.status, t.remark, t.created, t.updated, " +
        "mi.username AS nickname, m.mobile AS mobile " +
        "FROM deposit_trade t LEFT JOIN members m ON m.user_id = t.user_id " +
        "LEFT JOIN members_info mi ON mi.user_id = t.user_id WHERE t.trade_id = #{tradeId}")
    fun findTradeRow(@Param("tradeId") tradeId: String): DepositTradeRow?
}

/** CZ-A1/CZ-F2 列表行：动账本体 + 会员身份（datapass 判的是 `mobile`） */
class DepositTradeRow {
    var tradeId: String? = null
    var companyId: Long = 0
    var userId: Long = 0
    var tradeType: String? = null
    var relId: String? = null
    var amount: Long = 0
    var giftAmount: Long = 0
    var beforeBalance: Long = 0
    var afterBalance: Long = 0
    var status: String? = null
    var remark: String? = null
    var created: Long = 0
    var updated: Long = 0
    var nickname: String? = null
    var mobile: String? = null
}

/** CZ-A4 的现值三项（聚合同样落 DO，别用驼峰 Map 键） */
class DepositStatsDO {
    var principalSum: Long = 0
    var giftSum: Long = 0
    var memberCount: Long = 0
}

/** offline_payment：线下转账凭证。提交后订单仍待付款，`check_status=1` 由后台审核结清（SUP-6，M1-2 落地） */
class OfflinePaymentDO : Stampable {
    var id: Long = 0
    var orderId: Long = 0
    var companyId: Long = 0
    var totalFee: Long = 0
    var payFee: Long = 0
    /** 0 待处理 / 1 已审核 / 2 已拒绝 */
    var checkStatus: Int = 0
    var paySn: String? = null
    var voucherPic: String? = null
    var transferRemark: String? = null
    /** 审核人（M1-2 审核流写入；提交凭证时为 null） */
    var operatorName: String? = null
    var remark: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface OfflinePaymentMapper {

    @Insert("INSERT INTO offline_payment(order_id, company_id, total_fee, pay_fee, check_status, pay_sn, voucher_pic, " +
        "transfer_remark, remark, created, updated) " +
        "VALUES(#{orderId}, #{companyId}, #{totalFee}, #{payFee}, #{checkStatus}, #{paySn}, #{voucherPic}, " +
        "#{transferRemark}, #{remark}, #{created}, #{updated})")
    fun insert(p: OfflinePaymentDO): Int

    @Select("SELECT * FROM offline_payment WHERE order_id = #{orderId} ORDER BY id DESC")
    fun listByOrder(@Param("orderId") orderId: Long): List<OfflinePaymentDO>

    @Select("SELECT COUNT(*) FROM offline_payment WHERE order_id = #{orderId} AND check_status = 0")
    fun countPending(@Param("orderId") orderId: Long): Int

    @Select("SELECT * FROM offline_payment WHERE id = #{id} AND company_id = #{companyId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): OfflinePaymentDO?

    /**
     * SUP-6 审核列表：`f` 支持 `checkStatus`（0/1/2，null=全部）与 `orderId`。
     * 别名/列名一律走 `SELECT *` + map-underscore-to-camel-case，和 aftersales 族同一口径。
     */
    @Select("<script>SELECT * FROM offline_payment WHERE company_id = #{f.companyId}" +
        "<if test=\"f.checkStatus != null\"> AND check_status = #{f.checkStatus}</if>" +
        "<if test=\"f.orderId != null\"> AND order_id = #{f.orderId}</if>" +
        " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    fun page(
        @Param("f") f: Map<String, Any?>,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<OfflinePaymentDO>

    @Select("<script>SELECT COUNT(*) FROM offline_payment WHERE company_id = #{f.companyId}" +
        "<if test=\"f.checkStatus != null\"> AND check_status = #{f.checkStatus}</if>" +
        "<if test=\"f.orderId != null\"> AND order_id = #{f.orderId}</if></script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    /**
     * 审核动作（CAS）：只从 `check_status=0` 出发，重复点"通过"第二次拿 0 行，
     * 所以"审核通过 + 结清订单"不会被执行两遍——结清本身还有 trade 的 NOTPAY CAS 兜底。
     */
    @Update("UPDATE offline_payment SET check_status = #{toStatus}, pay_fee = #{payFee}, remark = #{remark}, " +
        "operator_name = #{operatorName}, updated = #{updated} " +
        "WHERE id = #{id} AND company_id = #{companyId} AND check_status = 0")
    fun audit(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("toStatus") toStatus: Int,
        @Param("payFee") payFee: Long,
        @Param("remark") remark: String?,
        @Param("operatorName") operatorName: String?,
        @Param("updated") updated: Long,
    ): Int
}
