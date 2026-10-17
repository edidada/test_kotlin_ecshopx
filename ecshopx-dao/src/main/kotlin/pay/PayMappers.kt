package com.ecshopx.kotlin.dao.pay

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
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
    override var created: Long = 0
    override var updated: Long = 0
}

class RechargeRuleDO {
    var id: Long = 0
    var companyId: Long = 0
    var ruleName: String? = null
    var rechargeAmount: Long = 0
    var giftAmount: Long = 0
    var status: Int = 1
}

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
        "before_balance, after_balance, status, created, updated) " +
        "VALUES(#{tradeId}, #{companyId}, #{userId}, #{tradeType}, #{relId}, #{amount}, #{giftAmount}, " +
        "#{beforeBalance}, #{afterBalance}, #{status}, #{created}, #{updated})")
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
