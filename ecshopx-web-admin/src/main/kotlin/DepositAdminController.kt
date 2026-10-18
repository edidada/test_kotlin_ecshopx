package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.asset.DepositService
import com.ecshopx.kotlin.service.asset.OperatorRechargeForm
import com.ecshopx.kotlin.service.asset.RechargeRuleForm
import com.ecshopx.kotlin.service.auth.Datapass
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.DistributorScope
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * CZ-A1..A4 后台储值（docs/04 §23.2，前缀 `/api`）。
 *
 * 三条权限码，各管一件事（§23.4）：
 *  - `deposit.lists`（56）看流水、看总览、看档位；
 *  - `deposit.rule.update`（57）改档位价目表；
 *  - `deposit.recharge`（58）代客充值 —— 这条单独一个码，因为它**动钱**。
 * 店铺运营型账号只有 56：能对着流水解释会员的余额，但不能给自己管的店充值、也不能改价目表。
 *
 * `CZ-A3` 不开第二条入账路径：走 `PayService.rechargeByOperator`，
 * 那条方法内部复用会员自助充值的同一个结清腿（CZ-R1），
 * 所以代客充值与自助充值在账上长得一模一样，对账脚本不需要分支。
 */
@RestController
class DepositAdminController(
    private val deposits: DepositService,
    private val pass: Datapass,
) {

    /** CZ-A1 储值交易列表（`trade_type`/`status`/`user_id`/时间段 + datapass） */
    @RequiresResource("deposit.lists")
    @GetMapping("/api/deposit/trades")
    fun trades(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(deposits.adminTrades(cid(p), raw, scope(p), pass.full(p)))

    /** CZ-A4 储值总览（现值与流水各说一件事，不拿一个去减另一个） */
    @RequiresResource("deposit.lists")
    @GetMapping("/api/deposit/count")
    fun count(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.count(cid(p), raw))

    /** CZ-A2 档位列表（`status?` 不传=含下架） */
    @RequiresResource("deposit.lists")
    @GetMapping("/api/deposit/recharge_rules")
    fun rules(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.adminRules(cid(p), raw))

    /** CZ-A2 新建档位（`recharge_amount > 0`、`gift_amount >= 0`） */
    @Audited(route = "deposit.rule.update")
    @RequiresResource("deposit.rule.update")
    @PostMapping("/api/deposit/recharge_rule")
    fun createRule(
        @RequestBody form: RechargeRuleForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.createRule(cid(p), form))

    /** CZ-A2 改档位（读-改-写：没传的字段保持原值） */
    @Audited(route = "deposit.rule.update")
    @RequiresResource("deposit.rule.update")
    @PutMapping("/api/deposit/recharge_rule/{id}")
    fun updateRule(
        @PathVariable("id") id: String,
        @RequestBody form: RechargeRuleForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.updateRule(cid(p), rid(id), form))

    /**
     * CZ-A2 删档位。
     *
     * **不设引用守卫**（与 `INV-F6` 删抬头相反）：档位是价目表不是账，
     * 历史 `deposit_trade` 已经把当时的金额写进自己那一行了，删掉档位不会让历史流水失去解释。
     */
    @Audited(route = "deposit.rule.update")
    @RequiresResource("deposit.rule.update")
    @DeleteMapping("/api/deposit/recharge_rule/{id}")
    fun deleteRule(
        @PathVariable("id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.deleteRule(cid(p), rid(id)))

    /** CZ-A3 后台代客充值（不走支付，当场入账；`remark` 必填） */
    @Audited(route = "deposit.recharge")
    @RequiresResource("deposit.recharge")
    @PostMapping("/api/deposit/recharge")
    fun recharge(
        @RequestBody form: OperatorRechargeForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(deposits.operatorRecharge(cid(p), form))

    /** 档位 id 非法与不存在同一个应答 */
    private fun rid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "档位不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
