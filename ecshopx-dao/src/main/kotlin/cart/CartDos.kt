package com.ecshopx.kotlin.dao.cart

import com.ecshopx.kotlin.dao.stamp.Stampable

/**
 * orders_cart 行（列定义见 sql/schema.sql §orders_cart）。
 * 快照列（item_name/pics/price）在加购时写入，列表回读商品主表校准，
 * 两者不一致时出参 is_last_price=true 提示前端"价格已变动"（与 PHP 同语义）。
 */
class OrdersCartDO : Stampable {
    var cartId: Long = 0
    var companyId: Long = 0
    var userId: Long = 0
    var userIdent: String? = null
    var shopType: String? = null            // shop/distributor/community
    var shopId: Long = 0
    var activityType: String = "normal"     // normal/package…（拼团等属 P1）
    var activityId: Long = 0
    var marketingType: String? = null
    var marketingId: Long = 0
    var itemType: String = "normal"
    var itemId: Long = 0
    var itemsId: String? = null             // 组合商品子 item 列表（JSON 串）
    var itemName: String = ""
    var pics: String? = null
    var brief: String? = null
    var num: Long = 1
    var price: Long = 0                     // 加入时单价（分）
    var point: Long = 0
    var isChecked: Boolean = true
    var isPlusBuy: Boolean = false          // 加价购行
    var sourceType: String? = null
    var promoterUserId: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

/** items 快照：算价只关心这几列，避免购物车链路依赖整个商品装配 */
class CartItemView {
    var itemId: Long = 0
    var companyId: Long = 0
    var goodsId: Long = 0
    var defaultItemId: Long = 0
    var isDefault: Boolean = true
    var itemName: String = ""
    var pics: String? = null
    var brief: String? = null
    var price: Long = 0
    var marketPrice: Long = 0
    var store: Long = 0
    var itemType: String = "normal"
    var itemCategory: String? = null
    var type: Int = 0
    var point: Long = 0
    var approveStatus: String = "instock"
    var distributorId: Long = 0
    var templatesId: Long = 0
    var weight: Double? = null
    var volume: Double? = null
    var nospec: Boolean = true
    var itemUnit: String? = null
}
