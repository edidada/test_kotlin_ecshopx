package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.goods.ItemForm
import com.ecshopx.kotlin.service.goods.ItemService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** GOODS-1..6 后台商品 / SKU（docs/04 §2.2）；读用 goods.items.lists，写用 goods.items.update */
@RestController
class GoodsAdminController(private val items: ItemService) {

    @RequiresResource("goods.items.lists")
    @GetMapping("/api/goods/items")
    fun list(@RequestParam raw: Map<String, String>, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(items.list(raw, companyId(p), front = false))

    @RequiresResource("goods.items.lists")
    @GetMapping("/api/goods/sku")
    fun sku(@RequestParam raw: Map<String, String>, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(items.skuList(raw, companyId(p)))

    @RequiresResource("goods.items.lists")
    @GetMapping("/api/goods/items/{item_id}")
    fun detail(@PathVariable("item_id") itemId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(items.adminDetail(itemId))

    @Audited(route = "goods.items.create")
    @RequiresResource("goods.items.update")
    @PostMapping("/api/goods/items")
    fun create(@RequestBody form: ItemForm, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("item_id" to items.create(companyId(p), form)))

    @Audited(route = "goods.items.update")
    @RequiresResource("goods.items.update")
    @PutMapping("/api/goods/items/{item_id}")
    fun update(@PathVariable("item_id") itemId: Long, @RequestBody form: ItemForm): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("item_id" to items.update(itemId, form)))

    @Audited(route = "goods.items.delete")
    @RequiresResource("goods.items.update")
    @DeleteMapping("/api/goods/items/{item_id}")
    fun delete(@PathVariable("item_id") itemId: Long): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("result" to (items.delete(itemId) > 0)))

    private fun companyId(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
