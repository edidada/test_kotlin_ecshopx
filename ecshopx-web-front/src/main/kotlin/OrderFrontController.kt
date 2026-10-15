package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.order.OrderCancelForm
import com.ecshopx.kotlin.service.order.OrderConfirmForm
import com.ecshopx.kotlin.service.order.OrderCreateForm
import com.ecshopx.kotlin.service.order.OrderCreateService
import com.ecshopx.kotlin.service.order.OrderFlowService
import com.ecshopx.kotlin.service.order.OrderQueryService
import com.ecshopx.kotlin.service.pay.PayService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * ORDER-2/4/5/6/7/8 前台订单（docs/04 §2.4）。
 *
 * user_id/company_id 一律取自会员令牌；ORDER-3（创建+预支付）随 M0-7 支付闭环交付。
 */
@RestController
class OrderFrontController(
    private val create: OrderCreateService,
    private val query: OrderQueryService,
    private val flow: OrderFlowService,
    private val pay: PayService,
) {

    /** ORDER-2 创建订单（不含支付）；带 client_token 时重复提交返回同一单 */
    @PostMapping("/api/h5app/wxapp/order_new")
    fun createOrder(
        @RequestBody form: OrderCreateForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(create.create(userId(p), cid(p), form))

    /**
     * ORDER-3 创建订单 + 预支付：建单后直接按 `pay_type` 出渠道参数（point/deposit 会当场结清）。
     * 建单与预支付是两个事务——下单成功但渠道失败时订单仍在待付款列表里，可再次 PAY-2 支付。
     */
    @PostMapping("/api/h5app/wxapp/order")
    fun createAndPay(
        @RequestBody form: OrderCreateForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val created = create.create(userId(p), cid(p), form)
        return ApiResponse.ok(pay.prepay(userId(p), cid(p), created.getValue("order_id").toString().toLong(), form.payType))
    }

    /** ORDER-4 我的订单列表（status 数字页签或精确态字符串，见 OrderQueryService.applyStatusTab） */
    @GetMapping("/api/h5app/wxapp/orders")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(query.list(raw, cid(p), userId(p)))

    /** ORDER-5 订单详情：orderInfo + items[] + 状态文案 + 取消申请 + 物流包裹 */
    @GetMapping("/api/h5app/wxapp/order/{order_id}")
    fun detail(
        @PathVariable("order_id") orderId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(query.detail(id(orderId), userId(p)))

    /** ORDER-6 用户取消（未付款直接关闭并回补库存） */
    @PostMapping("/api/h5app/wxapp/order/cancel")
    fun cancel(
        @RequestBody form: OrderCancelForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val oid = form.orderId ?: throw BizException(ErrorCode.VALIDATION, "order_id 必填", mapOf("order_id" to listOf("不能为空")))
        return ApiResponse.ok(flow.cancelByUser(userId(p), oid, form.cancelReason))
    }

    /** ORDER-7 确认收货 */
    @PostMapping("/api/h5app/wxapp/order/confirmReceipt")
    fun confirm(
        @RequestBody form: OrderConfirmForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val oid = form.orderId ?: throw BizException(ErrorCode.VALIDATION, "order_id 必填", mapOf("order_id" to listOf("不能为空")))
        return ApiResponse.ok(flow.confirmReceipt(userId(p), oid))
    }

    /** ORDER-8 各状态角标数 */
    @GetMapping("/api/h5app/wxapp/orderscount")
    fun counts(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(query.counts(cid(p), userId(p)))

    /** 路径里的 order_id 是 16 位字符串，非法即 404 语义（不做静默 0） */
    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun userId(p: Principal?): Long =
        p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long =
        p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
