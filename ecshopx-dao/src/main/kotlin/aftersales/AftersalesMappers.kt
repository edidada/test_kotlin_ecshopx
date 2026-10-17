package com.ecshopx.kotlin.dao.aftersales

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-1 售后域 DAO（`aftersales` / `aftersales_detail` / `aftersales_refund`）。
 *
 * 口径三条（docs/04 §11）：
 *  1. 状态码**照抄源系统整数码**（status 0..4，progress 0/1/2/8/9/3/4/7），不重命名、不复用位运算；
 *     主表与明细表**永远双写**（源系统所有迁移都是同 filter 两次 update），所以每个迁移方法都要成对调用。
 *  2. 一切迁移都是 CAS：`WHERE aftersales_bn = ? AND company_id = ? AND aftersales_status = ? AND progress = ?`，
 *     影响 0 行 = 并发已经改过状态，调用方必须放弃而不是补写。
 *  3. 守恒不变式落在 SQL 上（不是服务层 if）：`trade.refunded_fee` 的累加带
 *     `AND refunded_fee + ? <= pay_fee`，`user_deposit`/`point_member` 的退款累加同样条件更新——
 *     "多次部分退款不能退超"由数据库保证，抢输的一边拿到 0 行。
 */

/** `aftersales.aftersales_status`（源系统整数码） */
object AftersalesStatus {
    const val PENDING = 0 // 待处理
    const val PROCESSING = 1 // 处理中
    const val DONE = 2 // 已处理
    const val REFUSED = 3 // 已驳回
    const val CANCELLED = 4 // 已撤销

    /** 仅明细行有：5=审核中 */
    const val AUDITING = 5

    /**
     * "仍占用额度"的状态集合：申请侧金额/件数守恒只统计这些行，
     * 已驳回(3)/已撤销(4) 不占额度（与 PHP `aftersales_status IN (0,5,1,2)` 完全一致）。
     */
    const val OCCUPYING_IN = "0, 5, 1, 2"
}

/** `aftersales.progress`（源系统整数码） */
object AftersalesProgress {
    const val WAIT_SELLER = 0 // 等待商家处理
    const val WAIT_BUYER_RETURN = 1 // 已接受申请，待买家寄回
    const val BUYER_RETURNED = 2 // 买家已寄回
    const val SELLER_CONFIRMED = 8 // 商家已确认收货，待退款
    const val REFUND_PROCESSING = 9 // 退款处理中
    const val REFUSED = 3 // 申请已驳回
    const val FINISHED = 4 // 已处理（终态）
    const val CANCELLED = 7 // 已撤销（终态）
    const val REFUND_REFUSED = 5 // 退款驳回（确认收货后拒退）
    const val REFUND_FINISHED = 6 // 退款完成
}

/** `aftersales_type` */
object AftersalesType {
    const val ONLY_REFUND = "ONLY_REFUND" // 仅退款
    const val REFUND_GOODS = "REFUND_GOODS" // 退货退款
    const val EXCHANGING_GOODS = "EXCHANGING_GOODS" // 换货（M2）
}

/** 订单行 `aftersales_status`（源系统是字符串枚举，与主表整数码不同名同义） */
object ItemAftersalesStatus {
    const val WAIT_SELLER_AGREE = "WAIT_SELLER_AGREE"
    const val WAIT_BUYER_RETURN_GOODS = "WAIT_BUYER_RETURN_GOODS"
    const val WAIT_SELLER_CONFIRM_GOODS = "WAIT_SELLER_CONFIRM_GOODS"
    const val SELLER_REFUSE_BUYER = "SELLER_REFUSE_BUYER"
    const val REFUND_SUCCESS = "REFUND_SUCCESS"
    const val REFUND_CLOSED = "REFUND_CLOSED"
}

/** `aftersales_refund.refund_status` */
object RefundStatus {
    const val READY = "READY"
    const val AUDIT_SUCCESS = "AUDIT_SUCCESS"
    const val SUCCESS = "SUCCESS"
    const val REFUSE = "REFUSE"
    const val CANCEL = "CANCEL"

    /** 渠道退款失败：源系统不抛错，留一行 CHANGE 等人工处理（docs/04 §11.4） */
    const val CHANGE = "CHANGE"
}

/** `refund_channel` / `refund_type` */
object RefundChannel {
    const val ORIGINAL = "original"
    const val OFFLINE = "offline"
}

object RefundType {
    const val AFTERSALES = "0" // 售后申请退款
    const val CANCEL_ORDER = "1" // 取消订单
    const val REJECT = "2" // 拒收
}

/** 订单行 `aftersales_status='REFUND_SUCCESS'` 之外的行数统计行 */
class RefundSumRow {
    var refundedFee: Long = 0
    var refundedPoint: Long = 0
}

class AppliedRow {
    var appliedFee: Long = 0
    var appliedNum: Long = 0
    var appliedPoint: Long = 0
}

class AftersalesDO : Stampable {
    /** 业务单号：应用层号段生成（SeqName.AFTERSALES_NO），非自增 */
    var aftersalesBn: Long = 0

    /** 关联 aftersales_detail.detail_id（明细先插、回填主表，同 GOODS-10 口径） */
    var detailId: Long = 0
    var orderId: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var shopId: Long = 0
    var distributorId: Long = 0
    var supplierId: Long = 0
    var merchantId: Long = 0
    var itemId: Long = 0
    var itemBn: String? = null
    var itemName: String? = null

    /** 冗余商品图：列表页只查主表就能出图（§11.3 行字段含 pic） */
    var pic: String? = null
    var num: Long = 1
    var aftersalesType: String = AftersalesType.ONLY_REFUND
    var aftersalesStatus: Int = AftersalesStatus.PENDING
    var progress: Int = AftersalesProgress.WAIT_SELLER
    var refundFee: Long = 0
    var refundPoint: Long = 0
    var reason: String? = null
    var description: String? = null
    var refuseReason: String? = null
    var memo: String? = null

    /** JSON 数组字符串：凭证图（SUP-2 上传回来的 url 列表） */
    var evidencePic: String? = null
    var returnType: String? = null
    var contact: String? = null
    var mobile: String? = null
    var freight: Long = 0

    /** JSON 串：{corp_code,logi_no,filled_by,filled_at} */
    var sendbackData: String? = null

    /** 买家寄回截止（0=不限时）：超时由 scheduleAutoRefuse 自动驳回 */
    var autoRefuseTime: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

class AftersalesDetailDO : Stampable {
    var detailId: Long = 0
    var aftersalesBn: Long = 0
    var orderId: String = ""
    var subOrderId: Long = 0
    var goodsId: Long = 0
    var itemId: Long = 0
    var itemName: String? = null
    var pic: String? = null
    var num: Long = 1

    /** 实际入库件数：确认收货时按申请件数整笔写入（源系统唯一写入点同口径） */
    var refundedNum: Long = 0
    var refundFee: Long = 0
    var refundPoint: Long = 0
    var aftersalesType: String? = null
    var aftersalesStatus: Int = AftersalesStatus.PENDING
    var progress: Int = AftersalesProgress.WAIT_SELLER
    override var created: Long = 0
    override var updated: Long = 0
}

class AftersalesRefundDO : Stampable {
    var refundBn: Long = 0
    var aftersalesBn: Long = 0
    var orderId: String = ""
    var tradeId: String = ""
    var itemId: Long = 0
    var companyId: Long = 0
    var refundType: String = RefundType.AFTERSALES
    var refundChannel: String = RefundChannel.ORIGINAL
    var refundStatus: String = RefundStatus.READY
    var orderFee: Long = 0
    var refundFee: Long = 0
    var refundedFee: Long = 0
    var refundPoint: Long = 0
    var refundedPoint: Long = 0
    var returnFreight: Long = 0
    var payType: String? = null
    var refundId: String? = null
    var refundTime: Long? = null
    var refundSuccessTime: Long? = null

    /** 线下打款七字段（AF-A10） */
    var payeeName: String? = null
    var bankName: String? = null
    var bankAccount: String? = null
    var bankBranch: String? = null
    var bankProvince: String? = null
    var bankCity: String? = null
    var memo: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

private const val AS_COLS =
    "aftersales_bn, detail_id, order_id, company_id, user_id, shop_id, distributor_id, supplier_id, merchant_id, " +
        "item_id, item_bn, item_name, pic, num, aftersales_type, aftersales_status, progress, refund_fee, refund_point, " +
        "reason, description, refuse_reason, memo, evidence_pic, return_type, contact, mobile, freight, " +
        "sendback_data, auto_refuse_time, created, updated"

/** 列表/详情共用的筛选条件键：companyId/userId/aftersalesBn/orderId/status/type/mobile/from/to */
private const val AS_FILTER_SQL = """
    <if test="f.companyId != null"> AND company_id = #{f.companyId}</if>
    <if test="f.userId != null"> AND user_id = #{f.userId}</if>
    <if test="f.aftersalesBn != null"> AND aftersales_bn = #{f.aftersalesBn}</if>
    <if test="f.orderId != null"> AND order_id = #{f.orderId}</if>
    <if test="f.status != null"> AND aftersales_status = #{f.status}</if>
    <if test="f.type != null"> AND aftersales_type = #{f.type}</if>
    <if test="f.mobile != null"> AND mobile = #{f.mobile}</if>
    <if test="f.from != null"> AND created &gt;= #{f.from}</if>
    <if test="f.to != null"> AND created &lt;= #{f.to}</if>
"""

@Mapper
interface AftersalesMapper {

    @Insert(
        "INSERT INTO aftersales(aftersales_bn, detail_id, order_id, company_id, user_id, shop_id, distributor_id, " +
            "supplier_id, merchant_id, item_id, item_bn, item_name, pic, num, aftersales_type, aftersales_status, progress, " +
            "refund_fee, refund_point, reason, description, refuse_reason, memo, evidence_pic, return_type, contact, " +
            "mobile, freight, sendback_data, auto_refuse_time, created, updated) " +
            "VALUES(#{aftersalesBn}, #{detailId}, #{orderId}, #{companyId}, #{userId}, #{shopId}, #{distributorId}, " +
            "#{supplierId}, #{merchantId}, #{itemId}, #{itemBn}, #{itemName}, #{pic}, #{num}, #{aftersalesType}, #{aftersalesStatus}, " +
            "#{progress}, #{refundFee}, #{refundPoint}, #{reason}, #{description}, #{refuseReason}, #{memo}, #{evidencePic}, " +
            "#{returnType}, #{contact}, #{mobile}, #{freight}, #{sendbackData}, #{autoRefuseTime}, #{created}, #{updated})",
    )
    fun insert(a: AftersalesDO): Int

    @Select("SELECT $AS_COLS FROM aftersales WHERE aftersales_bn = #{bn} AND company_id = #{companyId}")
    fun findByBn(@Param("bn") bn: Long, @Param("companyId") companyId: Long): AftersalesDO?

    /** 会员面：越权与不存在同结果（0 行 → 上层 404），不在服务层比较 user_id */
    @Select("SELECT $AS_COLS FROM aftersales WHERE aftersales_bn = #{bn} AND company_id = #{companyId} AND user_id = #{userId}")
    fun findByBnAndUser(
        @Param("bn") bn: Long,
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
    ): AftersalesDO?

    @Select("<script>SELECT COUNT(*) FROM aftersales WHERE 1=1$AS_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $AS_COLS FROM aftersales WHERE 1=1$AS_FILTER_SQL" +
            " ORDER BY aftersales_bn DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(
        @Param("f") f: Map<String, Any?>,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<AftersalesDO>

    @Update("UPDATE aftersales SET detail_id = #{detailId}, updated = #{updated} WHERE aftersales_bn = #{bn}")
    fun bindDetail(
        @Param("bn") bn: Long,
        @Param("detailId") detailId: Long,
        @Param("updated") updated: Long,
    ): Int

    /**
     * 状态迁移（CAS）。@p 里必须给 to_status/to_progress 与守卫 from_status/from_progress，
     * 可选写列 refuse_reason/memo/sendback_data/auto_refuse_time/refund_fee/refund_point（非空才写）。
     */
    @Update(
        """
        <script>UPDATE aftersales SET aftersales_status = #{toStatus}, progress = #{toProgress}, updated = #{updated}
        <if test="refuseReason != null">, refuse_reason = #{refuseReason}</if>
        <if test="memo != null">, memo = #{memo}</if>
        <if test="sendbackData != null">, sendback_data = #{sendbackData}</if>
        <if test="autoRefuseTime != null">, auto_refuse_time = #{autoRefuseTime}</if>
        <if test="refundFee != null">, refund_fee = #{refundFee}</if>
        <if test="refundPoint != null">, refund_point = #{refundPoint}</if>
        <if test="contact != null">, contact = #{contact}</if>
        <if test="mobile != null">, mobile = #{mobile}</if>
        <if test="returnType != null">, return_type = #{returnType}</if>
        WHERE aftersales_bn = #{bn} AND company_id = #{companyId}
        <if test="userId != null"> AND user_id = #{userId}</if>
        <if test="fromStatus != null"> AND aftersales_status = #{fromStatus}</if>
        <if test="fromProgress != null"> AND progress = #{fromProgress}</if>
        </script>""",
    )
    fun transit(p: TransitParam): Int

    /** 后台改备注（不动状态） */
    @Update("UPDATE aftersales SET memo = #{memo}, updated = #{updated} WHERE aftersales_bn = #{bn} AND company_id = #{companyId}")
    fun updateMemo(
        @Param("bn") bn: Long,
        @Param("companyId") companyId: Long,
        @Param("memo") memo: String,
        @Param("updated") updated: Long,
    ): Int

    /**
     * 申请侧守恒读：同一订单行上**仍占用额度**的已申请金额/件数合计
     * （状态 IN 0,5,1,2；已驳回(3)/已撤销(4) 不占额度，与 PHP 完全一致）。
     * 走主表而非明细表——明细表没有 company_id，且主表就是一行售后单一订单行。
     */
    @Select(
        "SELECT COALESCE(SUM(refund_fee), 0) AS applied_fee, COALESCE(SUM(num), 0) AS applied_num, " +
            "COALESCE(SUM(refund_point), 0) AS applied_point FROM aftersales " +
            "WHERE company_id = #{companyId} AND order_id = #{orderId} AND item_id = #{itemId} " +
            "AND aftersales_status IN (0, 5, 1, 2)",
    )
    fun applied(
        @Param("companyId") companyId: Long,
        @Param("orderId") orderId: Long,
        @Param("itemId") itemId: Long,
    ): AppliedRow?


    /**
     * 整单仍占额度的申请件数合计（`left_aftersales_num` 镜像列的重算依据）：
     * 镜像列只是冗余计数，权威口径永远是这张 SUM，退款驳回/撤销后按它整值回写。
     */
    @Select("SELECT COALESCE(SUM(num), 0) FROM aftersales WHERE company_id = #{companyId} AND order_id = #{orderId} " +
        "AND aftersales_status IN (0, 5, 1, 2)")
    fun occupiedNum(@Param("companyId") companyId: Long, @Param("orderId") orderId: Long): Long

    /** 超时自动驳回候选：已接受待寄回且截止时间已过 */
    @Select(
        "SELECT $AS_COLS FROM aftersales WHERE progress = 1 AND auto_refuse_time > 0 AND auto_refuse_time < #{now} " +
            "ORDER BY aftersales_bn LIMIT #{limit}",
    )
    fun listExpiredRefuse(@Param("now") now: Long, @Param("limit") limit: Int): List<AftersalesDO>

    /** 迁移入参：null 字段表示"不写这一列 / 不加这一守卫" */
    class TransitParam {
        var bn: Long = 0
        var companyId: Long = 0
        var userId: Long? = null
        var fromStatus: Int? = null
        var fromProgress: Int? = null
        var toStatus: Int = 0
        var toProgress: Int = 0
        var refuseReason: String? = null
        var memo: String? = null
        var sendbackData: String? = null
        var autoRefuseTime: Long? = null
        var refundFee: Long? = null
        var refundPoint: Long? = null
        var contact: String? = null
        var mobile: String? = null
        var returnType: String? = null
        var updated: Long = 0
    }
}

@Mapper
interface AftersalesDetailMapper {

    @Insert(
        "INSERT INTO aftersales_detail(aftersales_bn, order_id, sub_order_id, goods_id, item_id, item_name, pic, num, " +
            "refunded_num, refund_fee, refund_point, aftersales_type, aftersales_status, progress, created, updated) " +
            "VALUES(#{aftersalesBn}, #{orderId}, #{subOrderId}, #{goodsId}, #{itemId}, #{itemName}, #{pic}, #{num}, " +
            "#{refundedNum}, #{refundFee}, #{refundPoint}, #{aftersalesType}, #{aftersalesStatus}, #{progress}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "detailId", keyColumn = "detail_id")
    fun insert(d: AftersalesDetailDO): Int

    @Select("SELECT * FROM aftersales_detail WHERE aftersales_bn = #{bn} ORDER BY detail_id")
    fun listByBn(@Param("bn") bn: Long): List<AftersalesDetailDO>

    /** 明细与主表永远双写：同一 filter 的迁移（守卫用主表读到的 status/progress） */
    @Update(
        "<script>UPDATE aftersales_detail SET aftersales_status = #{toStatus}, progress = #{toProgress}, updated = #{updated} " +
            "WHERE aftersales_bn = #{bn}" +
            "<if test=\"fromStatus != null\"> AND aftersales_status = #{fromStatus}</if>" +
            "<if test=\"fromProgress != null\"> AND progress = #{fromProgress}</if></script>",
    )
    fun transit(
        @Param("bn") bn: Long,
        @Param("fromStatus") fromStatus: Int?,
        @Param("fromProgress") fromProgress: Int?,
        @Param("toStatus") toStatus: Int,
        @Param("toProgress") toProgress: Int,
        @Param("updated") updated: Long,
    ): Int

    /** 确认收货：按申请件数整笔入库（源系统唯一 refunded_num 写入点） */
    @Update("UPDATE aftersales_detail SET refunded_num = #{num}, updated = #{updated} WHERE detail_id = #{detailId}")
    fun markReceived(
        @Param("detailId") detailId: Long,
        @Param("num") num: Long,
        @Param("updated") updated: Long,
    ): Int

    @Update("UPDATE aftersales_detail SET refunded_num = 0, updated = #{updated} WHERE detail_id = #{detailId}")
    fun clearReceived(@Param("detailId") detailId: Long, @Param("updated") updated: Long): Int

    /** 退款单行级分摊写回用：某售后单的明细 */
    @Select("SELECT * FROM aftersales_detail WHERE detail_id = #{detailId}")
    fun findById(@Param("detailId") detailId: Long): AftersalesDetailDO?
}

private const val RF_COLS =
    "refund_bn, aftersales_bn, order_id, trade_id, item_id, company_id, refund_type, refund_channel, refund_status, " +
        "order_fee, refund_fee, refunded_fee, refund_point, refunded_point, return_freight, pay_type, refund_id, " +
        "refund_time, refund_success_time, payee_name, bank_name, bank_account, bank_branch, bank_province, bank_city, " +
        "memo, created, updated"

private const val RF_FILTER_SQL = """
    <if test="f.companyId != null"> AND company_id = #{f.companyId}</if>
    <if test="f.refundBn != null"> AND refund_bn = #{f.refundBn}</if>
    <if test="f.orderId != null"> AND order_id = #{f.orderId}</if>
    <if test="f.aftersalesBn != null"> AND aftersales_bn = #{f.aftersalesBn}</if>
    <if test="f.status != null"> AND refund_status = #{f.status}</if>
    <if test="f.type != null"> AND refund_type = #{f.type}</if>
    <if test="f.from != null"> AND created &gt;= #{f.from}</if>
    <if test="f.to != null"> AND created &lt;= #{f.to}</if>
"""

@Mapper
interface AftersalesRefundMapper {

    @Insert(
        "INSERT INTO aftersales_refund(refund_bn, aftersales_bn, order_id, trade_id, item_id, company_id, refund_type, " +
            "refund_channel, refund_status, order_fee, refund_fee, refunded_fee, refund_point, refunded_point, " +
            "return_freight, pay_type, refund_id, refund_time, refund_success_time, payee_name, bank_name, bank_account, " +
            "bank_branch, bank_province, bank_city, memo, created, updated) " +
            "VALUES(#{refundBn}, #{aftersalesBn}, #{orderId}, #{tradeId}, #{itemId}, #{companyId}, #{refundType}, " +
            "#{refundChannel}, #{refundStatus}, #{orderFee}, #{refundFee}, #{refundedFee}, #{refundPoint}, #{refundedPoint}, " +
            "#{returnFreight}, #{payType}, #{refundId}, #{refundTime}, #{refundSuccessTime}, #{payeeName}, #{bankName}, " +
            "#{bankAccount}, #{bankBranch}, #{bankProvince}, #{bankCity}, #{memo}, #{created}, #{updated})",
    )
    fun insert(r: AftersalesRefundDO): Int

    @Select("SELECT $RF_COLS FROM aftersales_refund WHERE refund_bn = #{bn} AND company_id = #{companyId}")
    fun findByBn(@Param("bn") bn: Long, @Param("companyId") companyId: Long): AftersalesRefundDO?

    @Select("SELECT $RF_COLS FROM aftersales_refund WHERE aftersales_bn = #{aftersalesBn} ORDER BY refund_bn")
    fun listByAftersales(@Param("aftersalesBn") aftersalesBn: Long): List<AftersalesRefundDO>

    @Select("<script>SELECT COUNT(*) FROM aftersales_refund WHERE 1=1$RF_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $RF_COLS FROM aftersales_refund WHERE 1=1$RF_FILTER_SQL" +
            " ORDER BY refund_bn DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(
        @Param("f") f: Map<String, Any?>,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<AftersalesRefundDO>

    /** 状态迁移（CAS）：READY→AUDIT_SUCCESS→SUCCESS / REFUSE / CANCEL；SUCCESS 只从 AUDIT_SUCCESS 来 */
    @Update(
        """
        <script>UPDATE aftersales_refund SET refund_status = #{toStatus}, updated = #{updated}
        <if test="refundedFee != null">, refunded_fee = #{refundedFee}</if>
        <if test="refundedPoint != null">, refunded_point = #{refundedPoint}</if>
        <if test="refundId != null">, refund_id = #{refundId}</if>
        <if test="refundSuccessTime != null">, refund_success_time = #{refundSuccessTime}</if>
        <if test="refundTime != null">, refund_time = #{refundTime}</if>
        <if test="channel != null">, refund_channel = #{channel}</if>
        <if test="memo != null">, memo = #{memo}</if>
        WHERE refund_bn = #{bn} AND company_id = #{companyId} AND refund_status = #{fromStatus}
        </script>""",
    )
    fun transit(p: RefundTransitParam): Int

    /** 线下打款七字段落库（AF-A10），只在 AUDIT_SUCCESS 的单上写 */
    @Update(
        "UPDATE aftersales_refund SET payee_name = #{payeeName}, bank_name = #{bankName}, bank_account = #{bankAccount}, " +
            "bank_branch = #{bankBranch}, bank_province = #{bankProvince}, bank_city = #{bankCity}, memo = #{memo}, " +
            "updated = #{updated} WHERE refund_bn = #{refundBn} AND company_id = #{companyId} AND refund_status = 'AUDIT_SUCCESS'",
    )
    fun fillOfflineBank(r: AftersalesRefundDO): Int

    /**
     * 守恒不变式的读侧：同一订单（同公司）**非积分通道**已退合计。
     * 源系统 `__check` 就是 `pay_type != 'point'` 分池统计，积分单走 order.point 口径。
     */
    @Select(
        "SELECT COALESCE(SUM(refunded_fee), 0) AS refunded_fee, COALESCE(SUM(refunded_point), 0) AS refunded_point " +
            "FROM aftersales_refund WHERE order_id = #{orderId} AND company_id = #{companyId} " +
            "AND (pay_type IS NULL OR pay_type <> 'point')",
    )
    fun sumRefunded(@Param("orderId") orderId: String, @Param("companyId") companyId: Long): RefundSumRow?

    /** 已成功退款合计（只读口径，后台退款列表与对账用） */
    @Select(
        "SELECT COALESCE(SUM(refunded_fee), 0) AS refunded_fee, COALESCE(SUM(refunded_point), 0) AS refunded_point " +
            "FROM aftersales_refund WHERE order_id = #{orderId} AND company_id = #{companyId} AND refund_status = 'SUCCESS'",
    )
    fun sumRefundedSuccess(@Param("orderId") orderId: String, @Param("companyId") companyId: Long): RefundSumRow?

    /** 该订单是否还有在途退款单（AUDIT_SUCCESS 未执行） */
    @Select(
        "SELECT COUNT(*) FROM aftersales_refund WHERE order_id = #{orderId} AND company_id = #{companyId} " +
            "AND refund_status IN ('READY', 'AUDIT_SUCCESS')",
    )
    fun countPending(@Param("orderId") orderId: String, @Param("companyId") companyId: Long): Long

    /** READY 单是否已存在（同一售后单不重复申请退款） */
    @Select("SELECT COUNT(*) FROM aftersales_refund WHERE aftersales_bn = #{aftersalesBn} AND refund_status = 'READY'")
    fun countReadyByAftersales(@Param("aftersalesBn") aftersalesBn: Long): Long

    class RefundTransitParam {
        var bn: Long = 0
        var companyId: Long = 0
        var fromStatus: String = RefundStatus.READY
        var toStatus: String = RefundStatus.SUCCESS
        var refundedFee: Long? = null
        var refundedPoint: Long? = null
        var refundId: String? = null
        var refundTime: Long? = null
        var refundSuccessTime: Long? = null
        var channel: String? = null
        /**
         * `memo` 列是 `VARCHAR(300)`。写进去的内容有相当一部分是**渠道原话**
         * （`Invalid json response body[{...整段报文...}]` 这类），长度完全不由我们决定；
         * 一旦超长，MySQL/H2 直接报错，于是"退款状态推进"整笔失败回滚——渠道那边钱已经动了，
         * 我方却因为一句备注写不下去而停在原地。备注是可丢信息，状态推进不可丢，所以在入口截断。
         */
        var memo: String? = null
            set(value) {
                field = value?.take(300)
            }
        var updated: Long = 0
    }
}
