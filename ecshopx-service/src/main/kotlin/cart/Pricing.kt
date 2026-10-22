package com.ecshopx.kotlin.service.cart

import com.ecshopx.kotlin.dao.promotion.CouponUserDO
import com.ecshopx.kotlin.service.promotion.ActivityOffer
import com.ecshopx.kotlin.service.promotion.CrowdMatch
import com.ecshopx.kotlin.service.promotion.CrowdOffer
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
    /**
     * 人群折扣分摊到行（§29.3 order 25）。
     *
     * 它与 [memberDiscount] 一样**进 [discountFee]**（折让是抵扣、不是改价基准），
     * 单独记一份是为了 `discount_info[]` 能说清"这份折让是人群策略给的"，
     * 也让 `OrderCreateService` 能照它写 `crowd_discount_log`。
     */
    var crowdDiscount: Long = 0,
    /** 活动/满减在行上的优惠（M1-3 起是真金额，进 [feeDeducted]） */
    var discountFee: Long = 0,
    /** 券抵扣分摊到行（D28：逐行分摊，Σ行=组=单） */
    var couponFee: Long = 0,
    /** 积分抵扣分摊到行（同上） */
    var pointFee: Long = 0,
    /**
     * 秒杀/拼团让利（§14.5 order 15）。
     *
     * 这两个活动**改的是行单价基准**（`price` 直接换成活动价），所以让利额不进 [feeDeducted]
     * —— 进了就会减两次。它只为出参服务：`discount_info[]` 里的 `type=seckill` 一条、
     * 商详/结算页的"已省 X 元"，以及优惠快照行的金额。
     */
    var activityPriceFee: Long = 0,
    /** 命中的活动（`activity_type` + `activity_id`），下单侧占池/开团要用它定位 */
    var activityOffer: ActivityOffer? = null,
    var activityInfo: List<Map<String, Any?>> = emptyList(),
    /**
     * 库存池（§18.4 的第②条腿）：`TOTAL` 扣 `items.store`，`SHOP` 扣
     * `distribution_distributor_items.store`。下单与三条回补路径都只认这一列，
     * **扣和回必须是同一条腿** —— 漏一条就是"店铺卖光了、总部库存纹丝不动"。
     */
    var stockSource: String = StockSource.TOTAL,
    /** 挂品行是否共用总店库存（落 `orders_normal_items.is_total_store` 的判据） */
    var isTotalStore: Boolean = true,
    /**
     * 总部现价（`items.price`）。
     *
     * 有两个用处：出参的 `origin_price` 要说清"店铺价是把价改成了多少"，
     * 以及 [isLastPrice] 的比较基准 —— 店铺挂个特价不等于"商品变价了"，
     * 拿生效价去比快照，购物车里每一行都会挂着"价格已变动"的红字。
     * 0 = 未预取（属性测试里的裸行），此时回落到 [price] 比较，与 M0-5 的行为逐字一致。
     */
    var hqPrice: Long = 0,
) {
    /** 行小计（未减优惠） */
    val itemFee: Long get() = price * num

    /** 行已折让合计（三类抵扣之和，§14.5 的钳制口径） */
    val feeDeducted: Long get() = discountFee + couponFee + pointFee

    /** 行应付 */
    val totalFee: Long get() = (itemFee - feeDeducted).coerceAtLeast(0L)

    /** 加入后是否变价：比的是**总部现价**，不是店铺挂的价（见 [hqPrice]） */
    val isLastPrice: Boolean get() = snapshotPrice != if (hqPrice > 0) hqPrice else price
}

/**
 * 库存池的两个取值（docs/04 §18.4）。
 *
 * 用字符串而不是 Boolean：它要落进 `orders_normal_items.is_total_store`（源系统的列），
 * 也要在出参里说清"这一行的库存是从哪个池子看的"，两种写法各留一处就够呛。
 */
object StockSource {
    const val TOTAL = "TOTAL"
    const val SHOP = "SHOP"
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

    /**
     * 秒杀/拼团在本组的让利合计（§14.5 order 15）。
     *
     * **不进 [discountFee]**：活动价改的是行单价基准，`itemFee` 已经是活动后的金额，
     * 再记一次抵扣就会减两遍（`Σ行 total_fee + 运费 == 单 total_fee` 当场破）。
     * 这里只为出参与快照存在：`discount_info[]` 的 `type=seckill|groups` 一行、
     * `orders_rel_promotions` 的优惠额。
     */
    var activityPriceFee: Long = 0
    var activityPriceRows: List<Map<String, Any?>> = emptyList()

    /** 人群折扣在本组的让利合计与出参行（§29.3 order 25；进 [discountFee] 的口径） */
    var crowdFee: Long = 0
    var crowdRows: List<Map<String, Any?>> = emptyList()

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
    /**
     * 券在**算价当场**被拒的原因（状态/过期/门槛），由 `CouponRule` 写。
     *
     * 为什么要留在 ctx 上而不是等出参时重算：门槛比的是"会员折 + 满减之后、券与积分之前"的
     * 组内应付（§14.5），而整条链跑完时这个数已经被积分又减掉一截了。重算出来的
     * "当前 X 元"会把用户已经够得着的金额报成差得多的一大截（138.15 报成 69.08），
     * 判据没错、文案骗人。当场记下来，两边读同一个数。
     */
    var couponReject: String? = null,
    /** 积分抵扣上限比例（`point.deduct.max_ratio`，百分比；0=不允许抵扣） */
    val pointRatio: Int = 0,
    /** 秒杀/拼团活动价（§14.5 order 15）：由 buildContext 预取，规则链按 (类型, 活动, 商品) 命中 */
    val activityOffers: List<ActivityOffer> = emptyList(),
    /** 会员 grade_id（0=普通会员），人群折扣"grade 型"；buildContext 预取 */
    val gradeId: Long = 0,
    /** 会员注册时间（秒，0=未知），人群折扣 "new_user" 型的判据腿 */
    val memberCreated: Long = 0,
    /** 人群折扣（§29.3 order 25）：**命中当前用户**的启用中配置，规则链不再判人 */
    val crowdOffers: List<CrowdOffer> = emptyList(),
    /**
     * **is_plus_buy 行的换购价**（item_id → 分；批C order 16 的取数腿）。
     *
     * 行单价基准被它替换，卡口有二：表里**必须有这一行**（加价购活动圈品即行资格）
     * 且价 > 0。不出现在表里的行就是"没资格换购的那一行"，照常按商品现价算。
     */
    val plusItemPrices: Map<Long, Long> = emptyMap(),
    /** 人群折扣的实际命中（CrowdDiscountRule 写），下单事务里写日志的依据 */
    var crowdApplied: List<CrowdMatch> = emptyList(),
) {
    val checkedLines: List<PriceLine> get() = lines.filter { it.checked }
    val totalFreight: Long get() = groups.sumOf { it.freight }
    val totalLineDiscount: Long get() = checkedLines.sumOf { it.discountFee }
    val totalCouponFee: Long get() = checkedLines.sumOf { it.couponFee }
    val totalPointFee: Long get() = checkedLines.sumOf { it.pointFee }

    /** 秒杀/拼团让利合计（展示口径，不参与 [PriceLine.feeDeducted]） */
    val totalActivityPriceFee: Long get() = checkedLines.sumOf { it.activityPriceFee }

    /** 人群折扣让利合计（§29.3 order 25，进 discountFee 的展示口径） */
    val totalCrowdFee: Long get() = groups.sumOf { it.crowdFee }

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
