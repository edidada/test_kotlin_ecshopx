package com.ecshopx.kotlin.service.cart

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.OrderClass
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
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.pay.PointMapper
import com.ecshopx.kotlin.dao.promotion.CouponUserDO
import com.ecshopx.kotlin.dao.promotion.MarketingActivityMapper
import com.ecshopx.kotlin.dao.promotion.MarketingBound
import com.ecshopx.kotlin.dao.promotion.MarketingItemsMapper
import com.ecshopx.kotlin.dao.promotion.MarketingType
import com.ecshopx.kotlin.dao.tenant.CompanyMapper
import com.ecshopx.kotlin.dao.tenant.DistributorShopMapper
import com.ecshopx.kotlin.dao.tenant.ShopProfileDO
import com.ecshopx.kotlin.service.promotion.ActivityLineKey
import com.ecshopx.kotlin.service.promotion.ActivityOffer
import com.ecshopx.kotlin.service.promotion.ActivityOfferService
import com.ecshopx.kotlin.service.promotion.CouponCheck
import com.ecshopx.kotlin.service.promotion.CouponService
import com.ecshopx.kotlin.service.promotion.MarketingOffer
import com.ecshopx.kotlin.service.promotion.MarketingRuleParser
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
 *
 * M1-3 之后这里是**整条算价链唯一的取数点**：活动、券、积分余额、时间戳都在这里查完塞进
 * [PriceContext]，责任链节点因此可以保持纯函数（docs/04 §14.5）。
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
    private val marketing: MarketingActivityMapper,
    private val marketingItems: MarketingItemsMapper,
    private val points: PointMapper,
    private val configs: SysConfigMapper,
    private val coupons: CouponService,
    private val activityOfferService: ActivityOfferService,
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
        /** 满减/满折给的那部分（= 行折让合计 − 会员折让），出参里的 `goods_discount` */
        val activityDiscount: Long get() = (discountFee - memberDiscount).coerceAtLeast(0L)
        val couponDiscount: Long get() = ctx.couponDiscount
        val pointFee: Long get() = ctx.totalPointFee
        /**
         * 秒杀/拼团让利（§14.5 order 15）：**不在 [discountFee] 里**。
         * 活动价已经改写进 `price`，这笔钱只用于出参展示与优惠快照，
         * 绝不能再减一次（否则 `Σ行+运费==单` 的守恒当场破）。
         */
        val activityPriceFee: Long get() = ctx.groups.sumOf { it.activityPriceFee }
        val activityPriceRows: List<Map<String, Any?>> get() = ctx.groups.flatMap { it.activityPriceRows }
        val couponInfo: List<Map<String, Any?>> get() = ctx.groups.flatMap { it.couponInfo }
        val usedActivity: List<Map<String, Any?>> get() = ctx.groups.flatMap { it.usedActivity }
        val totalFee: Long get() = ctx.totalPayFee()
        val totalItemNum: Long get() = ctx.checkedLines.sumOf { it.num }
        val groupCount: Int get() = ctx.groups.count { it.cartTotalCount > 0 }
        val userPoint: Long get() = ctx.availablePoint
        val pointRatio: Int get() = ctx.pointRatio

        /**
         * 本单最多可抵积分：`min(余额, floor(商品应付 × ratio%))`。
         * 用"抵扣前应付"算上限，[com.ecshopx.kotlin.service.cart.PointDeductRule] 摊完的结果不会超过它。
         */
        val maxPoint: Long get() {
            if (ctx.pointRatio <= 0) return 0L
            val payable = ctx.groups.sumOf { it.cartTotalPrice } + pointFee
            return minOf(userPoint, payable * ctx.pointRatio / 100)
        }

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
                    fromEntries(
                        form.entries(), userId, companyId,
                        activityType = OrderClass.normalize(form.orderClass), activityId = form.actId ?: 0,
                    )
                else fromCart(userId, companyId, firstNonZero(form.shopId, form.distributorId)),
                couponCode = form.couponCode,
                usePoint = form.pointUse == true,
            ),
        )

    /** 车内勾选行 → Quote；M0-6 下单的取价入口 */
    fun quoteFromCart(userId: Long, companyId: Long, shopId: Long?, couponCode: String?, usePoint: Boolean): Quote =
        quote(userId, companyId, fromCart(userId, companyId, shopId).filter { it.checked }, couponCode, usePoint)

    /** fastbuy/直购 → Quote */
    fun quoteFromEntries(
        userId: Long,
        companyId: Long,
        entries: List<CartItemNum>,
        couponCode: String?,
        usePoint: Boolean,
        activityType: String = OrderClass.NORMAL,
        activityId: Long = 0,
    ): Quote =
        quote(userId, companyId, fromEntries(entries, userId, companyId, activityType, activityId), couponCode, usePoint)

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

    /** 唯一建 ctx 的地方：单价取商品主表现价，快照价只用于 is_last_price；活动/券/积分在此预取 */
    private fun buildContext(
        companyId: Long,
        userId: Long,
        grade: MemberGradeDO?,
        valid: List<Pending>,
        couponCode: String?,
        usePoint: Boolean,
    ): PriceContext {
        val now = TimeSec.now()
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
        val (coupon, couponReason) = couponOf(companyId, userId, couponCode)
        return PriceContext(
            companyId = companyId, userId = userId,
            gradeName = grade?.gradeName ?: "普通会员",
            gradeDiscount = grade?.discount ?: 100,
            lines = lines, couponCode = couponCode, usePoint = usePoint,
            availablePoint = pointBalance(companyId, userId),
            templates = freightTemplates(companyId),
            now = now,
            offers = pricableOffers(companyId, now, lines.map { it.itemId }.distinct()),
            coupon = coupon,
            couponReason = couponReason,
            pointRatio = if (usePoint) pointRatio(companyId) else 0,
            activityOffers = activityOffersOf(companyId, userId, lines, now),
        )
    }

    /**
     * 秒杀/拼团活动价预取（§14.5 order 15 的取数腿）。
     *
     * 只把**申报了活动**的行交给预取（`activity_type` 不是 `normal` 且 `activity_id>0`）：
     * 普通车行不该因为库里有一场秒杀就变价（口径 [ActivityOfferService] 第 1 条）。
     * 一次批量取，规则链拿到的仍是纯数据。
     */
    private fun activityOffersOf(
        companyId: Long,
        userId: Long,
        lines: List<PriceLine>,
        now: Long,
    ): List<ActivityOffer> {
        val keys = lines.filter { it.activityType in ACTIVITY_CLASSES && it.activityId > 0 }
            .map { ActivityLineKey(it.activityType, it.activityId, it.itemId, it.goodsId, it.price) }
        return if (keys.isEmpty()) emptyList() else activityOfferService.offers(companyId, userId, keys, now)
    }

    /**
     * 算价可参与的满减/满折活动（已审核 + 启用 + 在窗 + 类型在白名单）。
     *
     * 圈品一次批量读：`use_bound=1` 的活动只对本次车里的商品做命中查询，
     * `use_bound=0` 全场活动不看圈品表（PHP 同口径，圈品行只可能配在指定商品活动上）。
     */
    private fun pricableOffers(
        companyId: Long,
        now: Long,
        itemIds: List<Long>,
    ): List<MarketingOffer> {
        val acts = marketing.listPricable(companyId, now, MarketingType.PRICABLE)
        if (acts.isEmpty()) return emptyList()
        val boundIds = acts.filter { it.useBound == MarketingBound.ITEMS }.map { it.marketingId }
        val itemHit = if (boundIds.isEmpty() || itemIds.isEmpty()) emptyMap() else
            marketingItems.listHit(boundIds, itemIds).groupBy({ it.marketingId }, { it.itemId }).mapValues { e -> e.value.toSet() }
        return acts.map { a -> MarketingRuleParser.parse(a, mapper, itemHit[a.marketingId] ?: emptySet()) }
    }

    /**
     * 选中的券实例。归属校验在这里做（不在规则里做）：
     * 别人的券码等于不存在，[CouponCheck] 只负责"这张券本身能不能用"。
     */
    private fun couponOf(
        companyId: Long,
        userId: Long,
        code: String?,
    ): Pair<CouponUserDO?, String?> = when {
        code.isNullOrBlank() -> null to null
        else -> {
            val c = coupons.byCode(code, companyId)
            when {
                c == null -> null to "优惠券不存在"
                c.userId != userId -> null to "优惠券不属于当前会员"
                else -> c to null
            }
        }
    }

    private fun pointBalance(companyId: Long, userId: Long): Long = points.find(companyId, userId)?.point ?: 0L

    /** 积分抵扣上限比例：租户没配就用平台默认（company_id=0），都没有则不允许抵扣 */
    private fun pointRatio(companyId: Long): Int =
        configs.getValue(companyId, KEY_POINT_RATIO)?.toIntOrNull()
            ?: configs.getValue(0L, KEY_POINT_RATIO)?.toIntOrNull()?.coerceIn(0, 100)
            ?: 0

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

    /**
     * 直购行（fastbuy / ORDER-2 的 `items[]`）。
     *
     * 秒杀/拼团入口永远是一批同活动的行（活动页「马上抢」），所以活动标识按整批下发；
     * 车内行不是这样 —— [fromCart] 逐行读 `orders_cart.activity_type/activity_id`（CART-1 加购时就落库）。
     */
    private fun fromEntries(
        entries: List<CartItemNum>,
        userId: Long,
        companyId: Long,
        activityType: String = OrderClass.NORMAL,
        activityId: Long = 0,
    ): List<Pending> {
        val list = entries.filter { (it.itemId ?: 0) > 0 }
        if (list.isEmpty()) return emptyList()
        val itemById = loadItems(list.map { it.itemId!! })
        return list.map { e ->
            val item = itemById[e.itemId!!]
            val shopId = item?.distributorId ?: 0
            Pending(
                0, userId, companyId, e.itemId!!, item?.itemName ?: "", shopId,
                if (shopId > 0) "distributor" else "shop", activityType, activityId, null,
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
            "activity_price_fee" to 0L,
            "activity_price_info" to emptyList<Map<String, Any?>>(),
            "coupon_discount" to 0L,
            "coupon_info" to emptyList<Map<String, Any?>>(),
            "point_fee" to 0L,
            "is_free_postage" to false,
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
            this["activity_price_fee"] = g.activityPriceFee
            this["activity_price_info"] = g.activityPriceRows
            this["coupon_discount"] = g.couponDiscount
            this["coupon_info"] = g.couponInfo
            this["point_fee"] = g.pointFee
            this["is_free_postage"] = g.freePostage
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
            "coupon_fee" to 0L,
            "point_fee" to 0L,
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
            "coupon_fee" to l.couponFee,
            "point_fee" to l.pointFee,
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
            "activity_price_fee" to l.activityPriceFee,
            "activity_price_info" to l.activityInfo.filter { e -> ACTIVITY_CLASSES.any { it == e["type"] } },
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
            "discount_info" to discountInfo(q),
            "coupon_discount" to q.couponDiscount,
            "coupon_info" to q.couponInfo,
            "coupon_reason" to couponReason(q),
            "member_discount" to q.memberDiscount,
            "goods_discount" to q.activityDiscount,
            "activity_price_fee" to q.activityPriceFee,
            "point" to q.valid.sumOf { p -> (p.item?.point ?: 0L) * p.num },
            "point_use" to q.ctx.usePoint,
            "point_fee" to q.pointFee,
            "get_points" to q.valid.sumOf { p -> (p.item?.point ?: 0L) * p.num },
            "bonus_points" to 0L,
            "user_point" to q.userPoint,
            "max_point" to q.maxPoint,
            "limit_point" to q.pointRatio,
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
                    "activity_price_fee" to l.activityPriceFee,
                    "origin_price" to (l.activityOffer?.originPrice ?: l.price),
                    "coupon_fee" to l.couponFee, "point_fee" to l.pointFee,
                    "cart_id" to l.cartId, "is_checked" to l.checked, "store" to l.store,
                    "activity_type" to l.activityType, "activity_id" to l.activityId,
                    "item_spec_desc" to q.specDescById[l.itemId], "approve_status" to l.approveStatus,
                )
            },
            "invalid_items" to q.invalid.map { linkedMapOf("item_id" to it.itemId, "item_name" to (it.item?.itemName ?: it.itemName), "reason" to invalidReason(it)) },
            "group_count" to q.groupCount,
        )
    }

    /**
     * ORDER-1 `discount_info[]`：这笔单的折让构成（docs/04 §14.5，`type` 取
     * `full_minus`/`full_discount`/`seckill`/`coupon`/`point`）。
     *
     * 活动、券、积分各记一行：结算页一个列表就能说清"便宜是怎么来的"，
     * 不用在 `discount_fee/coupon_discount/point_fee` 三个字段之间自己拼。
     */
    private fun discountInfo(q: Quote): List<Map<String, Any?>> =
        q.activityPriceRows.map { a ->
            linkedMapOf<String, Any?>("type" to a["type"], "rule" to a["rule"], "discount_fee" to a["discount_fee"])
        } + q.usedActivity.map { a ->
            linkedMapOf<String, Any?>("type" to a["type"], "rule" to a["rule"], "discount_fee" to a["discount_fee"])
        } + q.couponInfo.map { c ->
            linkedMapOf<String, Any?>("type" to "coupon", "rule" to c["rule"], "discount_fee" to c["deduct_fee"])
        } + if (q.pointFee > 0) {
            listOf(linkedMapOf<String, Any?>("type" to "point", "rule" to "积分抵扣 ${q.pointFee} 分", "discount_fee" to q.pointFee))
        } else {
            emptyList()
        }

    private fun invalidReason(p: Pending): String = when {
        p.item == null -> "商品不存在"
        p.item.store <= 0 -> "库存不足"
        else -> "商品已下架"
    }

    /**
     * 给了券码却没抵到钱的人话原因（ORDER-1 `coupon_reason`）。
     * 归属问题在预取阶段就有结论；剩下那些（状态/过期/门槛）要等算价跑完才知道比的是哪个数，
     * 所以这里用**抵扣前的组内应付**再问一次 [CouponCheck]，与 CP-F4 的判据同源。
     *
     * 公开给下单侧复用（`OrderCreateService`）：预览说"这券能用"、下单却按不用券成交，
     * 是最难解释的一类分歧 —— 两处必须读同一个判据。
     */
    fun couponReason(q: Quote): String? {
        val ctx = q.ctx
        if (ctx.couponCode.isNullOrBlank()) return null
        ctx.couponReason?.let { return it }
        val c = ctx.coupon ?: return "优惠券不存在"
        if (q.couponDiscount > 0) return null
        val g = ctx.activeGroups.singleOrNull()
            ?: return if (ctx.activeGroups.size > 1) "本单包含多个店铺，优惠券只支持单店铺结算" else "没有可抵扣的商品金额"
        return CouponCheck.usable(c, g.cartTotalPrice + g.couponDiscount, ctx.now)
    }

    companion object {
        /** 积分抵现上限比例（`sys_config`，company_id=0 是平台默认） */
        private const val KEY_POINT_RATIO = "point.deduct.max_ratio"

        /** 会改单价基准的活动类（§14.5 order 15）：其余 `activity_type` 一律按普通行算价 */
        private val ACTIVITY_CLASSES = setOf(OrderClass.SECKILL, OrderClass.GROUPS)
    }
}
