package com.ecshopx.kotlin.service.cart

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0-5 门槛：预览价==下单价（随机购物车 × 券 × 会员等级 1000 例）。
 *
 * 做法：同一随机用例构造两份**互不共享对象**的 PriceContext
 * （模拟 CART-2 列表与 ORDER-1 预览/ M0-6 下单各取一次数），跑同一条规则链，
 * 逐分比对完整价格快照；同时校验算价不变量（合计=行之和、优惠不超行小计、运费非负）。
 * 快照用字符串是为了失败时能直接读出差异发生在哪一行哪一组。
 */
class PricingPropertyTest {

    private val engine = MarketingEngine(
        listOf(GroupingRule(), MemberDiscountRule(), FullReductionRule(), CouponRule(), FreightRule(), TotalSyncRule()),
    )

    private data class LineSpec(
        val itemId: Long,
        val shopId: Long,
        val price: Long,
        val num: Long,
        val checked: Boolean,
        val plusBuy: Boolean,
        val templatesId: Long,
        val weight: Double,
        val volume: Double,
    )

    private data class Case(
        val lines: List<LineSpec>,
        val gradeDiscount: Int,
        val couponCode: String?,
        val usePoint: Boolean,
        val templates: Map<Long, FreightTemplate>,
    )

    private fun randomCase(r: Random): Case {
        val n = 1 + r.nextInt(6)
        val templateIds = listOf(0L, 1L, 2L, 3L)
        val lines = (1..n).map { i ->
            LineSpec(
                itemId = 100L + i,
                shopId = r.nextLong(0, 3),
                price = listOf(0L, 1L, 9900L, 199900L, 8888888L).let { it[r.nextInt(it.size)] } * (1 + r.nextInt(3)),
                num = (1 + r.nextInt(5)).toLong(),
                checked = r.nextBoolean() || i == 1,     // 至少一行勾选
                plusBuy = r.nextInt(6) == 0,
                templatesId = templateIds[r.nextInt(templateIds.size)],
                weight = r.nextInt(0, 30) / 10.0,
                volume = r.nextInt(0, 50) / 10.0,
            )
        }
        val templates = mapOf(
            1L to FreightTemplate(1, isFree = true, freeOverFen = 0, valuation = "1", firstFeeFen = 0),
            2L to FreightTemplate(2, isFree = false, freeOverFen = 0, valuation = "1", firstFeeFen = 800),
            3L to FreightTemplate(3, isFree = false, freeOverFen = 50000, valuation = "2", firstFeeFen = 1000),
            4L to FreightTemplate(4, isFree = false, freeOverFen = 0, valuation = "3", firstFeeFen = 500),
        ).filterKeys { it in templateIds }
        return Case(
            lines = lines,
            gradeDiscount = listOf(100, 98, 95, 88, 50)[r.nextInt(5)],
            couponCode = if (r.nextBoolean()) null else "CP${r.nextInt(1000)}",
            usePoint = r.nextBoolean(),
            templates = templates,
        )
    }

    /** 每次调用都新建对象：两份 ctx 之间不共享任何 PriceLine，等价于两次独立请求 */
    private fun build(c: Case): PriceContext {
        val lines = c.lines.mapIndexed { i, s ->
            PriceLine(
                cartId = i + 1L, itemId = s.itemId, itemName = "SKU-${s.itemId}",
                shopId = s.shopId, shopType = if (s.shopId > 0) "distributor" else "shop",
                activityType = "normal", activityId = 0,
                itemType = "normal", itemCategory = "c", type = 0,
                goodsId = s.itemId, parentId = s.itemId, pics = null, brief = null,
                marketPrice = s.price, store = 999, approveStatus = "onsale",
                templatesId = s.templatesId, weight = s.weight, volume = s.volume,
                specDesc = null, snapshotPrice = s.price, price = s.price, num = s.num,
                checked = s.checked, plusBuy = s.plusBuy,
            )
        }
        return PriceContext(
            companyId = 1, userId = 1, gradeName = "G", gradeDiscount = c.gradeDiscount,
            lines = lines, couponCode = c.couponCode, usePoint = c.usePoint,
            templates = c.templates,
        )
    }

    /** 全量价格快照：组金额 + 行金额，逐分 */
    private fun snapshot(ctx: PriceContext): String = buildString {
        append("itemFee=").append(ctx.totalItemFee())
        append("|freight=").append(ctx.totalFreight)
        append("|discount=").append(ctx.totalLineDiscount)
        append("|total=").append(ctx.totalPayFee())
        ctx.groups.sortedBy { it.shopId }.forEach { g ->
            append(";g").append(g.shopId)
                .append('=').append(g.itemFee).append('/').append(g.freight)
                .append('/').append(g.discountFee).append('/').append(g.cartTotalPrice)
                .append('/').append(g.totalFee).append('/').append(g.memberDiscount)
                .append('/').append(g.cartTotalNum).append('/').append(g.cartTotalCount)
            g.lines.sortedBy { it.itemId }.forEach { l ->
                append(" L").append(l.itemId).append('=').append(l.price).append('/')
                    .append(l.discountFee).append('/').append(l.memberDiscount).append('/').append(l.totalFee)
            }
        }
    }

    @Test
    fun `1000 例随机购物车 预览与下单取价逐分相等`() {
        val r = Random(20261015)
        var mismatch = 0
        repeat(1000) { k ->
            val c = randomCase(r)
            val preview = snapshot(engine.price(build(c)))   // CART-2 / ORDER-1
            val submit = snapshot(engine.price(build(c)))    // M0-6 下单
            if (preview != submit) {
                mismatch++
                if (mismatch <= 3) println("case#$k 预览=$preview\n      下单=$submit")
            }
        }
        assertEquals(0, mismatch, "存在 $mismatch 例预览价≠下单价")
    }

    @Test
    fun `1000 例算价不变量恒成立`() {
        val r = Random(7)
        repeat(1000) {
            val c = randomCase(r)
            val ctx = engine.price(build(c))
            val checked = ctx.checkedLines
            // 合计 = 行之和
            assertEquals(checked.sumOf { it.itemFee }, ctx.totalItemFee(), "itemFee 合计")
            assertEquals(checked.sumOf { it.discountFee }, ctx.totalLineDiscount, "discount 合计")
            assertEquals(checked.sumOf { it.num }, ctx.groups.sumOf { it.cartTotalNum }, "件数合计")
            ctx.groups.forEach { g ->
                assertEquals(g.checkedLines.sumOf { it.totalFee }, g.cartTotalPrice, "组内商品应付")
                assertEquals(g.cartTotalPrice + g.freight, g.totalFee, "组应付=商品+运费")
                assertEquals(g.checkedLines.sumOf { it.discountFee }, g.discountFee, "组优惠=行之和")
                g.lines.forEach { l ->
                    assertEquals(l.price * l.num - l.discountFee, l.totalFee, "行应付")
                    assertTrue(l.discountFee <= l.itemFee, "行优惠不得超行小计")
                    assertTrue(l.discountFee >= 0 && l.memberDiscount >= 0, "优惠非负")
                }
                assertTrue(g.freight >= 0, "运费非负")
            }
            assertEquals(ctx.groups.sumOf { it.totalFee }, ctx.totalPayFee(), "总价=各组合计")
            // 会员折扣口径：折让 = 行小计 ×(100-折扣)/100 逐行向下取整
            if (c.gradeDiscount < 100) {
                val rate = 100 - c.gradeDiscount
                checked.forEach { l -> assertEquals(l.itemFee * rate / 100, l.memberDiscount, "会员折让") }
            } else {
                checked.forEach { l -> assertEquals(0L, l.memberDiscount, "100=无折扣") }
            }
        }
    }

    @Test
    fun `未勾选行不计入合计 加价购行单独列出`() {
        val c = Case(
            lines = listOf(
                LineSpec(101, 0, 10000, 2, checked = true, plusBuy = false, templatesId = 2, weight = 1.0, volume = 1.0),
                LineSpec(102, 0, 5000, 3, checked = false, plusBuy = false, templatesId = 2, weight = 1.0, volume = 1.0),
                LineSpec(103, 0, 100, 1, checked = true, plusBuy = true, templatesId = 2, weight = 0.5, volume = 0.5),
            ),
            gradeDiscount = 100, couponCode = null, usePoint = false,
            templates = mapOf(2L to FreightTemplate(2, isFree = false, freeOverFen = 0, valuation = "1", firstFeeFen = 800)),
        )
        val ctx = engine.price(build(c))
        val g = ctx.groups.single()
        // 勾选两行：10000*2 + 100*1 = 20100，按件运费 3 件 * 800 = 2400
        assertEquals(20100L, g.itemFee)
        assertEquals(3L, g.cartTotalNum)
        assertEquals(2400L, g.freight)
        assertEquals(22500L, g.totalFee)
        assertEquals(1, g.plusBuyList.size, "加价购行单独列出")
        assertEquals(15000L, ctx.lines[1].itemFee, "未勾选行仍带行小计供前端展示")
    }

    @Test
    fun `满额包邮按勾选应付判定 未达标按重量计档`() {
        val t = FreightTemplate(9, isFree = false, freeOverFen = 30000, valuation = "2", firstFeeFen = 1000)
        fun freight(num: Long, weight: Double): Long {
            val c = Case(
                lines = listOf(LineSpec(101, 0, 10000, num, true, false, 9, weight, 0.0)),
                gradeDiscount = 100, couponCode = null, usePoint = false,
                templates = mapOf(9L to t),
            )
            return engine.price(build(c)).groups.single().freight
        }
        assertEquals(0L, freight(3, 1.0), "30000 分达门槛 → 包邮")
        assertEquals(2000L, freight(1, 1.6), "未达标：ceil(1.6)=2 档 × 1000")
        assertEquals(1000L, freight(1, 0.2), "计价量至少 1 档")
    }
}
