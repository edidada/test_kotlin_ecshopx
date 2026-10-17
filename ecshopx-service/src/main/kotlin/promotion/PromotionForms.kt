package com.ecshopx.kotlin.service.promotion

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * M1-3 营销域入参（docs/04 §14.1/§14.2）。
 *
 * 一律 snake_case 绑定、字段全可空：**校验在服务层**，与 M0 起所有表单同一口径
 * （Bean Validation 的报错形状不是源系统的 `{message, errors}`，接上来只会破坏契约）。
 * 金额单位一律**分**（整数），前台/后台都不得传「元」；满减规则里的「元」只存在于
 * 存量 JSON 文本里，由 `MarketingRuleParser` 在解析处换算。
 */

// ===== 优惠券 =====

/** CP-A1/CP-A4 建券与改券（同一张表单，`coupon_id` 只在改券时用） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CouponForm(
    val couponId: Long? = null,
    /** cash=满减现金券（D22：未知类型不参与算价） */
    val couponType: String? = null,
    val couponName: String? = null,
    /** 面额（分） */
    val discountFee: Long? = null,
    /** 使用门槛（分），0=无门槛 */
    val minOrderFee: Long? = null,
    /** 发放总量，0=不限量 */
    val totalNum: Long? = null,
    /** range=固定起止 / days=领取后 N 天 */
    val validType: String? = null,
    val beginTime: Long? = null,
    val endTime: Long? = null,
    val validDays: Int? = null,
    /** 每人限领张数，0=不限 */
    val getLimit: Int? = null,
    val instruction: String? = null,
)

/** CP-A5 停发/恢复 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CouponStatusForm(val status: Boolean? = null)

/** CP-F2 领取 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CouponReceiveForm(val couponId: Long? = null)

/**
 * CP-A8 后台发券。三选一，优先级 `user_ids` > `grade_id` > 定向活动：
 *  - `user_ids`：点名发给这些会员；
 *  - `grade_id` + `coupon_id`：发给该等级的全部在籍会员；
 *  - 只给 `grade_id`：读 `promotions_activity.discount_config` 的等级→券映射自动配对。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class CouponSendForm(
    val couponId: Long? = null,
    val userIds: List<Long>? = null,
    val gradeId: Long? = null,
)

// ===== 满减 / 满折 =====

/** MK-A1/MK-A4 建活动与改活动 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MarketingForm(
    val marketingId: Long? = null,
    val marketingType: String? = null,
    val marketingName: String? = null,
    val adPic: String? = null,
    val startTime: Long? = null,
    val endTime: Long? = null,
    /** 0 全场 / 1 指定商品（2 分类属 P2，直接拒） */
    val useBound: Int? = null,
    val useShop: Int? = null,
    val shopIds: String? = null,
    /** totalfee=按金额 / quantity=按件数 */
    val conditionType: String? = null,
    /**
     * 阶梯规则。两种写法都收（docs/04 §14.4）：
     *  - 字符串：源系统存量形态，金额单位「元」，原样入库；
     *  - 数组：本项目新表单直接传 `[{full:100.00, minus:20.00}]`，服务端序列化成同形字符串再入库。
     */
    val conditionValue: Any? = null,
    val canjoinRepeat: Boolean? = null,
    val joinLimit: Int? = null,
    val freePostage: Boolean? = null,
    val promotionTag: String? = null,
    val status: Boolean? = null,
)

/** MK-A5 圈品（全量覆盖） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MarketingItemsForm(
    val items: List<MarketingItemForm>? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MarketingItemForm(
    val itemId: Long? = null,
    val goodsId: Long? = null,
    val itemName: String? = null,
    /** 分 */
    val price: Long? = null,
    val promotionTag: String? = null,
    val isShow: Boolean? = null,
)

/** MK-A6 审核 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class MarketingCheckForm(val checkStatus: String? = null)

// ===== 秒杀 =====

/** SK-A1/SK-A4 场次 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SeckillForm(
    val seckillId: Long? = null,
    val activityName: String? = null,
    /** 源系统整数码：1=普通秒杀 2=限时抢购（出参给文案，不改写） */
    val activityType: Int? = null,
    val activityStartTime: Long? = null,
    val activityEndTime: Long? = null,
    /** 下单后多少分钟内必须付款（D24 用它代替 Redis 资格票） */
    val validityPeriod: Int? = null,
    /** 单笔限额（分），0=不限 */
    val limitMoney: Long? = null,
    val disabled: Boolean? = null,
)

/** SK-A5 停用/恢复 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SeckillStatusForm(val disabled: Boolean? = null)

/** SK-A6 商品行 upsert */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SeckillGoodsForm(
    val items: List<SeckillGoodsItemForm>? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SeckillGoodsItemForm(
    val itemId: Long? = null,
    /** 活动价（分） */
    val activityPrice: Long? = null,
    /** 活动库存：新行是全量，已有行是**增量**（D24：售出/占用中的行不许缩） */
    val activityStore: Long? = null,
    val limitNum: Int? = null,
    val isShow: Boolean? = null,
)

// ===== 拼团 =====

/** GP-A1/GP-A4 拼团活动 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GroupsForm(
    val groupsActivityId: Long? = null,
    val actName: String? = null,
    val goodsId: Long? = null,
    /** 拼团价（分） */
    val actPrice: Long? = null,
    val personNum: Int? = null,
    val store: Long? = null,
    val limitBuyNum: Int? = null,
    /** 成团时效（小时） */
    val limitTime: Int? = null,
    val beginTime: Long? = null,
    val endTime: Long? = null,
    val robot: Boolean? = null,
    val instruction: String? = null,
    val disabled: Boolean? = null,
)

/** GP-A5 停用/强制结束（`end=true` = 未成团队立即失败退款） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GroupsStatusForm(
    val disabled: Boolean? = null,
    val end: Boolean? = null,
)

/** GP-A4 加库存（只给正数） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class GroupsStoreForm(val store: Long? = null)
