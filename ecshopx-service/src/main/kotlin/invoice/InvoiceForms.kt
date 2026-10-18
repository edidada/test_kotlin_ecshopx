package com.ecshopx.kotlin.service.invoice

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * M1-5 发票域入参（docs/04 §23.1 `INV-F1..F6`、§23.2 `INV-A1..A3`）。
 *
 * 三张表单分开写是有原因的：`INV-F1`（申请）与 `INV-F5`（抬头本）看着像同一组字段，
 * 实际是**两套词表**（`enterprise/individual` 对 `personal/corporate`，INV-R3），
 * 合并成一个 form 就等于让调用方猜"这次该传哪一套"。
 */

/** INV-F1 申请/修改发票（`pending` 态可反复改，走 upsert） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class InvoiceApplyForm(
    /** 单个订单号（INV-R1 收紧后的口径：一单一申请） */
    val orderId: String? = null,
    /** >0 时从抬头本带出抬头；只接受本人的，越权 404 */
    val invoicesId: Long? = null,
    /** enterprise/individual —— 抬头本那套 personal/corporate 不往这里传 */
    val invoiceType: String? = null,
    /** 01 专票 / 02 普票；受 `sys_config.invoice.types` 白名单约束 */
    val invoiceTypeCode: String? = null,
    val companyTitle: String? = null,
    /** 企业抬头必填（跨字段规则 → 422） */
    val taxNumber: String? = null,
    val email: String? = null,
    val mobile: String? = null,
    val remark: String? = null,
)

/** INV-F5 抬头本新增/修改（`invoices_id` 有则改） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class InvoiceHeadForm(
    val invoicesId: Long? = null,
    /** personal/corporate */
    val invoicesType: String? = null,
    val name: String? = null,
    val telephone: String? = null,
    /** corporate 必填（跨字段规则 → 422），personal 不填 */
    val taxNumber: String? = null,
    val businessAddress: String? = null,
    val bank: String? = null,
    val bankAccount: String? = null,
    /** 置 true 时把该会员其余行的 `is_def` 清零：默认抬头只有一个 */
    @JsonProperty("is_def") val isDef: Boolean? = null,
)

/** INV-A2 后台处理（`pending → issued/rejected`，单向、终态不可回退） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class InvoiceHandleForm(
    /** 只能填 issued/rejected；填 pending 等于"撤回处理"，本批不做（见 [InvoiceService.handle]） */
    val invoiceStatus: String? = null,
    val invoiceFileUrl: String? = null,
    /** 覆盖收件邮箱（会员申请后换了邮箱是常态，开票前允许改） */
    val email: String? = null,
    /** `rejected` 必填：驳回不给理由，会员只能反复重新申请 */
    val remark: String? = null,
)
