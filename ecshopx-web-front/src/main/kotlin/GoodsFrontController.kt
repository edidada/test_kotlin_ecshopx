package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.goods.ItemService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * GOODS-7/8 前台商品列表 / 详情（公开面，M0-4）。
 * 出参走同一 [com.ecshopx.kotlin.service.goods.GoodsViews] 装配，仅列表强制 onsale、详情取 onsale 行。
 */
@RestController
class GoodsFrontController(private val items: ItemService) {

    @GetMapping("/api/h5app/wxapp/goods/items")
    fun list(@RequestParam raw: Map<String, String>, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(items.list(raw, companyId(p), front = true))

    @GetMapping("/api/h5app/wxapp/goods/items/{item_id}")
    fun detail(@PathVariable("item_id") itemId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(items.frontDetail(itemId))

    /** 面客端带 token 时用其租户，匿名回落 TenantContext */
    private fun companyId(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
