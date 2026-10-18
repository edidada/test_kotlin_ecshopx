package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.rating.RateService
import com.ecshopx.kotlin.service.rating.RateSubmitForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * RATE-F1..F5 会员面评价（docs/04 §23.1，前缀 `/api/h5app/wxapp`）。
 *
 * `RATE-F1` 是本批唯一的写口，它同时是 `orders_normal_orders.is_rate` 这一列在全仓的**第一个写者**：
 * 建表就有、ORDER-8 的 `evaluation` 桶一直在读，M0/M1 却没人往里写，
 * 于是"待评价数"恒等于"已完成数"（BUCKET-R1 说的情形）。这一条落地后那个桶才是真的。
 *
 * 三个形状上的说明：
 *  - **闸门顺序在 [RateService.submit] 里**（非本人 404 → 非完成态 422 → 行对应关系 → CAS），
 *    控制层不重复判，避免"两处闸门各漏一条"；
 *  - `RATE-F2` 的 `viewer` 取令牌里的 `user_id`：匿名评价对别人印"匿名用户"、对自己印真名（RATE-R3），
 *    这个"自己"只能是当前令牌，不能是入参；
 *  - `RATE-F3` 的详情只给本人（Service 里 `findMine`），商品页看别人的评价走 `RATE-F2`。
 */
@RestController
class RateFrontController(private val rates: RateService) {

    /** RATE-F1 提交评价（一单一提交、按行落） */
    @PostMapping("/api/h5app/wxapp/order/rate")
    fun submit(
        @RequestBody form: RateSubmitForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(rates.submit(cid(p), uid(p), form))

    /** RATE-F2 评价列表（`item_id` 或 `distributor_id` 至少一个） */
    @GetMapping("/api/h5app/wxapp/order/rate/list")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(rates.list(cid(p), raw, p?.id))

    /** RATE-F3 我的评价详情（单行 + 全部回复） */
    @GetMapping("/api/h5app/wxapp/order/rate/{rate_id}")
    fun detail(
        @PathVariable("rate_id") rateId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(rates.detail(cid(p), rid(rateId), uid(p)))

    /** RATE-F4 我的评价 */
    @GetMapping("/api/h5app/wxapp/order/rate/my")
    fun mine(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(rates.mine(cid(p), uid(p), raw))

    /** RATE-F5 某单的待评价行 */
    @GetMapping("/api/h5app/wxapp/order/rate/pending")
    fun pending(
        @RequestParam(required = false, name = "order_id") orderId: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(rates.pending(cid(p), uid(p), oid(orderId)))

    /** 路径参数非法与查无此行同一个出口：都是"这条评价你没有" */
    private fun rid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "评价不存在")

    private fun oid(raw: String?): Long =
        raw?.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
