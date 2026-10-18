package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.asset.DepositService
import com.ecshopx.kotlin.service.asset.PointService
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PT-F1..F3 + CZ-F1..F3 会员面积分与储值（docs/04 §23.1，前缀 `/api/h5app/wxapp`）。
 *
 * 这六条合起来是 M1-5 补的第一件事：**账已经写了一半，但从来没人能读**。
 * `point_member_log`/`deposit_trade` 从 M0-7 起就在写，前台却看不到，
 * 会员只知道"我有多少分/多少钱"，对不上账时无从判断——所以这六条全是 GET、全是读，
 * 没有任何一处写口，写仍在 `PayService` 的结清点与 `PointChannel`/`DepositChannel` 里。
 *
 * 三条出参上的纪律：
 *  - `user_id` 一律取自令牌，**不接受入参**（§23.1 表头那条），所以别人的流水在这六条上不可能被读到；
 *  - 积分与储值的 `enable` 各自读自己的开关（`pay.point.enable` / `pay.deposit.enable`），
 *    关掉时余额数字照旧出、开关位告诉前台要不要把入口收起——余额是既成事实，不该被一个开关抹掉；
 *  - `NOTPAY` 的充值单也在储值流水里（他还在那一步等支付），出参用 `status` 区分，不隐藏。
 */
@RestController
class AssetFrontController(
    private val points: PointService,
    private val deposits: DepositService,
) {

    /** PT-F1 我的积分总览 */
    @GetMapping("/api/h5app/wxapp/point")
    fun point(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(points.overview(uid(p), cid(p)))

    /** PT-F2 我的积分流水（`remark?` + 分页） */
    @GetMapping("/api/h5app/wxapp/point_logs")
    fun pointLogs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(points.logs(uid(p), cid(p), raw))

    /** PT-F3 积分规则展示（库里不存文案，这一段是拼出来的） */
    @GetMapping("/api/h5app/wxapp/point_rule")
    fun pointRule(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(points.ruleView(cid(p)))

    /** CZ-F1 我的余额（`balance = deposit_amount + gift_amount`，赠金也是钱） */
    @GetMapping("/api/h5app/wxapp/deposit")
    fun deposit(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.overview(uid(p), cid(p)))

    /** CZ-F2 我的储值流水（`trade_type?` recharge/consume/refund） */
    @GetMapping("/api/h5app/wxapp/deposit_logs")
    fun depositLogs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(deposits.logs(uid(p), cid(p), raw))

    /** CZ-F3 充值档位（只出 `status=1`，按金额升序；无档位回空集） */
    @GetMapping("/api/h5app/wxapp/deposit_rules")
    fun depositRules(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.rules(cid(p)))

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
