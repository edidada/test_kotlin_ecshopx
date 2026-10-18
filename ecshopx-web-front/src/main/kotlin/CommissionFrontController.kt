package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.distribution.CommissionService
import com.ecshopx.kotlin.service.distribution.WithdrawalApplyForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * CW-F1/F2 会员面提现（docs/04 §18.2，前缀 `/api/h5app/wxapp`，鉴权=会员 JWT）。
 *
 * 两条都需要登录：提现是把钱提走，没有匿名这一说。
 * `user_id` 一律取自令牌而不是入参 —— 服务层用"这个 user 在不在这家店的在册员工表里"
 * 判店铺归属（C152：本项目 `distribution_distributor` 没有归属人列，
 * `distribution_distributor_salesman.user_id` 是唯一可信的那条关系），
 * 于是"替别家店里提"在 DAO 层就是 0 命中 → 403，而不是靠前端不传就没事。
 *
 * CW-F3/F4（导购提现）属 #64：那张表的归属链要先把 `salesman` 的登录面建起来。
 */
@RestController
class CommissionFrontController(private val commissions: CommissionService) {

    /** CW-F1 店铺提现申请：`{distributor_id, money}` → 出参 `{id, status:'applied', money}` */
    @PostMapping("/api/h5app/wxapp/cash_withdrawal")
    fun apply(
        @RequestBody form: WithdrawalApplyForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(commissions.apply(cid(p), userId(p), form))

    /** CW-F2 我的提现记录（`?status=` 过滤 + 分页） */
    @GetMapping("/api/h5app/wxapp/cash_withdrawals")
    fun mine(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(commissions.myList(cid(p), userId(p), raw))

    private fun userId(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
