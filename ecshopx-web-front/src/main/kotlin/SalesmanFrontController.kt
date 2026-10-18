package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.CommissionService
import com.ecshopx.kotlin.service.distribution.SalesmanBindForm
import com.ecshopx.kotlin.service.distribution.SalesmanService
import com.ecshopx.kotlin.service.distribution.SalesmanWithdrawalApplyForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * M1-4 第三批后半（3B）导购会员面（docs/04 §18.1 SM-F1/F2 + §18.5 CW-F3/F4，前缀 `/api/h5app/wxapp`，链=`frontChain`）。
 *
 * 四条里只有 SM-F1 匿名（标 * 那条）：扫码进店发生在登录之前，那一刻小程序还没有令牌，
 * 而它给出的也只有"这是谁的店、他的头像和岗位"——公开信息，不含手机号明文（§18.2 掩码同口径）。
 *
 * CW-F3/F4 与 CW-F1/F2 挂在同一个会员账号上，但**两套余额互不合并**（§22 C176）：
 * 前者按 `salesman_id` 聚合、后者按 `distributor_id` 聚合，两条申请各自排在各自的在途闸门后面。
 * `user_id` 一律取自令牌而不是入参 —— 服务层用"这个 user 有没有在册的导购岗"认人，
 * 没有岗位就是 403，传谁都一样。
 */
@RestController
class SalesmanFrontController(
    private val salesmen: SalesmanService,
    private val commissions: CommissionService,
) {

    /** SM-F1 导购信息（`?salesman_id=` 或 `?mobile=` 二选一，匿名） */
    @GetMapping("/api/h5app/wxapp/salesperson")
    fun info(
        @RequestParam(name = "salesman_id", required = false) salesmanId: String?,
        @RequestParam(required = false) mobile: String?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(salesmen.frontInfo(TenantContext.companyId, sid(salesmanId), mobile))

    /** SM-F2 绑定我的导购（写 `members.salesman_id`，可换绑；这之后他自己下单会带上这个归属） */
    @PostMapping("/api/h5app/wxapp/member/bindSalesperson")
    fun bind(
        @RequestBody form: SalesmanBindForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.bind(cid(p), userId(p), form))

    /** CW-F3 导购提现申请（`{money, distributor_id?}`；非在册导购 403） */
    @PostMapping("/api/h5app/wxapp/salesman/applyCashWithdrawal")
    fun apply(
        @RequestBody form: SalesmanWithdrawalApplyForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(commissions.salesmanApply(cid(p), userId(p), form))

    /** CW-F4 我的导购提现记录（只出 `salesman_id>0` 的行，与店铺那侧的 CW-F2 各一份） */
    @GetMapping("/api/h5app/wxapp/salesman/getCashWithdrawalList")
    fun mine(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(commissions.salesmanList(cid(p), userId(p), raw))

    /** 空串与非法值都当"没给"：`frontInfo` 自己会因为两个都为空回 400，这里不替它决定文案 */
    private fun sid(raw: String?): Long? = raw?.toLongOrNull()?.takeIf { it > 0 }

    private fun userId(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
