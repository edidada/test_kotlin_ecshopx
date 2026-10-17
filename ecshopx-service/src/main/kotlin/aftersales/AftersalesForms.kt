package com.ecshopx.kotlin.service.aftersales

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.dao.aftersales.AftersalesStatus
import com.ecshopx.kotlin.dao.aftersales.AftersalesProgress
import com.ecshopx.kotlin.dao.aftersales.AftersalesType
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * M1-1 售后入参（docs/04 §11.1/§11.2）。
 *
 * 命名沿用源系统 PHP 表单键（snake_case），会员面与后台面共用同一套形态：
 * 后台代客申请（AF-A6）只是多一个 `user_id` 由订单带出，因此不另开一个类。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AftersalesApplyForm(
    val orderId: String? = null,
    /** 订单行 id（orders_normal_orders_items.id），不是商品 item_id */
    val itemId: Long? = null,
    val num: Long? = null,
    val aftersalesType: String? = null,
    val reason: String? = null,
    val description: String? = null,
    /** 缺省 = 按件分摊值（floor(行 total_fee / 行 num) × 件数） */
    val refundFee: Long? = null,
    /** logistics=自行寄回 / offline=上门取件（后者必填 contact+mobile） */
    val returnType: String? = null,
    val contact: String? = null,
    val mobile: String? = null,
    /** 凭证图 URL 数组（SUP-2 上传回来的） */
    val evidencePic: List<String>? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AftersalesSendbackForm(
    val aftersalesBn: String? = null,
    val corpCode: String? = null,
    val logiNo: String? = null,
    /** offline 取件时的补充说明 */
    val memo: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AftersalesCloseForm(
    val aftersalesBn: String? = null,
    val memo: String? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AftersalesReviewForm(
    val aftersalesBn: String? = null,
    /** 1=同意 0=驳回（源系统就是整数开关，不做 boolean 兼容） */
    val isApproved: Int? = null,
    val refuseReason: String? = null,
    /** 缺省回填申请值；只允许调小（调大等于商家替买家加钱） */
    val refundFee: Long? = null,
    val refundPoint: Long? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AftersalesRefundCheckForm(
    val aftersalesBn: String? = null,
    /** 1=确认退款（终局，钱货两清） 0=拒绝退款 */
    val checkRefund: Int? = null,
    val refundFee: Long? = null,
    val refundsMemo: String? = null,
)

/** AF-A10 线下打款登记：银行七字段 + 退款单号（§11.2） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RefundOfflineForm(
    val refundBn: String? = null,
    val payeeName: String? = null,
    val bankName: String? = null,
    val bankAccount: String? = null,
    val bankBranch: String? = null,
    val bankProvince: String? = null,
    val bankCity: String? = null,
    val memo: String? = null,
)

/** 售后域对订单的合法性判定（与源系统一致：付款之后、完结之前都可申请） */
object AftersalesGate {

    fun requirePayable(o: com.ecshopx.kotlin.dao.order.NormalOrderDO) {
        if (o.payStatus != PayStatus.PAYED) {
            throw BizException(ErrorCode.BIZ, "订单未支付，不能申请售后", mapOf("order_id" to listOf("pay_status=${o.payStatus}")))
        }
        if (o.orderStatus == OrderStatus.CANCEL) {
            throw BizException(ErrorCode.BIZ, "订单已取消，不能申请售后")
        }
    }

    /** 申请类型白名单：换货（M2）与任何未知值都给 501/400，与 D5/D6 同口径 */
    fun normalizeType(raw: String?): String = when (raw) {
        AftersalesType.ONLY_REFUND, AftersalesType.REFUND_GOODS -> raw
        AftersalesType.EXCHANGING_GOODS -> throw BizException(
            ErrorCode.NOT_IMPLEMENTED, "换货流程属 M2，暂不支持", mapOf("aftersales_type" to listOf("EXCHANGING_GOODS")),
        )
        else -> throw BizException(
            ErrorCode.VALIDATION, "aftersales_type 必须是 ONLY_REFUND 或 REFUND_GOODS",
            mapOf("aftersales_type" to listOf("取值非法：${raw ?: "(空)"}")),
        )
    }
}

/** 类型/状态/进度的中文文案（出参 status_msg / progress_msg，§11.3） */
object AftersalesDict {

    fun typeMsg(t: String?): String = when (t) {
        AftersalesType.ONLY_REFUND -> "仅退款"
        AftersalesType.REFUND_GOODS -> "退货退款"
        AftersalesType.EXCHANGING_GOODS -> "换货"
        else -> t ?: "-"
    }

    fun statusMsg(s: Int?): String = when (s) {
        AftersalesStatus.PENDING -> "待处理"
        AftersalesStatus.PROCESSING -> "处理中"
        AftersalesStatus.DONE -> "已处理"
        AftersalesStatus.REFUSED -> "已驳回"
        AftersalesStatus.CANCELLED -> "已撤销"
        AftersalesStatus.AUDITING -> "审核中"
        else -> "未知($s)"
    }

    fun progressMsg(p: Int?): String = when (p) {
        AftersalesProgress.WAIT_SELLER -> "等待商家处理"
        AftersalesProgress.WAIT_BUYER_RETURN -> "商家已接受，等待买家回寄"
        AftersalesProgress.BUYER_RETURNED -> "买家已回寄"
        AftersalesProgress.REFUSED -> "商家已驳回申请"
        AftersalesProgress.FINISHED -> "售后完成"
        AftersalesProgress.REFUND_REFUSED -> "商家拒绝退款"
        AftersalesProgress.REFUND_FINISHED -> "退款完成"
        AftersalesProgress.CANCELLED -> "买家已撤销"
        AftersalesProgress.SELLER_CONFIRMED -> "商家已收货，待退款"
        AftersalesProgress.REFUND_PROCESSING -> "退款处理中"
        else -> "未知($p)"
    }

    /** 申请原因字典（D8：读 sys_config `aftersales.reason`，读不到回落四条内置文案） */
    val DEFAULT_REASONS = listOf("质量问题", "商品与描述不符", "不想要了", "其他原因")
}
