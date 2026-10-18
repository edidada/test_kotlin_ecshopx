package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.domain.DeliveryStatus
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderCancelOrderMapper
import com.ecshopx.kotlin.dao.order.OrderDeliveryItemMapper
import com.ecshopx.kotlin.dao.order.OrderDeliveryMapper
import com.ecshopx.kotlin.dao.order.OrderItemDO
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrderOperateLogMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.tenant.CompanyMapper
import com.ecshopx.kotlin.dao.tenant.DistributorShopMapper
import com.ecshopx.kotlin.dao.tenant.ShopProfileDO
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * 订单查询族（ORDER-4 列表 / ORDER-5 详情 / ORDER-8 角标 + 后台列表详情）。
 *
 * 出参全部为 LinkedHashMap，键顺序与 docs/04 §2.4 对齐；金额均为分、不做换算，
 * 单号 `order_id` 出参统一转字符串（16 位数字超出 JS 安全整数范围）。
 */
@Service
class OrderQueryService(
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val logs: OrderOperateLogMapper,
    private val cancelOrders: OrderCancelOrderMapper,
    private val deliveries: OrderDeliveryMapper,
    private val deliveryItems: OrderDeliveryItemMapper,
    private val shops: DistributorShopMapper,
    private val companies: CompanyMapper,
    private val goodsItems: ItemsMapper,
    private val mapper: ObjectMapper,
) {

    /**
     * ORDER-4 / 后台列表。
     * @param userId 前台必填（只查自己），后台传 null 查全租户
     * @param scope M1-4 数据权限：分销商账号只看自己名下的单（§18.8）
     */
    fun list(
        raw: Map<String, String>,
        companyId: Long,
        userId: Long?,
        scope: DistributorScope = DistributorScope.ALL,
    ): Map<String, Any?> {
        val f = filters(raw, companyId, userId)
        applyDistributorScope(f, scope)
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        val page = PageHelper.paginate(
            pq,
            count = { orders.count(f) },
            query = { offset, limit -> orders.list(f, offset, limit) },
            map = { rows -> decorate(rows) },
        )
        return linkedMapOf("total_count" to page.total_count, "list" to page.list)
    }

    /**
     * 数据权限收窄（§18.8）：admin 型不动；分销商账号把请求里的显式店过滤成"请求 ∩ 授权"，
     * 交集为空时用 [-1] 占位（`IN (-1)` 合法且自然 0 行，比拼出 `IN ()` 稳妥）。
     *
     * 列表面越界是**过滤到 0 行**而不是 404 —— 这里 distributor_id 是查询条件不是资源引用；
     * 资源引用面（[detail]、店铺档案）才按 404 处理。
     */
    private fun applyDistributorScope(f: MutableMap<String, Any?>, scope: DistributorScope) {
        if (scope.unrestricted) return
        val requested = (f["distributorId"] as? Long)?.let { listOf(it) }
        f["distributorId"] = null
        f["distributorIds"] = scope.narrow(requested)
    }

    /** 查询字典归一化：状态别名、status 数字口径、时间范围（秒） */
    private fun filters(raw: Map<String, String>, companyId: Long, userId: Long?): MutableMap<String, Any?> {
        val f = linkedMapOf<String, Any?>("companyId" to companyId)
        userId?.let { f["userId"] = it }
        raw["order_id"]?.toLongOrNull()?.let { f["orderId"] = it }
        raw["user_id"]?.toLongOrNull()?.let { if (userId == null) f["userId"] = it }
        raw["mobile"]?.takeIf { it.isNotBlank() }?.let { f["mobile"] = it }
        raw["receiver_name"]?.takeIf { it.isNotBlank() }?.let { f["receiverName"] = it }
        raw["distributor_id"]?.toLongOrNull()?.let { if (it > 0) f["distributorId"] = it }
        raw["shop_id"]?.toLongOrNull()?.let { if (it > 0) f["shopId"] = it }
        raw["pay_type"]?.takeIf { it.isNotBlank() }?.let { f["payType"] = it }
        raw["receipt_type"]?.takeIf { it.isNotBlank() }?.let { f["receiptType"] = it }
        raw["order_class"]?.takeIf { it.isNotBlank() }?.let { f["orderClass"] = it }
        raw["order_type"]?.takeIf { it.isNotBlank() }?.let { f["orderType"] = it }
        raw["is_distribution"]?.let { f["distributorId"] = if (it == "1" || it.equals("true", true)) 0L else f["distributorId"] }
        raw["time_start_begin"]?.toLongOrNull()?.let { f["createdStart"] = it }
        raw["time_start_end"]?.toLongOrNull()?.let { f["createdEnd"] = it }
        // 精确态优先于 status 数字口径
        raw["order_status"]?.takeIf { it.isNotBlank() }?.let { f["orderStatus"] = it.uppercase() }
        raw["pay_status"]?.takeIf { it.isNotBlank() }?.let { f["payStatus"] = it.uppercase() }
        raw["delivery_status"]?.takeIf { it.isNotBlank() }?.let { f["deliveryStatus"] = it.uppercase() }
        raw["cancel_status"]?.takeIf { it.isNotBlank() }?.let { f["cancelStatus"] = it.uppercase() }
        applyStatusTab(f, raw["status"]?.toIntOrNull())
        return f
    }

    /** ORDER-4 的 status 数字页签（1待收货 3完成 4待自提 5待付款 6待发货 7待评价） */
    private fun applyStatusTab(f: MutableMap<String, Any?>, status: Int?) {
        if (f["orderStatus"] != null || f["payStatus"] != null || status == null) return
        when (status) {
            1 -> f["orderStatus"] = OrderStatus.WAIT_BUYER_CONFIRM
            3 -> f["orderStatus"] = OrderStatus.DONE
            4 -> {
                f["receiptType"] = "ziti"
                f["payStatus"] = PayStatus.PAYED
                f["deliveryStatusIn"] = listOf(DeliveryStatus.PENDING, DeliveryStatus.PACKED, DeliveryStatus.PART_DONE)
            }
            5 -> f["orderStatus"] = OrderStatus.NOTPAY
            6 -> {
                // 与 ORDER-8 的 pending_delivery 同一口径：只认单头态 + 未发齐，不认 pay_status，
                // 否则退款中的单会「角标算它、页签查不到」（见 OrdersMapper.countByUser 注释）
                f["orderStatusIn"] = listOf(OrderStatus.PAYED, OrderStatus.PACKING)
                f["notDelivered"] = true
            }
            7 -> {
                f["orderStatus"] = OrderStatus.DONE
                f["unrated"] = true
            }
            else -> Unit
        }
    }

    /** 列表行 = 订单主要列 + items[] 摘要 + distributor{name,logo}（一次批量回捞，防 N+1） */
    private fun decorate(rows: List<NormalOrderDO>): List<Map<String, Any?>> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.orderId }
        val itemsByOrder = orderItems.listByOrders(ids).groupBy { it.orderId }
        val shopById = shopProfiles(rows.first().companyId, rows.map { it.shopId })
        return rows.map { o ->
            orderRowView(o, shopById[o.shopId], itemsByOrder[o.orderId].orEmpty().map { briefItem(it) })
        }
    }

    private fun orderRowView(o: NormalOrderDO, shop: ShopProfileDO?, items: List<Map<String, Any?>>): Map<String, Any?> =
        linkedMapOf(
            "order_id" to o.orderId.toString(),
            "title" to o.title,
            "company_id" to o.companyId,
            "shop_id" to o.shopId,
            "user_id" to o.userId,
            "order_class" to o.orderClass,
            "order_type" to o.orderType,
            "order_status" to o.orderStatus,
            "order_status_des" to OrderStatusMachine.desOf(o.orderStatus),
            "pay_status" to o.payStatus,
            "delivery_status" to o.deliveryStatus,
            "cancel_status" to o.cancelStatus,
            "receipt_type" to o.receiptType,
            "item_fee" to o.itemFee,
            "freight_fee" to o.freightFee,
            "discount_fee" to o.discountFee,
            "member_discount" to o.memberDiscount,
            "coupon_discount" to o.couponDiscount,
            "total_fee" to o.totalFee,
            "point_use" to o.pointUse,
            "point_fee" to o.pointFee,
            "get_points" to o.getPoints,
            "total_rebate" to o.totalRebate,
            "pay_type" to o.payType,
            "mobile" to o.mobile,
            "receiver_name" to o.receiverName,
            "receiver_mobile" to o.receiverMobile,
            "receiver_state" to o.receiverState,
            "receiver_city" to o.receiverCity,
            "receiver_district" to o.receiverDistrict,
            "receiver_address" to o.receiverAddress,
            "distributor_id" to o.distributorId,
            "salesman_id" to o.salesmanId,
            "is_distribution" to (o.distributorId > 0),
            "ziti_code" to o.zitiCode,
            "ziti_status" to o.zitiStatus,
            "delivery_corp" to o.deliveryCorp,
            "delivery_code" to o.deliveryCode,
            "delivery_time" to o.deliveryTime,
            "auto_cancel_time" to o.autoCancelTime,
            "auto_finish_time" to o.autoFinishTime,
            "end_time" to o.endTime,
            "is_rate" to o.isRate,
            "remark" to o.remark,
            "create_time" to o.created,
            "update_time" to o.updated,
            "distributor" to linkedMapOf(
                "distributor_id" to o.distributorId,
                "name" to (shop?.name ?: platformName(o.companyId)),
                "logo" to (shop?.logo ?: ""),
            ),
            "items" to items,
        )

    private fun briefItem(i: OrderItemDO): Map<String, Any?> = linkedMapOf(
        "id" to i.id,
        "order_id" to i.orderId.toString(),
        "item_id" to i.itemId,
        "item_bn" to i.itemBn,
        "item_name" to i.itemName,
        "pic" to i.pic,
        "num" to i.num,
        "price" to i.price,
        "total_fee" to i.totalFee,
        "item_fee" to i.itemFee,
        "delivery_status" to i.deliveryStatus,
        "order_item_type" to i.orderItemType,
        "item_spec_desc" to i.itemSpecDesc,
    )

    /** ORDER-5 详情 */
    fun detail(
        orderId: Long,
        userId: Long?,
        scope: DistributorScope = DistributorScope.ALL,
    ): Map<String, Any?> {
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (userId != null && o.userId != userId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        // 详情是资源引用面：越界的单对分销商账号一律"不存在"（§18.8，404 而不是 403）
        if (!scope.allows(o.distributorId)) throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        val rows = orderItems.listByOrder(orderId)
        val shop = shopProfiles(o.companyId, listOf(o.shopId))[o.shopId]
        val unitByItem = itemUnits(rows.map { it.itemId })
        val cancel = cancelOrders.findLatest(orderId)
        // 发货批次及其明细：整单包裹列表前台也要展示（自提核销、多包裹分包都靠它）
        val itemsByDelivery = deliveryItems.listByOrder(orderId).groupBy { it.ordersDeliveryId }
        val logistics = deliveries.listByOrder(orderId).map { d ->
            linkedMapOf<String, Any?>(
                "orders_delivery_id" to d.ordersDeliveryId,
                "order_id" to d.orderId.toString(),
                "delivery_corp" to d.deliveryCorp,
                "delivery_corp_name" to d.deliveryCorpName,
                "delivery_code" to d.deliveryCode,
                "package_type" to d.packageType,
                "delivery_time" to d.deliveryTime,
                "delivery_pics" to jsonList(d.deliveryPics),
                "items" to itemsByDelivery[d.ordersDeliveryId].orEmpty().map { di ->
                    linkedMapOf<String, Any?>(
                        "orders_delivery_items_id" to di.ordersDeliveryItemsId,
                        "order_items_id" to di.orderItemsId,
                        "item_id" to di.itemId,
                        "num" to di.num,
                    )
                },
            )
        }
        // 取消申请单：只回最新一条（多次申请在 M1-1 售后域里才是列表）
        val cancelView: Map<String, Any?> = cancel?.let {
            linkedMapOf(
                "cancel_id" to it.cancelId, "order_id" to it.orderId.toString(), "progress" to it.progress,
                "cancel_from" to it.cancelFrom, "cancel_reason" to it.cancelReason,
                "shop_reject_reason" to it.shopRejectReason, "refund_status" to it.refundStatus,
                "create_time" to it.created,
            )
        } ?: emptyMap()
        // 流转留痕后台全量给，前台按 PHP 口径只回状态文案不回填日志列表
        val operateLogs: List<Map<String, Any?>> =
            if (userId == null) logs.listByOrder(orderId).map { opLogView(it) } else emptyList()
        return linkedMapOf(
            "orderInfo" to orderInfo(o, shop, rows, unitByItem),
            "items" to rows.map { fullItem(it, unitByItem) },
            "order_status_des" to OrderStatusMachine.desOf(o.orderStatus),
            "order_status_msg" to OrderStatusMachine.msgOf(o.orderStatus, o.cancelStatus),
            "distributor" to linkedMapOf(
                "distributor_id" to o.distributorId,
                "name" to (shop?.name ?: platformName(o.companyId)),
                "logo" to (shop?.logo ?: ""),
                "mobile" to (shop?.mobile ?: ""),
                "address" to (shop?.address ?: ""),
            ),
            // trade 摘要在 M0-7 支付闭环落 trade 表后回填，M0 先给空对象保住字段
            "tradeInfo" to emptyMap<String, Any?>(),
            "cancelData" to cancelView,
            "logistics_items" to logistics,
            "operate_logs" to operateLogs,
        )
    }

    private fun opLogView(l: com.ecshopx.kotlin.dao.order.OrderOperateLogDO): Map<String, Any?> = linkedMapOf(
        "id" to l.id, "order_id" to l.orderId.toString(), "operator_id" to l.operatorId,
        "operator" to l.operator, "operator_role" to l.operatorRole, "behavior" to l.behavior,
        "log_text" to l.logText, "create_time" to l.created,
    )

    private fun orderInfo(
        o: NormalOrderDO,
        shop: ShopProfileDO?,
        rows: List<OrderItemDO>,
        unitByItem: Map<Long, String>,
    ): Map<String, Any?> = linkedMapOf(
        "order_id" to o.orderId.toString(),
        "title" to o.title,
        "company_id" to o.companyId,
        "user_id" to o.userId,
        "shop_id" to o.shopId,
        "order_class" to o.orderClass,
        "order_type" to o.orderType,
        "order_status" to o.orderStatus,
        "pay_status" to o.payStatus,
        "delivery_status" to o.deliveryStatus,
        "cancel_status" to o.cancelStatus,
        "order_source" to o.orderSource,
        "order_holder" to o.orderHolder,
        "order_from" to o.orderFrom,
        // 拼团单挂团号（D29）：参团页与"我的团"都要从订单详情跳回团，非拼团单为 null
        "team_id" to o.teamId,
        "item_fee" to o.itemFee,
        "market_fee" to o.marketFee,
        "cost_fee" to o.costFee,
        "freight_fee" to o.freightFee,
        "discount_fee" to o.discountFee,
        "discount_info" to discountInfo(rows),
        "member_discount" to o.memberDiscount,
        "coupon_discount" to o.couponDiscount,
        "point" to o.itemPoint,
        "point_use" to o.pointUse,
        "point_fee" to o.pointFee,
        "get_points" to o.getPoints,
        "total_rebate" to o.totalRebate,
        "total_fee" to o.totalFee,
        "pay_fee" to o.payFee,
        "pay_type" to o.payType,
        "pay_channel" to o.payChannel,
        "receipt_type" to o.receiptType,
        "ziti_code" to o.zitiCode,
        "ziti_status" to o.zitiStatus,
        "invoice" to o.invoice,
        "is_invoiced" to o.isInvoiced,
        "invoice_status" to o.invoiceStatus,
        "fee_type" to o.feeType,
        "fee_rate" to o.feeRate,
        "fee_symbol" to o.feeSymbol,
        "type" to o.type,
        "taxable_fee" to o.taxableFee,
        "total_tax" to o.totalTax,
        "mobile" to o.mobile,
        "remark" to o.remark,
        "receiver_name" to o.receiverName,
        "receiver_mobile" to o.receiverMobile,
        "receiver_state" to o.receiverState,
        "receiver_city" to o.receiverCity,
        "receiver_district" to o.receiverDistrict,
        "receiver_address" to o.receiverAddress,
        "salesman_id" to o.salesmanId,
        "distributor_id" to o.distributorId,
        "supplier_id" to o.supplierId,
        "is_distribution" to (o.distributorId > 0),
        "delivery_corp" to o.deliveryCorp,
        "delivery_code" to o.deliveryCode,
        "delivery_img" to "",
        "delivery_status" to o.deliveryStatus,
        "delivery_time" to o.deliveryTime,
        "is_rate" to o.isRate,
        "auto_cancel_time" to o.autoCancelTime,
        "auto_finish_time" to o.autoFinishTime,
        "end_time" to o.endTime,
        "create_time" to o.created,
        "update_time" to o.updated,
        "shop_name" to (shop?.name ?: platformName(o.companyId)),
    )

    /**
     * discount_info：把行上的活动明细聚合成订单级 `[{type,rule,discount_fee}]`。
     *
     * 这里**不能**复用 [jsonList]：那个helper是给 `pics` 这类"字符串数组"用的（元素回 `String`），
     * 而本列存的是对象数组。原先写成 `jsonList(...).mapNotNull { it as? Map }`，
     * 字符串永远 cast 不成 Map → 这个字段在任何单据上都是空数组（实测：满减单 `discount_fee=3000`
     * 而 `discount_info=[]`），折让构成从 HTTP 面整个消失。改成按对象数组解析。
     */
    private fun discountInfo(rows: List<OrderItemDO>): List<Map<String, Any?>> =
        rows.flatMap { i -> jsonMaps(i.discountInfo) }
            .map { linkedMapOf<String, Any?>("type" to it["type"], "rule" to it["rule"], "discount_fee" to it["discount_fee"]) }

    /** 对象数组列 → `List<Map>`；不是数组或解析失败都回空集（脏行不许把整张详情带崩） */
    private fun jsonMaps(raw: String?): List<Map<String, Any?>> {
        if (raw.isNullOrBlank()) return emptyList()
        val n = runCatching { mapper.readTree(raw) }.getOrNull() ?: return emptyList()
        if (!n.isArray) return emptyList()
        return n.mapNotNull { e ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                mapper.convertValue(e, Map::class.java) as Map<String, Any?>
            }.getOrNull()
        }
    }

    private fun fullItem(i: OrderItemDO, unitByItem: Map<Long, String>): Map<String, Any?> = linkedMapOf(
        "id" to i.id,
        "order_id" to i.orderId.toString(),
        "item_id" to i.itemId,
        "goods_id" to i.goodsId,
        "item_bn" to i.itemBn,
        "item_name" to i.itemName,
        "pic" to i.pic,
        "num" to i.num,
        "price" to i.price,
        "total_fee" to i.totalFee,
        "item_fee" to i.itemFee,
        "cost_fee" to i.costFee,
        "market_price" to i.marketPrice,
        "templates_id" to i.templatesId,
        "rebate" to i.rebate,
        "total_rebate" to i.totalRebate,
        "item_unit" to (unitByItem[i.itemId] ?: ""),
        "member_discount" to i.memberDiscount,
        "coupon_discount" to i.couponDiscount,
        "discount_fee" to i.discountFee,
        "delivery_status" to i.deliveryStatus,
        "delivery_item_num" to i.deliveryItemNum,
        "cancel_item_num" to i.cancelItemNum,
        "aftersales_status" to i.aftersalesStatus,
        "refunded_fee" to i.refundedFee,
        "item_spec_desc" to i.itemSpecDesc,
        "order_item_type" to i.orderItemType,
        "share_points" to i.sharePoints,
        "point" to i.point,
        "item_point" to i.itemPoint,
        "point_fee" to i.pointFee,
        "get_points" to 0L,
        "weight" to i.weight,
        "volume" to i.volume,
    )

    /** ORDER-8 角标 */
    fun counts(companyId: Long, userId: Long): Map<String, Any?> {
        val c = orders.countByUser(companyId, userId)
        return linkedMapOf(
            "notpay" to (c?.notpay ?: 0L),
            "pending_delivery" to (c?.pendingDelivery ?: 0L),
            "pending_receive" to (c?.pendingReceive ?: 0L),
            "done" to (c?.done ?: 0L),
            "evaluation" to (c?.evaluation ?: 0L),
            "cancel" to (c?.cancel ?: 0L),
            "total" to (c?.total ?: 0L),
        )
    }

    /** 后台列表用：待超时关单单（定时任务与后台排查共用一个口径） */
    fun timeoutIds(now: Long, limit: Int): List<Long> = orders.listTimeout(now, limit)

    /** 店铺档案：自营（shop_id=0）不在 distribution_distributor 里，回落到租户名 */
    private fun shopProfiles(companyId: Long, ids: List<Long>): Map<Long, ShopProfileDO> {
        val real = ids.filter { it > 0 }.distinct()
        if (real.isEmpty()) return emptyMap()
        return shops.listByIds(companyId, real).associateBy { it.distributorId }
    }

    private fun platformName(companyId: Long): String = companies.findById(companyId)?.companyName ?: ""

    /** item_unit 不在订单明细表里，展示时回查商品表（一次批量，M1 落单时再决定要不要快照进表） */
    private fun itemUnits(ids: List<Long>): Map<Long, String> {
        val uniq = ids.filter { it > 0 }.distinct()
        if (uniq.isEmpty()) return emptyMap()
        return goodsItems.listByIds(uniq).associate { it.itemId to (it.itemUnit ?: "") }
    }

    private fun jsonList(raw: String?): List<Any?> =
        if (raw.isNullOrBlank()) emptyList() else runCatching {
            val n = mapper.readTree(raw)
            if (n.isArray) n.map { it.toString() } else listOf(raw)
        }.getOrDefault(listOf(raw))
}
