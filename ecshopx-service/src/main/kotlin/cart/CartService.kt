package com.ecshopx.kotlin.service.cart

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MemberGradeDO
import com.ecshopx.kotlin.dao.auth.MembersGradeMapper
import com.ecshopx.kotlin.dao.auth.MembersMapper
import com.ecshopx.kotlin.dao.cart.CartItemView
import com.ecshopx.kotlin.dao.cart.CartItemsMapper
import com.ecshopx.kotlin.dao.cart.OrdersCartDO
import com.ecshopx.kotlin.dao.cart.OrdersCartMapper
import com.ecshopx.kotlin.dao.goods.RelAttributesMapper
import com.ecshopx.kotlin.dao.goods.ShippingTemplatesMapper
import com.ecshopx.kotlin.dao.tenant.CompanyMapper
import com.ecshopx.kotlin.dao.tenant.DistributorShopMapper
import com.ecshopx.kotlin.dao.tenant.ShopProfileDO
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * 购物车 + 算价装配（CART-1..6、ORDER-1）。
 *
 * 只有一条算价链路：车内行/fastbuy 行 → [Pending] → [quote] → MarketingEngine → 出参。
 * CART-2 与 ORDER-1 都调 [quote]，M0-6 下单同样只调 [quote]，
 * 「预览价==下单价」由此变成结构事实而不是约定。
 */
@Service
class CartService(
    private val carts: OrdersCartMapper,
    private val cartItems: CartItemsMapper,
    private val members: MembersMapper,
    private val grades: MembersGradeMapper,
    private val companies: CompanyMapper,
    private val shops: DistributorShopMapper,
    private val templates: ShippingTemplatesMapper,
    private val relAttrs: RelAttributesMapper,
    private val engine: MarketingEngine,
    private val db: Db,
    private val mapper: ObjectMapper,
) {

    /** 算价前的行：车内持久行与 fastbuy 临时行统一成这个形态 */
    class Pending(
        val cartId: Long,
        val userId: Long,
        val companyId: Long,
        val itemId: Long,
        val itemName: String,
        val shopId: Long,
        val shopType: String,
        val activityType: String,
        val activityId: Long,
        val itemsId: String?,
        val snapshotPrice: Long,
        val num: Long,
        val checked: Boolean,
        val plusBuy: Boolean,
        val item: CartItemView?,
    ) {
        val valid: Boolean get() = item != null && item.approveStatus == "onsale" && item.store > 0
    }

    /** 一次算价的全部结果：CART-2、ORDER-1、M0-6 下单共用 */
    class Quote(
        val ctx: PriceContext,
        val pendings: List<Pending>,
        val valid: List<Pending>,
        val invalid: List<Pending>,
        val grade: MemberGradeDO?,
        val specDescById: Map<Long, String>,
        /** shop_id → 店铺档案（shop_id=0 用租户名当自营店名） */
        val shopById: Map<Long, ShopProfileDO>,
        val companyName: String,
    ) {
        /** fastbuy 行 cartId=0，故查不到持久行时回落到临时行键 */
        val pendingByKey: Map<Pair<Long, Long>, Pending> =
            pendings.associateBy { it.cartId to it.itemId }
        val itemFee: Long get() = ctx.totalItemFee()
        val freightFee: Long get() = ctx.totalFreight
        val discountFee: Long get() = ctx.totalLineDiscount
        val memberDiscount: Long get() = ctx.groups.sumOf { it.memberDiscount }
        val totalFee: Long get() = ctx.totalPayFee()
        val totalItemNum: Long get() = ctx.checkedLines.sumOf { it.num }
        val groupCount: Int get() = ctx.groups.count { it.cartTotalCount > 0 }
        /** 参与算价的行（勾选与未勾选都在，未勾选金额不计入合计） */
        val pricedLines: List<PriceLine> get() = ctx.lines
        val checkStore: Boolean get() = valid.any { it.checked && (it.item?.store ?: 0) < it.num }

        fun profile(shopId: Long): ShopProfileDO =
            shopById[shopId] ?: ShopProfileDO().apply {
                distributorId = shopId
                name = if (shopId == 0L) companyName else ""
                isDelivery = true
            }
    }

    // ===== 读 =====

    fun view(userId: Long, companyId: Long, shopId: Long? = null): Map<String, Any?> =
        cartView(quote(userId, companyId, fromCart(userId, companyId, shopId), null, false))

    fun viewFastbuy(userId: Long, companyId: Long, entries: List<CartItemNum>): Map<String, Any?> =
        cartView(quote(userId, companyId, fromEntries(entries, userId, companyId), null, false))

    fun preview(userId: Long, companyId: Long, form: PreviewForm): Map<String, Any?> =
        orderPreview(
            quote(
                userId, companyId,
                pendings = if (form.cartType == "fastbuy" || form.cartType == "offline")
                    fromEntries(form.entries(), userId, companyId)
                else fromCart(userId, companyId, firstNonZero(form.shopId, form.distributorId)),
                couponCode = form.couponCode,
                usePoint = form.usePoint == true,
            ),
        )

    /** 车内勾选行 → Quote；M0-6 下单的取价入口 */
    fun quoteFromCart(userId: Long, companyId: Long, shopId: Long?, couponCode: String?, usePoint: Boolean): Quote =
        quote(userId, companyId, fromCart(userId, companyId, shopId).filter { it.checked }, couponCode, usePoint)

    /** fastbuy/直购 → Quote */
    fun quoteFromEntries(userId: Long, companyId: Long, entries: List<CartItemNum>, couponCode: String?, usePoint: Boolean): Quote =
        quote(userId, companyId, fromEntries(entries, userId, companyId), couponCode, usePoint)

    private fun quote(userId: Long, companyId: Long, pendings: List<Pending>, couponCode: String?, usePoint: Boolean): Quote {
        val valid = pendings.filter { it.valid }
        val invalid = pendings.filter { !it.valid }
        val grade = members.findById(userId)?.gradeId?.let { grades.findById(it) }
        val ctx = buildContext(companyId, userId, grade, valid, couponCode, usePoint)
        engine.price(ctx)
        val ids = pendings.map { it.itemId }.distinct()
        return Quote(
            ctx = ctx, pendings = pendings,
            valid = valid, invalid = invalid, grade = grade,
            specDescById = specDesc(ids),
            shopById = shopProfiles(companyId, pendings.map { it.shopId }.distinct()),
            companyName = companies.findById(companyId)?.companyName ?: "",
        )
    }

    /** 唯一建 ctx 的地方：单价取商品主表现价，快照价只用于 is_last_price */
    private fun buildContext(
        companyId: Long,
        userId: Long,
        grade: MemberGradeDO?,
        valid: List<Pending>,
        couponCode: String?,
        usePoint: Boolean,
    ): PriceContext {
        val lines = valid.map { p ->
            val it = p.item!!
            PriceLine(
                cartId = p.cartId, itemId = p.itemId, itemName = it.itemName, shopId = p.shopId,
                shopType = p.shopType, activityType = p.activityType, activityId = p.activityId,
                itemType = it.itemType, itemCategory = it.itemCategory, type = it.type,
                goodsId = it.goodsId, parentId = it.defaultItemId, pics = it.pics, brief = it.brief,
                marketPrice = it.marketPrice, store = it.store, approveStatus = it.approveStatus,
                templatesId = it.templatesId, weight = it.weight, volume = it.volume,
                specDesc = null, snapshotPrice = p.snapshotPrice, price = it.price, num = p.num,
                checked = p.checked, plusBuy = p.plusBuy,
            )
        }
        return PriceContext(
            companyId = companyId, userId = userId,
            gradeName = grade?.gradeName ?: "普通会员",
            gradeDiscount = grade?.discount ?: 100,
            lines = lines, couponCode = couponCode, usePoint = usePoint,
            templates = freightTemplates(companyId),
        )
    }

    private fun fromCart(userId: Long, companyId: Long, shopId: Long?): List<Pending> {
        val rows = carts.listByUser(companyId, userId, shopId)
        if (rows.isEmpty()) return emptyList()
        val itemById = loadItems(rows.map { it.itemId })
        return rows.map {
            Pending(
                it.cartId, userId, companyId, it.itemId, it.itemName, it.shopId, it.shopType ?: "shop",
                it.activityType, it.activityId, it.itemsId, it.price, it.num, it.isChecked, it.isPlusBuy,
                itemById[it.itemId],
            )
        }
    }

    private fun fromEntries(entries: List<CartItemNum>, userId: Long, companyId: Long): List<Pending> {
        val list = entries.filter { (it.itemId ?: 0) > 0 }
        if (list.isEmpty()) return emptyList()
        val itemById = loadItems(list.map { it.itemId!! })
        return list.map { e ->
            val item = itemById[e.itemId!!]
            val shopId = item?.distributorId ?: 0
            Pending(
                0, userId, companyId, e.itemId!!, item?.itemName ?: "", shopId,
                if (shopId > 0) "distributor" else "shop", "normal", 0, null,
                item?.price ?: 0, e.num ?: 1, true, false, item,
            )
        }
    }

    private fun loadItems(ids: List<Long>): Map<Long, CartItemView> =
        if (ids.isEmpty()) emptyMap() else cartItems.listByIds(ids.distinct()).associateBy { it.itemId }

    // ===== 写（CART-1/3/4/5） =====

    fun add(userId: Long, companyId: Long, form: CartAddForm): Map<String, Any?> {
        val entries = form.entries().ifEmpty {
            throw BizException(ErrorCode.VALIDATION, "item_id 非法", mapOf("item_id" to listOf("请选择商品")))
        }
        entries.forEach { e ->
            if ((e.num ?: 0) < 0) throw BizException(ErrorCode.VALIDATION, "num 非法", mapOf("num" to listOf("数量必须大于 0")))
        }
        val itemById = loadItems(entries.mapNotNull { it.itemId })
        db.transactional {
            entries.forEach { e ->
                val itemId = e.itemId!!
                val item = itemById[itemId] ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
                val num = e.num ?: 1
                if (num <= 0) throw BizException(ErrorCode.VALIDATION, "num 非法", mapOf("num" to listOf("数量必须大于 0")))
                val draft = draft(userId, companyId, form, item, itemId, num)
                val cur = carts.findByKey(draft)
                if (cur == null) {
                    carts.insert(draft)
                } else {
                    draft.cartId = cur.cartId
                    draft.num = if (form.isAccumulate == true) cur.num + num else num
                    draft.isChecked = true
                    carts.refreshSnapshot(draft)
                }
            }
            Unit
        }
        return view(userId, companyId, firstNonZero(form.shopId, form.distributorId))
    }

    private fun draft(userId: Long, companyId: Long, form: CartAddForm, item: CartItemView, itemId: Long, num: Long): OrdersCartDO =
        OrdersCartDO().apply {
            this.companyId = companyId
            this.userId = userId
            shopId = firstNonZero(form.shopId, form.distributorId) ?: item.distributorId
            shopType = form.shopType ?: if (this.shopId > 0) "distributor" else "shop"
            activityType = form.activityType ?: "normal"
            activityId = form.activityId ?: 0
            marketingType = form.cartType
            itemType = item.itemType
            this.itemId = itemId
            itemName = item.itemName
            pics = item.pics
            itemsId = form.itemsId?.joinToString(",")
            this.num = num
            price = item.price
            point = item.point
            isChecked = true
            isPlusBuy = form.isPlusBuy == true
            promoterUserId = form.promoterUserId ?: 0
        }

    fun updateNum(userId: Long, companyId: Long, cartId: Long, num: Long): Map<String, Any?> {
        val row = carts.findById(cartId)?.takeIf { it.userId == userId && it.companyId == companyId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "购物车行不存在")
        if (num <= 0) throw BizException(ErrorCode.VALIDATION, "num 非法", mapOf("num" to listOf("数量必须大于 0")))
        carts.updateNum(row.cartId, num, TimeSec.now())
        return view(userId, companyId, null)
    }

    fun updateCheck(userId: Long, companyId: Long, form: CartCheckForm): Map<String, Any?> {
        val checked = form.isChecked
            ?: throw BizException(ErrorCode.VALIDATION, "is_checked 必填", mapOf("is_checked" to listOf("不能为空")))
        val ids = when {
            form.all == true -> carts.listByUser(companyId, userId, null).map { it.cartId }
            else -> (form.cartIds ?: form.cartId?.let { listOf(it) }) ?: emptyList()
        }.filter { it > 0 }
        if (ids.isEmpty()) throw BizException(ErrorCode.VALIDATION, "cart_ids 非法", mapOf("cart_ids" to listOf("请选择购物车行")))
        carts.updateCheckedBatch(companyId, userId, ids, checked, TimeSec.now())
        return view(userId, companyId, null)
    }

    /** CART-3 删除后回吐全量购物车（与 PHP 一致） */
    fun delete(userId: Long, companyId: Long, form: CartDelForm): Map<String, Any?> {
        val ids = (form.cartIds ?: form.cartId?.let { listOf(it) } ?: emptyList()).filter { it > 0 }
        if (ids.isEmpty()) throw BizException(ErrorCode.VALIDATION, "cart_ids 非法", mapOf("cart_ids" to listOf("请选择购物车行")))
        carts.deleteByIds(userId, ids)
        return view(userId, companyId, null)
    }

    fun count(userId: Long, companyId: Long): Map<String, Any?> {
        val st = carts.statByUser(companyId, userId)
        return linkedMapOf("count" to (st?.cnt ?: 0L), "num" to (st?.num ?: 0L))
    }

    /** 下单成功后清车（M0-6 用）；只删已下单的勾选行 */
    fun clear(userId: Long, cartIds: List<Long>): Int =
        if (cartIds.isEmpty()) 0 else carts.deleteByIds(userId, cartIds)

    // ===== 出参装配 =====

    private fun cartView(q: Quote): Map<String, Any?> {
        val valid = q.ctx.groups.map { g -> shopView(g, q) }
        val invalid = q.invalid.groupBy { it.shopId }.map { (shopId, ps) -> invalidShopView(shopId, ps, q) }
        return linkedMapOf(
            "valid_cart" to valid,
            "invalid_cart" to invalid,
            "is_check_store" to q.checkStore,
        )
    }

    /** 店铺分组外壳：档案取自 distribution_distributor（M0 只做展示列，分销业务属 M1） */
    private fun shopShell(shopId: Long, q: Quote): LinkedHashMap<String, Any?> {
        val sp = q.profile(shopId)
        return linkedMapOf(
            "shop_id" to shopId,
            "shop_name" to sp.name,
            "address" to (sp.address ?: ""),
            "mobile" to (sp.mobile ?: ""),
            "lat" to (sp.lat ?: ""),
            "lng" to (sp.lng ?: ""),
            // distribution_distributor 无营业时间列，先给空串保住字段
            "hour" to "",
            "is_ziti" to sp.isZiti,
            "is_delivery" to sp.isDelivery,
            "cart_total_price" to 0L,
            "item_fee" to 0L,
            "freight" to 0L,
            "discount_fee" to 0L,
            "total_fee" to 0L,
            "cart_total_num" to 0L,
            "cart_total_count" to 0,
            "member_discount" to 0L,
            "used_activity" to emptyList<Map<String, Any?>>(),
            "used_activity_ids" to emptyList<Any?>(),
            "gift_activity" to emptyList<Map<String, Any?>>(),
            "plus_buy" to emptyList<Map<String, Any?>>(),
            "list" to emptyList<Map<String, Any?>>(),
        )
    }

    private fun shopView(g: PriceGroup, q: Quote): LinkedHashMap<String, Any?> =
        shopShell(g.shopId, q).apply {
            this["cart_total_price"] = g.cartTotalPrice
            this["item_fee"] = g.itemFee
            this["freight"] = g.freight
            this["discount_fee"] = g.discountFee
            this["total_fee"] = g.totalFee
            this["cart_total_num"] = g.cartTotalNum
            this["cart_total_count"] = g.cartTotalCount
            this["member_discount"] = g.memberDiscount
            this["used_activity"] = g.usedActivity
            this["used_activity_ids"] = g.usedActivity.mapNotNull { it["id"] }
            this["gift_activity"] = g.giftActivity
            this["plus_buy"] = g.plusBuyList
            this["list"] = g.lines.map { lineView(it, q) }
        }

    private fun invalidShopView(shopId: Long, ps: List<Pending>, q: Quote): LinkedHashMap<String, Any?> =
        shopShell(shopId, q).apply { this["list"] = ps.map { pendingView(it, q) } }

    /** 失效行：字段与有效行同构，金额取商品现价但不参与算价 */
    private fun pendingView(p: Pending, q: Quote): Map<String, Any?> {
        val it = p.item
        return linkedMapOf(
            "cart_id" to p.cartId,
            "company_id" to p.companyId,
            "user_id" to p.userId,
            "shop_type" to p.shopType,
            "shop_id" to p.shopId,
            "activity_type" to p.activityType,
            "activity_id" to p.activityId,
            "item_type" to (it?.itemType ?: "normal"),
            "item_id" to p.itemId,
            "items_id" to splitIds(p.itemsId),
            "item_name" to (it?.itemName ?: p.itemName),
            "pics" to jsonList(it?.pics ?: p.item?.pics),
            "price" to (it?.price ?: 0L),
            "num" to p.num,
            "is_checked" to p.checked,
            "is_plus_buy" to p.plusBuy,
            "is_last_price" to (p.snapshotPrice != (it?.price ?: 0L)),
            "discount_fee" to 0L,
            "total_fee" to 0L,
            "store" to (it?.store ?: 0L),
            "market_price" to (it?.marketPrice ?: 0L),
            "brief" to it?.brief,
            "approve_status" to (it?.approveStatus ?: "off"),
            "item_spec_desc" to q.specDescById[p.itemId],
            "parent_id" to (it?.defaultItemId ?: 0L),
            "goods_id" to (it?.goodsId ?: 0L),
            "item_category" to it?.itemCategory,
            "type" to (it?.type ?: 0),
            "grade_name" to q.gradeName(),
            "member_discount" to 0L,
            "activity_info" to emptyList<Map<String, Any?>>(),
        )
    }

    private fun lineView(l: PriceLine, q: Quote): Map<String, Any?> {
        val p = q.pendingByKey[l.cartId to l.itemId] ?: q.pendingByKey[0L to l.itemId]
        return linkedMapOf(
            "cart_id" to l.cartId,
            "company_id" to q.ctx.companyId,
            "user_id" to q.ctx.userId,
            "shop_type" to l.shopType,
            "shop_id" to l.shopId,
            "activity_type" to l.activityType,
            "activity_id" to l.activityId,
            "item_type" to l.itemType,
            "item_id" to l.itemId,
            "items_id" to splitIds(p?.itemsId),
            "item_name" to l.itemName,
            "pics" to jsonList(l.pics),
            "price" to l.price,
            "num" to l.num,
            "is_checked" to l.checked,
            "is_plus_buy" to l.plusBuy,
            "is_last_price" to l.isLastPrice,
            "discount_fee" to l.discountFee,
            "total_fee" to l.totalFee,
            "store" to l.store,
            "market_price" to l.marketPrice,
            "brief" to l.brief,
            "approve_status" to l.approveStatus,
            "item_spec_desc" to q.specDescById[l.itemId],
            "parent_id" to l.parentId,
            "goods_id" to l.goodsId,
            "item_category" to l.itemCategory,
            "type" to l.type,
            "grade_name" to q.gradeName(),
            "member_discount" to l.memberDiscount,
            "activity_info" to l.activityInfo,
        )
    }

    private fun Quote.gradeName(): String = grade?.gradeName ?: "普通会员"

    private fun splitIds(raw: String?): List<String> = raw?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    /** 前端 shop_id / distributor_id 常有一个是 0 占位，取非 0 的那个 */
    private fun firstNonZero(vararg v: Long?): Long? = v.firstOrNull { it != null && it > 0 }

    private fun jsonList(raw: String?): List<String> =
        if (raw.isNullOrBlank()) emptyList() else runCatching {
            val n = mapper.readTree(raw)
            if (n.isArray) n.map { it.asText() } else listOf(raw)
        }.getOrDefault(raw.split(',').filter { it.isNotBlank() })

    /** 规格描述：与商品域同源（属性值以顿号连接） */
    private fun specDesc(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return relAttrs.listByItems(ids)
            .filter { it.attributeType == "spec" }
            .groupBy { it.itemId }
            .mapNotNull { (itemId, rows) ->
                val text = rows.joinToString("、") { r -> r.attributeValue ?: r.customAttributeValue ?: "" }
                if (text.isBlank()) null else itemId to text
            }.toMap()
    }

    /** 店铺档案（shop_id=0 的平台自营店在 Quote.profile 里回落到租户名） */
    private fun shopProfiles(companyId: Long, shopIds: List<Long>): Map<Long, ShopProfileDO> {
        val ids = shopIds.filter { it > 0 }
        if (ids.isEmpty()) return emptyMap()
        return shops.listByIds(companyId, ids.distinct()).associateBy { it.distributorId }
    }

    /** 运费模板：minprice/first_fee 均为元，落成分参与算价 */
    private fun freightTemplates(companyId: Long): Map<Long, FreightTemplate> =
        templates.listByCompany(companyId).associate { t ->
            t.templateId to FreightTemplate(
                templateId = t.templateId,
                isFree = t.isFree.equals("y", ignoreCase = true),
                freeOverFen = yuanToFen(t.minprice),
                valuation = t.valuation,
                firstFeeFen = firstFeeFen(t.feeConf),
            )
        }

    private fun yuanToFen(v: BigDecimal?): Long = (v ?: BigDecimal.ZERO).movePointRight(2).toLong()

    private fun firstFeeFen(feeConf: String?): Long {
        if (feeConf.isNullOrBlank()) return 0
        val node = runCatching { mapper.readTree(feeConf) }.getOrNull() ?: return 0
        return Math.round(node.path("first_fee").asDouble(0.0) * 100)
    }

    // ===== ORDER-1 =====

    private fun orderPreview(q: Quote): Map<String, Any?> {
        val ctx = q.ctx
        return linkedMapOf(
            "item_fee" to q.itemFee,
            "freight_fee" to q.freightFee,
            "discount_fee" to q.discountFee,
            "discount_info" to ctx.groups.flatMap { g -> g.usedActivity.map { linkedMapOf("type" to it["type"], "rule" to it["rule"], "discount_fee" to it["discount_fee"]) } },
            "coupon_discount" to 0L,
            "coupon_info" to emptyList<Map<String, Any?>>(),
            "member_discount" to q.memberDiscount,
            "goods_discount" to 0L,
            "point" to q.valid.sumOf { p -> (p.item?.point ?: 0L) * p.num },
            "point_use" to q.ctx.usePoint,
            "point_fee" to 0L,
            "get_points" to 0L,
            "bonus_points" to 0L,
            "user_point" to 0L,
            "max_point" to 0L,
            "limit_point" to 0L,
            "total_fee" to q.totalFee,
            "total_rebate" to 0L,
            "totalItemNum" to q.totalItemNum,
            "cost_fee" to 0L,
            "fee_type" to "",
            "fee_rate" to 0,
            "fee_symbol" to "",
            "auto_cancel_time" to 0L,
            "distributor_id" to (q.valid.firstOrNull()?.shopId ?: 0L),
            "pack" to false,
            "is_logistics" to true,
            "items" to ctx.lines.map { l ->
                linkedMapOf<String, Any?>(
                    "item_id" to l.itemId, "item_bn" to null, "item_name" to l.itemName,
                    "pic" to jsonList(l.pics).firstOrNull(), "num" to l.num, "price" to l.price,
                    "item_fee" to l.itemFee, "total_fee" to l.totalFee,
                    "discount_fee" to l.discountFee, "member_discount" to l.memberDiscount,
                    "cart_id" to l.cartId, "is_checked" to l.checked, "store" to l.store,
                    "item_spec_desc" to q.specDescById[l.itemId], "approve_status" to l.approveStatus,
                )
            },
            "invalid_items" to q.invalid.map { linkedMapOf("item_id" to it.itemId, "item_name" to (it.item?.itemName ?: it.itemName), "reason" to invalidReason(it)) },
            "group_count" to q.groupCount,
        )
    }

    private fun invalidReason(p: Pending): String = when {
        p.item == null -> "商品不存在"
        p.item.store <= 0 -> "库存不足"
        else -> "商品已下架"
    }
}
