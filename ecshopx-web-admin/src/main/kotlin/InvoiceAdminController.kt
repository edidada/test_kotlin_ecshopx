package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Datapass
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.invoice.InvoiceHandleForm
import com.ecshopx.kotlin.service.invoice.InvoiceService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * INV-A1..A3 后台发票（docs/04 §23.2，前缀 `/api`）。
 *
 * 两条权限码（§23.4）：`invoice.lists`（61）看，`invoice.update`（62）开票/驳回。
 * 店铺运营型账号只有 61 —— 开票是给整个租户开的，不是给某家店开的：
 * `orders_invoice` 没有店铺归属列，一家的运营拿到另一家的抬头税号就是越界，
 * 所以这一域的读面按"这个会员在我名下的店有没有单"收窄（Service 里那条 EXISTS），
 * 写面整个不给 role 2。
 *
 * `INV-A3` 的详情与 `INV-A1` 的列表用**同一个权限码**（§23.3 第 12 条末句）：
 * 只拿到 61 的账号看到的 `tax_number/email/mobile/company_title` 是掩码值，
 * 再持有 `datapass.pass`（63）才出全值 —— 同一行、两个账号、两个出参（§23.5 门槛③末句）。
 */
@RestController
class InvoiceAdminController(
    private val invoices: InvoiceService,
    private val pass: Datapass,
) {

    /** INV-A1 发票申请列表（`invoice_status`/`order_id`/`keyword` + 分页） */
    @RequiresResource("invoice.lists")
    @GetMapping("/api/order/invoices")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(invoices.adminList(cid(p), raw, scope(p), pass.full(p)))

    /** INV-A3 申请详情（受限账号按"这会员在我名下有单"判，与列表同一条 EXISTS） */
    @RequiresResource("invoice.lists")
    @GetMapping("/api/order/invoice/{id}")
    fun detail(
        @PathVariable("id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(invoices.adminDetail(cid(p), iid(id), scope(p), pass.full(p)))

    /** INV-A2 处理（pending → issued/rejected；issued 要文件或邮箱，rejected 要备注） */
    @Audited(route = "invoice.update")
    @RequiresResource("invoice.update")
    @PutMapping("/api/order/invoice/{id}")
    fun handle(
        @PathVariable("id") id: String,
        @RequestBody form: InvoiceHandleForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(invoices.handle(cid(p), iid(id), form))

    /** 单号非法与查不到同一个应答 */
    private fun iid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
