package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.CancelStatus
import com.ecshopx.kotlin.common.domain.DeliveryStatus
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderCancelDO
import com.ecshopx.kotlin.dao.order.OrderCancelOrderMapper
import com.ecshopx.kotlin.dao.order.OrderDeliveryDO
import com.ecshopx.kotlin.dao.order.OrderDeliveryItemDO
import com.ecshopx.kotlin.dao.order.OrderDeliveryItemMapper
import com.ecshopx.kotlin.dao.order.OrderDeliveryMapper
import com.ecshopx.kotlin.dao.order.OrderItemDO
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrderOperateLogDO
import com.ecshopx.kotlin.dao.order.OrderOperateLogMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.order.TransitPatch
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * 订单流转（ORDER-6 取消 / 确认收货 / 后台发货 / 超时关单 / 结清挂点）。
 *
 * 每一次状态变化都是「机器判定合法 → 条件更新 CAS → 只有 CAS 成功才做副作用」：
 * 用户点取消与定时任务关单同时命中同一单时，只有一边能拿到受影响行数 1，
 * 失败方得到 422「订单状态已变更」，库存不会出现重复回补。
 */
@Service
class OrderFlowService(
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val logs: OrderOperateLogMapper,
    private val cancelOrders: OrderCancelOrderMapper,
    private val deliveries: OrderDeliveryMapper,
    private val deliveryItems: OrderDeliveryItemMapper,
    private val goodsItems: ItemsMapper,
    private val configs: SysConfigMapper,
    private val db: Db,
    private val mapper: ObjectMapper,
) {

    /** ORDER-6 会员取消 */
    fun cancelByUser(userId: Long, orderId: Long, reason: String?): Map<String, Any?> {
        val o = loadOwned(userId, orderId)
        return close(
            o, OrderEvent.CANCEL,
            operatorId = userId, operator = "user:$userId", role = "buyer",
            reason = reason?.takeIf { it.isNotBlank() } ?: "会员主动取消",
        )
    }

    /**
     * 超时自动关单（@Scheduled 调用）。
     * @return false = 已被并发迁移（用户刚好在这刻付款或取消），跳过而不是错误。
     */
    fun timeoutCancel(orderId: Long): Boolean {
        val o = orders.findById(orderId) ?: return false
        if (o.orderStatus != OrderStatus.NOTPAY || o.payStatus != PayStatus.NOTPAY) return false
        if (!OrderStatusMachine.can(o.orderStatus, OrderEvent.TIMEOUT_CANCEL)) return false
        return runClose(
            o, OrderStatusMachine.require(o.orderStatus, OrderEvent.TIMEOUT_CANCEL),
            operatorId = null, operator = "system", role = "system",
            reason = "超过 ${autoCancelMinutes(o.companyId)} 分钟未付款，系统自动关闭",
            fromPayStatus = PayStatus.NOTPAY,
        )
    }

    /**
     * ORDER-7 确认收货：WAIT_BUYER_CONFIRM --CONFIRM_RECEIPT--> DONE。
     *
     * 与取消的本质区别：**货已经发出去了，一行库存都不能回补，也不能生成取消申请单**，
     * 所以走通用的 [runTransit]（只迁移状态 + 同步路由 + 留痕），不复用关单副作用。
     */
    fun confirmReceipt(userId: Long, orderId: Long): Map<String, Any?> {
        val o = loadOwned(userId, orderId)
        val t = OrderStatusMachine.require(o.orderStatus, OrderEvent.CONFIRM_RECEIPT)
        val ok = runTransit(
            o, t, operatorId = userId, operator = "user:$userId", role = "buyer",
            text = "会员确认收货，交易完成（实付 ${o.totalFee} 分）",
        )
        if (!ok) throw BizException(ErrorCode.BIZ, "订单状态已变更，请刷新后重试")
        return statusView(o, t)
    }

    /**
     * 支付结清挂点（M0-7 的 TradeService 回调里调用）：NOTPAY --PAY--> PAYED。
     * @return false = 重复回调或并发已结清，调用方按幂等处理
     */
    fun markPaid(orderId: Long, payType: String?, payChannel: String?, logText: String? = null): Boolean {
        val o = orders.findById(orderId) ?: return false
        if (!OrderStatusMachine.can(o.orderStatus, OrderEvent.PAY)) return false
        val t = OrderStatusMachine.require(o.orderStatus, OrderEvent.PAY)
        val now = TimeSec.now()
        val patch = t.toPatch().apply {
            this.payType = payType
            this.payChannel = payChannel
        }
        return db.transactional {
            val hit = orders.transit(orderId, patch, fromStatus = o.orderStatus, fromPayStatus = null, userId = null, updated = now)
            if (hit == 0) return@transactional false
            orders.syncAssoc(orderId, OrderStatus.PAYED, o.cancelStatus, DeliveryStatus.PENDING, null, now)
            logs.insert(
                OrderOperateLogDO().apply {
                    this.orderId = orderId; operatorRole = "system"; operator = "system"; behavior = t.behavior
                    this.logText = logText ?: "支付结清（${payType ?: "-"}），实付 ${o.totalFee} 分"
                },
            )
            true
        }
    }

    /** 后台改备注 */
    fun updateRemark(orderId: Long, remark: String?, operatorId: Long?, operator: String?): Map<String, Any?> {
        val o = load(orderId)
        val text = remark.orEmpty()
        db.transactional {
            orders.updateRemark(orderId, text, TimeSec.now())
            logs.insert(
                OrderOperateLogDO().apply {
                    this.orderId = orderId; this.operatorId = operatorId; this.operator = operator
                    operatorRole = "seller"; behavior = "remark"; logText = if (text.isBlank()) "清空备注" else "备注：$text"
                },
            )
            true
        }
        return mapOf("order_id" to o.orderId.toString(), "remark" to text)
    }

    /**
     * ORDER-9 后台发货：PAYED/PACKING --SHIP--> WAIT_BUYER_CONFIRM。
     * items 缺省=整单未发行全发；给出则只发这些行（可多次分包，delivery_status 走 PART_DONE）。
     */
    fun ship(orderId: Long, form: OrderDeliveryForm, operatorId: Long?, operator: String?): Map<String, Any?> {
        val o = load(orderId)
        val t = OrderStatusMachine.require(o.orderStatus, OrderEvent.SHIP)
        val rows = orderItems.listByOrder(orderId)
        if (rows.isEmpty()) throw BizException(ErrorCode.BIZ, "订单明细为空，无法发货")
        val plan = shipPlan(rows, form.items)
        val now = TimeSec.now()
        val shipped = db.transactional {
            val deliveryStatus = if (plan.fullyDelivered) DeliveryStatus.DONE else DeliveryStatus.PART_DONE
            val patch = t.toPatch().apply {
                this.deliveryStatus = deliveryStatus
                this.deliveryTime = now
                this.deliveryCorp = form.deliveryCorp
                this.deliveryCode = form.deliveryCode
                this.autoFinishTime = now + autoFinishDays(o.companyId) * 86400L
            }
            val hit = orders.transit(orderId, patch, fromStatus = o.orderStatus, fromPayStatus = null, userId = null, updated = now)
            if (hit == 0) return@transactional false
            val d = OrderDeliveryDO().apply {
                this.orderId = orderId; companyId = o.companyId; supplierId = o.supplierId
                deliveryCorp = form.deliveryCorp; deliveryCorpName = form.deliveryCorpName
                deliveryCode = form.deliveryCode
                packageType = if (form.items.isNullOrEmpty()) "batch" else "sep"
                deliveryTime = now
                deliveryPics = form.deliveryPics?.let { mapper.writeValueAsString(it) }
                created = now
            }
            deliveries.insert(d)
            plan.each { item, num ->
                deliveryItems.insert(
                    OrderDeliveryItemDO().apply {
                        ordersDeliveryId = d.ordersDeliveryId; this.orderId = orderId
                        orderItemsId = item.id; itemId = item.itemId; this.num = num
                    },
                )
                // 明细行自己的发货态：本次发完才是 DONE，否则 PART_DONE（还能继续补发）
                val delivered = item.deliveryItemNum + num
                val rowStatus = if (delivered >= item.num) DeliveryStatus.DONE else DeliveryStatus.PART_DONE
                orderItems.markDelivered(item.id, rowStatus, now, delivered.toInt(), now)
            }
            orders.syncAssoc(orderId, OrderStatus.WAIT_BUYER_CONFIRM, o.cancelStatus, deliveryStatus, null, now)
            logs.insert(
                OrderOperateLogDO().apply {
                    this.orderId = orderId; this.operatorId = operatorId; this.operator = operator
                    operatorRole = "seller"; behavior = t.behavior
                    logText = "发货：${form.deliveryCorpName ?: form.deliveryCorp ?: "-"} ${form.deliveryCode ?: "-"}，共 ${plan.totalNum} 件"
                },
            )
            true
        }
        if (!shipped) throw BizException(ErrorCode.BIZ, "订单状态已变更，请刷新后重试")
        return linkedMapOf(
            "order_id" to orderId.toString(),
            "order_status" to OrderStatus.WAIT_BUYER_CONFIRM,
            "delivery_status" to if (plan.fullyDelivered) DeliveryStatus.DONE else DeliveryStatus.PART_DONE,
            "delivery_num" to plan.totalNum,
        )
    }

    /** 发货计划：本次要发的 (明细行, 件数) + 发完后是否整单发齐 */
    private class ShipPlan(
        val rows: List<Pair<OrderItemDO, Long>>,
        val totalNum: Long,
        val fullyDelivered: Boolean,
    ) {
        fun each(block: (OrderItemDO, Long) -> Unit) = rows.forEach { (item, num) -> block(item, num) }
    }

    private fun shipPlan(rows: List<OrderItemDO>, req: List<DeliveryItemForm>?): ShipPlan {
        val pending = rows.filter { it.cancelItemNum == 0 && it.deliveryStatus != DeliveryStatus.DONE }
        if (pending.isEmpty()) throw BizException(ErrorCode.BIZ, "订单已发货完毕")
        val pairs: List<Pair<OrderItemDO, Long>> = if (req.isNullOrEmpty()) {
            pending.map { it to (it.num - it.deliveryItemNum) }
        } else {
            val byId = pending.associateBy { it.id }
            req.map { r ->
                val id = r.orderItemsId
                    ?: throw BizException(ErrorCode.VALIDATION, "order_items_id 必填", mapOf("order_items_id" to listOf("不能为空")))
                val row = byId[id] ?: throw BizException(ErrorCode.BIZ, "明细行不可发货：$id")
                val n = r.num ?: (row.num - row.deliveryItemNum)
                if (n <= 0) throw BizException(ErrorCode.VALIDATION, "发货数量必须大于 0", mapOf("num" to listOf("必须大于 0")))
                if (n > row.num - row.deliveryItemNum) throw BizException(ErrorCode.BIZ, "发货数量超过未发件数")
                row to n
            }
        }
        val numById = pairs.groupBy { it.first.id }.mapValues { e -> e.value.sumOf { it.second } }
        // 未发行发完且没有残余件数 = 整单发齐；否则 PART_DONE（多包裹分包）
        val full = pending.all { row -> (row.num - row.deliveryItemNum) == (numById[row.id] ?: 0L) }
        return ShipPlan(pairs, pairs.sumOf { it.second }, full)
    }

    /** 关单副作用：回补库存 + 明细回写 + 取消申请单 + 路由表同步 + 留痕 */
    private fun close(
        o: NormalOrderDO,
        event: OrderEvent,
        operatorId: Long?,
        operator: String?,
        role: String,
        reason: String,
        transitOverride: Transit? = null,
    ): Map<String, Any?> {
        val t = transitOverride ?: OrderStatusMachine.require(o.orderStatus, event)
        val ok = runClose(o, t, operatorId, operator, role, reason, fromPayStatus = null)
        if (!ok) throw BizException(ErrorCode.BIZ, "订单状态已变更，请刷新后重试")
        return statusView(o, t)
    }

    /** 状态迁移的统一出参（前台取消/确认收货共用） */
    private fun statusView(o: NormalOrderDO, t: Transit): Map<String, Any?> = linkedMapOf(
        "order_id" to o.orderId.toString(),
        "order_status" to (t.orderStatus ?: o.orderStatus),
        "cancel_status" to (t.cancelStatus ?: o.cancelStatus),
        "order_status_des" to OrderStatusMachine.desOf(t.orderStatus ?: o.orderStatus),
        "order_status_msg" to OrderStatusMachine.msgOf(t.orderStatus ?: o.orderStatus, t.cancelStatus ?: o.cancelStatus),
    )

    /**
     * 纯状态迁移：只 CAS 状态列 + 同步路由表 + 留痕，不碰库存、不写取消申请单。
     * 确认收货这类"往前推进"的事件必须走这里，走 [runClose] 会把已发货的库存退回仓库。
     */
    private fun runTransit(
        o: NormalOrderDO,
        t: Transit,
        operatorId: Long?,
        operator: String?,
        role: String,
        text: String,
    ): Boolean = db.transactional {
        val now = TimeSec.now()
        val hit = orders.transit(o.orderId, t.toPatch(), fromStatus = o.orderStatus, fromPayStatus = null, userId = null, updated = now)
        if (hit == 0) return@transactional false
        orders.syncAssoc(
            o.orderId, t.orderStatus ?: o.orderStatus, t.cancelStatus ?: o.cancelStatus,
            t.deliveryStatus ?: o.deliveryStatus, if (t.clearTimers) now else o.endTime, now,
        )
        logs.insert(
            OrderOperateLogDO().apply {
                this.orderId = o.orderId; this.operatorId = operatorId; this.operator = operator
                operatorRole = role; behavior = t.behavior; logText = text
            },
        )
        true
    }

    private fun runClose(
        o: NormalOrderDO,
        t: Transit,
        operatorId: Long?,
        operator: String?,
        role: String,
        reason: String,
        fromPayStatus: String?,
    ): Boolean = db.transactional {
        val now = TimeSec.now()
        val hit = orders.transit(
            o.orderId, t.toPatch(), fromStatus = o.orderStatus, fromPayStatus = fromPayStatus, userId = null, updated = now,
        )
        if (hit == 0) return@transactional false
        val rows = orderItems.listByOrder(o.orderId)
        val restored = rows.filter { it.cancelItemNum == 0 }
        restored.forEach { row ->
            goodsItems.restoreStock(row.itemId, row.num - row.deliveryItemNum, now)
            orderItems.markCancelled(row.id, (row.cancelItemNum + (row.num - row.deliveryItemNum)).toInt(), now)
        }
        // 整单关闭后路由表状态同步；已发货行（deliveryItemNum>0）不参与回补，避免"货已发出库存又回来"
        orders.syncAssoc(
            o.orderId, t.orderStatus ?: o.orderStatus, t.cancelStatus ?: CancelStatus.NOCANCEL,
            t.deliveryStatus ?: o.deliveryStatus, if (t.clearTimers) now else o.endTime, now,
        )
        cancelOrders.insert(
            OrderCancelDO().apply {
                orderId = o.orderId; companyId = o.companyId; userId = o.userId
                shopId = o.shopId; distributorId = o.distributorId; supplierId = o.supplierId
                totalFee = o.totalFee; progress = 1; cancelFrom = role
                cancelReason = reason; refundStatus = if (o.payStatus == PayStatus.PAYED) "WAIT" else "NONE"
                payType = o.payType
            },
        )
        logs.insert(
            OrderOperateLogDO().apply {
                this.orderId = o.orderId; this.operatorId = operatorId; this.operator = operator
                operatorRole = role; behavior = t.behavior; logText = reason
            },
        )
        true
    }

    private fun load(orderId: Long): NormalOrderDO =
        orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun loadOwned(userId: Long, orderId: Long): NormalOrderDO {
        val o = load(orderId)
        if (o.userId != userId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        return o
    }

    private fun autoCancelMinutes(companyId: Long): Long =
        configs.getValue(companyId, "order.auto_cancel_minutes")?.toLongOrNull() ?: 30L

    private fun autoFinishDays(companyId: Long): Long =
        configs.getValue(companyId, "order.auto_finish_days")?.toLongOrNull() ?: 10L
}

/** [Transit] → 可落库的 CAS 补丁 */
private fun Transit.toPatch(): TransitPatch = TransitPatch().apply {
    orderStatus = this@toPatch.orderStatus
    payStatus = this@toPatch.payStatus
    deliveryStatus = this@toPatch.deliveryStatus
    cancelStatus = this@toPatch.cancelStatus
    clearAutoCancelTime = this@toPatch.clearTimers
}
