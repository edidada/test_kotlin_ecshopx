package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.CommissionCloseForm
import com.ecshopx.kotlin.service.distribution.CommissionService
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.distribution.WithdrawalCheckForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * DL-A1/A2/A3 + CW-A1/A2/A3 后台佣金与提现（docs/04 §18.5、§18.2，前缀 `/api`）。
 *
 * 四条权限码把"看钱 / 把钱变成可提 / 收提现申请 / 批提现"分成四个岗（§18.7）：
 *  - `distribution.logs`：只读，店铺运营账号也给它（他要看自己店的佣金）；
 *  - `distribution.close`：结算是把"在途"推成"可提"的动作，只能平台财务干；
 *  - `withdraw.lists` / `withdraw.check`：申请方与审核方必须分人，
 *    与 M1-1「退款发起与退款审核分立」同一个理由。
 *
 * 每条都带 [DistributorScope]：分销商账号只看自己绑定的店，
 * 传别人的 `distributor_id` 在明细上是 404、在列表上是"这一页 0 行"（§18.8 门槛② 的延续）。
 */
@RestController
class CommissionAdminController(private val commissions: CommissionService) {

    /** DL-A1 佣金记录（`distributor_id`/`order_id`/`is_close`/`begin_time`/`end_time`/`keyword` + 分页） */
    @RequiresResource("distribution.logs")
    @GetMapping("/api/distribution/logs")
    fun logs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(commissions.list(cid(p), raw, scope(p)))

    /** DL-A2 分销统计（全场或按 `distributor_id` 收窄） */
    @RequiresResource("distribution.logs")
    @GetMapping("/api/distribution/count")
    fun count(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(commissions.stats(cid(p), raw, scope(p)))

    /** DL-A3 显式结算：把到期未结的行一把置已结算（幂等，第二次 `closed_count=0`） */
    @Audited(route = "distribution.close")
    @RequiresResource("distribution.close")
    @PostMapping("/api/distribution/logs/close")
    fun close(
        @RequestBody form: CommissionCloseForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            commissions.close(
                companyId = cid(p),
                distributorId = form.distributorId?.takeIf { it > 0 },
                toTime = form.toTime?.takeIf { it > 0 },
                scope = scope(p),
            ),
        )

    /** CW-A1 提现申请列表 */
    @RequiresResource("withdraw.lists")
    @GetMapping("/api/distribution/cash_withdrawals")
    fun withdrawals(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(commissions.adminList(cid(p), raw, scope(p)))

    /** CW-A2 处理提现：`applied→confirmed/refused`、`confirmed→paid/refused` */
    @Audited(route = "withdraw.check")
    @RequiresResource("withdraw.check")
    @PutMapping("/api/distribution/cash_withdrawal/{id}")
    fun check(
        @PathVariable("id") withdrawId: String,
        @RequestBody form: WithdrawalCheckForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(commissions.check(cid(p), id(withdrawId), form, scope(p)))

    /** CW-A3 打款信息（mock 回执，D47：不接渠道代付） */
    @RequiresResource("distribution.logs")
    @GetMapping("/api/distributor/cash_withdrawal/payinfo/{id}")
    fun payinfo(
        @PathVariable("id") withdrawId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(commissions.payinfo(cid(p), id(withdrawId), scope(p)))

    /** 路径变量按 String 收：非数字不是 400 而是 404，与源系统"查不到这条单"一致 */
    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.NOT_FOUND, "提现申请不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
