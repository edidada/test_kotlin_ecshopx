package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.DistributorService
import com.ecshopx.kotlin.service.distribution.ShopItemService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * DS-F1/F2 + SI-F1/F2 会员面店铺与店铺商品（docs/04 §18.2，前缀 `/api/h5app/wxapp`）。
 *
 * 前台只出**已审核 + 启用 + 在营业**的店（服务层 `frontList` 的筛子与详情页判据同一条），
 * 于是"店铺列表点进去 404"这种分歧不会发生；未营业的店对前台一律按"不存在"处理，
 * 不用 403 —— 前台不该知道一家店存在但被关了。
 *
 * 四条都可匿名：租户从 [TenantContext]（域名/租户解析）拿，登录了就用令牌里的 `company_id`。
 */
@RestController
class DistributorFrontController(
    private val distributors: DistributorService,
    private val shopItems: ShopItemService,
) {

    /** DS-F1 营业中店铺列表 */
    @GetMapping("/api/h5app/wxapp/distributors")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(distributors.frontList(cid(p), raw))

    /** DS-F2 店铺主页（档案 + 简介 + 挂品数） */
    @GetMapping("/api/h5app/wxapp/distributors/{distributor_id}")
    fun detail(
        @PathVariable("distributor_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(distributors.frontDetail(cid(p), id(id)))

    /** SI-F1 店铺商品列表（`?distributor_id=` 必填） */
    @GetMapping("/api/h5app/wxapp/goods/shopitems")
    fun shopItemsList(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(shopItems.frontList(cid(p), raw))

    /** SI-F2 单商品在本店的价格与库存（`?distributor_id=` 缺省=总部态） */
    @GetMapping("/api/h5app/wxapp/goods/items_price_store/{item_id}")
    fun priceStore(
        @PathVariable("item_id") id: String,
        @RequestParam(required = false, name = "distributor_id") distributorId: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(shopItems.priceStore(cid(p), id(id), distributorId?.toLongOrNull()?.takeIf { it > 0 }))

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "资源不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
