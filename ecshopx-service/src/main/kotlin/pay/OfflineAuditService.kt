package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderOperateLogDO
import com.ecshopx.kotlin.dao.order.OrderOperateLogMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.pay.OfflinePaymentDO
import com.ecshopx.kotlin.dao.pay.OfflinePaymentMapper
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.stereotype.Service

/**
 * SUP-6 线下转账审核流（docs/05 §M1-2 的最后一块，也是 docs/04 §8 C55 里"审核结清属 M1"的兑现）。
 *
 * M0-7 只做到"提交凭证"：`PAY-2 pay_type=offline_pay` 落一行 `offline_payment`（`check_status=0`）、
 * 订单保持待付款。本轮补上后台侧的两个动作：
 *  - **审核通过** → 订单结清（走与渠道回调同一条 `trade NOTPAY CAS + markPaid + 积分入账`，
 *    只是实付额由审核人填写、没有渠道流水号）；
 *  - **驳回** → 只把凭证标成 `check_status=2`，订单原样留在待付款，买家可以改金额/换渠道重新提交。
 *
 * 三条不变式：
 *  1. **一凭证一结论**：`check_status` 的 CAS 只从 0 出发，重复点"通过"第二次拿 0 行 → 422，
 *     不会出现两次结清（结清本身还有 trade 的 CAS 兜底）；
 *  2. **审核必须对得上订单**：只审 `order_status=NOTPAY` 的单，已付/已关的单要退钱就走售后（AF 族），
 *     不允许用审核入口把已结清的订单再结一次；
 *  3. **驳回不动钱**：`check_status=2` 与订单状态无关，凭证被拒不产生任何资金动作。
 */
@Service
class OfflineAuditService(
    private val offs: OfflinePaymentMapper,
    private val orders: OrdersMapper,
    private val orderLogs: OrderOperateLogMapper,
    private val pay: PayService,
) {

    /** SUP-6a 后台审核列表：`check_status` 缺省看全部，`order_id` 可钻取单笔订单的凭证 */
    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        val f = linkedMapOf<String, Any?>("companyId" to companyId)
        raw["check_status"]?.toIntOrNull()?.let { f["checkStatus"] = it }
        raw["order_id"]?.toLongOrNull()?.let { f["orderId"] = it }
        return PageHelper.paginate(
            pq,
            count = { offs.count(f) },
            query = { offset, limit -> offs.page(f, offset, limit) },
            map = { rows -> rows.map { view(it) } },
        )
    }

    /**
     * SUP-6b 审核：`check_status=1` 通过并结清，`=2` 驳回。
     * 结清失败（订单已被别的动作推走）整笔回滚，凭证回到待处理，后台看到的就是"审核没生效"。
     */
    fun audit(companyId: Long, operator: String?, form: OfflineAuditForm): Map<String, Any?> {
        val id = form.id?.toLongOrNull()
            ?: throw BizException(ErrorCode.VALIDATION, "id 必须是凭证行号", mapOf("id" to listOf("格式非法")))
        val to = form.checkStatus
            ?: throw BizException(ErrorCode.VALIDATION, "check_status 只能 1（通过）或 2（驳回）",
                mapOf("check_status" to listOf("不能为空")))
        if (to != 1 && to != 2) {
            throw BizException(ErrorCode.VALIDATION, "check_status 只能 1（通过）或 2（驳回）",
                mapOf("check_status" to listOf("收到 $to")))
        }
        val p = offs.findById(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "线下转账凭证不存在")
        if (p.checkStatus != 0) {
            throw BizException(ErrorCode.BIZ, "本凭证已审核（${statusText(p.checkStatus)}），不能重复处理")
        }
        val o = orders.findById(p.orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (to == 1 && o.orderStatus != OrderStatus.NOTPAY) {
            throw BizException(
                ErrorCode.BIZ,
                "订单当前状态不可通过审核：${o.orderStatus}（要退款请走售后）",
                mapOf("order_status" to listOf("只有待付款订单能审核通过")),
            )
        }
        val payFee = if (to == 1) (form.payFee ?: p.totalFee) else 0L
        if (payFee < 0L) throw BizException(ErrorCode.VALIDATION, "pay_fee 不能为负", mapOf("pay_fee" to listOf("负数")))
        if (to == 1 && payFee != p.totalFee) {
            throw BizException(
                ErrorCode.BIZ,
                "线下转账按应付全额结清：本单应付 ${p.totalFee} 分，收到 $payFee 分",
                mapOf("pay_fee" to listOf("少收请让买家补转或改走其他渠道")),
            )
        }
        val remark = form.remark?.takeIf { it.isNotBlank() }
        val now = TimeSec.now()

        if (offs.audit(id, companyId, to, payFee, remark, operator?.takeIf { it.isNotBlank() } ?: "seller", now) == 0) {
            throw BizException(ErrorCode.BIZ, "凭证状态已变更，请刷新后重试")
        }
        val settled = if (to == 1) pay.settleByOffline(o.orderId, p.paySn, payFee, remark) else null
        orderLogs.insert(
            OrderOperateLogDO().apply {
                orderId = o.orderId
                this.operator = operator?.takeIf { it.isNotBlank() } ?: "seller"
                operatorRole = "seller"
                behavior = if (to == 1) "offline_paid" else "offline_reject"
                logText = if (to == 1) {
                    "线下转账审核通过，实付 $payFee 分${remark?.let { "（$it）" } ?: ""}"
                } else {
                    "线下转账凭证被驳回${remark?.let { "：$it" } ?: "，订单保持待付款"}"
                }
            },
        )
        return linkedMapOf(
            "id" to id.toString(),
            "order_id" to o.orderId.toString(),
            "check_status" to to,
            "check_status_text" to statusText(to),
            "pay_fee" to payFee,
            "remark" to remark,
            "operator_name" to (operator ?: "seller"),
            "trade_state" to (settled?.get("trade_state") ?: (if (to == 2) "NOTPAY" else null)),
            "trade_id" to settled?.get("trade_id"),
            "order_status" to (if (to == 1) OrderStatus.PAYED else o.orderStatus),
        )
    }

    /** 前台/详情侧的凭证读取（PAY-2 之后买家想知道"我转的账商家看到没有"） */
    fun listByOrder(orderId: Long, companyId: Long, userId: Long?): List<Map<String, Any?>> {
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (userId != null && o.userId != userId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        if (o.companyId != companyId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        return offs.listByOrder(orderId).map { view(it) }
    }

    private fun view(p: OfflinePaymentDO): Map<String, Any?> = linkedMapOf(
        "id" to p.id.toString(),
        "order_id" to p.orderId.toString(),
        "total_fee" to p.totalFee,
        "pay_fee" to p.payFee,
        "check_status" to p.checkStatus,
        "check_status_text" to statusText(p.checkStatus),
        "pay_sn" to p.paySn,
        "voucher_pic" to p.voucherPic,
        "transfer_remark" to p.transferRemark,
        "operator_name" to p.operatorName,
        "remark" to p.remark,
        "created" to p.created,
        "updated" to p.updated,
    )

    private fun statusText(status: Int): String = when (status) {
        0 -> "待审核"
        1 -> "已审核"
        2 -> "已拒绝"
        else -> "未知($status)"
    }
}

/** SUP-6b 入参：`pay_fee` 只在通过时有意义，缺省=按应付全额结清 */
@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OfflineAuditForm(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("check_status") val checkStatus: Int? = null,
    @JsonProperty("pay_fee") val payFee: Long? = null,
    @JsonProperty("remark") val remark: String? = null,
)
