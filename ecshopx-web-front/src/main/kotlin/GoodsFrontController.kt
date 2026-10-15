package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.goods.ItemService
import com.ecshopx.kotlin.service.member.CollectService
import com.ecshopx.kotlin.service.member.MemberService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * GOODS-7/8 前台商品列表 / 详情（公开面，M0-4）。
 * 出参走同一 [com.ecshopx.kotlin.service.goods.GoodsViews] 装配，仅列表强制 onsale、详情取 onsale 行。
 *
 * M0-8 追加：详情带会员令牌时记一条浏览足迹并回 `is_collected`（个人中心"我的浏览"数据源）。
 * 记录失败绝不影响商品展示——足迹是附属信息，用 runCatching 兜住。
 */
@RestController
class GoodsFrontController(
    private val items: ItemService,
    private val collect: CollectService,
    private val member: MemberService,
) {

    @GetMapping("/api/h5app/wxapp/goods/items")
    fun list(@RequestParam raw: Map<String, String>, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(items.list(raw, companyId(p), front = true))

    @GetMapping("/api/h5app/wxapp/goods/items/{item_id}")
    fun detail(
        @PathVariable("item_id") itemId: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val body = items.frontDetail(itemId).toMutableMap()
        if (p != null) {
            runCatching { collect.recordBrowse(p.companyId, p.id, itemId) }
            body["collect"] = runCatching { collect.statusOf(p.companyId, p.id, itemId) }.getOrNull()
            // 会员等级价：与购物车 MemberDiscountRule 同口径（详情页显示价必须等于结算价）
            val price = (body["price"] as? Long) ?: (body["price"] as? Int)?.toLong() ?: 0L
            body["member_price"] = runCatching { member.memberPrice(p.companyId, p.id, price) }.getOrDefault(price)
        } else {
            body["collect"] = null
        }
        return ApiResponse.ok(body)
    }

    /** 面客端带 token 时用其租户，匿名回落 TenantContext */
    private fun companyId(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
