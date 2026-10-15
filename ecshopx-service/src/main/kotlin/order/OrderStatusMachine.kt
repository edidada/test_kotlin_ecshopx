package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.CancelStatus
import com.ecshopx.kotlin.common.domain.DeliveryStatus
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus

/**
 * 订单事件（docs/02 §3 的箭头）。
 *
 * 状态机只回答"从这个状态、这个事件，能落到哪"，不碰库存、不发通知——
 * 副作用由 [OrderFlowService] 在迁移 CAS 成功之后执行，避免并发双写。
 */
enum class OrderEvent {
    PAY, PACK, SHIP, CONFIRM_RECEIPT, APPLY_CANCEL, AGREE_CANCEL, REFUSE_CANCEL, CANCEL, TIMEOUT_CANCEL
}

/**
 * 一次迁移要落库的增量：字段为 null 表示"这一列不动"。
 *
 * 发货后的 delivery_status 取决于"是否全部发出"（DONE/PART_DONE），属于业务判定而非状态机语义，
 * 所以 [Transit.deliveryStatus] 留空由调用方补。
 */
data class Transit(
    val orderStatus: String? = null,
    val payStatus: String? = null,
    val deliveryStatus: String? = null,
    val cancelStatus: String? = null,
    /** orders_operate_logs.behavior 取值 */
    val behavior: String = "",
    /** 迁移后清空 auto_cancel_time/auto_finish_time（已付款、已关闭、已完成都不该再被定时任务扫到） */
    val clearTimers: Boolean = false,
)

/**
 * 订单状态机（docs/05 M0-6 工作项①）：白名单迁移表 + 文案表，
 * 替代 PHP 散落在各 service 里的 if 判断（docs/02 §3）。
 *
 * 纯对象、无 DB、无时间，便于把全迁移表做成表驱动单测。
 */
object OrderStatusMachine {

    /** (order_status, event) → 迁移结果；不在表里即非法迁移 */
    private val table: Map<Pair<String, OrderEvent>, Transit> = mapOf(
        // 支付：pay_status 同步 PAYED，发货态等后台动作
        (OrderStatus.NOTPAY to OrderEvent.PAY) to Transit(
            orderStatus = OrderStatus.PAYED, payStatus = PayStatus.PAYED, behavior = "payed", clearTimers = true,
        ),
        // 后台打包（P1 挂点，M0 表先就位）
        (OrderStatus.PAYED to OrderEvent.PACK) to Transit(
            orderStatus = OrderStatus.PACKING, deliveryStatus = DeliveryStatus.PACKED, behavior = "pack",
        ),
        // 已打包发货
        (OrderStatus.PACKING to OrderEvent.SHIP) to Transit(
            orderStatus = OrderStatus.WAIT_BUYER_CONFIRM, behavior = "delivery",
        ),
        // 未打包直接发货（后台常见操作）
        (OrderStatus.PAYED to OrderEvent.SHIP) to Transit(
            orderStatus = OrderStatus.WAIT_BUYER_CONFIRM, behavior = "delivery",
        ),
        // 分包补发：整单已在待收货、但仍有明细行未发时允许再发一包，状态不回退
        // （"还有没有未发行"由 OrderFlowService.shipPlan 判定，发齐后再点发货得到 422）
        (OrderStatus.WAIT_BUYER_CONFIRM to OrderEvent.SHIP) to Transit(
            orderStatus = OrderStatus.WAIT_BUYER_CONFIRM, behavior = "delivery",
        ),
        (OrderStatus.WAIT_BUYER_CONFIRM to OrderEvent.CONFIRM_RECEIPT) to Transit(
            orderStatus = OrderStatus.DONE, deliveryStatus = DeliveryStatus.DONE, behavior = "confirm_receipt",
            clearTimers = true,
        ),
        // 取消申请（订单状态不动，只把 cancel_status 推到 CANCEL_APPLY）
        (OrderStatus.NOTPAY to OrderEvent.APPLY_CANCEL) to Transit(
            cancelStatus = CancelStatus.CANCEL_APPLY, behavior = "cancel_apply",
        ),
        (OrderStatus.PAYED to OrderEvent.APPLY_CANCEL) to Transit(
            cancelStatus = CancelStatus.CANCEL_APPLY, behavior = "cancel_apply",
        ),
        (OrderStatus.PACKING to OrderEvent.APPLY_CANCEL) to Transit(
            cancelStatus = CancelStatus.CANCEL_APPLY, behavior = "cancel_apply",
        ),
        (OrderStatus.PAYED to OrderEvent.AGREE_CANCEL) to Transit(
            orderStatus = OrderStatus.CANCEL, cancelStatus = CancelStatus.CANCEL_AGREE, behavior = "cancel_agree",
            clearTimers = true,
        ),
        (OrderStatus.PACKING to OrderEvent.AGREE_CANCEL) to Transit(
            orderStatus = OrderStatus.CANCEL, cancelStatus = CancelStatus.CANCEL_AGREE, behavior = "cancel_agree",
            clearTimers = true,
        ),
        // 拒绝取消：cancel_status 回 REFUSE_CANCEL，订单继续走
        (OrderStatus.PAYED to OrderEvent.REFUSE_CANCEL) to Transit(
            cancelStatus = CancelStatus.REFUSE_CANCEL, behavior = "cancel_refuse",
        ),
        // 未付款直接取消（前台 ORDER-6、超时关单都走这里）
        (OrderStatus.NOTPAY to OrderEvent.CANCEL) to Transit(
            orderStatus = OrderStatus.CANCEL, cancelStatus = CancelStatus.CANCEL_AGREE, behavior = "cancel",
            clearTimers = true,
        ),
        (OrderStatus.NOTPAY to OrderEvent.TIMEOUT_CANCEL) to Transit(
            orderStatus = OrderStatus.CANCEL, cancelStatus = CancelStatus.CANCEL_AGREE, behavior = "timeout_cancel",
            clearTimers = true,
        ),
        // 已付款未发货取消：M0 直接关单，退款单在 M1-1 售后域补
        (OrderStatus.PAYED to OrderEvent.CANCEL) to Transit(
            orderStatus = OrderStatus.CANCEL, cancelStatus = CancelStatus.CANCEL_AGREE, behavior = "cancel",
            clearTimers = true,
        ),
    )

    /** 合法迁移集合（单测/文档核对用） */
    fun transitions(): Set<Pair<String, OrderEvent>> = table.keys.toSet()

    fun can(from: String?, event: OrderEvent): Boolean = table.containsKey(from to event)

    /** 取迁移结果，非法迁移直接 422（前端可见文案） */
    fun require(from: String?, event: OrderEvent): Transit =
        table[from to event] ?: throw BizException(
            ErrorCode.BIZ, "当前订单状态不允许该操作",
            mapOf("order_status" to listOf("状态 $from 下不能执行 ${event.name}")),
        )

    private val desByStatus = linkedMapOf(
        OrderStatus.NOTPAY to "待付款",
        OrderStatus.PAYED to "待发货",
        OrderStatus.PACKING to "打包中",
        OrderStatus.WAIT_BUYER_CONFIRM to "待收货",
        OrderStatus.DONE to "交易成功",
        OrderStatus.CANCEL to "交易关闭",
    )

    private val msgByStatus = linkedMapOf(
        OrderStatus.NOTPAY to "请会员及时完成付款，超时订单将自动关闭",
        OrderStatus.PAYED to "买家已付款，等待卖家发货",
        OrderStatus.PACKING to "卖家正在打包，即将发货",
        OrderStatus.WAIT_BUYER_CONFIRM to "卖家已发货，等待买家确认收货",
        OrderStatus.DONE to "交易成功，欢迎再次购买",
        OrderStatus.CANCEL to "交易已关闭",
    )

    /** PHP 前台状态标题；未知状态原样返回（拆单/拼团等新态在 M1 扩表） */
    fun desOf(status: String?): String = desByStatus[status] ?: status ?: ""

    /**
     * 状态提示文案。cancel_status=CANCEL_APPLY 时优先给"取消申请处理中"，
     * 与 PHP 一致：申请中不改 order_status，但前台话术要变。
     */
    fun msgOf(status: String?, cancelStatus: String? = CancelStatus.NOCANCEL): String {
        if (cancelStatus == CancelStatus.CANCEL_APPLY) return "会员已申请取消，等待卖家处理"
        return msgByStatus[status] ?: status ?: ""
    }
}
