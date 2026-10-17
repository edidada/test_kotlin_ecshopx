package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.MarketingService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * MK-F1/F2/F3 会员面满减满折（docs/04 §14.1，前缀 `/api/h5app/wxapp`，鉴权=会员 JWT）。
 *
 * 前台只看得到**已审核 + 启用 + 在窗**的活动（服务层 `frontList` 的筛子与算价链的取数条件同一条），
 * 于是"页面上写着满 100 减 20、结算时不减"这种最难解释的分歧不会发生。
 */
@RestController
class MarketingFrontController(private val marketing: MarketingService) {

    /** MK-F1 进行中的活动列表 */
    @GetMapping("/api/h5app/wxapp/marketing/activities")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(marketing.frontList(cid(p), raw))

    /** MK-F2 活动详情（`use_bound=1` 时带圈定商品） */
    @GetMapping("/api/h5app/wxapp/marketing/activities/{marketing_id}")
    fun detail(
        @PathVariable("marketing_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(marketing.frontDetail(cid(p), id(id)))

    /** MK-F3 单商品可享的全部营销（满减 tag + 秒杀价占位） */
    @GetMapping("/api/h5app/wxapp/items/{item_id}/promotions")
    fun itemPromotions(
        @PathVariable("item_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(marketing.itemPromotions(cid(p), id(id)))

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "资源不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
