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
    /** M0 只支持 normal；service/groups/seckill 属 P1/P2 */
    val orderType: String? = null,
    val items: List<CartItemNum>? = null,
    val itemId: Long? = null,
    val itemNum: Long? = null,
    val shopId: Long? = null,
    val distributorId: Long? = null,
    /** logistics（默认）/ ziti */
    val receiptType: String? = null,
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
    val salesmanId: Long? = null,
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
