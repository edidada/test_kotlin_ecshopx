package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.CrowdDiscountDO
import com.ecshopx.kotlin.dao.promotion.CrowdDiscountLogDO
import com.ecshopx.kotlin.dao.promotion.CrowdDiscountLogMapper
import com.ecshopx.kotlin.dao.promotion.CrowdDiscountMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * 人群折扣（docs/04 §29.3）：后台 CRUD + 算价取数 + 优惠日志。
 *
 * 定位与 strategies：
 *  **它不是营销活动**（`promotions_marketing_activity` 那一族）——活动要审核、有生效窗、一店一活动
 *  的折让规则；人群折扣是"哪些人享多少折"的定向口径，挂的是 `crowd_discount` 独立表、
 *  算价链 order 25（会员折 20 → 人群折 25 → 满减 30）逐行分摊。
 *
 * 三条口径：
 *  1. **折扣百分比同 members_grade**：85 = 8.5 折、100 = 无折。校验放 `validate`（脏配置进不了库）；
 *  2. **用户匹配在取数腿做**（[offersFor]，`CartService.buildContext` 预取），规则链拿到的
 *     是"这个用户已经命中的那份配置"——引擎继续保持不查库、不取时间的纯函数纪律（M0-5）；
 *  3. **圈品**：`use_bound=1` 时 `item_ids` 逗号分隔；`all` 型人群不判圈品之外还有个空圈品兜底。
 */

/** 算价视图：已启用、在窗、且**命中当前用户**的那份配置。规则链只读它 */
data class CrowdOffer(
    val id: Long,
    val name: String,
    /** 折扣百分比，85=8.5 折 */
    val discount: Int,
    val useBound: Int,
    val itemIds: Set<Long>,
)

/** 引擎算完记下的"本单真的用了哪条折让、让了多少"，下单事务里据此写日志 */
data class CrowdMatch(val id: Long, val fee: Long)

class CrowdForm {
    var name: Any? = null
    var discount: Any? = null
    var crowdType: Any? = null
    var crowdValue: Any? = null
    var useBound: Any? = null
    var itemIds: List<Any?>? = null
    var disabled: Any? = null
    var startTime: Any? = null
    var endTime: Any? = null
}

@Service
class CrowdDiscountService(
    private val rows: CrowdDiscountMapper,
    private val logs: CrowdDiscountLogMapper,
    private val mapper: ObjectMapper,
) {

    // ===================== 后台 CRUD =====================

    fun save(companyId: Long, form: CrowdForm): Map<String, Any?> {
        val draft = toDo(companyId, form).apply {
            created = TimeSec.now()
            disabled = form.disabled == true
        }
        validate(draft)
        rows.insert(draft)
        return view(draft)
    }

    fun update(companyId: Long, id: Long, form: CrowdForm) {
        val old = act(companyId, id)
        val draft = toDo(companyId, form).apply {
            this.id = old.id
            created = old.created
            disabled = form.disabled ?: old.disabled
        }
        validate(draft)
        rows.update(draft)
    }

    fun delete(companyId: Long, id: Long) {
        act(companyId, id)
        rows.delete(companyId, id)
    }

    fun adminPage(companyId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { rows.count(companyId) },
        query = { off, lim -> rows.page(companyId, off, lim) },
        map = { list -> list.map { view(it) } },
    )

    fun adminDetail(companyId: Long, id: Long): Map<String, Any?> = view(act(companyId, id))

    /** 优惠日志：翻页 + 关联折扣名（没删掉的配置回显名字，删了给占位） */
    fun adminLogs(companyId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { logs.count(companyId) },
        query = { off, lim -> logs.page(companyId, off, lim) },
        map = { list ->
            list.map { l ->
                val cfg = rows.find(companyId, l.discountId)
                linkedMapOf(
                    "id" to l.id,
                    "discount_id" to l.discountId,
                    "discount_name" to (cfg?.name ?: "已删除配置"),
                    "order_id" to l.orderId,
                    "user_id" to l.userId,
                    "fee" to l.fee,
                    "created" to l.created,
                )
            }
        },
    )

    // ===================== 算价取数腿 =====================

    /**
     * 命中当前会员的活动人群配置（已启用 + 在窗 + 人群匹配）。
     *
     * 圈品集合在这里展开成 `Set<Long>`，规则链做 "行 ∩ 圈品" 的纯集合运算；
     * 匹配失败/未命中返回空列表，等于"这个用户没有人群折"。
     */
    fun offersFor(companyId: Long, gradeId: Long, memberCreated: Long, now: Long): List<CrowdOffer> =
        rows.listActive(companyId, now).filter { matchesUser(it, gradeId, memberCreated) }.map { o ->
            CrowdOffer(
                id = o.id,
                name = o.name,
                discount = o.discount,
                useBound = o.useBound,
                itemIds = splitIds(o.itemIds).toSet(),
            )
        }

    /** 人群匹配：all 恒真；grade 看 grade_id ∈ 配置；new_user 看注册天数 */
    private fun matchesUser(d: CrowdDiscountDO, gradeId: Long, memberCreated: Long): Boolean = when (d.crowdType) {
        "all" -> true
        "grade" -> parseCrowdValue(d.crowdValue).path("grade_ids")
            .let { n -> n.takeIf { it.isArray }?.map { it.asLong() } ?: emptyList() }
            .contains(gradeId)
        "new_user" -> {
            val days = parseCrowdValue(d.crowdValue).path("days")?.asLong(7L) ?: 7L
            memberCreated > 0 && TimeSec.now() - memberCreated <= days * 86400L
        }
        else -> false
    }

    /** 下单事务写日志的唯一入口（OrderCreateService 调，§29.3）：一笔单一条/一份折让 */
    fun logDiscount(companyId: Long, discountId: Long, orderId: Long, userId: Long, fee: Long, now: Long) {
        if (fee <= 0) return
        logs.insert(
            CrowdDiscountLogDO().apply {
                this.companyId = companyId
                this.discountId = discountId
                this.orderId = orderId
                this.userId = userId
                this.fee = fee
                created = now
                updated = now
            },
        )
    }

    // ===================== 内部 =====================

    private fun act(companyId: Long, id: Long): CrowdDiscountDO =
        rows.find(companyId, id) ?: throw BizException(ErrorCode.NOT_FOUND, "人群折扣配置不存在")

    private fun toDo(companyId: Long, form: CrowdForm): CrowdDiscountDO = CrowdDiscountDO().apply {
        this.companyId = companyId
        name = form.name?.toString()?.trim() ?: ""
        discount = form.discount as? Int ?: form.discount?.toString()?.toIntOrNull() ?: 100
        crowdType = form.crowdType?.toString()?.trim() ?: "all"
        crowdValue = when (val v = form.crowdValue) {
            null -> ""
            is String -> v
            else -> mapper.writeValueAsString(v)
        }
        useBound = form.useBound as? Int ?: form.useBound?.toString()?.toIntOrNull() ?: 0
        itemIds = (form.itemIds ?: emptyList()).mapNotNull { v ->
            when (val n = v?.toString()?.toLongOrNull()) {
                null, 0L -> null
                else -> n
            }
        }.distinct().joinToString(",")
        startTime = form.startTime?.toString()?.toLongOrNull() ?: TimeSec.now()
        endTime = form.endTime?.toString()?.toLongOrNull() ?: 0L
    }

    private fun validate(d: CrowdDiscountDO) {
        val e = linkedMapOf<String, List<String>>()
        if (d.name.isBlank()) e["name"] = listOf("不能为空")
        if (d.name.length > 128) e["name"] = listOf("最长 128 字")
        if (d.discount !in 1..100) e["discount"] = listOf("折扣取值 1..100（85=打 8.5 折），100=无折扣")
        if (d.crowdType !in setOf("all", "grade", "new_user")) e["crowd_type"] = listOf("只支持 all/grade/new_user")
        if (d.crowdType == "grade") {
            val ids = parseCrowdValue(d.crowdValue).path("grade_ids").takeIf { it.isArray }
            if (ids == null || ids.size() == 0) e["crowd_value"] = listOf("按等级圈人必须给 grade_ids 数组")
        }
        if (d.crowdType == "new_user" && (parseCrowdValue(d.crowdValue).path("days")?.asLong(7L) ?: 7L) <= 0) {
            e["crowd_value"] = listOf("days 必须大于 0")
        }
        if (d.useBound !in setOf(0, 1)) e["use_bound"] = listOf("只支持 0 全场 / 1 指定商品")
        if (d.useBound == 1 && splitIds(d.itemIds).isEmpty()) e["item_ids"] = listOf("圈品至少给一个 item_id")
        if (d.startTime <= 0) e["start_time"] = listOf("开始时间必填")
        if (d.endTime < 0) e["end_time"] = listOf("结束时间不能为负")
        if (d.endTime > 0 && d.endTime <= d.startTime) e["end_time"] = listOf("结束时间必须晚于开始时间")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "人群折扣配置不合法", e)
    }

    private fun parseCrowdValue(raw: String?): com.fasterxml.jackson.databind.JsonNode {
        val clean = raw?.takeIf { it.isNotBlank() } ?: return mapper.createObjectNode()
        return runCatching { mapper.readTree(clean) }.getOrDefault(mapper.createObjectNode())
    }

    private fun splitIds(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.filter { it > 0 } ?: emptyList()

    private fun view(d: CrowdDiscountDO): Map<String, Any?> = linkedMapOf(
        "id" to d.id,
        "company_id" to d.companyId,
        "name" to d.name,
        "discount" to d.discount,
        "discount_text" to discountText(d.discount),
        "crowd_type" to d.crowdType,
        "crowd_value" to (d.crowdValue.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readTree(it) }.getOrNull() } ?: emptyMap<String, Any?>()),
        "use_bound" to d.useBound,
        "item_ids" to splitIds(d.itemIds),
        "disabled" to d.disabled,
        "start_time" to d.startTime,
        "end_time" to d.endTime,
        "created" to d.created,
        "updated" to d.updated,
    )

    /** 85 → "8.5折"、100 → "无折扣"（与 MarketingOffer.discountText 同形） */
    private fun discountText(discount: Int): String = when {
        discount >= 100 -> "无折扣"
        discount <= 0 -> "特惠"
        else -> {
            val zhe = discount / 10.0
            val s = if (zhe % 1 == 0.0) zhe.toInt().toString() else zhe.toString().trimEnd('0')
            "${s}折"
        }
    }
}
