package com.ecshopx.kotlin.service.goods

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageParam
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.domain.MoneyFen

/**
 * GOODS-1/6/7 查询参数归一化（原始 query → Mapper 动态 WHERE 的 f-map）。
 * 与 PHP ItemsLists/ExportItems 实测对齐：price_gt/price_lt 以元入参 ×100 转分；
 * store_gt/store_lt 直接 intval（URL 文档 §2.2 曾把四者并写 ×100，见 docs/04 文末勘误）。
 */
object ItemQuery {

    fun toFilter(
        raw: Map<String, String?>,
        companyId: Long,
        front: Boolean,
        expand: (Long) -> Set<Long> = { setOf(it) },
    ): Pair<PageQuery, MutableMap<String, Any?>> {
        fun s(k: String): String? = raw[k]?.takeIf { it.isNotBlank() }
        fun l(k: String): Long? = s(k)?.toLongOrNull()
        fun b(k: String): Boolean? = s(k)?.let { it == "true" || it == "1" || it == "y" }

        val f = mutableMapOf<String, Any?>("companyId" to companyId)
        val pq = PageParam(s("page")?.toIntOrNull(), s("pageSize")?.toIntOrNull(), s("page_size")?.toIntOrNull()).toQuery()

        f["defaultOnly"] = if (b("is_sku") == true) null else true
        s("item_type")?.let { f["itemType"] = it } ?: run { f["excludeServices"] = true }
        s("item_name")?.let { f["itemName"] = it }
        s("keywords")?.let { f["keywords"] = it }
        s("item_bn")?.let { f["itemBn"] = it }
        s("barcode")?.let { f["barcode"] = it }
        l("item_id")?.let { f["itemId"] = it }
        l("goods_id")?.let { f["goodsId"] = it }
        s("approve_status")?.let { f["approveStatus"] = it }
        s("audit_status")?.let { f["auditStatus"] = it }
        l("supplier_id")?.let { f["supplierId"] = it }
        s("item_holder")?.let { f["itemHolder"] = it }
        s("item_source")?.let { f["itemSource"] = it }
        l("type")?.let { f["type"] = it }
        b("nospec")?.let { f["nospec"] = it }
        b("is_gift")?.let { f["isGift"] = it }
        b("is_warning")?.let { f["isWarning"] = it }
        l("brand_id")?.let { f["brandId"] = it }
        l("tag_id")?.let { f["tagId"] = it }
        l("templates_id")?.let { f["templatesId"] = it }
        s("special_type")?.let { f["specialType"] = it }
        s("rebate")?.let { rb ->
            if (rb == "0") f["rebateZero"] = true else if (rb in setOf("1", "2", "3")) f["rebatePos"] = true
        }
        // 元/分：价格边界 ×100；库存边界原样
        s("price_gt")?.let { f["priceGt"] = MoneyFen.fromYuanText(it).fen }
        s("price_lt")?.let { f["priceLt"] = MoneyFen.fromYuanText(it).fen }
        l("store_gt")?.let { f["storeGt"] = it }
        l("store_lt")?.let { f["storeLt"] = it }
        l("created_time_start")?.let { f["createdStart"] = it }
        l("created_time_end")?.let { f["createdEnd"] = it }
        distributorFilter(s("distributor_id"))?.let { f["distributorIds"] = it }

        val catIds = linkedSetOf<Long>()
        s("category")?.let { c ->
            c.split(',').mapNotNull { it.toLongOrNull() }.forEach { id -> catIds += expand(id) }
        }
        s("main_cat_id")?.let { m -> m.toLongOrNull()?.let { catIds += expand(it) } }
        if (catIds.isNotEmpty()) f["categoryIds"] = catIds.toList()

        if (front) f["approveStatus"] = "onsale" // 前台公开列表只出在售
        return pq to f
    }

    /** 数字 | all_distributor | csv → null(不过滤) 或 id 列表 */
    private fun distributorFilter(v: String?): List<Long>? = when {
        v == null -> null
        v == "all_distributor" -> null
        else -> v.split(',').mapNotNull { it.trim().toLongOrNull() }.ifEmpty { null }
    }

    fun requireId(raw: String?): Long =
        raw?.toLongOrNull() ?: throw BizException(ErrorCode.VALIDATION, "item_id 非法", mapOf("item_id" to listOf("必填且需为数字")))
}
