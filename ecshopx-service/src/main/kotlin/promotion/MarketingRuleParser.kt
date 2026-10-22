package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.dao.promotion.MarketingActivityDO
import com.ecshopx.kotlin.dao.promotion.MarketingBound
import com.ecshopx.kotlin.dao.promotion.MarketingType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 满减 / 满折规则解析（docs/04 §14.4）。
 *
 * 规则文本落在 `promotions_marketing_activity.condition_value`，源系统形态是**元为单位的字符串**
 * （`[{"full":"100.00","minus":"20.00"}]`），本项目内部一律分，换算只发生在 [yuanToFen] 这一处。
 *
 * 三条容错口径（都是"脏数据不能把算价链打挂"）：
 *  1. 空文本 / 非数组 / 非对象元素 → 该活动**视为未配置**：不报错、不产生优惠（`steps` 为空）；
 *  2. `full<=0` 的档、`minus>full` 的档、`discount` 不在 1..<100 的档 → 单档丢弃，其余档照常生效；
 *  3. 白名单外的 `marketing_type`（满赠/自选）解析得动但不出金额 —— 认得，更后面再批；
 *     **加价购例外**：批C 起 `plus_price_buy` 的 `price`（换购价）随档解析出来参与算价（§29.3）。
 *
 * 命中口径固定：**按 `full` 升序取最高命中档**，
 * `canjoin_repeat=true`（上不封顶）再乘 `floor(基数/full)`。
 */

/** 一档阶梯：`full` 与金额都是分，`discount` 是百分数（85=打 8.5 折，0=本档不是折扣档） */
data class MarketingStep(val full: Long, val minus: Long, val discount: Int, val plusPrice: Long = 0)

/**
 * 解析完的活动视图，进 [com.ecshopx.kotlin.service.cart.PriceContext] 的就是它。
 *
 * `useBound=[MarketingBound.ITEMS]` 时 [itemIds] 是圈定的商品集合；
 * 全场活动（`ALL`）不看它。规则链只读这个对象，不再碰 JSON 文本，也不查库。
 */
data class MarketingOffer(
    val marketingId: Long,
    val marketingType: String,
    val marketingName: String,
    /** totalfee=按组内应付 / quantity=按组内件数 */
    val conditionType: String,
    val canjoinRepeat: Boolean,
    val freePostage: Boolean,
    val promotionTag: String?,
    val useBound: Int,
    /** 按 full 升序、已清洗；空 = 该活动视为未配置 */
    val steps: List<MarketingStep>,
    val itemIds: Set<Long> = emptySet(),
) {
    val pricable: Boolean get() = marketingType in MarketingType.PRICABLE && steps.isNotEmpty()

    /**
     * 加价购活动的算价资格（§29.3 order 16 的判据之一）：
     * 类型是 `plus_price_buy`（≠ 本满折 [pricable] 的口径）**且至少一档配出了换购价**。
     * 满档没价的活动只会被后台表格显示，结算时静默不改价 —— 与满减"解析不出档就不减"同一律。
     */
    val plusPricable: Boolean
        get() = marketingType == MarketingType.PLUS_PRICE_BUY && steps.any { it.plusPrice > 0 }

    /** 最高命中档（未达门槛返回 null） */
    fun hit(base: Long): MarketingStep? = steps.lastOrNull { it.full <= base }

    /** 本档折让（分）：减额直接用，折扣按基数比例折、向下取整到分 */
    fun feeOf(step: MarketingStep, base: Long): Long = when (marketingType) {
        MarketingType.FULL_MINUS -> step.minus
        MarketingType.FULL_DISCOUNT -> base * (100 - step.discount) / 100
        else -> 0L
    }

    /**
     * 上不封顶：`可叠倍数 = floor(基数 / 档门槛)`，只算一次时恒 1。
     * 倍数为 0（基数比门槛小）时这一档本来也不会命中，钳到 1 交给调用方的 `hit()` 判定。
     */
    fun timesOf(step: MarketingStep, base: Long): Long =
        if (canjoinRepeat && step.full > 0) (base / step.full).coerceAtLeast(1L) else 1L

    /** 前台展示的档位文案（MK-F1 的 `condition_text`）：默认取首档，即"最低门槛那档怎么说" */
    fun conditionText(): String = if (steps.isEmpty()) "暂未配置优惠档位" else conditionText(steps.first())

    /** 命中档的文案（`used_activity[].rule` 要说清"便宜是按哪一档给的"） */
    fun conditionText(step: MarketingStep): String {
        val prefix = if (canjoinRepeat) "每" else ""
        val base = if (conditionType == CONDITION_QUANTITY) "${prefix}满${step.full}件" else "${prefix}满${yuan(step.full)}元"
        val tail = when (marketingType) {
            MarketingType.FULL_MINUS -> "减${yuan(step.minus)}"
            MarketingType.FULL_DISCOUNT -> "打${discountText(step.discount)}"
            MarketingType.PLUS_PRICE_BUY -> "可加价购"
            MarketingType.FULL_GIFT, MarketingType.SINGLE_GIFT -> "可赠"
            else -> "享优惠"
        }
        return base + tail
    }

    /** 85 → 8.5 折、80 → 8 折（去掉无意义的尾零，与前台角标习惯一致） */
    private fun discountText(discount: Int): String {
        if (discount <= 0 || discount >= 100) return "特惠"
        val zhe = discount / 10.0
        val s = if (zhe % 1 == 0.0) zhe.toInt().toString() else zhe.toString().trimEnd('0')
        return "${s}折"
    }

    companion object {
        const val CONDITION_TOTAL_FEE = "totalfee"
        const val CONDITION_QUANTITY = "quantity"
    }
}

/**
 * 解析器（无状态、不查库、不取时间：属性测试可以直接喂 JSON 字符串）。
 *
 * 输入形态两种都认：数组（本项目新表单也是数组）与单个对象（PHP 某些老活动只配一档）。
 * `items` 由调用方按圈品表查好后传入，解析器不负责取数。
 */
object MarketingRuleParser {

    fun parse(
        a: MarketingActivityDO,
        mapper: ObjectMapper,
        itemIds: Set<Long> = emptySet(),
    ): MarketingOffer = parse(a, rawNodes(a.conditionValue, mapper), itemIds)

    /** 已拆成节点数组的规则文本 → offer；脏 JSON 得到 `steps=emptyList()` 而不是异常 */
    fun parse(
        a: MarketingActivityDO,
        stepsRaw: List<JsonNode>,
        itemIds: Set<Long> = emptySet(),
    ): MarketingOffer {
        val quantity = a.conditionType == MarketingOffer.CONDITION_QUANTITY
        return MarketingOffer(
            marketingId = a.marketingId,
            marketingType = a.marketingType,
            marketingName = a.marketingName,
            conditionType = if (quantity) MarketingOffer.CONDITION_QUANTITY else MarketingOffer.CONDITION_TOTAL_FEE,
            canjoinRepeat = a.canjoinRepeat,
            freePostage = a.freePostage,
            promotionTag = a.promotionTag,
            useBound = a.useBound,
            steps = stepsRaw.mapNotNull { stepOf(it, a.marketingType, quantity) }.sortedBy { s -> s.full },
            itemIds = if (a.useBound == MarketingBound.ITEMS) itemIds else emptySet(),
        )
    }

    /** 单档解析：任何一处不合格就丢这一档（整条活动不该被一档脏数据废掉） */
    private fun stepOf(
        n: JsonNode,
        type: String,
        quantity: Boolean,
    ): MarketingStep? {
        if (!n.isObject) return null
        // `condition_type=quantity` 时 full 是**件数**（"满 2 件"），不能按元换算
        val full = if (quantity) count(n.get("full")) else fen(n.get("full"))
        if (full == null || full <= 0) return null
        return when (type) {
            MarketingType.FULL_MINUS -> {
                val minus = fen(n.get("minus")) ?: return null
                // 满 100 减 150 等于倒找钱，源系统靠运营自觉，本项目丢档（§14.4 脏数据口径）；
                // 按件满减时"减额 vs 件数"没有可比性，只查正数
                if (minus <= 0) null else if (!quantity && minus > full) null else MarketingStep(full, minus, 0)
            }

            MarketingType.FULL_DISCOUNT -> {
                val d = n.get("discount")?.asInt(0) ?: 0
                if (d <= 0 || d >= 100) null else MarketingStep(full, 0, d)
            }

            // 加价购：`price` 是**换购价**（分，批C 起 order 16 用它改行单价基准）；
            // 没有/非法换购价的档仍然解析得动（full 供展示），只是不会改价
            // 满赠/自选：门槛解析出来供展示，金额一律 0（不参与算价）
            MarketingType.PLUS_PRICE_BUY ->
                MarketingStep(full, 0, 0, fen(n.get("price")) ?: 0L)

            MarketingType.FULL_GIFT, MarketingType.SINGLE_GIFT, "self_select" ->
                MarketingStep(full, 0, 0)

            else -> null
        }
    }

    /** 元字符串/数字都认；非法或非正数返回 null（调用方丢档） */
    private fun fen(n: JsonNode?): Long? {
        if (n == null || n.isNull) return null
        val raw = when {
            n.isNumber -> n.decimalValue().toPlainString()
            n.isTextual -> n.asText()
            else -> return null
        }
        val fen = yuanToFen(raw)
        return if (fen > 0) fen else null
    }

    /** 件数档：只认整数，"2.5 件"这种一律丢档（满减门槛不该有半个商品） */
    private fun count(n: JsonNode?): Long? {
        if (n == null || n.isNull) return null
        val v = when {
            n.isInt -> n.asLong()
            n.isNumber -> n.decimalValue().stripTrailingZeros().let { if (it.scale() <= 0) it.toLong() else null }
            n.isTextual -> n.asText().trim().toLongOrNull()
            else -> null
        }
        return if (v != null && v > 0) v else null
    }

    /** 规则文本 → 节点数组（非数组时按单档对象处理，兼容只配一档的老活动） */
    fun rawNodes(conditionValue: String?, mapper: ObjectMapper): List<JsonNode> {
        val raw = conditionValue?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return emptyList()
        return when {
            node.isArray -> node.toList()
            node.isObject -> listOf(node)
            else -> emptyList()
        }
    }

    /** 后台表单直接给 List<Map> 时的同形序列化（存进库仍是 PHP 形态的元字符串） */
    fun toStoredJson(steps: List<Map<String, Any?>>, mapper: ObjectMapper): String =
        mapper.writeValueAsString(steps)
}
