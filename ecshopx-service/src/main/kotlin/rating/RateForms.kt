package com.ecshopx.kotlin.service.rating

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * M1-5 评价域入参（docs/04 §23.1 `RATE-F1`、§23.2 `RATE-A2`）。
 *
 * `RATE-F1` 的 `items[]` 是**按行**提交的（一单一提交、一次可以给多行），
 * 所以这里是一个嵌套结构而不是平铺的 `item_id + star`：
 * 平铺写法要么逼会员点 N 次（每行一次），要么把多行的星级挤成一个字段。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RateSubmitForm(
    val orderId: String? = null,
    val items: List<RateItemForm>? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RateItemForm(
    /** 订单行的 `item_id`（商品 id），不是 `orders_normal_orders_items.id` */
    val itemId: Long? = null,
    /** 唯一必填的数值项（RATE-R2）：1..5 */
    val star: Int? = null,
    /** 允许空内容评价（源系统就这样，本项目不收紧） */
    val content: String? = null,
    val ratePic: List<String>? = null,
    val anonymous: Boolean? = null,
)

/** RATE-A2 商家回复 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RateReplyForm(
    val rateId: Long? = null,
    val content: String? = null,
)

/** RATE-A3 上下架（源系统叫"屏蔽"） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RateDisableForm(
    /** true=下架（别人看不见了），false=上架回来 */
    val disabled: Boolean? = null,
)
