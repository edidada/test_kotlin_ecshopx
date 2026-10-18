package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.dao.distribution.DistributorDO
import com.ecshopx.kotlin.dao.distribution.DistributorMapper
import com.ecshopx.kotlin.dao.distribution.DistributorValid
import com.ecshopx.kotlin.dao.distribution.ShopItemDO
import com.ecshopx.kotlin.dao.distribution.ShopItemMapper
import com.ecshopx.kotlin.dao.distribution.ShopItemRow
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import org.springframework.stereotype.Service

/**
 * M1-4 店铺商品（SI-A1..A4 后台 + SI-F1/F2 会员面，docs/04 §18.2/§18.1/§18.4）。
 *
 * 这一层只管"挂品行"的读写，**不参与算价**：算价侧唯一的取数口是 [priceRows]
 * （由 `CartService.buildContext` 调用），规则链继续不查库（M0-5 的属性测试因此照旧成立）。
 *
 * 双模的两条腿各自独立（§18.4）：
 *  - `price>0` 才覆盖总部现价，0/未挂品 = 跟随总部；
 *  - `is_total_store` 决定扣哪个池子，TRUE=总部池、FALSE=店铺池。
 * `is_can_sale=FALSE` 的行进 `invalid_cart`，`is_show=FALSE` 只影响前台列表 —— 两者都能让商品
 * 从店铺列表消失，但只有前者会拦下单，因为"暂时不展示"和"不许卖"是运营的两个不同动作。
 */
@Service
class ShopItemService(
    private val shopItems: ShopItemMapper,
    private val distributors: DistributorMapper,
    private val items: ItemsMapper,
) {

    // ===================== SI-A1 挂品（按 (店, 商品) upsert） =====================

    /**
     * 整表 upsert：已存在的行**只覆盖传进来的字段**，其余保留（与 SK-A6 的"全量覆盖圈品"不同，
     * 这里运营改价时常常只带 `price` 一栏，把 `is_show` 顺手清掉是最容易被误伤的）。
     */
    fun saveItems(
        companyId: Long,
        form: ShopItemsForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val shop = shop(companyId, form.distributorId ?: 0, scope)
        val lines = form.items.orEmpty()
        if (lines.isEmpty()) {
            throw BizException(ErrorCode.VALIDATION, "items 不能为空", mapOf("items" to listOf("至少挂一个商品")))
        }
        val errs = linkedMapOf<String, List<String>>()
        lines.forEachIndexed { i, l ->
            if ((l.itemId ?: 0) <= 0) errs["items[$i].item_id"] = listOf("商品 id 非法")
            if ((l.price ?: 0) < 0) errs["items[$i].price"] = listOf("价格不能为负")
            if ((l.store ?: 0) < 0) errs["items[$i].store"] = listOf("库存不能为负")
        }
        val ids = lines.mapNotNull { it.itemId }.filter { it > 0 }.distinct()
        if (ids.size != lines.size) errs["items"] = listOf("同一商品一次只能挂一行")
        if (errs.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "挂品数据不合法", errs)

        val itemById = items.listByIds(ids).associateBy { it.itemId }
        ids.forEach { id ->
            val it = itemById[id] ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在：$id")
            if (it.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "商品不存在：$id")
        }

        val saved = ids.map { id ->
            val line = lines.first { it.itemId == id }
            val item = itemById[id]!!
            val cur = shopItems.findByKey(shop.distributorId, id)
            val row = (cur ?: ShopItemDO().apply {
                distributorId = shop.distributorId
                itemId = id
                defaultItemId = item.defaultItemId
                goodsId = item.goodsId
                shopId = shop.distributorId
            }).apply { merge(line) }
            if (cur == null) shopItems.insert(row) else shopItems.updateConfig(row)
            row
        }
        return linkedMapOf(
            "distributor_id" to shop.distributorId,
            "items" to saved.map { savedRow(it) },
        )
    }

    /**
     * 表单 → 行的合并：null 一律保留旧值。
     *
     * `price=0` 不是笔误，它是"跟随总部现价"的显式写法（§18.4 第一行），
     * 所以这里不给 0 做任何"聪明"处理：给了就落 0，读的时候 0 就回落到 `items.price`。
     */
    private fun ShopItemDO.merge(line: ShopItemLine) {
        line.price?.let { price = it }
        line.store?.let { store = it }
        line.isShow?.let { isShow = it }
        line.isCanSale?.let { isCanSale = it }
        line.isTotalStore?.let { isTotalStore = it }
    }

    private fun savedRow(i: ShopItemDO): Map<String, Any?> = linkedMapOf(
        "id" to i.id,
        "distributor_id" to i.distributorId,
        "item_id" to i.itemId,
        "default_item_id" to i.defaultItemId,
        "goods_id" to i.goodsId,
        "shop_id" to i.shopId,
        "price" to i.price,
        "store" to i.store,
        "sales" to i.sales,
        "is_show" to i.isShow,
        "is_can_sale" to i.isCanSale,
        "is_total_store" to i.isTotalStore,
    )

    // ===================== SI-A2 / SI-A3 / SI-A4 =====================

    /** SI-A2 店铺商品列表：行同时给店铺态与总部态，运营才看得出"这家店挂的是 59、总部是 89" */
    fun list(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): PageResult<Map<String, Any?>> {
        val shop = shop(companyId, (raw["distributor_id"] ?: "0").toLongOrNull() ?: 0, scope)
        val pq = pageQuery(raw)
        val f = linkedMapOf(
            "distributorId" to shop.distributorId,
            "show" to raw["is_show"]?.let { it == "true" || it == "1" },
            "canSale" to raw["is_can_sale"]?.let { it == "true" || it == "1" },
            "keyword" to raw["keyword"]?.takeIf { it.isNotBlank() },
        )
        return PageHelper.paginate(
            pq,
            count = { shopItems.count(f) },
            query = { offset, limit -> shopItems.page(f, offset, limit) },
            map = { rows -> rows.map { listRow(it) } },
        )
    }

    /** SI-A3 配置价格/库存/上下架（`store` 是绝对值） */
    fun updateConfig(
        companyId: Long,
        form: ShopItemConfigForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val shop = shop(companyId, form.distributorId ?: 0, scope)
        val itemId = form.itemId ?: 0
        if (itemId <= 0) throw BizException(ErrorCode.VALIDATION, "item_id 非法", mapOf("item_id" to listOf("必填")))
        val e = linkedMapOf<String, List<String>>()
        if ((form.price ?: 0) < 0) e["price"] = listOf("价格不能为负")
        if ((form.store ?: 0) < 0) e["store"] = listOf("库存不能为负")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "店铺商品配置不合法", e)
        val row = shopItems.findByKey(shop.distributorId, itemId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "该店铺未挂此商品", mapOf("item_id" to listOf("请先用 SI-A1 挂品")))
        val item = items.findById(itemId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        if (item.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        row.merge(
            ShopItemLine(
                itemId = itemId,
                price = form.price,
                store = form.store,
                isShow = form.isShow,
                isCanSale = form.isCanSale,
                isTotalStore = form.isTotalStore,
            ),
        )
        shopItems.updateConfig(row)
        return linkedMapOf("distributor_id" to shop.distributorId, "item" to savedRow(row))
    }

    /** SI-A4 取消挂品：只删关联行，不动 `items`，也不动订单历史快照 */
    fun delete(
        companyId: Long,
        form: ShopItemDeleteForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val shop = shop(companyId, form.distributorId ?: 0, scope)
        val ids = (form.itemIds ?: emptyList()).filter { it > 0 }.distinct()
        if (ids.isEmpty()) {
            throw BizException(ErrorCode.VALIDATION, "item_ids 非法", mapOf("item_ids" to listOf("请选择要取消挂品的商品")))
        }
        val deleted = shopItems.deleteByKeys(shop.distributorId, ids)
        return linkedMapOf("distributor_id" to shop.distributorId, "deleted" to deleted)
    }

    // ===================== SI-F1 / SI-F2 会员面 =====================

    /** SI-F1 店铺商品列表：只出「上架 + 可售 + 总部在架」的行，价与库存给店铺态 */
    fun frontList(
        companyId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val distributorId = (raw["distributor_id"] ?: "0").toLongOrNull() ?: 0
        if (distributorId <= 0) throw BizException(ErrorCode.VALIDATION, "distributor_id 非法", mapOf("distributor_id" to listOf("必填")))
        val pq = pageQuery(raw)
        val keyword = raw["keyword"]?.takeIf { it.isNotBlank() }
        return PageHelper.paginate(
            pq,
            count = { shopItems.countShopOnSale(companyId, distributorId, keyword) },
            query = { offset, limit -> shopItems.listShopOnSale(companyId, distributorId, keyword, offset, limit) },
            map = { rows -> rows.map { frontRow(it) } },
        )
    }

    /**
     * SI-F2 单行价格与库存：`?distributor_id=` 缺省=总部态。
     *
     * 商详页与"进店看价"都调它，判据与 [priceRows] 同一条（回落总部），
     * 于是页面上写的价和购物车里算出来的价不会分叉。
     */
    fun priceStore(
        companyId: Long,
        itemId: Long,
        distributorId: Long?,
    ): Map<String, Any?> {
        val item = items.findById(itemId)?.takeIf { it.companyId == companyId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        val shopRow = distributorId?.takeIf { it > 0 }?.let { shopItems.findByKey(it, itemId) }
        val price = shopRow?.price?.takeIf { it > 0 } ?: item.price
        val store = if (shopRow != null && !shopRow.isTotalStore) shopRow.store else item.store
        return linkedMapOf(
            "item_id" to itemId,
            "distributor_id" to (distributorId ?: 0),
            "price" to price,
            "store" to store,
            "is_show" to (shopRow?.isShow ?: true),
            "is_can_sale" to (shopRow?.isCanSale ?: true),
            "is_total_store" to (shopRow?.isTotalStore ?: true),
            "shop_price" to (shopRow?.price ?: 0),
            "shop_store" to (shopRow?.store ?: 0),
            "origin_price" to item.price,
        )
    }

    // ===================== 算价侧的取数口（§18.4） =====================

    /**
     * (店, 商品) → 挂品行。**算价链唯一的店铺取数出口**，由 `CartService.buildContext` 批量调用。
     *
     * 返回的 Map 里只有"真挂过品"的组合：没挂过品的商品不在 Map 里，调用方按**回落总部态**处理
     * （见 §19 记录的实现期偏差：源系统把未挂品判成不可售，本项目演示形态的 seed 全靠这条回落才跑得动）。
     */
    fun priceRows(
        shopIds: List<Long>,
        itemIds: List<Long>,
    ): Map<Pair<Long, Long>, ShopItemDO> {
        val shops = shopIds.filter { it > 0 }
        val items = itemIds.filter { it > 0 }
        if (shops.isEmpty() || items.isEmpty()) return emptyMap()
        return shopItems.listForPrice(shops.distinct(), items.distinct())
            .associateBy { it.distributorId to it.itemId }
    }

    // ===================== 装配与闸门 =====================

    private fun listRow(r: ShopItemRow): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        "distributor_id" to r.distributorId,
        "item_id" to r.itemId,
        "default_item_id" to r.defaultItemId,
        "goods_id" to r.goodsId,
        "item_name" to r.itemName,
        "item_bn" to r.itemBn,
        "pics" to r.pics,
        // 两组价/库存并排给：左列是这家店的实况，右列是总部现值
        "shop_price" to r.shopPrice,
        "shop_store" to r.shopStore,
        "shop_sales" to r.shopSales,
        "price" to r.price,
        "store" to r.store,
        "is_show" to r.isShow,
        "is_can_sale" to r.isCanSale,
        "is_total_store" to r.isTotalStore,
        "effect_price" to if (r.shopPrice > 0) r.shopPrice else r.price,
        "effect_store" to if (!r.isTotalStore) r.shopStore else r.store,
        "approve_status" to r.approveStatus,
        "templates_id" to r.templatesId,
    )

    private fun frontRow(r: ShopItemRow): Map<String, Any?> = linkedMapOf(
        "item_id" to r.itemId,
        "goods_id" to r.goodsId,
        "default_item_id" to r.defaultItemId,
        "distributor_id" to r.distributorId,
        "item_name" to r.itemName,
        "item_bn" to r.itemBn,
        "pics" to r.pics,
        // 前台只看得到这家店的价与池子，总部现值不在会员面出参里
        "price" to (if (r.shopPrice > 0) r.shopPrice else r.price),
        "store" to (if (!r.isTotalStore) r.shopStore else r.store),
        "sales" to r.shopSales,
        "is_show" to r.isShow,
        "is_can_sale" to r.isCanSale,
        "is_total_store" to r.isTotalStore,
        "templates_id" to r.templatesId,
    )

    /** 店铺存在 + 属本租户 + 在数据权限内 + 没关店，四道一起过 */
    private fun shop(
        companyId: Long,
        distributorId: Long,
        scope: DistributorScope,
    ): DistributorDO {
        scope.require(distributorId)
        val d = distributors.findById(distributorId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
        if (d.isValid == DistributorValid.CLOSED) throw BizException(ErrorCode.BIZ, "该店铺已关店")
        return d
    }

    private fun pageQuery(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
}
