package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.aftersales.AftersalesApplyForm
import com.ecshopx.kotlin.service.aftersales.AftersalesRefundCheckForm
import com.ecshopx.kotlin.service.aftersales.AftersalesReviewForm
import com.ecshopx.kotlin.service.aftersales.AftersalesSendbackForm
import com.ecshopx.kotlin.service.aftersales.AftersalesService
import com.ecshopx.kotlin.service.aftersales.RefundOfflineForm
import com.ecshopx.kotlin.service.aftersales.RefundService
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AF-A1..10 后台售后与退款（docs/04 §11.2，前缀 `/api`，运营 JWT + `@RequiresResource`，写路径 `@Audited`）。
 *
 * 与前台的两处差别：
 *  1. 查询不带 `user_id` 约束（卖家看全租户），但 `company_id` 仍取自令牌 —— 跨租户永远查不到别人的单；
 *  2. 「审核 → 退款确认」是两条独立权限（`aftersales.review` / `aftersales.refund_check`）：
 *     能同意退货的人不该天然具备把钱退出去的能力，源系统就是两个角色，这里用权限码原样隔开。
 *
 * 「商家确认收货」不单开端点（D11）：它就是对 progress=2 的单再调一次 AF-A3，
 * 同意即入库并生成退款单，所以本层看不到第四个写入口。
 */
@RestController
class AftersalesAdminController(
    private val aftersales: AftersalesService,
    private val refunds: RefundService,
) {

    /** AF-A1 售后列表（筛选见 AftersalesService.filterOf） */
    @RequiresResource("aftersales.lists")
    @GetMapping("/api/aftersales")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(aftersales.list(null, cid(p), raw))

    /** AF-A2 售后详情（含 buttons 可执行动作） */
    @RequiresResource("aftersales.detail")
    @GetMapping("/api/aftersales/{aftersales_bn}")
    fun detail(
        @PathVariable("aftersales_bn") bn: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(aftersales.info(bn(bn), cid(p), null))

    /** AF-A3 审核：同意/驳回，兼作 D11 的"确认收货"（对 progress=2 的单再调一次即推进到待退款） */
    @Audited(route = "aftersales.review")
    @RequiresResource("aftersales.review")
    @PostMapping("/api/aftersales/review")
    fun review(
        @RequestBody form: AftersalesReviewForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aftersales.review(bn(form.aftersalesBn ?: bad("aftersales_bn")), cid(p), form))

    /** AF-A4 退款确认（终局，唯一动钱的售后端点） */
    @Audited(route = "aftersales.refund_check")
    @RequiresResource("aftersales.refund_check")
    @PostMapping("/api/aftersales/refundCheck")
    fun refundCheck(
        @RequestBody form: AftersalesRefundCheckForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(refunds.checkRefund(cid(p), form))

    /** AF-A5 商家备注（不动状态，最长 150 字） */
    @Audited(route = "aftersales.update")
    @RequiresResource("aftersales.update")
    @PutMapping("/api/aftersales/remark")
    fun remark(
        @RequestBody form: RemarkForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aftersales.remark(bn(form.aftersalesBn ?: bad("aftersales_bn")), cid(p), form.memo))

    /**
     * AF-A6 代客申请：归属仍是订单买家（`user_id` 从订单带出），
     * 仅退款的单当场审核通过，直接进「待退款」并生成退款单。
     */
    @Audited(route = "aftersales.apply")
    @RequiresResource("aftersales.apply")
    @PostMapping("/api/aftersales/apply")
    fun apply(
        @RequestBody form: AftersalesApplyForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val orderId = form.orderId ?: bad("order_id")
        return ApiResponse.ok(aftersales.apply(aftersales.ownerOf(orderId), cid(p), form, autoApprove = true))
    }

    /** AF-A7 代填回寄（买家不填物流时商家代录，filled_by=seller） */
    @Audited(route = "aftersales.update")
    @RequiresResource("aftersales.update")
    @PostMapping("/api/aftersales/sendback")
    fun sendback(
        @RequestBody form: AftersalesSendbackForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aftersales.sendback(bn(form.aftersalesBn ?: bad("aftersales_bn")), cid(p), null, form))

    /** AF-A8 退款单列表 */
    @RequiresResource("refund.lists")
    @GetMapping("/api/refund")
    fun refundList(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(refunds.list(cid(p), raw))

    /** AF-A9 退款单详情（带"本支付单还能退多少"的守恒读口径） */
    @RequiresResource("refund.detail")
    @GetMapping("/api/refund/detail/{refund_bn}")
    fun refundDetail(
        @PathVariable("refund_bn") bn: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(refunds.detail(bn(bn), cid(p)))

    /** AF-A10 线下打款登记：银行七字段补齐即落 SUCCESS（额度已在 AF-A4 占用） */
    @Audited(route = "refund.offline")
    @RequiresResource("refund.offline")
    @PostMapping("/api/refund/offline")
    fun refundOffline(
        @RequestBody form: RefundOfflineForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(refunds.offline(cid(p), form))

    /** AF-A5 入参：与售后单同域但字段独立，不与前台表单混用 */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
        com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy::class,
    )
    data class RemarkForm(val aftersalesBn: String? = null, val memo: String? = null)

    private fun bad(field: String): Nothing =
        throw BizException(ErrorCode.VALIDATION, "$field 必填", mapOf(field to listOf("不能为空")))

    private fun bn(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "单据不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
