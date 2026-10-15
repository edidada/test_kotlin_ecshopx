package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.pay.PayForm
import com.ecshopx.kotlin.service.pay.PayService
import com.ecshopx.kotlin.service.pay.RechargeForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PAY-1..4 前台支付（docs/04 §2.5，h5app 面，会员 JWT）。
 *
 * user_id/company_id 一律取自令牌；PAY-5 渠道回调是匿名 webhook，落在 admin 面的 NotifyController。
 */
@RestController
class PaymentFrontController(private val pay: PayService) {

    /** PAY-1 取既有订单的支付参数（余额/积分/可用渠道/待付金额） */
    @GetMapping("/api/h5app/wxapp/payment/config")
    fun config(
        @RequestParam("order_id") orderId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(pay.config(orderId, userId(p), cid(p)))

    /** PAY-2 支付订单：point/deposit 即时结清，wxpay 返回调起参数，offline_pay 落凭证；order_id=CZ… 时结清充值单 */
    @PostMapping("/api/h5app/wxapp/payment")
    fun payment(
        @RequestBody form: PayForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(pay.pay(userId(p), cid(p), form))

    /** PAY-3 轮询支付结果 */
    @PostMapping("/api/h5app/wxapp/payment/query")
    fun query(
        @RequestBody body: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val tradeId = body["trade_id"]?.toString()?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "trade_id 不能为空", mapOf("trade_id" to listOf("缺少支付单号")))
        return ApiResponse.ok(pay.tradeState(tradeId, userId(p), cid(p)))
    }

    /** PAY-4 储值充值下单（CZ 单），随 PAY-2 用 order_id=CZ… 完成到账 */
    @PostMapping("/api/h5app/wxapp/payment_deposit")
    fun deposit(
        @RequestBody form: RechargeForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(pay.recharge(userId(p), cid(p), form))

    private fun userId(p: Principal?): Long =
        p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long =
        p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
