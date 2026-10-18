package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.OrderClass
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.SeckillActivityDO
import com.ecshopx.kotlin.dao.promotion.SeckillActivityMapper
import com.ecshopx.kotlin.dao.promotion.SeckillGoodsDO
import com.ecshopx.kotlin.dao.promotion.SeckillGoodsMapper
import com.ecshopx.kotlin.dao.promotion.SeckillType
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Service

/**
 * M1-3 秒杀（SK-A1..A7 后台 + SK-F1/F2 会员面，docs/04 §14.1/§14.2/§14.6）。
 *
 * 三件与满减域不同的事，都落在"库存"上：
 *  1. **场次是容器，商品行才是活动本体**：价格、活动库存、限购全都长在 `promotions_seckill_rel_goods` 上，
 *     场次只给时间窗、`validity_period`、`limit_money`。所以 SK-A6 是"商品行 upsert"而不是"编辑活动"；
 *  2. **库存只许加不许减**（D24）：`activity_store` 一旦有 `sales_store`/`hold_store`，
 *     减下来就等于把已付的货变没。因此已产生销量或占用的行只走 [SeckillGoodsMapper.addStore]，
 *     负数增量直接 422 —— 想收回额度请停用该行（`is_show=false`），别改总量；
 *  3. **场次状态是派生的**（pending/doing/end），不落列：落库就会出现"过期了但列还是 doing"这种要人修的脏状态，
 *     与满减域同一口径（docs/04 §14.6 第一行）。
 *
 * 池子的三个数（总/已售/占用）由下单与结清链路维护，本服务只读不写它们 ——
 * 唯一的例外是 SK-A6 的加库存，它动的是总量列，与占用无关。
 */
@Service
class SeckillService(
    private val acts: SeckillActivityMapper,
    private val goods: SeckillGoodsMapper,
    private val items: ItemsMapper,
    private val db: Db,
) {

    // ===================== SK-A1 / SK-A4 =====================

    fun save(companyId: Long, form: SeckillForm): Map<String, Any?> {
        val id = form.seckillId?.takeIf { it > 0 }
        val cur = id?.let { act(it, companyId) }
        // 编辑是"覆盖给出的列"：没给的列保持原值，否则一次 PATCH 会把限额改回 0
        val draft = SeckillActivityDO().also { d ->
            cur?.let { c ->
                d.seckillId = c.seckillId
                d.activityName = c.activityName
                d.adPic = c.adPic
                d.activityStartTime = c.activityStartTime
                d.activityEndTime = c.activityEndTime
                d.releaseTime = c.releaseTime
                d.distributorId = c.distributorId
                d.seckillType = c.seckillType
                d.limitMoney = c.limitMoney
                d.validityPeriod = c.validityPeriod
                d.disabled = c.disabled
                d.created = c.created
                d.updated = c.updated
            }
            d.companyId = companyId
            form.activityName?.trim()?.takeIf { it.isNotEmpty() }?.let { d.activityName = it }
            form.activityStartTime?.let { d.activityStartTime = it }
            form.activityEndTime?.let { d.activityEndTime = it }
            form.validityPeriod?.let { d.validityPeriod = it }
            form.limitMoney?.let { d.limitMoney = it }
            form.disabled?.let { d.disabled = it }
            form.distributorId?.takeIf { it.isNotBlank() }?.let { d.distributorId = it }
            form.adPic?.takeIf { it.isNotBlank() }?.let { d.adPic = it }
            // 两种写法都收：源系统后台表单发整数码（1/2），本项目新表单直接发列值
            form.activityType?.takeIf { it != 0 }?.let { d.seckillType = seckillTypeOf(it) }
            form.seckillType?.takeIf { it.isNotBlank() }?.let { d.seckillType = it }
        }
        validate(draft)
        val now = TimeSec.now()
        if (cur == null) {
            draft.releaseTime = now
            acts.insert(draft)
        } else {
            draft.updated = now
            acts.update(draft)
        }
        return detail(companyId, draft.seckillId)
    }

    private fun validate(a: SeckillActivityDO) {
        val e = linkedMapOf<String, List<String>>()
        if (a.activityName.isBlank()) e["activity_name"] = listOf("场次名称不能为空")
        if (a.activityName.length > 128) e["activity_name"] = listOf("最长 128 字")
        if (a.activityStartTime <= 0) e["activity_start_time"] = listOf("开始时间必填")
        if (a.activityEndTime <= a.activityStartTime) e["activity_end_time"] = listOf("结束时间必须晚于开始时间")
        if (a.validityPeriod < 0 || a.validityPeriod > MINUTE_CAP) e["validity_period"] = listOf("支付时限 0..$MINUTE_CAP 分钟")
        if (a.limitMoney < 0) e["limit_money"] = listOf("单笔限额不能为负")
        if (a.seckillType !in SECKILL_TYPES) e["seckill_type"] = listOf("只支持 ${SECKILL_TYPES.joinToString("/")}")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "秒杀场次配置不合法", e)
    }

    // ===================== SK-A2 / SK-A3 / SK-A5 =====================

    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = filters(companyId, raw)
        return PageHelper.paginate(
            pq,
            count = { acts.count(f) },
            query = { offset, limit -> acts.page(f, offset, limit) },
            map = { rows ->
                val counts = if (rows.isEmpty()) emptyMap() else
                    goods.countBatch(rows.map { it.seckillId }).associate { it.seckillId to it.cnt }
                rows.map { row(it, (counts[it.seckillId] ?: 0L).toInt()) }
            },
        )
    }

    /** SK-A3 详情 + 商品行（含 `hold_store`：后台要能看见"被未付款单占着多少"） */
    fun detail(companyId: Long, seckillId: Long): Map<String, Any?> {
        val a = act(seckillId, companyId)
        val rows = goods.listBySeckill(seckillId)
        return LinkedHashMap(row(a, rows.size)).apply { put("items", rows.map { goodsRow(it, true) }) }
    }

    fun status(companyId: Long, seckillId: Long, disabled: Boolean): Map<String, Any?> {
        val a = act(seckillId, companyId)
        acts.updateDisabled(a.seckillId, companyId, disabled, TimeSec.now())
        return detail(companyId, seckillId)
    }

    // ===================== SK-A6 商品行 upsert =====================

    /**
     * 圈品并定价。`items[]` 是**全量覆盖语义里的增量例外**：
     * 传进来的行只新增或修改，**不会被删除**（删除 = 把已下单的行退回去，那是停用该行该做的事）。
     */
    fun saveGoods(companyId: Long, seckillId: Long, form: SeckillGoodsForm): Map<String, Any?> {
        val a = act(seckillId, companyId)
        val rows = form.items.orEmpty()
        if (rows.isEmpty()) throw BizException(ErrorCode.VALIDATION, "items 不能为空", mapOf("items" to listOf("至少要配一个秒杀商品")))
        val errs = linkedMapOf<String, List<String>>()
        rows.forEachIndexed { i, r ->
            if ((r.itemId ?: 0) <= 0) errs["items[$i].item_id"] = listOf("商品 id 非法")
            if ((r.activityPrice ?: 0) <= 0) errs["items[$i].activity_price"] = listOf("活动价必须大于 0")
            if ((r.limitNum ?: 0) < 0) errs["items[$i].limit_num"] = listOf("限购件数不能为负")
        }
        if (errs.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "秒杀商品不合法", errs)
        val ids = rows.mapNotNull { it.itemId }.filter { it > 0 }
        if (ids.distinct().size != ids.size) throw BizException(ErrorCode.VALIDATION, "items 有重复商品", mapOf("items" to listOf("同一商品只能配一行")))

        val now = TimeSec.now()
        db.transactional {
            rows.forEach { r ->
                val existed = goods.find(seckillId, r.itemId!!)
                if (existed == null) {
                    val item = items.findById(r.itemId!!)?.takeIf { it.companyId == companyId }
                        ?: throw BizException(ErrorCode.VALIDATION, "商品不存在", mapOf("item_id" to listOf("${r.itemId} 不属于本租户")))
                    val store = r.activityStore ?: 0
                    if (store < 0) throw BizException(ErrorCode.VALIDATION, "activity_store 不能为负", mapOf("activity_store" to listOf("不能为负")))
                    goods.insert(
                        SeckillGoodsDO().apply {
                            this.seckillId = seckillId
                            itemId = item.itemId
                            itemTitle = item.itemName
                            pic = jsonFirst(item.pics)
                            activityPrice = r.activityPrice!!
                            activityStore = store
                            limitNum = r.limitNum ?: 0
                            isShow = r.isShow ?: true
                            disabled = false
                        },
                    )
                } else {
                    val add = r.activityStore ?: 0
                    if (add < 0) throw BizException(ErrorCode.BIZ, "秒杀库存只能加不能减，请改用停用该行")
                    existed.activityPrice = r.activityPrice!!
                    existed.limitNum = r.limitNum ?: 0
                    existed.isShow = r.isShow ?: true
                    existed.itemTitle = r.itemName?.takeIf { it.isNotBlank() } ?: existed.itemTitle
                    goods.update(existed)
                    // 加量走 CAS 语句（`#{num} > 0` 在 SQL 里再守一次），与"只允许加"这条口径双保险
                    if (add > 0 && goods.addStore(existed.id, add) == 0) {
                        throw BizException(ErrorCode.BIZ, "秒杀库存加不上，请刷新后重试")
                    }
                }
            }
            Unit
        }
        return linkedMapOf("seckill_id" to a.seckillId, "activity_start_time" to a.activityStartTime, "activity_end_time" to a.activityEndTime,
            "items" to goods.listBySeckill(seckillId).map { goodsRow(it, true) })
    }

    // ===================== SK-A7 报表 =====================

    fun report(companyId: Long, seckillId: Long): Map<String, Any?> {
        val a = act(seckillId, companyId)
        val rows = goods.report(seckillId)
        val sold = rows.sumOf { it.salesStore }
        val hold = rows.sumOf { it.holdStore }
        return linkedMapOf(
            "seckill_id" to a.seckillId,
            "activity_name" to a.activityName,
            "activity_start_time" to a.activityStartTime,
            "activity_end_time" to a.activityEndTime,
            "date_status" to dateStatus(a, TimeSec.now()),
            "item_count" to rows.size,
            "total_store" to rows.sumOf { it.activityStore },
            "sales_store" to sold,
            "hold_store" to hold,
            "available_store" to rows.sumOf { (it.activityStore - it.salesStore - it.holdStore).coerceAtLeast(0L) },
            // 已成交额按"活动价 × 已售"计（分）：占用中的钱还没到账，不算业绩
            "sales_fee" to rows.sumOf { it.activityPrice * it.salesStore },
            "hold_fee" to rows.sumOf { it.activityPrice * it.holdStore },
            "items" to rows.map { goodsRow(it, true) },
        )
    }

    // ===================== SK-F1 / SK-F2 会员面 =====================

    /**
     * SK-F1 场次列表。前台看得到未开始与已结束场次（秒杀页的 tab 就靠 `date_status` 分），
     * 但**停用的场次不给**：那是运营收回入口，不是给用户看"今天本来有场"。
     */
    fun frontActivities(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "disabled" to false,
            "dateStatus" to raw["date_status"]?.takeIf { it.isNotBlank() },
            "now" to TimeSec.now(),
        )
        return PageHelper.paginate(
            pq,
            count = { acts.count(f) },
            query = { offset, limit -> acts.page(f, offset, limit) },
            map = { rows ->
                val counts = if (rows.isEmpty()) emptyMap() else
                    goods.countBatch(rows.map { it.seckillId }).associate { it.seckillId to it.cnt }
                rows.map { a ->
                    linkedMapOf(
                        "seckill_id" to a.seckillId,
                        "activity_name" to a.activityName,
                        "ad_pic" to a.adPic,
                        "activity_start_time" to a.activityStartTime,
                        "activity_end_time" to a.activityEndTime,
                        "date_status" to dateStatus(a, TimeSec.now()),
                        "validity_period" to a.validityPeriod,
                        "items_count" to (counts[a.seckillId] ?: 0L).toInt(),
                    )
                }
            },
        )
    }

    /**
     * SK-F2 场次商品（会员面）。
     *
     * `bought_by_me` 走 [SeckillGoodsMapper.boughtByUser]：含未付款在途单，
     * 所以限购 2 件的用户下过两笔待付款单后，前台这里就显示"还能买 0 件"——
     * 与服务端下单时的判据同一个数，不会出现"页面说能买、提交被拒"。
     */
    fun frontItems(companyId: Long, seckillId: Long, userId: Long?): List<Map<String, Any?>> {
        val a = act(seckillId, companyId)
        if (a.disabled) throw BizException(ErrorCode.NOT_FOUND, "场次不存在")
        val now = TimeSec.now()
        return goods.listBySeckill(seckillId).filter { it.isShow && !it.disabled }.map { g ->
            val item = items.findById(g.itemId)
            val bought = if (userId != null && userId > 0) goods.boughtByUser(companyId, userId, seckillId, g.itemId) else 0L
            val remain = if (g.limitNum <= 0) Long.MAX_VALUE else (g.limitNum - bought).coerceAtLeast(0L)
            linkedMapOf<String, Any?>(
                "item_id" to g.itemId,
                "item_title" to (item?.itemName ?: g.itemTitle),
                "pic" to (g.pic ?: jsonFirst(item?.pics)),
                "activity_price" to g.activityPrice,
                "origin_price" to (item?.price ?: g.activityPrice),
                "market_price" to (item?.marketPrice ?: 0L),
                "sales_store" to g.salesStore,
                "available" to (g.activityStore - g.salesStore - g.holdStore).coerceAtLeast(0L),
                "limit_num" to g.limitNum,
                "bought_by_me" to bought,
                "remain_for_me" to if (remain == Long.MAX_VALUE) 0L else remain,
                "is_show" to g.isShow,
                "item_status" to (item?.approveStatus ?: "off"),
                "date_status" to dateStatus(a, now),
                "activity_type" to OrderClass.SECKILL,
                "activity_id" to seckillId,
            )
        }
    }

    /**
     * MK-F3 用：单商品此刻的秒杀价（商详页角标）。
     *
     * 只认**在窗、未停用、该行可见**的场次；同商品同时挂在两个场次时取**价低的那条** ——
     * 角标要说的是"现在买最低能到多少"，按场次建表顺序取会让用户看到一个更贵的价。
     * 命中口径与算价链（§14.5 order 15）完全一致：这里显示的价，结算时一定拿得到。
     */
    fun itemOffer(
        companyId: Long,
        itemId: Long,
        userId: Long? = null,
    ): Map<String, Any?>? {
        val now = TimeSec.now()
        val running = acts.listRunning(companyId, now).filter { !it.disabled }
        if (running.isEmpty()) return null
        val best = goods.listOffer(running.map { it.seckillId }, listOf(itemId))
            .filter { it.itemId == itemId && it.isShow && !it.disabled }
            .minByOrNull { it.activityPrice }
            ?: return null
        val a = running.first { it.seckillId == best.seckillId }
        val item = items.findById(itemId)
        return linkedMapOf(
            "seckill_id" to a.seckillId,
            "activity_name" to a.activityName,
            "item_id" to itemId,
            "activity_price" to best.activityPrice,
            "origin_price" to (item?.price ?: best.activityPrice),
            "available" to (best.activityStore - best.salesStore - best.holdStore).coerceAtLeast(0L),
            "limit_num" to best.limitNum,
            "bought_by_me" to if (userId != null && userId > 0) goods.boughtByUser(companyId, userId, a.seckillId, itemId) else 0L,
            "activity_start_time" to a.activityStartTime,
            "activity_end_time" to a.activityEndTime,
            "validity_period" to a.validityPeriod,
            "activity_type" to OrderClass.SECKILL,
            "activity_id" to a.seckillId,
        )
    }

    // ===================== 出参装配 =====================

    private fun row(a: SeckillActivityDO, itemCount: Int): LinkedHashMap<String, Any?> = linkedMapOf(
        "seckill_id" to a.seckillId,
        "company_id" to a.companyId,
        "activity_name" to a.activityName,
        "ad_pic" to a.adPic,
        "activity_start_time" to a.activityStartTime,
        "activity_end_time" to a.activityEndTime,
        "release_time" to a.releaseTime,
        "distributor_id" to a.distributorId,
        // 出参双形态：`activity_type` 是源系统的整数码（1/2），`seckill_type` 是本项目列值
        "activity_type" to seckillTypeCode(a.seckillType),
        "seckill_type" to a.seckillType,
        "seckill_type_text" to seckillTypeText(a.seckillType),
        "limit_money" to a.limitMoney,
        "validity_period" to a.validityPeriod,
        "disabled" to a.disabled,
        "date_status" to dateStatus(a, TimeSec.now()),
        "item_count" to itemCount,
        "created" to a.created,
        "updated" to a.updated,
    )

    private fun goodsRow(g: SeckillGoodsDO, withHold: Boolean): LinkedHashMap<String, Any?> {
        val base = linkedMapOf<String, Any?>(
            "id" to g.id,
            "seckill_id" to g.seckillId,
            "item_id" to g.itemId,
            "item_title" to g.itemTitle,
            "pic" to g.pic,
            "activity_price" to g.activityPrice,
            "activity_store" to g.activityStore,
            "sales_store" to g.salesStore,
            "limit_num" to g.limitNum,
            "is_show" to g.isShow,
            "disabled" to g.disabled,
            "available" to (g.activityStore - g.salesStore - g.holdStore).coerceAtLeast(0L),
        )
        if (withHold) base["hold_store"] = g.holdStore
        return base
    }

    private fun filters(companyId: Long, raw: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "companyId" to companyId,
        "disabled" to raw["disabled"]?.let { it == "true" || it == "1" },
        "dateStatus" to raw["date_status"]?.takeIf { it.isNotBlank() },
        "now" to TimeSec.now(),
    )

    private fun dateStatus(a: SeckillActivityDO, now: Long): String = when {
        a.activityStartTime > now -> "pending"
        a.activityEndTime < now -> "end"
        else -> "doing"
    }

    /** 源系统整数码 → 列值：只认 1/2，其余留空交给 [validate] 报"只支持 …"，不能静默降级成普通秒杀 */
    private fun seckillTypeOf(code: Int): String = when (code) {
        0, 1 -> SeckillType.NORMAL
        2 -> SeckillType.LIMITED_TIME_SALE
        else -> ""
    }

    private fun seckillTypeCode(type: String): Int = if (type == SeckillType.LIMITED_TIME_SALE) 2 else 1

    private fun seckillTypeText(type: String): String =
        if (type == SeckillType.LIMITED_TIME_SALE) "限时抢购" else "普通秒杀"

    private fun jsonFirst(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.trim().removePrefix("[").removeSuffix("]").trim().trim('"').takeIf { it.isNotBlank() }
    }

    private fun act(seckillId: Long, companyId: Long): SeckillActivityDO =
        acts.findById(seckillId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "秒杀场次不存在")

    private fun pageQuery(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    companion object {
        /** 支付时限上限（分钟）：比最长的一批订单超时配置还大的"秒杀保留"没有意义 */
        private const val MINUTE_CAP = 1440
        private val SECKILL_TYPES = setOf(SeckillType.NORMAL, SeckillType.LIMITED_TIME_SALE)
    }
}
