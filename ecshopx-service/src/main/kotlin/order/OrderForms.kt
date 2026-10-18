package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.service.cart.CartItemNum
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * ORDER-1/2/3 公共入参（docs/04 §2.4）在下单侧的完整形态。
 *
 * 金额、单价一律不接收——下单价只来自服务端重算（docs/03 §7.2 步骤 2）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrderCreateForm(
    /** cart（读车内勾选项）/ fastbuy / offline */
    val cartType: String? = null,
    /** M0 只支持 normal；service/groups/seckill 一律 501（守卫不动） */
    val orderType: String? = null,
    /**
     * M1-3 活动单类目（docs/04 §14.3）：`normal`/`seckill`/`groups`。
     *
     * 与 `order_type` 是两件事：`order_type` 是"订单业务形态"（normal/service…），
     * `order_class` 是"这单属于哪个活动域"，两者在源系统就是两列，本项目不合并。
     * 缺省按 `normal`；非法值不报错（[com.ecshopx.kotlin.common.domain.OrderClass.normalize] 归一），
     * 与 ORDER-1 同一口径 —— 前台传错类目不该把结算打断，按普通单算就行。
     */
    val orderClass: String? = null,
    /** 活动 id：`order_class=seckill` 时是 `seckill_id`，`groups` 时是 `groups_activity_id` */
    val actId: Long? = null,
    /** 参团时带上团号；不传=自己开新团（仅 `order_class=groups` 有意义） */
    val teamId: String? = null,
    val items: List<CartItemNum>? = null,
    val itemId: Long? = null,
    val itemNum: Long? = null,
    val shopId: Long? = null,
    val distributorId: Long? = null,
    /** logistics（默认）/ ziti */
    val receiptType: String? = null,
    /** 自提点 id（`receipt_type=ziti` 时用；不传=取该店第一个启用的点） */
    val pickupLocationId: Long? = null,
    /** 传地址簿 id 时服务端展开收货六件套 */
    val addressId: Long? = null,
    val receiverName: String? = null,
    val receiverMobile: String? = null,
    val receiverState: String? = null,
    val receiverCity: String? = null,
    val receiverDistrict: String? = null,
    val receiverAddress: String? = null,
    /** 券码（PHP 字段名就叫 coupon_discount），与 ORDER-1 的 coupon_code 双名兼容 */
    @JsonAlias("coupon_code") val couponDiscount: String? = null,
    val notUseCoupon: Boolean? = null,
    val pointUse: Boolean? = null,
    val pack: Boolean? = null,
    val remark: String? = null,
    val invoiceContent: String? = null,
    val invoiceType: String? = null,
    /**
     * 归属导购。前台（ORDER-2）给了就用给的；没给时服务层回落到"这个会员当前绑的导购"（C177）。
     * 员工端（SO-E1）**入参给了也忽略**，一律由会话强制注入（§18.3）。
     */
    val salesmanId: Long? = null,
    /**
     * SO-E1 代客下单时被代的那个会员（散客可空）。
     *
     * 只在员工面上有意义：前台（ORDER-2/3）的 `user_id` 永远取自令牌，
     * 给 form 开这个口子是为了让"顾客的小程序"与"导购的工单"共用同一个 create 入口，
     * 而不是再写第二条下单链。
     */
    val userId: Long? = null,
    val promoterUserId: Long? = null,
    val sourceId: String? = null,
    val monitorId: String? = null,
    /** ORDER-3 必填；M0 取值 point/deposit/offline_pay/wxpay(mock) */
    val payType: String? = null,
    val returnUrl: String? = null,
    /** 下单来源：pc/h5/wxapp/aliapp，落 order_from */
    val orderFrom: String? = null,
    /** 幂等 token（docs/03 §7.2 步骤 1）：同一 token 重复提交返回同一单 */
    val clientToken: String? = null,
) {
    fun entries(): List<CartItemNum> =
        items?.filter { it.itemId != null && it.itemId > 0 }
            ?: listOfNotNull(itemId?.let { CartItemNum(it, itemNum ?: 1) })

    /** cart 模式以外都按传入行下单；与 ORDER-1 的分流口径必须一致 */
    val directBuy: Boolean get() = cartType == "fastbuy" || cartType == "offline"

    /** 券开关位：not_use_coupon=1 时不下发券码（M0 券本身是开关位） */
    fun couponCode(): String? = if (notUseCoupon == true) null else couponDiscount?.takeIf { it.isNotBlank() }
}

/** ORDER-6 用户取消 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrderCancelForm(
    val orderId: Long? = null,
    val cancelReason: String? = null,
)

/** ORDER-7 确认收货 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrderConfirmForm(
    val orderId: Long? = null,
)

/** 后台改备注 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrderRemarkForm(
    val orderId: Long? = null,
    val remark: String? = null,
)

/** ORDER-9 发货明细行 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DeliveryItemForm(
    val orderItemsId: Long? = null,
    val num: Long? = null,
)

/** ORDER-9 发货：items 缺省=整单未发行全发（package_type=batch），给出则按行分包（sep） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OrderDeliveryForm(
    val orderId: Long? = null,
    val deliveryCorp: String? = null,
    val deliveryCorpName: String? = null,
    val deliveryCode: String? = null,
    val items: List<DeliveryItemForm>? = null,
    val deliveryPics: List<String>? = null,
)
