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
import com.ecshopx.kotlin.service.rating.RateDisableForm
import com.ecshopx.kotlin.service.rating.RateReplyForm
import com.ecshopx.kotlin.service.rating.RateService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * RATE-A1..A3 后台评价（docs/04 §23.2，前缀 `/api`）。
 *
 * 本域只有两条权限码（§23.4：59 `rate.lists` / 60 `rate.reply`），
 * `RATE-A3` 的上下架归到写码 `rate.reply` 下 —— 不是凑数：
 * 加盟体系里"把本店一条不实差评暂时收起"正是店铺运营要的能力，
 * 而它又不该是"能看别的店的评分"那种全场权限。
 *
 * 两条与归属有关的纪律：
 *  - `RATE-A1` 的行按 `distributor_id` 收窄（seed 的 role 2 只看得到本店那几行），
 *    手机号按 datapass 决定全值还是掩码，**后台面永远不匿名**（RATE-R3）；
 *  - `RATE-A2/A3` 传别人店的 `rate_id` → 404（Service 里 `scope.require`），
 *    不给"这条评价存在但不归你管"（§23.5 门槛③第 4 句）。
 */
@RestController
class RateAdminController(
    private val rates: RateService,
    private val pass: Datapass,
) {

    /** RATE-A1 评价列表（`order_id`/`item_id`/`star`/`is_reply`/`distributor_id` + 分页） */
    @RequiresResource("rate.lists")
    @GetMapping("/api/comments")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(rates.adminList(cid(p), raw, scope(p), pass.full(p)))

    /** RATE-A2 商家回复（重复回复 = 覆盖最新那条 seller 行，RATE-R4） */
    @Audited(route = "rate.reply")
    @RequiresResource("rate.reply")
    @PostMapping("/api/comment/reply")
    fun reply(
        @RequestBody form: RateReplyForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(rates.reply(cid(p), scope(p), form))

    /** RATE-A3 上下架（只翻可见位，不删行、不动订单的 `is_rate`） */
    @Audited(route = "rate.reply")
    @RequiresResource("rate.reply")
    @PutMapping("/api/comment/{rate_id}/disable")
    fun disable(
        @PathVariable("rate_id") rateId: String,
        @RequestBody form: RateDisableForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val disabled = form.disabled
            ?: throw BizException(ErrorCode.VALIDATION, "disabled 不能为空", mapOf("disabled" to listOf("不能为空")))
        return ApiResponse.ok(rates.setDisabled(cid(p), scope(p), rid(rateId), disabled))
    }

    /** 路径 id 非法与查不到同一个应答 */
    private fun rid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "评价不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
