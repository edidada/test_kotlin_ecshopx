package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.OrderClass
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MembersAddressMapper
import com.ecshopx.kotlin.dao.cart.OrdersCartMapper
import com.ecshopx.kotlin.dao.goods.ItemDO
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderAssocDO
import com.ecshopx.kotlin.dao.order.OrderAssocMapper
import com.ecshopx.kotlin.dao.order.OrderItemDO
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrderOperateLogDO
import com.ecshopx.kotlin.dao.order.OrderOperateLogMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.pay.PointMapper
import com.ecshopx.kotlin.dao.promotion.RelPromotionDO
import com.ecshopx.kotlin.dao.promotion.RelPromotionMapper
import com.ecshopx.kotlin.dao.promotion.SeckillGoodsMapper
import com.ecshopx.kotlin.service.cart.CartService
import com.ecshopx.kotlin.service.cart.PriceLine
import com.ecshopx.kotlin.service.cart.StockSource
import com.ecshopx.kotlin.service.distribution.StockPool
import com.ecshopx.kotlin.service.promotion.ActivityOffer
import com.ecshopx.kotlin.service.promotion.CouponService
import com.ecshopx.kotlin.service.promotion.GroupsService
import com.ecshopx.kotlin.service.promotion.yuan
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import org.springframework.stereotype.Service

/**
 * ORDER-2 创建订单（docs/03 §7.2 步骤化）。
 *
 * 三条铁律：
 * 1. 金额只来自服务端算价（[CartService.quoteFromCart]/[CartService.quoteFromEntries]），入参带 price 一律忽略；
 * 2. 扣库存走 CAS（`UPDATE items SET store=store-? WHERE item_id=? AND store>=?`），
 *    受影响行数 0 即库存不足/并发抢占，同事务回滚，绝不"读—判—写"；
 * 3. 一单一店：M0 不拆单（多店铺合并下单直接拒绝，`original_order_id` 列已预留，拆单属 M1-4）。
 *
 * M1-3 起再加一条：**用户资产（券、积分）与库存同一条 CAS 语义** ——
 * 券走 `UNUSED→LOCKED`、积分走 `point >= ?` 条件扣减，抢不到就整笔回滚。
 * 算价已经把这些抵扣减进应付了，占用失败还继续下单等于白拿折扣（docs/04 §14.6）。
 *
 * M1-3 #57 再加一条：**活动单的活动池也走同一条 CAS**（D24/D25）——
 * 秒杀抢 `hold_store`、拼团抢活动 `store` 与团席位，抢不到就 422 回滚。
 * 算价链里的 `available` 只用来"让前端别显示买不到的价"，抢占只在这里发生一次；
 * 申报了活动却没命中活动（活动结束/商品不在场次）同样直接拒单，
 * 绝不允许"按普通单下一笔带活动价的单"。
 *
 * ORDER-3（创建+预支付）依赖 trade 表与 PayChannel，随 M0-7 支付闭环一并交付。
 */
@Service
class OrderCreateService(
    private val carts: CartService,
    private val cartRows: OrdersCartMapper,
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val assoc: OrderAssocMapper,
    private val logs: OrderOperateLogMapper,
    private val goodsItems: ItemsMapper,
    private val pool: StockPool,
    private val addresses: MembersAddressMapper,
    private val configs: SysConfigMapper,
    private val points: PointMapper,
    private val coupons: CouponService,
    private val rels: RelPromotionMapper,
    private val seckillGoods: SeckillGoodsMapper,
    private val groupsService: GroupsService,
    private val seq: SeqGenerator,
    private val db: Db,
    private val mapper: ObjectMapper,
) {

    /**
     * 下单幂等（docs/03 §7.2 步骤 1 的 SETNX 语义）。
     *
     * Caffeine 的 `get(key, loader)` 对同一 key 串行执行 loader，等价单机 SETNX：
     * 同 token 并发提交只会真正下单一次，其余拿到同一个 order_id。
     * 局限：进程内缓存，重启/多实例后失效——M0 单实例部署够用，M1 换 Redis SETNX（键 sys:order:idem:{token}）。
     */
    private val idempotency = Caffeine.newBuilder()
        .maximumSize(20_000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .build<String, Long>()

    fun create(userId: Long, companyId: Long, form: OrderCreateForm): Map<String, Any?> {
        val token = form.clientToken?.takeIf { it.isNotBlank() }
        val key = "$companyId:$userId:$token"
        if (token == null) return payloadOf(doCreate(userId, companyId, form))
        // loader 抛异常时 Caffeine 不落缓存，重试仍能重新下单
        return payloadOf(idempotency.get(key) { doCreate(userId, companyId, form) })
    }

    /** 幂等命中时按已存在的单回填出参 */
    private fun payloadOf(orderId: Long): Map<String, Any?> {
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.BIZ, "订单不存在")
        return linkedMapOf(
            "order_id" to o.orderId.toString(),
            "pay_type" to o.payType,
            "order_type" to o.orderType,
            // §14.3：开团/参团成功才回团号，普通单恒 null（M0 的预留位落地）
            "team_id" to o.teamId,
            "total_fee" to o.totalFee,
        )
    }

    private fun doCreate(userId: Long, companyId: Long, form: OrderCreateForm): Long {
        if (!form.orderType.isNullOrBlank() && form.orderType != "normal") {
            throw BizException(ErrorCode.NOT_IMPLEMENTED, "该订单类型尚未开放：${form.orderType}")
        }
        val orderClass = OrderClass.normalize(form.orderClass)
        if (orderClass == OrderClass.NORMAL && (form.actId ?: 0) > 0) {
            throw BizException(ErrorCode.VALIDATION, "普通订单不能带 act_id", mapOf("act_id" to listOf("请同时指定 order_class")))
        }
        val q = if (form.directBuy) {
            if (form.entries().isEmpty()) throw BizException(ErrorCode.VALIDATION, "items 不能为空", mapOf("items" to listOf("不能为空")))
            carts.quoteFromEntries(
                userId, companyId, form.entries(), form.couponCode(), form.pointUse == true,
                activityType = orderClass, activityId = form.actId ?: 0,
            )
        } else {
            carts.quoteFromCart(userId, companyId, firstNonZero(form.shopId, form.distributorId), form.couponCode(), form.pointUse == true)
        }

        val lines = q.ctx.checkedLines
        if (lines.isEmpty()) throw BizException(ErrorCode.BIZ, "没有可结算的商品")
        // 勾选行里有失效商品（下架/无货）时不允许下单，文案与 ORDER-1 的 invalid_items 同源
        val broken = q.invalid.filter { it.checked }
        if (broken.isNotEmpty()) throw BizException(ErrorCode.BIZ, "存在不可购买的商品：${reason(broken.first())}")
        if (q.checkStore) throw BizException(ErrorCode.BIZ, "商品库存不足")
        if (q.groupCount > 1) throw BizException(ErrorCode.BIZ, "暂不支持多店铺合并下单，请按店铺分别结算")
        // 活动单的全部判据（同活动、命中、限购、限额）都在这一步，算价链只负责算钱不负责拒单
        val act = activityOrder(orderClass, form, lines, q)
        // 用户显式点了券就不许"悄悄不用"：抵扣为 0 时拒绝成交并回预览同款原因
        // （docs/04 §14.3 撤销 C25 的开关位口径——预览与下单同一个 quote，两边判据也同一个）
        val code = q.ctx.couponCode
        if (!code.isNullOrBlank() && q.couponDiscount == 0L) {
            throw BizException(ErrorCode.BIZ, carts.couponReason(q) ?: "优惠券不可用", mapOf("coupon_code" to listOf("不可用")))
        }

        val shopId = q.ctx.groups.first { it.cartTotalCount > 0 }.shopId
        val recv = receiver(userId, form)
        val cartIds = lines.map { it.cartId }.filter { it > 0 }
        val snapshot = itemSnapshot(companyId, lines.map { it.itemId })

        val orderId = db.transactional {
            val id = seq.next(SeqName.ORDER_NO)
            val now = TimeSec.now()
            deductStock(lines, now)
            val teamId = act?.let { holdActivityPool(it, form, userId, companyId, lines, id, now) }
            holdUserAssets(userId, q, id, now)

            val order = orderRow(id, userId, companyId, shopId, form, q, lines, recv, now, snapshot, act, teamId)
            check(orders.insert(order) == 1) { "订单主行插入失败" }
            lines.forEach { l -> orderItems.insert(itemRow(id, userId, companyId, shopId, l, q, snapshot[l.itemId])) }
            snapshotPromotions(id, userId, q, lines, now)
            assoc.insert(assocRow(order))
            logs.insert(
                OrderOperateLogDO().apply {
                    orderId = id; operatorId = userId; operator = "user:$userId"; operatorRole = "buyer"
                    behavior = "create"; logText = "创建订单，应付 ${order.totalFee} 分（含运费 ${order.freightFee} 分）"
                },
            )
            if (cartIds.isNotEmpty()) cartRows.deleteByIds(userId, cartIds)
            id
        }
        return orderId
    }

    /**
     * 活动单归一与全部前置判据（docs/04 §14.3/§14.5）。
     *
     * 三条口径：
     *  1. **入参没说、行上申报了也算活动单**：秒杀入口在商详页，但 `orders_cart` 早于本功能就带
     *     `activity_type/activity_id` 两列（CART-1）。如果只认入参，用户从购物车结算秒杀品会变成
     *     「按活动价付普通单」——活动价拿了、池子没占，等于凭空多卖；
     *  2. **申报了活动就必须命中活动**：`l.activityOffer == null` 说明场次结束/商品不在场次/行不属于该活动，
     *     直接 422 而不是回落现价（预览按现价、下单按活动价的价差是最难解释的客诉）；
     *  3. **一笔单只允许一个活动**：`order_class` 与 `act_id` 都是单头列，混活动没法记快照也没法发货。
     */
    private fun activityOrder(
        orderClass: String,
        form: OrderCreateForm,
        lines: List<PriceLine>,
        q: CartService.Quote,
    ): ActivityOrder? {
        val declared = lines.map { it.activityType to it.activityId }
            .filter { it.first in ACTIVITY_CLASSES && it.second > 0 }
            .distinct()
        if (orderClass == OrderClass.NORMAL && declared.isEmpty()) return null
        if (declared.size > 1) throw BizException(ErrorCode.BIZ, "一笔订单只能参加一个活动，请分别下单")
        val type = if (orderClass != OrderClass.NORMAL) orderClass else declared.first().first
        val id = form.actId?.takeIf { it > 0 } ?: declared.firstOrNull()?.second
            ?: throw BizException(ErrorCode.VALIDATION, "act_id 必填", mapOf("act_id" to listOf("${type} 单必须指定活动 id")))
        if (declared.isNotEmpty() && declared.first() != type to id) {
            throw BizException(ErrorCode.BIZ, "商品行申报的活动与本单声明的不一致，请重新从活动页下单")
        }
        val head = lines.map { l ->
            if (l.activityType != type || l.activityId != id) throw BizException(ErrorCode.BIZ, "活动单里的商品必须同属一个活动：${l.itemName}")
            l.activityOffer ?: throw BizException(ErrorCode.BIZ, "活动已结束或该商品不在此场次：${l.itemName}")
        }.first()
        if (type == OrderClass.GROUPS && (lines.size != 1 || lines.first().num != 1L)) {
            throw BizException(ErrorCode.BIZ, "拼团一次只能拍 1 件（一件坐一个团席位）")
        }
        lines.forEach { l ->
            val o = l.activityOffer ?: return@forEach
            if (l.num > o.remainForMe()) {
                throw BizException(ErrorCode.BIZ, "${l.itemName} 限购 ${o.limitNum} ${o.boughtUnit}，你已使用 ${o.bought}")
            }
            if (l.num > o.available) throw BizException(ErrorCode.BIZ, "${l.itemName} 活动名额不足")
        }
        if (head.limitMoney > 0 && q.totalFee > head.limitMoney) {
            throw BizException(ErrorCode.BIZ, "本场次单笔限额 ${yuan(head.limitMoney)} 元，请减少数量")
        }
        return ActivityOrder(type, id, head)
    }

    /** 活动单头信息：`order_class` + `act_id` + 用于占池/时限的行级命中结果 */
    private class ActivityOrder(val type: String, val id: Long, val head: ActivityOffer)

    /**
     * 抢占活动池（D24/D25），**都在下单事务内**，条件更新 0 行即抛错整笔回滚：
     *  - 秒杀：`hold_store += num`（可购量 = 总量 − 已售 − 占用）；
     *  - 拼团：活动 `store -= num` + 开团或占席位（`join_person_num < person_num` CAS），回团号。
     *
     * 顺序很关键：先抢活动池再锁券——池子抢不到时券根本没被 `LOCKED`，用户回列表还能用那张券，
     * 反之会留下一张"被一笔不存在的订单锁住"的券，只能等超时关单回收。
     */
    private fun holdActivityPool(
        act: ActivityOrder,
        form: OrderCreateForm,
        userId: Long,
        companyId: Long,
        lines: List<PriceLine>,
        orderId: Long,
        now: Long,
    ): String? {
        if (act.type == OrderClass.SECKILL) {
            lines.forEach { l ->
                if (seckillGoods.hold(act.id, l.itemId, l.num) == 0) {
                    throw BizException(ErrorCode.BIZ, "秒杀商品已被抢完：${l.itemName}", mapOf("item_id" to listOf(l.itemId.toString())))
                }
            }
            return null
        }
        return groupsService.occupySeat(companyId, userId, act.id, form.teamId, orderId, lines.sumOf { it.num }, now).teamId
    }

    /**
     * 失效原因：与 ORDER-1 的 `invalid_items[].reason` 同一个判据（[CartService.invalidReason]）。
     *
     * 原来这里自己写了一套"下架/库存不足"三行 when，M1-4 加了店铺池子之后它会把
     * "这家店停售了"报成"库存不足"（总部池还满着）—— 预览与下单两张脸就是这么来的。
     */
    private fun reason(p: CartService.Pending): String = carts.invalidReason(p)

    /**
     * 占用用户资产（M1-3）。**只在算价真的扣了钱时才占用**：
     * 券码给了但门槛没到 / 多店铺被拒时，抵扣是 0，此时不该把券锁住（用户还能拿它去别的单）。
     *
     * 两条都是 CAS，0 行即抛错回滚整笔下单：
     *  - 券：`UNUSED→LOCKED` 并把 order_id 写进实例行，后续结清走 `consume(order_id)`、关单走 `release(order_id)`；
     *  - 积分：`point >= ?` 条件扣减，扣完余额不够说明这一分钟内别的单先用掉了。
     */
    private fun holdUserAssets(
        userId: Long,
        q: CartService.Quote,
        orderId: Long,
        now: Long,
    ) {
        val ctx = q.ctx
        val code = ctx.coupon?.couponCode?.takeIf { q.couponDiscount > 0 }
        if (code != null) {
            if (!coupons.lock(code, ctx.companyId, userId, orderId, now)) {
                throw BizException(ErrorCode.BIZ, "优惠券已被其他订单占用，请重新结算", mapOf("coupon_code" to listOf("不可用")))
            }
        }
        if (q.pointFee > 0 && points.deduct(ctx.companyId, userId, q.pointFee, now) == 0) {
            throw BizException(ErrorCode.BIZ, "积分已变动，请重新结算", mapOf("use_point" to listOf("可用积分不足")))
        }
    }

    /**
     * 优惠快照（`orders_rel_promotions`）：这笔单便宜在哪个活动、那张券当时抵了多少，
     * 活动模板与券模板后面会被改被删，快照是退款与对账唯一能信的历史记录（docs/03 §7.1）。
     */
    private fun snapshotPromotions(
        orderId: Long,
        userId: Long,
        q: CartService.Quote,
        lines: List<PriceLine>,
        now: Long,
    ) {
        lines.forEach { l ->
            l.activityInfo.forEach { a ->
                rels.insert(
                    RelPromotionDO().apply {
                        moid = orderId; this.userId = userId
                        orderType = "normal"
                        itemId = l.itemId; itemName = l.itemName; itemType = l.itemType
                        // 满减行两个键都给（`marketing_id`），秒杀/拼团行只给 `id`（§14.5 order 15 的行痕迹）
                        activityId = num(a["marketing_id"]).takeIf { it > 0 } ?: num(a["id"])
                        activityName = str(a["name"]); activityType = str(a["type"])
                        discountFee = num(a["discount_fee"]); created = now; updated = now
                    },
                )
            }
        }
        q.couponInfo.forEach { c ->
            rels.insert(
                RelPromotionDO().apply {
                    moid = orderId; this.userId = userId
                    orderType = "normal"
                    itemName = str(c["coupon_name"]); activityType = "coupon"
                    activityId = num(c["coupon_id"]); activityName = str(c["coupon_name"])
                    couponId = num(c["coupon_id"]); couponCode = str(c["coupon_code"])
                    discountFee = num(c["deduct_fee"]); created = now; updated = now
                },
            )
        }
        if (q.pointFee > 0) {
            rels.insert(
                RelPromotionDO().apply {
                    moid = orderId; this.userId = userId
                    orderType = "normal"; itemName = "积分抵扣"
                    activityType = "point"; discountFee = q.pointFee; created = now; updated = now
                },
            )
        }
    }

    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L

    private fun str(v: Any?): String? = v as? String

    /**
     * 扣库存（§18.4 第②条腿的下单侧）。
     *
     * 聚合键从 `itemId` 扩成 `(池子归属, 店, 商品)`：同一商品可以在这一单里既走总部池又走店铺池吗 ——
     * 不能（一单一店），但**同一商品的多行**（不同规格/加购两次）必须合并成一次 CAS，
     * 所以键里既要留 shopId 也要留 isTotalStore，两者在建单侧与明细行同源。
     */
    private fun deductStock(lines: List<PriceLine>, now: Long) {
        lines.groupBy { Triple(it.stockSource, it.shopId, it.itemId) }.forEach { (k, ls) ->
            val (_, shopId, itemId) = k
            pool.deduct(shopId, itemId, ls.sumOf { it.num }, ls.first().isTotalStore, ls.first().itemName, now)
        }
    }

    /** 收货信息：address_id 服务端展开，否则用明文六件套；物流单必填校验 */
    private fun receiver(userId: Long, form: OrderCreateForm): Receiver {
        val fromBook = form.addressId?.takeIf { it > 0 }?.let { id ->
            addresses.findById(id, userId)
                ?: throw BizException(ErrorCode.BIZ, "收货地址不存在", mapOf("address_id" to listOf("不存在")))
        }
        val r = Receiver(
            name = fromBook?.username ?: form.receiverName,
            mobile = fromBook?.telephone ?: form.receiverMobile,
            state = fromBook?.province ?: form.receiverState,
            city = fromBook?.city ?: form.receiverCity,
            district = fromBook?.county ?: form.receiverDistrict,
            address = fromBook?.adrdetail ?: form.receiverAddress,
        )
        if (form.receiptType == "ziti") {
            requireMobile(r)
            return r
        }
        val errs = linkedMapOf<String, List<String>>()
        if (r.name.isNullOrBlank()) errs["receiver_name"] = listOf("收货人不能为空")
        if (r.mobile.isNullOrBlank()) errs["receiver_mobile"] = listOf("联系电话不能为空")
        if (r.address.isNullOrBlank()) errs["receiver_address"] = listOf("收货地址不能为空")
        if (errs.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "收货信息不完整", errs)
        requireMobile(r)
        return r
    }

    private fun requireMobile(r: Receiver) {
        val m = r.mobile.orEmpty()
        if (m.isBlank()) throw BizException(ErrorCode.VALIDATION, "联系电话不能为空", mapOf("receiver_mobile" to listOf("不能为空")))
        if (!Regex("^1[3-9]\\d{9}$").matches(m)) {
            throw BizException(ErrorCode.VALIDATION, "手机号格式不正确", mapOf("receiver_mobile" to listOf("手机号格式不正确")))
        }
    }

    private data class Receiver(
        val name: String?, val mobile: String?, val state: String?,
        val city: String?, val district: String?, val address: String?,
    )

    private fun itemSnapshot(companyId: Long, ids: List<Long>): Map<Long, ItemDO> {
        val uniq = ids.distinct()
        if (uniq.isEmpty()) return emptyMap()
        val f = linkedMapOf<String, Any?>("companyId" to companyId, "itemIds" to uniq)
        return goodsItems.listItems(f, 0L, uniq.size).associateBy { it.itemId }
    }

    private fun orderRow(
        id: Long,
        userId: Long,
        companyId: Long,
        shopId: Long,
        form: OrderCreateForm,
        q: CartService.Quote,
        lines: List<PriceLine>,
        recv: Receiver,
        now: Long,
        snapshot: Map<Long, ItemDO>,
        act: ActivityOrder?,
        teamId: String?,
    ): NormalOrderDO {
        val invoiceJson = invoiceOf(form)
        return NormalOrderDO().apply {
            orderId = id; title = titleOf(lines)
            this.companyId = companyId; this.shopId = shopId; this.userId = userId
            mobile = recv.mobile
            orderSource = "h5app"
            orderHolder = if (shopId > 0) "distributor" else "platform"
            orderFrom = form.orderFrom ?: "h5"
            itemFee = q.itemFee
            totalFee = q.totalFee
            marketFee = lines.sumOf { (snapshot[it.itemId]?.marketPrice ?: it.marketPrice) * it.num }
            costFee = lines.sumOf { (snapshot[it.itemId]?.costPrice ?: 0L) * it.num }
            freightFee = q.freightFee
            discountFee = q.discountFee
            memberDiscount = q.memberDiscount
            couponDiscount = q.couponDiscount
            // 积分抵现：1 积分 = 1 分（PointRate.POINT_PER_FEN），故抵扣金额与使用积分数同值
            pointFee = q.pointFee
            pointUse = q.pointFee
            itemPoint = lines.sumOf { (snapshot[it.itemId]?.point ?: 0L) * it.num }
            totalRebate = lines.sumOf { (snapshot[it.itemId]?.rebate ?: 0L) * it.num }
            distributorId = shopId
            salesmanId = form.salesmanId ?: 0
            receiptType = if (form.receiptType == "ziti") "ziti" else "logistics"
            invoice = invoiceJson
            isInvoiced = invoiceJson != null
            remark = form.remark
            receiverName = recv.name; receiverMobile = recv.mobile; receiverState = recv.state
            receiverCity = recv.city; receiverDistrict = recv.district; receiverAddress = recv.address
            // 活动单头三件套：order_class/act_id 决定这单归哪个活动域，team_id 只有拼团才有
            orderClass = act?.type ?: OrderClass.NORMAL
            actId = act?.id ?: 0
            this.teamId = teamId
            // 超时关单时长走租户配置（seed: order.auto_cancel_minutes=30）；
            // 秒杀单另算：`min(now + validity_period 分钟, 活动结束)`（D24 —— 活动都结束了不该还留着待付款）
            autoCancelTime = when {
                act == null || act.type != OrderClass.SECKILL -> now + autoCancelMinutes(companyId) * 60L
                act.head.validityPeriod <= 0 -> minOf(now + autoCancelMinutes(companyId) * 60L, actEnd(act))
                else -> minOf(now + act.head.validityPeriod * 60L, actEnd(act))
            }
            created = now; updated = now
            type = 0
        }
    }

    private fun titleOf(lines: List<PriceLine>): String {
        val first = lines.first().itemName
        return if (lines.size == 1) first else "$first 等${lines.size}件"
    }

    private fun invoiceOf(form: OrderCreateForm): String? {
        val content = form.invoiceContent?.takeIf { it.isNotBlank() } ?: return null
        return mapper.writeValueAsString(
            linkedMapOf("type" to (form.invoiceType ?: "personal"), "content" to content),
        )
    }

    private fun autoCancelMinutes(companyId: Long): Long =
        configs.getValue(companyId, "order.auto_cancel_minutes")?.toLongOrNull()
            ?: configs.getValue(0L, "order.auto_cancel_minutes")?.toLongOrNull()
            ?: 30L

    /** 活动结束时间（0=未配结束，视为无上限）：`auto_cancel_time` 的第二个上限 */
    private fun actEnd(act: ActivityOrder): Long = if (act.head.endTime > 0) act.head.endTime else Long.MAX_VALUE / 2

    private fun itemRow(
        orderId: Long,
        userId: Long,
        companyId: Long,
        shopId: Long,
        l: PriceLine,
        q: CartService.Quote,
        item: ItemDO?,
    ): OrderItemDO = OrderItemDO().apply {
        this.orderId = orderId; this.companyId = companyId; this.userId = userId
        goodsId = l.goodsId; itemId = l.itemId; itemBn = item?.itemBn
        // 明细行的 distributor_id 记的是**这行货从哪家店的池子出**（店铺池单），
        // 而不是商品挂在谁名下 —— 回补只读这一列，两者不一致就会把别人的池子加回去
        this.shopId = shopId
        distributorId = if (l.stockSource == StockSource.SHOP) shopId else (item?.distributorId ?: shopId)
        actId = l.activityId
        itemName = l.itemName
        pic = jsonFirst(l.pics)
        num = l.num
        // 成交价一律取算价结果，商品表现价只做快照描述
        price = l.price
        itemFee = l.itemFee
        totalFee = l.totalFee
        costFee = (item?.costPrice ?: 0L) * l.num
        marketPrice = item?.marketPrice ?: l.marketPrice
        costPrice = item?.costPrice ?: 0L
        rebate = item?.rebate ?: 0L
        totalRebate = rebate * l.num
        memberDiscount = l.memberDiscount
        couponDiscount = l.couponFee
        pointFee = l.pointFee
        discountFee = l.discountFee
        discountInfo = mapper.writeValueAsString(l.activityInfo)
        orderItemType = if (l.plusBuy) "plus_buy" else "normal"
        templatesId = l.templatesId
        itemPoint = (item?.point ?: 0L) * l.num
        point = item?.point ?: 0L
        itemSpecDesc = q.specDescById[l.itemId]
        taxRate = item?.taxRate?.toString()
        weight = item?.weight; volume = item?.volume
        // §18.4：这行货从哪个池子出，就按哪个池子记 —— 回补时只读明细行，不回读挂品行
        isTotalStore = l.isTotalStore
    }

    /** pics 存的是 JSON 数组字符串，订单明细只留首图 */
    private fun jsonFirst(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val n = runCatching { mapper.readTree(raw) }.getOrNull() ?: return raw
        return if (n.isArray) n.get(0)?.asText() else raw
    }

    private fun assocRow(o: NormalOrderDO): OrderAssocDO = OrderAssocDO().apply {
        orderId = o.orderId; companyId = o.companyId; userId = o.userId
        orderClass = o.orderClass; orderType = o.orderType
        orderStatus = OrderStatus.NOTPAY
        totalFee = o.totalFee; memberDiscount = o.memberDiscount; couponDiscount = o.couponDiscount
        totalRebate = o.totalRebate
        isDistribution = o.distributorId > 0
        salesmanId = o.salesmanId
        created = o.created; updated = o.updated
    }

    private fun firstNonZero(vararg v: Long?): Long? = v.firstOrNull { it != null && it > 0 }

    companion object {
        /** 会占活动池的订单类目（§14.5 order 15 的两类；`normal` 不进活动判据） */
        private val ACTIVITY_CLASSES = setOf(OrderClass.SECKILL, OrderClass.GROUPS)
    }
}
