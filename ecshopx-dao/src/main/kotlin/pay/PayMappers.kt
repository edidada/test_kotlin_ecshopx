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

/** offline_payment：线下转账凭证，提交后订单仍待付款，等后台审核（审核动作属 M1-4） */
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
}
