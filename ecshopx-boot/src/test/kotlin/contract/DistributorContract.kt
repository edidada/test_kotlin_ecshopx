package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §18.1/§18.2/§18.4 抄录的店铺与分销域字段清单（M1-4 第一批：DS 与 SI 族）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object DistributorContract {

    /** DS-A2 店铺行：档案 + 三列状态 + 四张账的派生数（订单/成交额/未结佣金/可提现） */
    val SHOP_ROW = setOf(
        "distributor_id", "company_id", "is_distributor", "name", "mobile", "province", "city", "area",
        "address", "lng", "lat", "logo", "banner", "is_valid", "review_status", "source_from", "shop_id",
        "distribution_type", "distribution_type_text", "auto_sync_goods", "is_ziti", "is_delivery",
        "is_self_delivery", "payment_subject", "payment_subject_text", "rate", "rate_text",
        "item_count", "order_count", "pay_fee", "refund_fee", "commission_fee", "commission_closed",
        "balance", "created", "updated",
    )

    /** DS-A3 详情 = 列表行 + 简介 */
    val SHOP_DETAIL = SHOP_ROW + "introduce"

    /** DS-F1 前台店铺行：只给"挑店要用"的列，账上一律不外露 */
    val SHOP_FRONT_ROW = setOf(
        "distributor_id", "name", "logo", "banner", "is_valid", "distribution_type",
        "is_ziti", "is_delivery", "province", "city", "address", "mobile",
    )

    /** DS-F2 店铺主页 */
    val SHOP_FRONT_DETAIL = SHOP_FRONT_ROW + setOf("introduce", "item_count")

    /** DS-A8 店铺统计（§18.5 余额公式的七个组成数） */
    val SHOP_STATS = setOf(
        "distributor_id", "order_count", "pay_fee", "refund_fee",
        "commission_total", "commission_open", "commission_closed", "balance", "withdraw_in_flight",
    )

    /** SI-A1 挂品结果行（= SI-A3 的 `item`） */
    val SHOP_ITEM_SAVED = setOf(
        "id", "distributor_id", "item_id", "default_item_id", "goods_id", "shop_id",
        "price", "store", "sales", "is_show", "is_can_sale", "is_total_store",
    )

    /** SI-A1 出参 */
    val SHOP_ITEMS_SAVED = setOf("distributor_id", "items")

    /** SI-A2 列表行：店铺态与总部态并排（§18.4 两条腿 + effect_* 给"实际生效的是哪个"） */
    val SHOP_ITEM_ROW = setOf(
        "id", "distributor_id", "item_id", "default_item_id", "goods_id", "item_name", "item_bn", "pics",
        "shop_price", "price", "shop_store", "store", "shop_sales",
        "is_show", "is_can_sale", "is_total_store", "effect_price", "effect_store",
        "approve_status", "templates_id",
    )

    /** SI-A3 出参 */
    val SHOP_ITEM_CONFIGURED = setOf("distributor_id", "item")

    /** SI-A4 出参 */
    val SHOP_ITEMS_DELETED = setOf("distributor_id", "deleted")

    /** SI-F1 前台店铺商品行：只有店铺态，总部现值不外露 */
    val SHOP_ITEM_FRONT_ROW = setOf(
        "item_id", "goods_id", "default_item_id", "distributor_id", "item_name", "item_bn", "pics",
        "price", "store", "sales", "is_show", "is_can_sale", "is_total_store", "templates_id",
    )

    /** SI-F2 单行价格与库存（§18.4 两腿 + 两组原值，会员页据此说"店里 59、总部 89"） */
    val SHOP_ITEM_PRICE_STORE = setOf(
        "item_id", "distributor_id", "price", "store", "is_show", "is_can_sale", "is_total_store",
        "shop_price", "shop_store", "origin_price",
    )

    /** §18.4 落到购物车行与预览行上的三个新键 */
    val CART_LINE_SHOP_FIELDS = setOf("is_total_store", "stock_source", "origin_price")
}
