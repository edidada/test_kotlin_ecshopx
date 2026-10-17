package com.ecshopx.kotlin.service.cart

import com.ecshopx.kotlin.dao.promotion.CouponUserDO
import com.ecshopx.kotlin.service.promotion.MarketingOffer

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
    /** 活动/满减/秒杀在行上的优惠（M1-3 起是真金额） */
    var discountFee: Long = 0,
    /** 券抵扣分摊到行（D28：逐行分摊，Σ行=组=单） */
    var couponFee: Long = 0,
    /** 积分抵扣分摊到行（同上） */
    var pointFee: Long = 0,
    var activityInfo: List<Map<String, Any?>> = emptyList(),
) {
    /** 行小计（未减优惠） */
    val itemFee: Long get() = price * num

    /** 行已折让合计（三类抵扣之和，§14.5 的钳制口径） */
    val feeDeducted: Long get() = discountFee + couponFee + pointFee

    /** 行应付 */
    val totalFee: Long get() = (itemFee - feeDeducted).coerceAtLeast(0L)

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

    /** 满减命中「包邮」档：由 FullReductionRule 写、FreightRule 读（§14.5 的 30→60 依赖） */
    var freePostage: Boolean = false

    /** 本组用掉的券（券只在单店铺组生效，§14.5） */
    var couponInfo: List<Map<String, Any?>> = emptyList()

    val checkedLines: List<PriceLine> get() = lines.filter { it.checked }
    val itemFee: Long get() = checkedLines.sumOf { it.itemFee }
    val memberDiscount: Long get() = checkedLines.sumOf { it.memberDiscount }
    val couponDiscount: Long get() = checkedLines.sumOf { it.couponFee }
    val pointFee: Long get() = checkedLines.sumOf { it.pointFee }
    val cartTotalNum: Long get() = checkedLines.sumOf { it.num }
    val cartTotalCount: Int get() = checkedLines.size
    /** cart_total_price = 商品应付合计（不含运费） */
    val cartTotalPrice: Long get() = checkedLines.sumOf { it.totalFee }
    /** total_fee = 商品应付 + 运费 */
    val totalFee: Long get() = cartTotalPrice + freight
}

/**
 * 算价输入：会员/勾选行/可选券与积分 + **全部预取数据**。
 *
 * M1-3 的硬口径（docs/04 §14.5）：满减活动、券实例、积分余额、`now` 一律由
 * `CartService.buildContext` 取好塞进这里，规则链本身不查库、不取时间、无随机 ——
 * M0-5 的属性测试（同 ctx 跑两次逐分相等）因此继续成立。
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
    /** 取数时刻（秒）：一次算价只取一次，活动/券的在窗判定都读它 */
    val now: Long = 0,
    /** 已审核、在窗、类型在白名单里的满减满折活动（含圈定商品集合） */
    val offers: List<MarketingOffer> = emptyList(),
    /** 选中的券实例（已确认归属本人且属本租户）；null = 没用券 */
    val coupon: CouponUserDO? = null,
    /** 给了券码但用不了的人话原因（券不存在/不属于本人/被别的单占用…） */
    val couponReason: String? = null,
    /** 积分抵扣上限比例（`point.deduct.max_ratio`，百分比；0=不允许抵扣） */
    val pointRatio: Int = 0,
) {
    val checkedLines: List<PriceLine> get() = lines.filter { it.checked }
    val totalFreight: Long get() = groups.sumOf { it.freight }
    val totalLineDiscount: Long get() = checkedLines.sumOf { it.discountFee }
    val totalCouponFee: Long get() = checkedLines.sumOf { it.couponFee }
    val totalPointFee: Long get() = checkedLines.sumOf { it.pointFee }

    /** 结算页与订单详情都要的三张抵扣账 */
    val couponDiscount: Long get() = groups.sumOf { it.couponDiscount }
    val memberDiscount: Long get() = groups.sumOf { it.memberDiscount }

    /** 参与算价的店铺分组（未勾选行的组不计） */
    val activeGroups: List<PriceGroup> get() = groups.filter { it.cartTotalCount > 0 }

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

/**
 * 逐行分摊（D28）：按各行**当前应付**占比 floor 分配，余数补给应付最大的那一行。
 *
 * 为什么必须分摊到行：售后（M1-1）按件分摊退款金额，
 * 抵扣只记在单级会让"退一件把整单的券抵扣重复退一次"。
 * 分摊后 `Σ行抵扣 == 本次抵扣总额` 恒成立（钳制除外：单行不超过其应付）。
 */
fun spreadToLines(
    total: Long,
    lines: List<PriceLine>,
    setter: (PriceLine, Long) -> Unit,
): Long {
    if (total <= 0 || lines.isEmpty()) return 0L
    val base = lines.sumOf { it.totalFee }
    if (base <= 0) return 0L
    val cap = total.coerceAtMost(base)
    val each = MutableList(lines.size) { i -> (cap * lines[i].totalFee / base).coerceAtMost(lines[i].totalFee) }
    // floor 之后最多缺 lines.size-1 分：按应付从大到小补，每行都不越过自己的应付
    var rest = cap - each.sum()
    val order = lines.indices.sortedByDescending { i -> lines[i].totalFee }
    var p = 0
    while (rest > 0 && p < order.size) {
        val i = order[p++]
        val room = lines[i].totalFee - each[i]
        if (room > 0) {
            val give = minOf(rest, room)
            each[i] += give
            rest -= give
        }
    }
    lines.forEachIndexed { i, l -> if (each[i] > 0) setter(l, each[i]) }
    return each.sum()
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
