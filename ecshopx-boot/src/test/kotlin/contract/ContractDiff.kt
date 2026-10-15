package com.ecshopx.kotlin.boot.contract

import com.fasterxml.jackson.databind.JsonNode

/**
 * 出参契约 diff（M0-4 建立的测试框架，M1/M2 各域复用）。
 *
 * 期望字段集抄自 docs/04-URL列表文档.md（§2.2 商品行 / GOODS-8 详情），
 * 判定规则：**缺失字段即失败**（契约破坏）；多出的字段不算失败但会回显，
 * 因为源系统出参普遍比文档列举更宽（PHP Fractal 直接吐实体列）。
 */
object ContractDiff {

    class Result(val missing: Set<String>, val extra: Set<String>, val label: String) {
        val ok get() = missing.isEmpty()
        override fun toString() = "[$label] 缺失=${missing.sorted()} 多出=${extra.sorted()}"
    }

    fun diff(actual: Set<String>, expected: Set<String>, label: String): Result =
        Result(expected - actual, actual - expected, label)

    fun keysOf(node: JsonNode): Set<String> = node.fieldNames().asSequence().toSet()

    /** 断言用：失败信息里带上实际键集，便于定位是文档漏写还是实现漏字段 */
    fun failMessage(r: Result, actualKeys: Set<String>) =
        "$r\n实际字段=${actualKeys.sorted()}"
}

/** docs/04 §2.2 抄录的字段清单（唯一事实来源仍是文档，这里只做机械化比对） */
object GoodsContract {

    /** GOODS-1/6 商品行（docs/04 §2.2 "商品行字段"） */
    val ITEM_ROW = setOf(
        "item_id", "item_type", "item_category", "consume_type", "item_name", "item_bn", "barcode", "brief",
        "company_id", "price", "cost_price", "market_price", "item_unit", "store", "sales", "nospec",
        "is_default", "default_item_id", "goods_id", "pics", "videos", "weight", "volume", "approve_status",
        "audit_status", "distributor_id", "supplier_id", "brand_id", "goods_brand", "type", "is_point",
        "point", "rebate", "is_gift", "is_medicine", "is_prescription", "created", "updated",
        "item_cat_id", "item_main_cat_id", "spec_items", "type_labels",
    )

    /** is_sku=true 时每行追加 */
    val SKU_ROW = ITEM_ROW + "item_spec"

    /** GOODS-1 列表 data 外层 */
    val LIST_ENVELOPE = setOf("total_count", "warning_store", "list")

    /** GOODS-8 前台详情 = 商品行 + 聚合字段（含 item_spec，详情按 SKU 行组装） */
    val FRONT_DETAIL = ITEM_ROW + setOf(
        "item_spec", "intro", "item_spec_list", "item_params_list", "member_price", "distributor_info",
        "cur", "tdk_data", "recommend_items", "tagList",
    )

    val SPEC_ITEM_ROW = setOf("item_id", "item_name", "price", "store", "item_spec", "custom_spec_id", "custom_spec_name")
    val CUR = setOf("currency", "symbol", "rate")
    val TDK = setOf("title", "mate_description", "mate_keywords")
}
