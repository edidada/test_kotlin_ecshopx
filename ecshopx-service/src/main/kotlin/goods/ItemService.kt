package com.ecshopx.kotlin.service.goods

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.AttributesMapper
import com.ecshopx.kotlin.dao.goods.BarcodeMapper
import com.ecshopx.kotlin.dao.goods.GoodsRelCatDO
import com.ecshopx.kotlin.dao.goods.GoodsRelCatsMapper
import com.ecshopx.kotlin.dao.goods.ItemAttrRelView
import com.ecshopx.kotlin.dao.goods.ItemBarcodeDO
import com.ecshopx.kotlin.dao.goods.ItemDO
import com.ecshopx.kotlin.dao.goods.ItemRelAttrDO
import com.ecshopx.kotlin.dao.goods.ItemRelTagDO
import com.ecshopx.kotlin.dao.goods.ItemTagDO
import com.ecshopx.kotlin.dao.goods.ItemsCategoryMapper
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.goods.RelAttributesMapper
import com.ecshopx.kotlin.dao.goods.RelTypeMapper
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.goods.TagsMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import com.ecshopx.kotlin.service.openapi.EventPublisher
import com.ecshopx.kotlin.service.tx.Db
import com.ecshopx.kotlin.service.webhook.WebhookTopics

/**
 * 商品域服务（GOODS-1..8）。
 * 读：ItemQuery 归一化 → PageHelper.paginate（count+data 双语句）→ [enrich] 批量回捞防 N+1。
 * 写：Db.transactional 包住主行 + 多规格子行 + 5 张关联表。
 */
@Service
class ItemService(
    private val items: ItemsMapper,
    private val relCats: GoodsRelCatsMapper,
    private val relAttrs: RelAttributesMapper,
    private val attributes: AttributesMapper,
    private val barcodes: BarcodeMapper,
    private val tags: TagsMapper,
    private val relTypes: RelTypeMapper,
    private val categoryMapper: ItemsCategoryMapper,
    private val categories: CategoryService,
    private val sysConfig: SysConfigMapper,
    private val mapper: ObjectMapper,
    private val events: EventPublisher,
    private val db: Db,
) {

    // ===== 读 =====

    /** GOODS-1 / GOODS-7 列表：`{total_count, warning_store, list[]}` */
    fun list(raw: Map<String, String?>, companyId: Long, front: Boolean): Map<String, Any?> {
        val (pq, f) = ItemQuery.toFilter(raw, companyId, front) { categories.expandSelf(it, companyId) }
        val skuRow = raw["is_sku"] == "true"
        val page = PageHelper.paginate(
            pq,
            { items.countItems(f) },
            { offset, limit -> items.listItems(f, offset, limit) },
        ) { rows -> rowList(rows, companyId, skuRow) }
        return linkedMapOf(
            "total_count" to page.total_count,
            "warning_store" to warningStoreGlobal(companyId),
            "list" to page.list,
        )
    }

    /** GOODS-6 SKU 列表：主行 + 子行全部展平，每行带 item_spec */
    fun skuList(raw: Map<String, String?>, companyId: Long): Map<String, Any?> {
        val (pq, f) = ItemQuery.toFilter(raw + mapOf("is_sku" to "true"), companyId, front = false) {
            categories.expandSelf(it, companyId)
        }
        f.remove("defaultOnly")
        val page = PageHelper.paginate(
            pq,
            { items.countItems(f) },
            { offset, limit -> items.listItems(f, offset, limit) },
        ) { rows -> rowList(rows, companyId, skuRow = true) }
        return linkedMapOf("total_count" to page.total_count, "warning_store" to warningStoreGlobal(companyId), "list" to page.list)
    }

    /** GOODS-2 后台详情：商品行 + intro + spec_items 子行 */
    fun adminDetail(itemId: Long): Map<String, Any?> {
        val it = items.findById(itemId) ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        val row = GoodsViews.row(mapper, it, enrich(listOf(it)), skuRow = true)
        row["intro"] = it.intro
        return row
    }

    /** GOODS-8 前台详情聚合（对齐 PHP V2 Action 出参） */
    fun frontDetail(itemId: Long): Map<String, Any?> {
        val it = items.findOnsaleById(itemId) ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在或已下架")
        val e = enrich(listOf(it))
        val row = GoodsViews.row(mapper, it, e, skuRow = true)
        row["intro"] = it.intro
        row["spec_items"] = GoodsViews.specItemsFront(mapper, e.childrenByParent[it.itemId].orEmpty(), e.attrsByItem)
        // 规格轴取自主行 + 全部子行的 spec 属性（多规格主行自身不挂规格）
        val allAttrs = (e.attrsByItem[it.itemId].orEmpty() +
            e.childrenByParent[it.itemId].orEmpty().flatMap { c -> e.attrsByItem[c.itemId].orEmpty() })
            .distinctBy { a -> attrKey(a) }
        row["item_spec_list"] = specList(allAttrs)
        row["item_params_list"] = GoodsViews.paramsList(allAttrs)
        row["member_price"] = it.price                          // 会员等级折扣属 M0-8，先按售价回填
        row["distributor_info"] = emptyMap<String, Any?>()      // 分销商档案属 M1
        row["cur"] = cur(it.companyId)
        row["tdk_data"] = tdk(it.companyId, it)
        row["recommend_items"] = recommend(it)
        row["tagList"] = e.tagsByItem[it.itemId].orEmpty().filter { t -> t.frontShow == 1 }.map { t ->
            linkedMapOf<String, Any?>("tag_id" to t.tagId, "tag_name" to t.tagName, "tag_color" to t.tagColor, "font_color" to t.fontColor)
        }
        return row
    }

    // ===== 写 =====

    /** GOODS-3 新增：主行（+多规格子行）与关联表同事务落库，返回主行 item_id */
    fun create(companyId: Long, form: ItemForm): Long {
        validate(form)
        val specs = form.specItems.orEmpty()
        val multi = specs.isNotEmpty()
        return db.transactional {
            val main = form.toNew(companyId, isDefault = true, defaultItemId = 0, goodsId = 0, nospec = !multi)
            items.insert(main)
            // goods_id / default_item_id 指向自身（与 PHP 及 seed 约定一致）
            main.goodsId = main.itemId
            main.defaultItemId = main.itemId
            items.stampIdentity(main.itemId, main.itemId, main.itemId)
            writeRelations(main.itemId, companyId, form)

            specs.forEach { sf ->
                val child = sf.toNewChild(main)
                items.insert(child)
                inheritCats(main.itemId, child.itemId, companyId)
                sf.itemSpec?.let { replaceAttrs(child.itemId, companyId, it, GoodsViews.ATTR_SPEC) }
                sf.barcode?.takeIf { it.isNotBlank() }?.let { code ->
                    barcodes.insert(barcodeRow(child.itemId, code, main.itemId, companyId))
                }
            }
            // 只发**主行**事件（子行是对端拿 `item.list` 自己展开的事）：
            // 一次建品发 N+1 条会把对端的接收队列刷成规格流水，而它要的只是"这个商品上架了"
            events.publish(companyId, WebhookTopics.GOODS_CREATED, goodsData(main))
            main.itemId
        }
    }

    /** GOODS-4 更新：读-改-写主行，关联表整体替换；spec_items 中带 item_id 视为改子行，缺省视为新增 */
    fun update(itemId: Long, form: ItemForm): Long {
        val cur = items.findById(itemId) ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        validate(form)
        db.transactional {
            form.applyTo(cur)
            items.updateFull(cur)
            writeRelations(itemId, cur.companyId, form)
            form.specItems?.forEach { sf ->
                val childId = sf.itemId?.let { id ->
                    items.findById(id)?.takeIf { it.defaultItemId == itemId }?.let { child ->
                        sf.applyToChild(child)
                        items.updateFull(child)
                        child.itemId
                    }
                } ?: run {
                    val child = sf.toNewChild(cur)
                    items.insert(child)
                    inheritCats(itemId, child.itemId, cur.companyId)
                    child.itemId
                }
                sf.itemSpec?.let { replaceAttrs(childId, cur.companyId, it, GoodsViews.ATTR_SPEC) }
            }
            events.publish(cur.companyId, WebhookTopics.GOODS_UPDATED, goodsData(cur))
            Unit
        }
        categories.invalidate(cur.companyId)
        return itemId
    }

    /** GOODS-5 删除 = 逻辑下架（主行 + 全部子行） */
    fun delete(itemId: Long): Int {
        val it = items.findById(itemId) ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        return db.transactional {
            var n = items.softDelete(itemId, TimeSec.now())
            if (it.isDefault) items.listChildren(listOf(itemId)).forEach { c -> n += items.softDelete(c.itemId, TimeSec.now()) }
            // 载荷只有两个键：删除事件里那些"当时的价格/库存"没有意义（对端拿到也只能当历史看），
            // 而这一条一旦带上全量字段，就会诱导对接方拿它去覆盖自己的本地档案
            events.publish(it.companyId, WebhookTopics.GOODS_DELETED, linkedMapOf("item_id" to itemId, "item_bn" to it.itemBn))
            n
        }
    }

    /** `goods.*` 三个 topic 的同一份载荷（字段清单见 `WebhookTopics`；三条事件必须同形，否则对端要为删除单独写一支解析） */
    private fun goodsData(o: ItemDO): Map<String, Any?> = linkedMapOf(
        "item_id" to o.itemId,
        "item_bn" to o.itemBn,
        "item_name" to o.itemName,
        "price" to o.price,
        "store" to o.store,
        "approve_status" to o.approveStatus,
    )

    // ===== 装配 =====

    private fun rowList(rows: List<ItemDO>, companyId: Long, skuRow: Boolean): List<Map<String, Any?>> {
        if (rows.isEmpty()) return emptyList()
        val e = enrich(rows)
        return rows.map { GoodsViews.row(mapper, it, e, skuRow = skuRow || !it.isDefault) }
    }

    /** 主行 + 子行 + 分类/属性/条码/标签/服务标签批量回捞 */
    private fun enrich(rows: List<ItemDO>): GoodsViews.Enrichment {
        if (rows.isEmpty()) return GoodsViews.Enrichment()
        val companyId = rows.first().companyId
        val parents = rows.filter { it.isDefault }.map { it.itemId }
        val children = if (parents.isEmpty()) emptyList() else items.listChildren(parents)
        val ids = (rows.map { it.itemId } + children.map { it.itemId }).distinct()
        val relCatRows = relCats.listByItems(ids)
        val catList = categoryMapper.listByCompany(companyId)
        val mainCatOf = mainCategoryResolver(catList)
        val tagById = tags.listByCompany(companyId).associateBy { it.tagId }
        val relTagRows = tags.relByItems(ids)
        return GoodsViews.Enrichment(
            childrenByParent = children.groupBy { it.defaultItemId },
            attrsByItem = relAttrs.listByItems(ids).groupBy { it.itemId },
            barcodesByItem = barcodes.listByItems(ids).groupBy({ it.itemId }, { it.barcode }),
            catsByItem = relCatRows.groupBy({ it.itemId }, { it.categoryId }),
            mainCat = relCatRows.groupBy { it.itemId }
                .mapNotNull { (itemId, rels) -> rels.firstNotNullOfOrNull { mainCatOf(it.categoryId) }?.let { itemId to it } }
                .toMap(),
            typeLabelsByItem = relTypes.listByItems(ids).groupBy { it.itemId },
            tagsByItem = relTagRows.mapNotNull { r -> tagById[r.tagId]?.let { r.itemId to it } }
                .groupBy({ it.first }, { it.second }),
        )
    }

    /**
     * 分类 → 所属"主分类"（PHP item_main_cat_id 语义：挂的是叶子分类，主分类取其在树上的祖先根）。
     * 返回 categoryId→mainCategoryId 映射，父链缺失（分类被删）时该 id 解析为 null。
     */
    private fun mainCategoryResolver(catList: List<com.ecshopx.kotlin.dao.goods.ItemCategoryDO>): (Long) -> Long? {
        val byId = catList.associateBy { it.categoryId }
        val memo = HashMap<Long, Long?>()
        fun resolve(id: Long, guard: Set<Long>): Long? {
            memo[id]?.let { return it }
            val c = byId[id] ?: return null
            if (guard.contains(id)) return null
            val main = if (c.isMainCategory) id else resolve(c.parentId, guard + id)
            memo[id] = main
            return main
        }
        return { id -> resolve(id, emptySet()) }
    }

    private fun inheritCats(fromItemId: Long, toItemId: Long, companyId: Long) {
        relCats.listByItem(fromItemId).forEach { rc ->
            relCats.insert(
                GoodsRelCatDO().apply {
                    itemId = toItemId
                    categoryId = rc.categoryId
                    this.companyId = companyId
                },
            )
        }
    }

    private fun barcodeRow(itemId: Long, code: String, defaultItemId: Long, companyId: Long): ItemBarcodeDO =
        ItemBarcodeDO().apply {
            this.itemId = itemId
            barcode = code
            this.defaultItemId = defaultItemId
            this.companyId = companyId
        }

    private fun writeRelations(itemId: Long, companyId: Long, form: ItemForm) {
        form.category?.let { replaceCats(itemId, companyId, it) }
        form.attrs?.let { replaceAttrs(itemId, companyId, it, forceType = null) }
        form.barcodes?.let { replaceBarcodes(itemId, companyId, it) }
        form.tagIds?.let { replaceTags(itemId, companyId, it) }
        form.typeLabels?.let { replaceLabels(itemId, it) }
    }

    private fun replaceCats(itemId: Long, companyId: Long, category: List<Long>) {
        relCats.deleteByItem(itemId)
        category.filter { it > 0 }.distinct().forEach { c ->
            relCats.insert(
                GoodsRelCatDO().apply {
                    this.itemId = itemId
                    categoryId = c
                    this.companyId = companyId
                },
            )
        }
    }

    private fun replaceAttrs(itemId: Long, companyId: Long, list: List<ItemForm.AttrForm>, forceType: String?) {
        relAttrs.deleteByItem(itemId)
        list.forEach { a ->
            val type = forceType ?: a.attributeType ?: attributeTypeOf(a.attributeId, companyId)
            relAttrs.insert(
                ItemRelAttrDO().apply {
                    this.itemId = itemId
                    attributeId = a.attributeId ?: 0L
                    attributeType = type
                    attributeValueId = a.attributeValueId
                    customAttributeValue = a.customAttributeValue
                    this.companyId = companyId
                },
            )
        }
    }

    private fun replaceBarcodes(itemId: Long, companyId: Long, list: List<String>) {
        barcodes.deleteByItem(itemId)
        list.filter { it.isNotBlank() }.distinct().forEach { code ->
            barcodes.insert(barcodeRow(itemId, code, itemId, companyId))
        }
    }

    private fun replaceTags(itemId: Long, companyId: Long, tagIds: List<Long>) {
        tags.deleteRelByItem(itemId)
        tagIds.filter { it > 0 }.distinct().forEach { t ->
            tags.insertRel(
                ItemRelTagDO().apply {
                    tagId = t
                    this.itemId = itemId
                    this.companyId = companyId
                },
            )
        }
    }

    private fun replaceLabels(itemId: Long, labels: List<ItemForm.LabelForm>) {
        relTypes.deleteByItem(itemId)
        labels.forEach { relTypes.insert(it.toRel(itemId)) }
    }

    private fun attributeTypeOf(attributeId: Long?, companyId: Long): String? =
        attributes.listByCompany(companyId).firstOrNull { it.attributeId == attributeId }?.attributeType

    /** 规格轴（前台选规格）：attribute_id → 该商品可选取值集合 */
    private fun specList(itemAttrs: List<ItemAttrRelView>): List<Map<String, Any?>> =
        itemAttrs.filter { it.attributeType == GoodsViews.ATTR_SPEC }
            .groupBy { it.attributeId }
            .toList()
            .sortedBy { it.first }
            .map { (attrId, rows) ->
                linkedMapOf<String, Any?>(
                    "attribute_id" to attrId,
                    "attribute_name" to rows.firstNotNullOfOrNull { it.attributeName },
                    "values" to rows.mapNotNull { it.attributeValue ?: it.customAttributeValue }.distinct()
                        .map { v -> linkedMapOf<String, Any?>("attribute_value" to v) },
                )
            }

    private fun attrKey(a: ItemAttrRelView): String = "${a.attributeId}-${a.attributeValueId}-${a.customAttributeValue}"

    private fun recommend(main: ItemDO): List<Map<String, Any?>> {
        val catIds = relCats.listByItem(main.itemId).map { it.categoryId }
        if (catIds.isEmpty()) return emptyList()
        val f = mapOf<String, Any?>(
            "companyId" to main.companyId, "approveStatus" to "onsale", "defaultOnly" to true,
            "excludeServices" to true, "categoryIds" to catIds,
        )
        return rowList(items.listItems(f, 0, 10).filter { it.itemId != main.itemId }.take(8), main.companyId, skuRow = false)
    }

    private fun warningStoreGlobal(companyId: Long): Long =
        sysConfig.getValue(companyId, CFG_WARNING_STORE)?.toLongOrNull()
            ?: sysConfig.getValue(0L, CFG_WARNING_STORE)?.toLongOrNull()
            ?: 0L

    private fun cur(companyId: Long): Map<String, Any?> = linkedMapOf(
        "currency" to (sysConfig.getValue(companyId, "site.currency") ?: "CNY"),
        "symbol" to (sysConfig.getValue(companyId, "site.currency_symbol") ?: "¥"),
        "rate" to (sysConfig.getValue(companyId, "site.currency_rate")?.toDoubleOrNull() ?: 1.0),
    )

    private fun tdk(companyId: Long, item: ItemDO): Map<String, Any?> = linkedMapOf(
        "title" to (sysConfig.getValue(companyId, "site.tdk.title") ?: item.itemName),
        "mate_description" to (sysConfig.getValue(companyId, "site.tdk.description") ?: item.brief ?: item.itemName),
        "mate_keywords" to (sysConfig.getValue(companyId, "site.tdk.keywords") ?: item.itemName),
    )

    private fun validate(form: ItemForm) {
        val errors = linkedMapOf<String, List<String>>()
        if (form.itemName.isNullOrBlank()) errors["item_name"] = listOf("商品名称不能为空")
        form.price?.let { if (it < 0) errors["price"] = listOf("价格不能为负") } ?: run { errors["price"] = listOf("价格不能为空") }
        form.store?.let { if (it < 0) errors["store"] = listOf("库存不能为负") } ?: run { errors["store"] = listOf("库存不能为空") }
        if (errors.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "参数校验失败", errors)
    }

    companion object {
        /** 全局预警库存阈值（sys_config；缺失取 0，行级 warning_store 仍各自生效） */
        const val CFG_WARNING_STORE = "item.warning_store"
    }
}
