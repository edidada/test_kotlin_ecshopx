package com.ecshopx.kotlin.service.goods

import com.ecshopx.kotlin.dao.goods.ItemAttrRelView
import com.ecshopx.kotlin.dao.goods.ItemDO
import com.ecshopx.kotlin.dao.goods.ItemRelTypeDO
import com.ecshopx.kotlin.dao.goods.ItemTagDO
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * DO → 出参行（snake_case，字段清单对齐 docs/04 §GOODS-1 商品行 / §GOODS-8 详情）。
 * 一次批量装配：主行 + 子行（spec_items）+ 条码/属性/分类/标签/服务标签，避免 N+1。
 * 值语义：整数分、Unix 秒、布尔照 DB；pics/videos 由 CLOB JSON 字符串解析为数组。
 */
object GoodsViews {

    const val ATTR_SPEC = "spec"
    const val ATTR_PARAM = "item_param"

    /** 批量装配上下文：ItemService.enrich 一次性回捞后传入 */
    class Enrichment(
        val childrenByParent: Map<Long, List<ItemDO>> = emptyMap(),
        val attrsByItem: Map<Long, List<ItemAttrRelView>> = emptyMap(),
        val barcodesByItem: Map<Long, List<String>> = emptyMap(),
        val catsByItem: Map<Long, List<Long>> = emptyMap(),
        val mainCat: Map<Long, Long> = emptyMap(),
        val typeLabelsByItem: Map<Long, List<ItemRelTypeDO>> = emptyMap(),
        val tagsByItem: Map<Long, List<ItemTagDO>> = emptyMap(),
    )

    /** 图片列存 JSON 数组字符串（PHP json_encode），坏值降级为空数组 */
    fun jsonList(mapper: ObjectMapper, raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val node: JsonNode = runCatching { mapper.readTree(raw) }.getOrNull() ?: return raw.split(',').filter { it.isNotBlank() }
        return when {
            node.isArray -> node.mapNotNull { if (it.isTextual) it.textValue() else it.toString() }
            node.isObject -> listOf(node.toString())
            node.isTextual -> listOf(node.textValue())
            else -> emptyList()
        }
    }

    /**
     * 商品行。
     * @param skuRow 是否输出 item_spec（GOODS-1 的 is_sku=true 行 / SKU 列表 / 子行）
     */
    fun row(
        mapper: ObjectMapper,
        it: ItemDO,
        e: Enrichment,
        skuRow: Boolean,
    ): LinkedHashMap<String, Any?> {
        val attrs = e.attrsByItem[it.itemId].orEmpty()
        val cats = e.catsByItem[it.itemId].orEmpty()
        val r = LinkedHashMap<String, Any?>()
        r["item_id"] = it.itemId
        r["item_type"] = it.itemType
        r["item_category"] = it.itemCategory
        r["consume_type"] = it.consumeType
        r["item_name"] = it.itemName
        r["item_bn"] = it.itemBn
        r["barcode"] = e.barcodesByItem[it.itemId]?.firstOrNull()
        r["brief"] = it.brief
        r["company_id"] = it.companyId
        r["price"] = it.price
        r["cost_price"] = it.costPrice
        r["market_price"] = it.marketPrice
        r["item_unit"] = it.itemUnit
        r["store"] = it.store
        r["warning_store"] = it.warningStore
        r["sales"] = it.sales
        r["nospec"] = it.nospec
        r["is_default"] = it.isDefault
        r["default_item_id"] = it.defaultItemId
        r["goods_id"] = it.goodsId
        r["pics"] = jsonList(mapper, it.pics)
        r["videos"] = jsonList(mapper, it.videos)
        r["weight"] = it.weight
        r["volume"] = it.volume
        r["approve_status"] = it.approveStatus
        r["audit_status"] = it.auditStatus
        r["distributor_id"] = it.distributorId
        r["supplier_id"] = it.supplierId
        r["brand_id"] = it.brandId
        r["goods_brand"] = ""                        // 品牌主数据属 M1，先占位保住字段
        r["type"] = it.type
        r["is_point"] = it.isPoint
        r["point"] = it.point
        r["rebate"] = it.rebate
        r["is_gift"] = it.isGift
        r["is_medicine"] = it.isMedicine
        r["is_prescription"] = it.isPrescription
        r["templates_id"] = it.templatesId
        r["item_source"] = it.itemSource
        r["created"] = it.created
        r["updated"] = it.updated
        r["item_cat_id"] = cats.firstOrNull()
        r["item_main_cat_id"] = e.mainCat[it.itemId]
        r["type_labels"] = e.typeLabelsByItem[it.itemId].orEmpty().map { tl ->
            linkedMapOf<String, Any?>(
                "label_id" to tl.labelId, "label_name" to tl.labelName,
                "label_price" to tl.labelPrice, "num_type" to tl.numType,
                "num" to tl.num, "is_not_limit_num" to tl.isNotLimitNum,
                "limit_time" to tl.limitTime,
            )
        }
        r["tagList"] = e.tagsByItem[it.itemId].orEmpty().map { t ->
            linkedMapOf<String, Any?>(
                "tag_id" to t.tagId, "tag_name" to t.tagName,
                "tag_color" to t.tagColor, "font_color" to t.fontColor,
                "front_show" to t.frontShow,
            )
        }
        if (skuRow) r["item_spec"] = specOf(attrs)
        // 商品行形态统一：子行也带 spec_items（空数组），出参字段集合不随行动态变化
        r["spec_items"] = e.childrenByParent[it.itemId].orEmpty().map { c ->
            row(mapper, c, e, skuRow = true)
        }
        return r
    }

    /** 规格属性 → [{attribute_id, attribute_value}] */
    fun specOf(attrs: List<ItemAttrRelView>): List<Map<String, Any?>> =
        attrs.filter { it.attributeType == ATTR_SPEC }.map { a ->
            linkedMapOf<String, Any?>(
                "attribute_id" to a.attributeId,
                "attribute_value" to (a.attributeValue ?: a.customAttributeValue),
            )
        }

    /** 前台详情 spec_items：补 custom_spec_id "1-2" / custom_spec_name "红、L" */
    fun specItemsFront(
        mapper: ObjectMapper,
        children: List<ItemDO>,
        attrsByItem: Map<Long, List<ItemAttrRelView>>,
    ): List<Map<String, Any?>> = children.map { c ->
        val spec = attrsByItem[c.itemId].orEmpty().filter { it.attributeType == ATTR_SPEC }
        val base = row(mapper, c, Enrichment(attrsByItem = attrsByItem), skuRow = true)
        base.remove("spec_items")
        base["custom_spec_id"] = spec.joinToString("-") { it.attributeId.toString() }
        base["custom_spec_name"] = spec.joinToString("、") { it.attributeValue ?: it.customAttributeValue ?: "" }
        base
    }

    /** 商品参数（item_param） */
    fun paramsList(attrs: List<ItemAttrRelView>): List<Map<String, Any?>> =
        attrs.filter { it.attributeType == ATTR_PARAM }
            .sortedBy { it.attributeId }
            .map { a ->
                linkedMapOf<String, Any?>(
                    "attribute_id" to a.attributeId,
                    "attribute_name" to a.attributeName,
                    "attribute_value" to (a.attributeValue ?: a.customAttributeValue),
                )
            }

    /** 契约/测试用：商品行必备字段名（与 docs/04 §GOODS-1 一致，顺序无关） */
    val ROW_FIELDS = listOf(
        "item_id", "item_type", "item_category", "consume_type", "item_name", "item_bn", "barcode", "brief",
        "company_id", "price", "cost_price", "market_price", "item_unit", "store", "sales", "nospec",
        "is_default", "default_item_id", "goods_id", "pics", "videos", "weight", "volume", "approve_status",
        "audit_status", "distributor_id", "supplier_id", "brand_id", "goods_brand", "type", "is_point",
        "point", "rebate", "is_gift", "is_medicine", "is_prescription", "created", "updated",
        "item_cat_id", "item_main_cat_id",
    )

    /** GOODS-8 详情附加字段 */
    val FRONT_DETAIL_FIELDS = listOf(
        "intro", "spec_items", "item_spec_list", "item_params_list", "member_price",
        "distributor_info", "cur", "tdk_data", "recommend_items", "tagList",
    )
}
