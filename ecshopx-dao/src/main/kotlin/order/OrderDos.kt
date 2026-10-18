package com.ecshopx.kotlin.dao.order

import com.ecshopx.kotlin.dao.stamp.Stampable

/**
 * orders_normal_orders（列定义见 sql/schema.sql）。
 * 金额一律分；状态沿用源系统字符串枚举（docs/02 §3），迁移由 OrderStatusMachine 白名单把关。
 */
class NormalOrderDO : Stampable {
    var orderId: Long = 0
    var title: String? = null
    var companyId: Long = 0
    var shopId: Long = 0
    var userId: Long = 0
    var actId: Long = 0
    var mobile: String? = null
    var orderClass: String = "normal"
    var orderType: String = "normal"
    var orderStatus: String = "NOTPAY"
    var payStatus: String = "NOTPAY"
    var deliveryStatus: String = "PENDING"
    var cancelStatus: String = "NOCANCEL"
    var orderSource: String? = null
    var orderHolder: String? = null
    var orderFrom: String? = null
    var itemFee: Long = 0
    var totalFee: Long = 0
    /** 实付（分）：M0-7 结清时写入，未付款恒 0；渠道侧金额以 trade.pay_fee 为准 */
    var payFee: Long = 0
    var marketFee: Long = 0
    var costFee: Long = 0
    var commissionFee: Long = 0
    var freightFee: Long = 0
    var discountFee: Long = 0
    var memberDiscount: Long = 0
    var couponDiscount: Long = 0
    var pointFee: Long = 0
    var pointUse: Long = 0
    var itemPoint: Long = 0
    var getPoints: Long = 0
    var totalRebate: Long = 0
    var distributorId: Long = 0
    var salesmanId: Long = 0
    var supplierId: Long = 0
    var merchantId: Long = 0
    /** 拆单前主单（M0 一单一店不用，列先就位） */
    var originalOrderId: Long = 0
    var type: Int = 0
    var taxableFee: Long = 0
    var totalTax: Long = 0
    var feeType: String? = null
    var feeRate: Double? = null
    var feeSymbol: String? = null
    var payType: String? = null
    var payChannel: String? = null
    var receiptType: String? = "logistics"
    var zitiCode: String? = null
    var zitiStatus: String? = null
    var invoice: String? = null
    var isInvoiced: Boolean = false
    var invoiceStatus: String? = null
    var autoCancelTime: Long? = null
    var autoFinishTime: Long? = null
    var endTime: Long? = null
    var deliveryTime: Long? = null
    var deliveryCorp: String? = null
    var deliveryCode: String? = null
    var receiverName: String? = null
    var receiverMobile: String? = null
    var receiverState: String? = null
    var receiverCity: String? = null
    var receiverDistrict: String? = null
    var receiverAddress: String? = null
    var isRate: Boolean = false
    var leftAftersalesNum: Int = 0
    var sourceFrom: String? = null
    var isOnlineOrder: Boolean = true
    var remark: String? = null

    /** 拼团团号（M1-3 D29）：普通单恒 null，`order_class='groups'` 时落 GT 号 */
    var teamId: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/** orders_normal_orders_items：下单时的商品与价格快照 */
class OrderItemDO : Stampable {
    var id: Long = 0
    var orderId: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var goodsId: Long = 0
    var itemId: Long = 0
    var itemBn: String? = null
    var goodsBn: String? = null
    var shopId: Long = 0
    var distributorId: Long = 0
    var actId: Long = 0
    var itemName: String = ""
    var pic: String? = null
    var num: Long = 1
    var price: Long = 0
    var totalFee: Long = 0
    var itemFee: Long = 0
    var costFee: Long = 0
    var commissionFee: Long = 0
    var marketPrice: Long = 0
    var costPrice: Long = 0
    var rebate: Long = 0
    var totalRebate: Long = 0
    var memberDiscount: Long = 0
    var couponDiscount: Long = 0
    var discountFee: Long = 0
    var discountInfo: String? = null
    /** normal/gift/plus_buy */
    var orderItemType: String = "normal"
    var deliveryStatus: String = "PENDING"
    var deliveryTime: Long? = null
    var aftersalesStatus: String? = null
    var refundedFee: Long = 0
    var templatesId: Long = 0
    var isTotalStore: Boolean = true
    var itemPoint: Long = 0
    var point: Long = 0
    var pointFee: Long = 0
    var sharePoints: Long = 0
    var itemSpecDesc: String? = null
    var taxRate: String? = null
    var crossBorderTax: Long = 0
    var weight: Double? = null
    var volume: Double? = null
    var isInvoice: Int = 0
    var cancelItemNum: Int = 0
    var deliveryItemNum: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

/** orders_associations：全渠道单号路由表（状态与主表同步，列表按它查） */
class OrderAssocDO : Stampable {
    var orderId: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var orderClass: String = "normal"
    var orderType: String = "normal"
    var orderStatus: String = "NOTPAY"
    var cancelStatus: String = "NOCANCEL"
    var deliveryStatus: String = "PENDING"
    var totalFee: Long = 0
    var memberDiscount: Long = 0
    var couponDiscount: Long = 0
    var totalRebate: Long = 0
    var isDistribution: Boolean = false
    var promoterUserId: Long = 0
    var sourceId: String? = null
    var monitorId: String? = null
    var salesmanId: Long = 0
    var wxaAppid: String? = null
    var authorizerAppid: String? = null
    var endTime: Long? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/** orders_operate_logs：全流转留痕 */
class OrderOperateLogDO : Stampable {
    var id: Long = 0
    var orderId: Long = 0
    var operatorId: Long? = null
    var operator: String? = null
    /** buyer/seller/system */
    var operatorRole: String = "system"
    /** create/payed/delivery/cancel/timeout_cancel/confirm_receipt/remark */
    var behavior: String = ""
    var logText: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/** orders_cancel_orders：取消申请单（progress 0待处理 1已取消 2处理中 3拒绝） */
class OrderCancelDO : Stampable {
    var cancelId: Long = 0
    var orderId: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var shopId: Long = 0
    var distributorId: Long = 0
    var supplierId: Long = 0
    var totalFee: Long = 0
    var progress: Int = 0
    var cancelFrom: String? = null
    var cancelReason: String? = null
    var shopRejectReason: String? = null
    var refundStatus: String? = null
    var point: Long = 0
    var payType: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/** orders_delivery：发货单（表只有 created 列，不参与 Stampable） */
class OrderDeliveryDO {
    var ordersDeliveryId: Long = 0
    var orderId: Long = 0
    var companyId: Long = 0
    var supplierId: Long = 0
    var deliveryCorp: String? = null
    var deliveryCorpName: String? = null
    var deliveryCode: String? = null
    /** batch 整单 / sep 分包 */
    var packageType: String = "batch"
    var deliveryTime: Long? = null
    var deliveryPics: String? = null
    /** orders_delivery 只有 created 一列（没有 updated），所以本类不实现 Stampable，由调用方填 */
    var created: Long = 0
}

class OrderDeliveryItemDO {
    var ordersDeliveryItemsId: Long = 0
    var ordersDeliveryId: Long = 0
    var orderId: Long = 0
    var orderItemsId: Long = 0
    var itemId: Long = 0
    var num: Long = 1
}

/** ORDER-8 角标聚合行 */
class OrderCountRow {
    var notpay: Long = 0
    var pendingDelivery: Long = 0
    var pendingReceive: Long = 0
    var done: Long = 0
    /** 待评价：DONE 且 is_rate=false */
    var evaluation: Long = 0
    var cancel: Long = 0
    var total: Long = 0
}

/**
 * MEM-1 消费统计行：累计实付（分）+ 已付订单数。
 * 口径与后台一致——只认 `pay_status = 'PAYED'`，未付/已取消的单不计入成长值。
 */
class ConsumptionRow {
    var totalConsumption: Long = 0
    var paidOrders: Long = 0
}

/**
 * 状态迁移补丁：字段**全部可空**，null=不改。
 *
 * 不能拿 NormalOrderDO 当补丁用——它的状态列是非空 String 且带默认值（NOTPAY/PENDING…），
 * `<if test='patch.orderStatus != null'>` 会恒真，于是「只改支付状态」会顺手把 order_status 重置回 NOTPAY。
 */
class TransitPatch {
    var orderStatus: String? = null
    var payStatus: String? = null
    var deliveryStatus: String? = null
    var cancelStatus: String? = null
    var payType: String? = null
    var payChannel: String? = null
    /** 实付金额（分）：M0-7 支付结清随状态一并落库 */
    var payFee: Long? = null
    var endTime: Long? = null
    var deliveryTime: Long? = null
    var deliveryCorp: String? = null
    var deliveryCode: String? = null
    var autoCancelTime: Long? = null
    var autoFinishTime: Long? = null
    /** 置空定时器列（取消/支付成功后不该再被超时任务扫到） */
    var clearAutoCancelTime: Boolean = false
    var zitiCode: String? = null
    var zitiStatus: String? = null
    var title: String? = null
    var remark: String? = null
    var mobile: String? = null
    var receiverName: String? = null
    var receiverMobile: String? = null
    var receiverState: String? = null
    var receiverCity: String? = null
    var receiverDistrict: String? = null
    var receiverAddress: String? = null
}
