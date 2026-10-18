package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.invoice.InvoiceApplyForm
import com.ecshopx.kotlin.service.invoice.InvoiceHeadForm
import com.ecshopx.kotlin.service.invoice.InvoiceService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * INV-F1..F6 会员面发票（docs/04 §23.1，前缀 `/api/h5app/wxapp`）。
 *
 * 六条分两组，别混：`/order/invoice*` 是**申请单**（一单一申请，终态不可改），
 * `/member/invoice*` 是**抬头本**（可复用、可删，被引用过就不能删）。
 * 两组的类型词表也不同（`enterprise/individual` 对 `personal/corporate`，INV-R3），
 * 映射只在 [InvoiceService] 里发生，控制层按各自词表收参数。
 *
 * 路径上 `/order/invoice` 与 `/order/invoice/{id}` 是两条（F2 列表 / F3 详情），
 * `/member/invoices` 复数走列表、`/member/invoice` 单数走新增，与 §23.1 一致。
 */
@RestController
class InvoiceFrontController(private val invoices: InvoiceService) {

    /** INV-F1 申请/修改发票（pending 态 upsert） */
    @PostMapping("/api/h5app/wxapp/order/invoice/apply")
    fun apply(
        @RequestBody form: InvoiceApplyForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(invoices.apply(cid(p), uid(p), form))

    /** INV-F2 我的申请列表（`invoice_status?` + 分页） */
    @GetMapping("/api/h5app/wxapp/order/invoice")
    fun myInvoices(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(invoices.myList(cid(p), uid(p), raw))

    /** INV-F3 申请详情（非本人与不存在同样 404） */
    @GetMapping("/api/h5app/wxapp/order/invoice/{id}")
    fun detail(
        @PathVariable("id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(invoices.detail(cid(p), uid(p), iid(id)))

    /** INV-F4 抬头本列表（只出本人，默认抬头在前） */
    @GetMapping("/api/h5app/wxapp/member/invoices")
    fun heads(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<List<Map<String, Any?>>> = ApiResponse.ok(invoices.headList(cid(p), uid(p)))

    /** INV-F5 新增/修改抬头（`invoices_id` 有则改；置默认时清其余） */
    @PostMapping("/api/h5app/wxapp/member/invoice")
    fun saveHead(
        @RequestBody form: InvoiceHeadForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(invoices.saveHead(cid(p), uid(p), form))

    /** INV-F6 删除抬头（被发票申请引用过 → 422） */
    @DeleteMapping("/api/h5app/wxapp/member/invoice/{id}")
    fun deleteHead(
        @PathVariable("id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(invoices.deleteHead(cid(p), uid(p), iid(id)))

    /** id 非法与查不到同一个应答：不给"这个 id 存在但不是你的"（§23.5 门槛③） */
    private fun iid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "记录不存在")

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
