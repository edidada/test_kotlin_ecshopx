package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §14.1 / §14.2 / §14.6 抄录的秒杀字段清单（M1-3 #57 SK-*）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object SeckillContract {

    /** SK-A2 场次行（时间窗派生 `date_status`，`activity_type` 是源系统整数码、`seckill_type` 是列值） */
    val ACT_ROW = setOf(
        "seckill_id", "company_id", "activity_name", "ad_pic", "activity_start_time", "activity_end_time",
        "release_time", "distributor_id", "activity_type", "seckill_type", "seckill_type_text",
        "limit_money", "validity_period", "disabled", "date_status", "item_count", "created", "updated",
    )

    /** SK-A3 详情追加商品行 */
    val ACT_DETAIL = ACT_ROW + setOf("items")

    /** 商品行（D24 三列池：总量 / 已售 / 占用，`available` 是派生值） */
    val GOODS_ROW = setOf(
        "id", "seckill_id", "item_id", "item_title", "pic", "activity_price", "activity_store",
        "sales_store", "hold_store", "limit_num", "is_show", "disabled", "available",
    )

    /** SK-A6 商品行保存出参 */
    val GOODS_SAVED = setOf("seckill_id", "activity_start_time", "activity_end_time", "items")

    /** SK-A7 报表（`sales_fee` 只算已付，占用中的钱不算业绩） */
    val REPORT = setOf(
        "seckill_id", "activity_name", "activity_start_time", "activity_end_time", "date_status",
        "item_count", "total_store", "sales_store", "hold_store", "available_store",
        "sales_fee", "hold_fee", "items",
    )

    /** SK-F1 前台场次行（停用场次不进这里，故无 `disabled`） */
    val FRONT_ROW = setOf(
        "seckill_id", "activity_name", "ad_pic", "activity_start_time", "activity_end_time",
        "date_status", "validity_period", "items_count",
    )

    /** SK-F2 前台商品行（`bought_by_me` 含未付在途单，与下单判据同一个数） */
    val FRONT_ITEM = setOf(
        "item_id", "item_title", "pic", "activity_price", "origin_price", "market_price",
        "sales_store", "available", "limit_num", "bought_by_me", "remain_for_me", "is_show",
        "item_status", "date_status", "activity_type", "activity_id",
    )

    /** MK-F3 `seckill` 键位（#57 落实，此前占位为 null） */
    val ITEM_OFFER = setOf(
        "seckill_id", "activity_name", "item_id", "activity_price", "origin_price", "available",
        "limit_num", "bought_by_me", "activity_start_time", "activity_end_time", "validity_period",
        "activity_type", "activity_id",
    )
}
