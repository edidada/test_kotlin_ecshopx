package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.order.OrderDeliveryForm
import com.ecshopx.kotlin.service.order.OrderQueryService
import com.ecshopx.kotlin.service.order.OrderRemarkForm
import com.ecshopx.kotlin.service.order.OrderFlowService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * ORDER-9 后台订单族（docs/04 §2.4）：列表 / 详情 / 备注 / 发货。
 *
 * 详情复用前台装配但传 userId=null → 附带 operate_logs 与包裹内明细（卖家视角全量）。
 * 店铺数据权限（distributor_id 越权过滤）按计划属 M1-4，这里只按请求参数筛。
 */
@RestController
class OrderAdminController(
    private val query: OrderQueryService,
    private val flow: OrderFlowService,
) {

    @RequiresResource("orders.lists")
    @GetMapping("/api/orders")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(query.list(raw, companyId(p), userId = null))

    @RequiresResource("orders.lists")
    @GetMapping("/api/orders/{order_id}")
    fun detail(@PathVariable("order_id") orderId: String): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(query.detail(id(orderId), userId = null))

    @Audited(route = "orders.update")
    @RequiresResource("orders.update")
    @PutMapping("/api/orders/remark")
    fun remark(
        @RequestBody form: OrderRemarkForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val oid = form.orderId ?: throw BizException(ErrorCode.VALIDATION, "order_id 必填", mapOf("order_id" to listOf("不能为空")))
        return ApiResponse.ok(flow.updateRemark(oid, form.remark, p?.id, p?.let { "operator:${it.id}" }))
    }

    @Audited(route = "orders.delivery")
    @RequiresResource("orders.delivery")
    @PutMapping("/api/orders/delivery")
    fun delivery(
        @RequestBody form: OrderDeliveryForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val oid = form.orderId ?: throw BizException(ErrorCode.VALIDATION, "order_id 必填", mapOf("order_id" to listOf("不能为空")))
        return ApiResponse.ok(flow.ship(oid, form, p?.id, p?.let { "operator:${it.id}" }))
    }

    /** 超时单排查：列出当前已到 auto_cancel_time 的待付款单（定时任务同款口径） */
    @RequiresResource("orders.lists")
    @GetMapping("/api/orders/timeout")
    fun timeout(@RequestParam(defaultValue = "50") limit: Int): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            mapOf("order_ids" to query.timeoutIds(now(), limit).map { it.toString() }),
        )

    private fun now(): Long = TimeSec.now()

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun companyId(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
