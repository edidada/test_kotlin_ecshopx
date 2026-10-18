package com.ecshopx.kotlin.service.cart

import com.ecshopx.kotlin.common.domain.OrderClass
import com.ecshopx.kotlin.dao.promotion.MarketingBound
import com.ecshopx.kotlin.service.promotion.ActivityOffer
import com.ecshopx.kotlin.service.promotion.CouponCheck
import com.ecshopx.kotlin.service.promotion.MarketingOffer
import com.ecshopx.kotlin.service.promotion.MarketingStep
import com.ecshopx.kotlin.service.promotion.yuan
import org.springframework.stereotype.Component

/**
 * 算价责任链（详细设计 §7 + docs/04 §14.5）。M1-3 挂八档：
 *
 * ```
 * 10 Grouping        店铺分组
 * 15 ActivityPrice   秒杀/拼团活动价 —— 改 price 基准（D24/D25，让利不进 discountFee）
 * 20 MemberDiscount  会员等级折让
 * 30 FullReduction   满减 / 满折（+ 命中包邮标记）
 * 40 Coupon          优惠券抵扣（单店铺组）
 * 50 PointDeduct     积分抵扣（受 point.deduct.max_ratio 上限）
 * 60 Freight         运费（读 30 写的包邮标记）
 * 90 TotalSync       组级抵扣合计回写
 * ```
 *
 * 每个节点都是 ctx 上的纯函数（不查库、不取时间、不依赖随机）：
 * 「预览价==下单价」= 同一 ctx 跑两次结果逐分相等（M0-5 的属性测试守着这条）。
 * 跨节点依赖只允许"读更小 order 写过的字段"，所以 `now`、活动、券、积分余额
 * 全部由 `CartService.buildContext` 预取进 ctx。
 *
 * 金额口径（§14.5）：`行小计 = 活动价 × 件数`、`行应付 = 行小计 − 活动折让(会员/满减) − 券抵扣 − 积分抵扣`，
 * 三类抵扣都**逐行分摊**（D28），于是 `Σ行 = Σ组 = 单` 恒成立，售后按件退才不会重复退抵扣。
 */
@Component
class MarketingEngine(rules: List<PriceRule>) {

    private val chain = rules.sortedBy { it.order }

    fun price(ctx: PriceContext): PriceContext {
        if (ctx.groups.isEmpty()) ctx.groups.addAll(PriceContext.groupByShop(ctx.lines))
        chain.forEach { it.apply(ctx) }
        // 兜底：任何规则（含 #57 的活动价）都不许把一行抵成负数
        ctx.lines.forEach { l -> clampLine(l) }
        ctx.groups.forEach { g -> g.discountFee = g.checkedLines.sumOf { it.discountFee } }
        return ctx
    }

    /**
     * 越界回收顺序：积分 → 券 → 活动。
     * 先退积分/券是因为这两项是"用户资产"，抵多了只会少用；活动折让是商家让利，砍它会直接改到展示给用户的活动力度。
     */
    private fun clampLine(l: PriceLine) {
        var over = l.feeDeducted - l.itemFee
        if (over <= 0) return
        val takePoint = minOf(over, l.pointFee); l.pointFee -= takePoint; over -= takePoint
        val takeCoupon = minOf(over, l.couponFee); l.couponFee -= takeCoupon; over -= takeCoupon
        l.discountFee = (l.discountFee - over).coerceAtLeast(0L)
    }
}

/** 店铺分组：无副作用，仅保证 groups 就绪（引擎在链路前已建，这里兜自定义链） */
@Component
class GroupingRule : PriceRule {
    override val order = 10
    override fun apply(ctx: PriceContext) {
        if (ctx.groups.isEmpty()) ctx.groups.addAll(PriceContext.groupByShop(ctx.lines))
    }
}

/**
 * 秒杀 / 拼团活动价（order 15，docs/04 §14.5 + D24/D25）。
 *
 * 它是链路上**唯一改单价基准**的节点，所以排在会员折让之前：会员折、满减、券、积分
 * 全部按活动价之后的应付计算（"秒杀价再打会员折"是源系统的实际行为，也是用户的预期）。
 *
 * 四条口径：
 *  1. **只命中申报了活动的行**：`activity_type='normal'` 或 `activity_id<=0` 的行一律不动。
 *     秒杀/拼团入口在活动页，加购与下单必须带 `activity_type/activity_id`（CART-1 早已接收），
 *     静默套用等于把秒杀当满减用，活动池会被普通单吃光；
 *  2. **让利记在 [PriceLine.activityPriceFee]，不进 `discountFee`**：`price` 已被换成活动价，
 *     `itemFee` 就是活动后的金额，再抵一次会减两遍并破坏 `Σ行+运费==单` 的守恒断言；
 *  3. **活动价不低于现价时不改价**（[ActivityOffer.covers]），但仍把 offer 挂到行上 ——
 *     占池、限购、成团这些动作看的是"这行属于哪场活动"，不是"便宜了多少"；
 *  4. **申报了活动却查不到 offer = 活动已结束/商品不在场次里**，这里只留 `activityOffer=null`，
 *     由下单侧（`OrderCreateService`）拒单。预览阶段照常出个原价，
 *     用户在结算页看到的就是"这单不再享受活动价"。
 *
 * 池子余量（[ActivityOffer.available]）在这里**只做展示不做卡口**：
 * 与 M0-6 商品库存同构 —— 预览永远给价，真抢在下单时用 CAS 定生死（0 行 = 422）。
 * 预览若跟着余量变价，同一 ctx 跑两次就可能不等，M0-5 的属性测试会先失败。
 */
@Component
class ActivityPriceRule : PriceRule {
    override val order = 15

    override fun apply(ctx: PriceContext) {
        if (ctx.activityOffers.isEmpty()) return
        val byKey = ctx.activityOffers.associateBy { Triple(it.activityType, it.activityId, it.itemId) }
        ctx.groups.forEach { g ->
            // 未勾选的行不换价：CART-2 的未勾选行按现价展示，勾选后重新算价自然出活动价
            val hit = g.checkedLines.mapNotNull { l ->
                byKey[Triple(l.activityType, l.activityId, l.itemId)]?.let { l to it }
            }
            if (hit.isEmpty()) return@forEach
            hit.forEach { (l, o) ->
                l.activityOffer = o
                if (o.covers()) {
                    l.activityPriceFee = (o.originPrice - o.activityPrice) * l.num
                    l.price = o.activityPrice
                    l.activityInfo = l.activityInfo + lineRow(o, l.activityPriceFee)
                }
            }
            g.activityPriceFee = g.checkedLines.sumOf { it.activityPriceFee }
            // 一店一组通常一活动；同活动多行合成一行，跨活动分行列（type 不同必须分开说）
            g.activityPriceRows = hit.map { it.second }
                .distinctBy { it.activityType to it.activityId }
                .mapNotNull { o ->
                    val fee = g.checkedLines.filter { it.activityOffer === o }.sumOf { it.activityPriceFee }
                    if (fee <= 0) null else groupRow(o, fee)
                }
        }
    }

    private fun lineRow(o: ActivityOffer, fee: Long): Map<String, Any?> = linkedMapOf(
        "id" to o.activityId,
        "type" to o.activityType,
        "name" to o.activityName,
        "rule" to ruleText(o),
        "discount_fee" to fee,
        "activity_price" to o.activityPrice,
        "origin_price" to o.originPrice,
    )

    private fun groupRow(o: ActivityOffer, fee: Long): Map<String, Any?> = linkedMapOf(
        "id" to o.activityId,
        "activity_id" to o.activityId,
        "type" to o.activityType,
        "name" to o.activityName,
        "rule" to ruleText(o),
        "discount_fee" to fee,
    )

    /** 活动力度文案：秒杀说"限时抢购"，拼团说清几人团（前台 `discount_info[].rule` 要看懂省在哪） */
    private fun ruleText(o: ActivityOffer): String {
        val price = "现价 ${yuan(o.originPrice)} 元，活动价 ${yuan(o.activityPrice)} 元"
        return if (o.activityType == OrderClass.GROUPS && o.personNum > 0) "${o.personNum}人团，$price" else "限时秒杀，$price"
    }
}

/**
 * 会员等级折扣：members_grade.discount 是百分比（100=无折扣），
 * 折让 = 行小计 ×(100-discount)/100，向下取整到分（与 PHP 逐行折让一致）。
 */
@Component
class MemberDiscountRule : PriceRule {
    override val order = 20
    override fun apply(ctx: PriceContext) {
        if (ctx.gradeDiscount >= 100) return
        val rate = (100 - ctx.gradeDiscount).coerceIn(0, 100)
        ctx.checkedLines.forEach { l ->
            val d = l.itemFee * rate / 100
            l.memberDiscount = d
            l.discountFee += d
        }
    }
}

/**
 * 满减 / 满折（order 30，docs/04 §14.4/§14.5）。
 *
 * 四条口径都写在代码注释里，因为它们都会影响"预览价==下单价"：
 *  1. **一店一活动**：同一店铺组只取一个满减 —— 抵扣最大的那个，同额取 `marketing_id` 小的先。
 *     源系统同样是一单一活动（`getMarketing` 取首条命中），叠满会让"退一张券"变成多轮协商；
 *  2. **基数是会员折让之后的组内应付**（读 order 20 写过的 `totalFee`），运费不参与；
 *  3. **加价购行不计门槛、不被满减**（PHP 同口径：加价购商品是换购件，不该再吃满减）；
 *  4. `use_bound=1` 只圈这些商品：命中行集合就是"本组 ∩ 圈品"，全场活动看整组；
 *     `use_bound=2`（按分类）属 P2，本档直接跳过（`itemIds` 为空时不匹配）。
 *
 * `canjoin_repeat=true`（上不封顶）乘 `floor(基数/档门槛)`，再钳到基数以内；
 * `free_postage=true` 只写 `g.freePostage`，真正的免运费由 order 60 读它执行（30→60 依赖）。
 */
@Component
class FullReductionRule : PriceRule {
    override val order = 30

    /** 一次命中：档位 + 折让 + 可叠倍数 + 命中行 */
    private class Match(val step: MarketingStep, val fee: Long, val times: Long, val lines: List<PriceLine>)

    /** 活动 × 命中（一店一活动，择优用） */
    private class Hit(val offer: MarketingOffer, val m: Match)

    override fun apply(ctx: PriceContext) {
        val offers = ctx.offers.filter { it.pricable }
        ctx.groups.forEach { g ->
            // 加价购行不进满减基数，也不接受满减折让
            val targets = g.checkedLines.filter { !it.plusBuy }
            val best = if (targets.isEmpty()) null else
                offers.mapNotNull { o -> matchOf(o, targets)?.let { Hit(o, it) } }
                    .sortedWith(compareByDescending<Hit> { it.m.fee }.thenBy { it.offer.marketingId })
                    .firstOrNull()

            g.plusBuyList = g.lines.filter { it.plusBuy && it.checked }.map {
                linkedMapOf<String, Any?>("item_id" to it.itemId, "cart_id" to it.cartId, "price" to it.price, "num" to it.num)
            }
            g.giftActivity = emptyList()      // 满赠属 P2，字段先保住
            if (best == null) { g.usedActivity = emptyList(); return@forEach }

            val offer = best.offer
            val m = best.m
            val applied = spreadToLines(m.fee, m.lines) { l, share ->
                l.discountFee += share
                l.activityInfo = l.activityInfo + activityRow(offer, m, share)
            }
            if (applied <= 0) { g.usedActivity = emptyList(); return@forEach }
            g.usedActivity = listOf(usedRow(offer, m, applied))
            if (offer.freePostage) g.freePostage = true
        }
    }

    /** 单个活动在本组行上的命中：不命中返回 null（含脏数据、未达门槛、圈品无交集） */
    private fun matchOf(o: MarketingOffer, targets: List<PriceLine>): Match? {
        val lines = if (o.useBound == MarketingBound.ALL) targets else {
            if (o.useBound != MarketingBound.ITEMS || o.itemIds.isEmpty()) return null
            targets.filter { it.itemId in o.itemIds }
        }
        if (lines.isEmpty()) return null
        val money = lines.sumOf { it.totalFee }
        if (money <= 0) return null
        // 按件满减的门槛比件数，按金额满减的门槛比应付；折让金额都按应付算（件数当基数会算出"打8.5折×件数"）
        val base = if (o.conditionType == MarketingOffer.CONDITION_QUANTITY) lines.sumOf { it.num } else money
        val step = o.hit(base) ?: return null
        val fee = o.feeOf(step, money).coerceAtMost(money)
        if (fee <= 0) return null
        val times = o.timesOf(step, base)
        // 上不封顶再乘倍数，但折让永远不超过本组应付（满折×倍数最容易越界，这里钳死）
        return Match(step, (fee * times).coerceAtMost(money), times, lines)
    }

    private fun usedRow(o: MarketingOffer, m: Match, applied: Long): Map<String, Any?> = linkedMapOf(
        "id" to o.marketingId,
        "marketing_id" to o.marketingId,
        "type" to o.marketingType,
        "name" to o.marketingName,
        "rule" to o.conditionText(m.step),
        "condition_type" to o.conditionType,
        "discount_fee" to applied,
        "free_postage" to o.freePostage,
        "times" to m.times,
    )

    /** 行上的活动痕迹：结算页要能说清"这一行的便宜是谁给的" */
    private fun activityRow(o: MarketingOffer, m: Match, share: Long): Map<String, Any?> = linkedMapOf(
        "id" to o.marketingId,
        "marketing_id" to o.marketingId,
        "type" to o.marketingType,
        "name" to o.marketingName,
        "rule" to o.conditionText(m.step),
        "discount_fee" to share,
    )
}

/**
 * 优惠券抵扣（order 40）。
 *
 * 券的可用性判据只有 [CouponCheck] 一份 —— 结算页的可用券列表与这里必须同判据，
 * 否则会出现"列表说能用、下单说不能用"。
 * 多店铺组不参与：券按组抵扣才有确定的门槛答案（§14.5），
 * 而 M0-6 下单本来就 `group_count>1` 直接拒单，所以这里静默不抵即可。
 */
@Component
class CouponRule : PriceRule {
    override val order = 40
    override fun apply(ctx: PriceContext) {
        val c = ctx.coupon ?: return
        val g = ctx.activeGroups.singleOrNull() ?: return
        val payFee = g.cartTotalPrice
        if (CouponCheck.usable(c, payFee, ctx.now) != null) return
        val deduct = CouponCheck.deductOf(c, payFee)
        val applied = spreadToLines(deduct, g.checkedLines) { l, share -> l.couponFee += share }
        if (applied <= 0) return
        g.couponInfo = listOf(CouponCheck.viewOf(c, applied))
    }
}

/**
 * 积分抵扣（order 50，D29）。
 *
 * `可抵金额 = min(可用积分, floor(全单商品应付 × max_ratio%))`，
 * 比例来自 `sys_config.point.deduct.max_ratio`（1 积分 = 1 分，见 `PayChannel.POINT_PER_FEN`）。
 * 组间按应付从大到小摊，摊完的余额留在用户账上（宁可少用一分，也不许出现"抵扣之和 > 应付"）。
 *
 * 与 `pay_type=point`（纯积分支付）互斥 —— 那是支付口径，由下单/支付层拒，不在此处判。
 */
@Component
class PointDeductRule : PriceRule {
    override val order = 50
    override fun apply(ctx: PriceContext) {
        if (!ctx.usePoint || ctx.pointRatio <= 0 || ctx.availablePoint <= 0) return
        val groups = ctx.activeGroups.sortedByDescending { it.cartTotalPrice }
        val payable = groups.sumOf { it.cartTotalPrice }
        if (payable <= 0) return
        val total = minOf(ctx.availablePoint, payable * ctx.pointRatio / 100, payable)
        if (total <= 0) return
        var left = total
        groups.forEach { g ->
            if (left <= 0 || g.cartTotalPrice <= 0) return@forEach
            val share = minOf(left, total * g.cartTotalPrice / payable)
            if (share <= 0) return@forEach
            left -= share
            spreadToLines(share, g.checkedLines) { l, s -> l.pointFee += s }
        }
    }
}

/**
 * 运费（shipping_templates）。M1-3 口径：
 * 1) **满减命中带包邮**（order 30 写的 `g.freePostage`）→ 0；
 * 2) 模板 is_free='y' → 0；
 * 3) 组内应付 ≥ 满额包邮门槛 freeOverFen(分) → 0；
 * 4) 否则按 valuation 取计价量（1 件数 / 2 重量 / 3 体积，向上取整且至少 1），
 *    单档收取 firstFeeFen（地区分级与续重属 P1）。
 * 组内多模板取首个 templates_id>0；全无模板按包邮处理（演示环境无运费）。
 *
 * 注意门槛比的是**抵扣之后的组内应付**（含券与积分）：券把金额抵到包邮线下，就不该再收运费。
 */
@Component
class FreightRule : PriceRule {
    override val order = 60
    override fun apply(ctx: PriceContext) {
        ctx.groups.forEach { g ->
            if (g.checkedLines.isEmpty()) { g.freight = 0; return@forEach }
            val tid = g.checkedLines.map { it.templatesId }.firstOrNull { it > 0L } ?: 0L
            val t = ctx.templates[tid]
            g.freight = when {
                g.freePostage -> 0L
                t == null || t.isFree -> 0L
                t.freeOverFen > 0L && g.cartTotalPrice >= t.freeOverFen -> 0L
                else -> t.firstFeeFen * units(t.valuation, g)
            }
        }
    }

    private fun units(valuation: String, g: PriceGroup): Long = when (valuation) {
        "2" -> ceilSum(g.checkedLines.map { l -> (l.weight ?: 0.0) * l.num })
        "3" -> ceilSum(g.checkedLines.map { l -> (l.volume ?: 0.0) * l.num })
        else -> g.cartTotalNum
    }.coerceAtLeast(1L)

    private fun ceilSum(parts: List<Double>): Long = Math.ceil(parts.sum()).toLong()
}

/** 汇总校验位：把"合计=各行之和"这条不变量显式化，属性测试直接断言它 */
@Component
class TotalSyncRule : PriceRule {
    override val order = 90
    override fun apply(ctx: PriceContext) {
        ctx.groups.forEach { g -> g.discountFee = g.checkedLines.sumOf { it.discountFee } }
    }
}
