package com.ecshopx.kotlin.service.cart

/**
 * 算价上下文（详细设计 §7）：购物车/预览/下单三条链路共用同一入口，
 * 「预览价==下单价」由"同一个 ctx、同一条规则链、纯函数无副作用"保证。
 *
 * 金额一律分（Long）；规则只写自己负责的字段，读其它字段前视为已就绪（按 order 升序执行）。
 */
class PriceLine(
    val cartId: Long,
    val itemId: Long,
    val itemName: String,
    val shopId: Long,
    val shopType: String,
    val activityType: String,
    val activityId: Long,
    val itemType: String,
    val itemCategory: String?,
    val type: Int,
    val goodsId: Long,
    val parentId: Long,
    val pics: String?,
    val brief: String?,
    val marketPrice: Long,
    val store: Long,
    val approveStatus: String,
    val templatesId: Long,
    val weight: Double?,
    val volume: Double?,
    val specDesc: String?,
    val snapshotPrice: Long,
    /** 商品现价，规则链的基准单价 */
    var price: Long,
    var num: Long,
    var checked: Boolean,
    var plusBuy: Boolean,
    /** 会员折扣分摊到行 */
    var memberDiscount: Long = 0,
    /** 活动/券在行上的优惠 */
    var discountFee: Long = 0,
    var activityInfo: List<Map<String, Any?>> = emptyList(),
) {
    /** 行小计（未减优惠） */
    val itemFee: Long get() = price * num
    /** 行应付 */
    val totalFee: Long get() = itemFee - discountFee
    /** 加入后是否变价 */
    val isLastPrice: Boolean get() = snapshotPrice != price
}

/** 一店一组（CART-2 的 valid_cart 元素） */
class PriceGroup(
    val shopId: Long,
    val lines: MutableList<PriceLine> = mutableListOf(),
) {
    var freight: Long = 0
    var discountFee: Long = 0
    var usedActivity: List<Map<String, Any?>> = emptyList()
    var giftActivity: List<Map<String, Any?>> = emptyList()
    var plusBuyList: List<Map<String, Any?>> = emptyList()

    val checkedLines: List<PriceLine> get() = lines.filter { it.checked }
    val itemFee: Long get() = checkedLines.sumOf { it.itemFee }
    val memberDiscount: Long get() = checkedLines.sumOf { it.memberDiscount }
    val cartTotalNum: Long get() = checkedLines.sumOf { it.num }
    val cartTotalCount: Int get() = checkedLines.size
    /** cart_total_price = 商品应付合计（不含运费） */
    val cartTotalPrice: Long get() = checkedLines.sumOf { it.totalFee }
    /** total_fee = 商品应付 + 运费 */
    val totalFee: Long get() = cartTotalPrice + freight
}

/**
 * 算价输入：会员/勾选行/可选券与积分。
 * couponCode 与 usePoint 是开关位（券与积分入账属 M1/M0-8），M0 只保证"给了也不出错"。
 */
class PriceContext(
    val companyId: Long,
    val userId: Long,
    val gradeName: String,
    /** 等级折扣百分比，100=无折扣 */
    val gradeDiscount: Int,
    val lines: List<PriceLine>,
    val couponCode: String? = null,
    val usePoint: Boolean = false,
    val availablePoint: Long = 0,
    /** 模板 id → 模板；由 CartService 预取，规则链不再查库 */
    val templates: Map<Long, FreightTemplate> = emptyMap(),
    val groups: MutableList<PriceGroup> = mutableListOf(),
) {
    val checkedLines: List<PriceLine> get() = lines.filter { it.checked }
    val totalFreight: Long get() = groups.sumOf { it.freight }
    val totalLineDiscount: Long get() = checkedLines.sumOf { it.discountFee }

    fun totalItemFee(): Long = checkedLines.sumOf { it.itemFee }
    fun totalPayFee(): Long = groups.sumOf { it.totalFee }

    companion object {
        /** 按 shop_id 分组（保持首次出现顺序，与 PHP 一致） */
        fun groupByShop(lines: List<PriceLine>): MutableList<PriceGroup> {
            val ordered = LinkedHashMap<Long, PriceGroup>()
            lines.forEach { l -> ordered.getOrPut(l.shopId) { PriceGroup(l.shopId) }.lines.add(l) }
            return ordered.values.toMutableList()
        }
    }
}

/** shipping_templates 的算价视图（M0 只取包邮与首重价，地区分级属 P1） */
class FreightTemplate(
    val templateId: Long,
    val isFree: Boolean,
    /** 满额包邮门槛，单位分；0=不启用 */
    val freeOverFen: Long,
    /** 计费方式：1 按件 2 按重 3 按体积 */
    val valuation: String,
    /** 首件/首重费用（分） */
    val firstFeeFen: Long,
)

/** 责任链节点。order 小者先执行，跨节点依赖只允许"读更小 order 写过的字段" */
interface PriceRule {
    val order: Int
    fun apply(ctx: PriceContext)
}
