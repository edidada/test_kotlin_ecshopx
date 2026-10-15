package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
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
import com.ecshopx.kotlin.service.cart.CartService
import com.ecshopx.kotlin.service.cart.PriceLine
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
    private val addresses: MembersAddressMapper,
    private val configs: SysConfigMapper,
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
            "team_id" to null,
            "total_fee" to o.totalFee,
        )
    }

    private fun doCreate(userId: Long, companyId: Long, form: OrderCreateForm): Long {
        if (!form.orderType.isNullOrBlank() && form.orderType != "normal") {
            throw BizException(ErrorCode.NOT_IMPLEMENTED, "该订单类型尚未开放：${form.orderType}")
        }
        val q = if (form.directBuy) {
            if (form.entries().isEmpty()) throw BizException(ErrorCode.VALIDATION, "items 不能为空", mapOf("items" to listOf("不能为空")))
            carts.quoteFromEntries(userId, companyId, form.entries(), form.couponCode(), form.pointUse == true)
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

        val shopId = q.ctx.groups.first { it.cartTotalCount > 0 }.shopId
        val recv = receiver(userId, form)
        val cartIds = lines.map { it.cartId }.filter { it > 0 }
        val snapshot = itemSnapshot(companyId, lines.map { it.itemId })

        val orderId = db.transactional {
            val id = seq.next(SeqName.ORDER_NO)
            val now = TimeSec.now()
            deductStock(lines, now)

            val order = orderRow(id, userId, companyId, shopId, form, q, lines, recv, now, snapshot)
            check(orders.insert(order) == 1) { "订单主行插入失败" }
            lines.forEach { l -> orderItems.insert(itemRow(id, userId, companyId, shopId, l, q, snapshot[l.itemId])) }
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

    /** 失效原因（与 CartService 的 valid 判定同口径：商品不存在 / 已下架 / 库存不足） */
    private fun reason(p: CartService.Pending): String = when {
        p.item == null -> "商品不存在"
        p.item?.approveStatus != "onsale" -> "商品已下架"
        else -> "库存不足"
    }

    /** 同商品可能有多行（不同规格/加购），按商品聚合后一次 CAS，避免同物品重复扣 */
    private fun deductStock(lines: List<PriceLine>, now: Long) {
        lines.groupBy { it.itemId }.forEach { (itemId, ls) ->
            val num = ls.sumOf { it.num }
            if (goodsItems.deductStock(itemId, num, now) == 0) {
                throw BizException(
                    ErrorCode.BIZ, "商品库存不足：${ls.first().itemName}",
                    mapOf("item_id" to listOf("库存不足，请减少数量")),
                )
            }
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
            couponDiscount = 0L
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
            // 超时关单时长走租户配置（seed: order.auto_cancel_minutes=30）
            autoCancelTime = now + autoCancelMinutes(companyId) * 60L
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
        this.shopId = shopId; distributorId = item?.distributorId ?: shopId
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
        couponDiscount = 0L
        discountFee = l.discountFee
        discountInfo = mapper.writeValueAsString(l.activityInfo)
        orderItemType = if (l.plusBuy) "plus_buy" else "normal"
        templatesId = l.templatesId
        itemPoint = (item?.point ?: 0L) * l.num
        point = item?.point ?: 0L
        itemSpecDesc = q.specDescById[l.itemId]
        taxRate = item?.taxRate?.toString()
        weight = item?.weight; volume = item?.volume
        isTotalStore = true
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
}
