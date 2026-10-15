package com.ecshopx.kotlin.service.cart

import org.springframework.stereotype.Component

/**
 * 算价责任链（详细设计 §7）。M0 只挂五档：
 * 分组 → 会员折扣 → 满减满折(骨架) → 券(开关位) → 运费 → 汇总钳制。
 *
 * 每个节点都是 ctx 上的纯函数（不查库、不取时间、不依赖随机），
 * 「预览价==下单价」= 同一 ctx 跑两次结果逐分相等。
 */
@Component
class MarketingEngine(rules: List<PriceRule>) {

    private val chain = rules.sortedBy { it.order }

    fun price(ctx: PriceContext): PriceContext {
        if (ctx.groups.isEmpty()) ctx.groups.addAll(PriceContext.groupByShop(ctx.lines))
        chain.forEach { it.apply(ctx) }
        // 兜底：任何规则都不允许把行优惠打到超过行小计
        ctx.lines.forEach { l -> if (l.discountFee > l.itemFee) l.discountFee = l.itemFee }
        ctx.groups.forEach { g -> g.discountFee = g.checkedLines.sumOf { it.discountFee } }
        return ctx
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
 * 满减 / 满折骨架（P1 接 marketing_full_reduction 数据源）。
 * M0 无活动表：命中集恒空，但出参字段与结构先就位，避免前端二次改造。
 */
@Component
class FullReductionRule : PriceRule {
    override val order = 30
    override fun apply(ctx: PriceContext) {
        ctx.groups.forEach { g ->
            val activities = listOf<Map<String, Any?>>()   // ← P1：按 shop 维度取满减满折命中
            val gifts = listOf<Map<String, Any?>>()        // ← P1：满赠
            val plusBuy = g.lines.filter { it.plusBuy && it.checked }.map {
                linkedMapOf<String, Any?>("item_id" to it.itemId, "cart_id" to it.cartId, "price" to it.price, "num" to it.num)
            }
            g.usedActivity = activities
            g.giftActivity = gifts
            g.plusBuyList = plusBuy
        }
    }
}

/**
 * 优惠券开关位：M0 不接 coupon 表，选中券号也不产生优惠，
 * 但保留入参与出参字段（券抵扣属 M1-2 营销域）。
 */
@Component
class CouponRule : PriceRule {
    override val order = 40
    override fun apply(ctx: PriceContext) {
        if (ctx.couponCode.isNullOrBlank()) return
        // M1: 校验券归属/有效期/门槛 → g.discountFee += min(券面额, 可用小计)
    }
}

/**
 * 运费（shipping_templates）。M0 口径：
 * 1) 模板 is_free='y' → 0；
 * 2) 组内应付 ≥ 满额包邮门槛 freeOverFen(分) → 0；
 * 3) 否则按 valuation 取计价量（1 件数 / 2 重量 / 3 体积，向上取整且至少 1），
 *    单档收取 firstFeeFen（地区分级与续重属 P1）。
 * 组内多模板取首个 templates_id>0；全无模板按包邮处理（演示环境无运费）。
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
