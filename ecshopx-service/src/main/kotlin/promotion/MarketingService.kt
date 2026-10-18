package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.MarketingActivityDO
import com.ecshopx.kotlin.dao.promotion.MarketingActivityMapper
import com.ecshopx.kotlin.dao.promotion.MarketingBound
import com.ecshopx.kotlin.dao.promotion.MarketingCheckStatus
import com.ecshopx.kotlin.dao.promotion.MarketingItemDO
import com.ecshopx.kotlin.dao.promotion.MarketingItemsMapper
import com.ecshopx.kotlin.dao.promotion.MarketingType
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * M1-3 满减 / 满折服务（MK-A1..A7 后台 + MK-F1/F2/F3 会员面，docs/04 §14.2/§14.4）。
 *
 * 四条口径：
 *  1. **规则文本仍是 PHP 形态**：`condition_value` 存"元为单位"的 JSON 阶梯串。
 *     新表单直接传数组，服务端序列化后入库，读的时候一律经 [MarketingRuleParser] 换算成分 ——
 *     存量活动因此不用迁移，算价链也不会拿到两种单位（§14.4）；
 *  2. **审核是唯一的生效开关**：`check_status='reviewed' && status && 时间在窗` 才进算价链（MK-A6）。
 *     编辑会把状态打回 `non-reviewed`：改过力度就该重新看一遍，不能让"改完悄悄继续生效"；
 *  3. **不认识的类型认得、不算钱**：`full_gift/plus_price_buy/single_gift` 能配、能展示，
 *     金额恒 0（P2 再做赠品与加价购履约）。白名单外的类型直接拒（400），免得后台攒出一堆永远不进算价的活动；
 *  4. `use_bound=2`（按分类圈品）属 P2，配置台直接拒。
 *
 * 时间窗判定用 `date_status`（pending/doing/end）派生列，不落库：源系统同样是查询时算，
 * 落库就会出现"活动过期了但列还是 doing"这种要人修的脏状态。
 */
@Service
class MarketingService(
    private val acts: MarketingActivityMapper,
    private val items: MarketingItemsMapper,
    private val seckill: SeckillService,
    private val db: Db,
    private val mapper: ObjectMapper,
) {

    // ===================== MK-A1 / MK-A4 建与改 =====================

    fun save(
        companyId: Long,
        form: MarketingForm,
    ): Map<String, Any?> {
        val id = form.marketingId?.takeIf { it > 0 }
        val draft = MarketingActivityDO().apply {
            this.companyId = companyId
            marketingType = form.marketingType?.takeIf { it.isNotBlank() } ?: MarketingType.FULL_MINUS
            marketingName = form.marketingName?.trim().orEmpty()
            adPic = form.adPic?.takeIf { it.isNotBlank() }
            startTime = form.startTime ?: 0
            endTime = form.endTime ?: 0
            // 发布时间 = 建/改的这一刻：源系统用它做"活动可见"的时间下限，本项目只落列不参与算价
            releaseTime = TimeSec.now()
            useBound = form.useBound ?: MarketingBound.ALL
            useShop = form.useShop ?: 0
            shopIds = form.shopIds?.takeIf { it.isNotBlank() }
            conditionType = if (form.conditionType == MarketingOffer.CONDITION_QUANTITY) MarketingOffer.CONDITION_QUANTITY else MarketingOffer.CONDITION_TOTAL_FEE
            conditionValue = storedRule(form.conditionValue)
            canjoinRepeat = form.canjoinRepeat == true
            joinLimit = form.joinLimit ?: 0
            freePostage = form.freePostage == true
            promotionTag = form.promotionTag?.takeIf { it.isNotBlank() }
            checkStatus = MarketingCheckStatus.NON_REVIEWED
            status = form.status ?: true
        }
        validate(draft)
        // 解析一次就当"配置正确"的证据：解析完没有一档能用，等于这条活动永远不会减钱
        val offer = MarketingRuleParser.parse(draft, mapper)
        if (offer.marketingType in MarketingType.PRICABLE && offer.steps.isEmpty()) {
            throw BizException(ErrorCode.VALIDATION, "阶梯规则不合法", mapOf("condition_value" to listOf("至少要有一档合法规则")))
        }

        if (id == null) {
            acts.insert(draft)
            return detail(companyId, draft.marketingId)
        }
        activity(id, companyId)
        draft.marketingId = id
        // 编辑必重审：力度变了就要重新过一遍审核（MK-A4），不存在"改完继续悄悄生效"。
        // `update` 语句本身不碰 check_status（那是审核动作的列），所以这里显式打回
        draft.checkStatus = MarketingCheckStatus.NON_REVIEWED
        acts.update(draft)
        acts.check(id, companyId, MarketingCheckStatus.NON_REVIEWED, TimeSec.now())
        return detail(companyId, id)
    }

    private fun validate(a: MarketingActivityDO) {
        val e = linkedMapOf<String, List<String>>()
        if (a.marketingType !in KNOWN_TYPES) e["marketing_type"] = listOf("只支持 ${KNOWN_TYPES.joinToString("/")}")
        if (a.marketingName.isBlank()) e["marketing_name"] = listOf("不能为空")
        if (a.marketingName.length > 128) e["marketing_name"] = listOf("最长 128 字")
        if (a.startTime <= 0) e["start_time"] = listOf("开始时间必填")
        if (a.endTime < 0) e["end_time"] = listOf("结束时间不能为负")
        if (a.startTime > 0 && a.endTime > 0 && a.endTime <= a.startTime) e["end_time"] = listOf("结束时间必须晚于开始时间")
        if (a.useBound !in setOf(MarketingBound.ALL, MarketingBound.ITEMS)) e["use_bound"] = listOf("只支持 0 全场 / 1 指定商品（按分类属 P2）")
        if (a.joinLimit < 0) e["join_limit"] = listOf("参与次数不能为负")
        if (a.conditionValue.isNullOrBlank() && a.marketingType in MarketingType.PRICABLE) e["condition_value"] = listOf("满减/满折必须配置阶梯规则")
        if (!a.conditionValue.isNullOrBlank() && MarketingRuleParser.rawNodes(a.conditionValue, mapper).isEmpty()) {
            e["condition_value"] = listOf("规则必须是 JSON 数组或对象")
        }
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "活动配置不合法", e)
    }

    /** 表单的阶梯 → 入库文本（数组/单对象/原始 JSON 串三种写法都收，见 [MarketingForm.conditionValue]） */
    private fun storedRule(v: Any?): String? {
        val raw = when (v) {
            null -> return null
            is String -> return v.trim()
            is List<*> -> v.mapNotNull { it as? Map<*, *> }
            is Map<*, *> -> listOf(v)
            else -> return v.toString()
        }
        if (raw.isEmpty()) return null
        return mapper.writeValueAsString(raw.map { row -> row.entries.filter { it.key != null }.associate { (k, x) -> k.toString() to x } })
    }

    // ===================== MK-A2 / MK-A3 后台查询 =====================

    fun list(
        companyId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = filters(companyId, raw)
        return PageHelper.paginate(
            pq,
            count = { acts.count(f) },
            query = { offset, limit -> acts.page(f, offset, limit) },
            map = { rows ->
                val counts = if (rows.isEmpty()) emptyMap() else items.countBatch(rows.map { it.marketingId }).associate { it.marketingId to it.cnt }
                rows.map { row(it, (counts[it.marketingId] ?: 0L).toInt()) }
            },
        )
    }

    fun detail(
        companyId: Long,
        marketingId: Long,
    ): Map<String, Any?> {
        val a = activity(marketingId, companyId)
        val rows = items.listByMarketing(marketingId)
        return LinkedHashMap(row(a, rows.size)).apply { put("items", rows.map { itemRow(it) }) }
    }

    /** MK-A6 审核：reviewed 才进算价链；unreviewed/non-reviewed 都把它请出算价链 */
    fun check(
        companyId: Long,
        marketingId: Long,
        checkStatus: String,
    ): Map<String, Any?> {
        if (checkStatus !in CHECKABLE) {
            throw BizException(ErrorCode.VALIDATION, "check_status 非法", mapOf("check_status" to listOf("只支持 ${CHECKABLE.joinToString("/")}")))
        }
        activity(marketingId, companyId)
        acts.check(marketingId, companyId, checkStatus, TimeSec.now())
        return detail(companyId, marketingId)
    }

    /** MK-A5 圈品：全量覆盖。空数组等于"清空圈品"，此时活动不该再是 `use_bound=1` */
    fun saveItems(
        companyId: Long,
        marketingId: Long,
        form: MarketingItemsForm,
    ): Map<String, Any?> {
        val a = activity(marketingId, companyId)
        val rows = form.items.orEmpty()
        if (a.useBound == MarketingBound.ITEMS && rows.isEmpty()) {
            throw BizException(ErrorCode.VALIDATION, "items 不能为空", mapOf("items" to listOf("指定商品活动至少要圈一个商品")))
        }
        val errs = linkedMapOf<String, List<String>>()
        rows.forEachIndexed { i, r ->
            if ((r.itemId ?: 0) <= 0) errs["items[$i].item_id"] = listOf("商品 id 非法")
            if ((r.price ?: -1) < 0) errs["items[$i].price"] = listOf("价格不能为负")
        }
        if (errs.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "圈品数据不合法", errs)
        val ids = rows.mapNotNull { it.itemId }.filter { it > 0 }.distinct()
        if (ids.size != rows.size) {
            throw BizException(ErrorCode.VALIDATION, "items 有重复商品", mapOf("items" to listOf("同一商品只能圈一次")))
        }
        db.transactional {
            items.deleteByMarketing(marketingId)
            rows.forEach { r ->
                items.insert(
                    MarketingItemDO().apply {
                        this.marketingId = marketingId
                        this.itemId = r.itemId!!
                        goodsId = r.goodsId ?: 0
                        marketingType = a.marketingType
                        itemName = r.itemName?.takeIf { it.isNotBlank() } ?: ""
                        price = r.price ?: 0
                        promotionTag = r.promotionTag
                        startTime = a.startTime
                        endTime = a.endTime
                        status = true
                        isShow = r.isShow ?: true
                    },
                )
            }
            Unit
        }
        return linkedMapOf("marketing_id" to marketingId, "items" to items.listByMarketing(marketingId).map { itemRow(it) })
    }

    /** MK-A7 删除活动 + 圈品行（同事务：只删一头会留下指向空洞的圈品行） */
    fun delete(
        companyId: Long,
        marketingId: Long,
    ): Map<String, Any?> {
        activity(marketingId, companyId)
        db.transactional {
            items.deleteByMarketing(marketingId)
            acts.delete(marketingId, companyId)
            Unit
        }
        return linkedMapOf("marketing_id" to marketingId, "deleted" to true)
    }

    // ===================== MK-F1 / F2 / F3 会员面 =====================

    /**
     * MK-F1 进行中的活动。会员面只给**已审核 + 启用 + 在窗**的：
     * 未审核的活动前台看见却买不到，比看不见更难解释（§14.4 的生效条件三条与这里一致）。
     */
    fun frontList(
        companyId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val now = TimeSec.now()
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "checkStatus" to MarketingCheckStatus.REVIEWED,
            "status" to true,
            "dateStatus" to "doing",
            "now" to now,
            "type" to raw["marketing_type"]?.takeIf { it.isNotBlank() },
        )
        return PageHelper.paginate(
            pq,
            count = { acts.count(f) },
            query = { offset, limit -> acts.page(f, offset, limit) },
            map = { rows -> rows.map { frontRow(it) } },
        )
    }

    fun frontDetail(
        companyId: Long,
        marketingId: Long,
    ): Map<String, Any?> {
        val a = activity(marketingId, companyId)
        // 未审核/已停用的活动对前台就是"不存在"：不给"存在但看不见"这种需要解释的状态
        if (a.checkStatus != MarketingCheckStatus.REVIEWED || !a.status) throw BizException(ErrorCode.NOT_FOUND, "活动不存在")
        return LinkedHashMap(frontRow(a)).apply {
            put("items", if (a.useBound == MarketingBound.ITEMS) items.listByMarketing(marketingId).map { itemRow(it) } else emptyList())
        }
    }

    /**
     * MK-F3 单商品可享的全部营销（商详页角标）。
     *
     * 满减/满折与秒杀在这里合成一次应答：`marketing[]` 是"满 X 减 Y"那一类，
     * `seckill` 是"此刻的活动价"那一类（null=不在秒杀里）。两者判据各自与算价链同源
     * （§14.5 的 30 与 15），所以角标上写着的价，结算一定给得起。
     */
    fun itemPromotions(
        companyId: Long,
        itemId: Long,
    ): Map<String, Any?> {
        val now = TimeSec.now()
        val sec = seckill.itemOffer(companyId, itemId)
        val all = acts.listPricable(companyId, now, MarketingType.PRICABLE)
        if (all.isEmpty()) return linkedMapOf("item_id" to itemId, "marketing" to emptyList<Map<String, Any?>>(), "tags" to emptyList<String>(), "seckill" to sec)
        val hitIds = items.listHit(all.map { it.marketingId }, listOf(itemId)).map { it.marketingId }.toSet()
        val hits = all.mapNotNull { a ->
            val o = MarketingRuleParser.parse(a, mapper, hitIds)
            if (o.pricable && (o.useBound == MarketingBound.ALL || itemId in o.itemIds)) a to o else null
        }
        return linkedMapOf(
            "item_id" to itemId,
            "marketing" to hits.map { frontRow(it.first) },
            "tags" to hits.mapNotNull { it.first.promotionTag }.distinct(),
            // 秒杀价：与 §14.5 order 15 同判据，前台角标与结算价不会分叉
            "seckill" to sec,
        )
    }

    // ===================== 出参装配 =====================

    private fun row(a: MarketingActivityDO, itemCount: Int): Map<String, Any?> {
        val offer = MarketingRuleParser.parse(a, mapper)
        return linkedMapOf(
            "marketing_id" to a.marketingId,
            "company_id" to a.companyId,
            "marketing_type" to a.marketingType,
            "marketing_name" to a.marketingName,
            "ad_pic" to a.adPic,
            "start_time" to a.startTime,
            "end_time" to a.endTime,
            "release_time" to a.releaseTime,
            "use_bound" to a.useBound,
            "use_shop" to a.useShop,
            "shop_ids" to a.shopIds,
            "condition_type" to a.conditionType,
            // 原样回 + 解析后阶梯：后台表单要能回填，前端要能看懂"减多少"
            "condition_value" to a.conditionValue,
            "steps" to offer.steps.map { s -> linkedMapOf("full" to s.full, "minus" to s.minus, "discount" to s.discount, "rule" to offer.conditionText(s)) },
            "canjoin_repeat" to a.canjoinRepeat,
            "join_limit" to a.joinLimit,
            "free_postage" to a.freePostage,
            "promotion_tag" to a.promotionTag,
            "check_status" to a.checkStatus,
            "status" to a.status,
            "date_status" to dateStatus(a, TimeSec.now()),
            "item_count" to itemCount,
            "created" to a.created,
            "updated" to a.updated,
        )
    }

    private fun frontRow(a: MarketingActivityDO): Map<String, Any?> {
        val offer = MarketingRuleParser.parse(a, mapper)
        return linkedMapOf(
            "marketing_id" to a.marketingId,
            "marketing_type" to a.marketingType,
            "marketing_name" to a.marketingName,
            "ad_pic" to a.adPic,
            "promotion_tag" to a.promotionTag,
            "condition_type" to a.conditionType,
            "condition_text" to offer.conditionText(),
            "start_time" to a.startTime,
            "end_time" to a.endTime,
            "use_bound" to a.useBound,
            "free_postage" to a.freePostage,
            "date_status" to dateStatus(a, TimeSec.now()),
        )
    }

    private fun itemRow(i: MarketingItemDO): Map<String, Any?> = linkedMapOf(
        "id" to i.id,
        "marketing_id" to i.marketingId,
        "item_id" to i.itemId,
        "goods_id" to i.goodsId,
        "item_name" to i.itemName,
        "price" to i.price,
        "promotion_tag" to i.promotionTag,
        "start_time" to i.startTime,
        "end_time" to i.endTime,
        "status" to i.status,
        "is_show" to i.isShow,
    )

    private fun dateStatus(a: MarketingActivityDO, now: Long): String = when {
        a.startTime > now -> "pending"
        a.endTime > 0 && a.endTime < now -> "end"
        else -> "doing"
    }

    private fun filters(companyId: Long, raw: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "companyId" to companyId,
        "type" to raw["marketing_type"]?.takeIf { it.isNotBlank() },
        "checkStatus" to raw["check_status"]?.takeIf { it.isNotBlank() },
        "status" to raw["status"]?.let { it == "true" || it == "1" },
        "name" to raw["marketing_name"]?.takeIf { it.isNotBlank() },
        "dateStatus" to raw["date_status"]?.takeIf { it.isNotBlank() },
        "now" to TimeSec.now(),
    )

    private fun activity(marketingId: Long, companyId: Long): MarketingActivityDO =
        acts.findById(marketingId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "营销活动不存在")

    private fun pageQuery(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    companion object {
        /** 配置台认识的类型（算价只用前两种，其余只解析不出金额） */
        private val KNOWN_TYPES = setOf(
            MarketingType.FULL_MINUS, MarketingType.FULL_DISCOUNT,
            MarketingType.PLUS_PRICE_BUY, MarketingType.FULL_GIFT, MarketingType.SINGLE_GIFT,
        )
        private val CHECKABLE = setOf(MarketingCheckStatus.REVIEWED, MarketingCheckStatus.UNREVIEWED, MarketingCheckStatus.NON_REVIEWED)
    }
}
