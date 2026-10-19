package com.ecshopx.kotlin.service.webhook

/**
 * 事件目录（M2-1 §25.4 那张表的代码形态）。
 *
 * 它同时是三样东西的唯一来源：发布侧的 topic 常量、`WH-A8` 的对外文档面、e2e 里断载荷字段的清单。
 * 源系统的对应物是 `EventServiceProvider` 里那张 `事件 → 监听器` 的硬编码映射，
 * 那份只有代码能读、对接口没有任何面 —— 本项目把目录做成一个接口（`WH-A8`），
 * 理由是"文档面从代码出"：另维护一份清单必然漂，漂了受害的是对接方。
 */
object WebhookTopics {
    const val ORDER_CREATED = "order.created"
    const val ORDER_PAID = "order.paid"
    const val ORDER_SHIPPED = "order.shipped"
    const val ORDER_COMPLETED = "order.completed"
    const val ORDER_CLOSED = "order.closed"
    const val GOODS_CREATED = "goods.created"
    const val GOODS_UPDATED = "goods.updated"
    const val GOODS_DELETED = "goods.deleted"
    const val REFUND_FINISHED = "refund.finished"

    /** 只有 WH-A9 会发这一条：它是"连通性自检"，不是业务事件 */
    const val TEST = "webhook.test"

    /** 挂点：哪个类的哪个方法在发这一条（`WH-A8` 出参里给对接方看，也给 reviewer 看） */
    private data class Spec(val topic: String, val hook: String, val fields: List<String>, val summary: String)

    private val specs = listOf(
        Spec(
            ORDER_CREATED, "OrderCreateService.doCreate() 事务内",
            listOf("order_id", "user_id", "distributor_id", "shop_id", "pay_fee", "status", "created"),
            "订单创建成功",
        ),
        Spec(
            ORDER_PAID, "OrderFlowService.markPaid() CAS 命中那一支",
            listOf("order_id", "pay_fee", "pay_type", "pay_time"),
            "支付结清（重复回调不重发）",
        ),
        Spec(
            ORDER_SHIPPED, "OrderFlowService.ship()",
            listOf("order_id", "orders_delivery_id", "delivery_corp", "delivery_code", "delivered"),
            "发货（含分包）",
        ),
        Spec(
            ORDER_COMPLETED, "OrderFlowService.confirmReceipt()",
            listOf("order_id", "finish_time"),
            "确认收货、交易完成",
        ),
        Spec(
            ORDER_CLOSED, "OrderFlowService.cancelByUser() / timeoutCancel() 迁移命中后",
            listOf("order_id", "reason", "closed_at"),
            "订单关闭（用户取消或超时自动关单）",
        ),
        Spec(
            GOODS_CREATED, "ItemService.create()",
            listOf("item_id", "item_bn", "item_name", "price", "store", "approve_status"),
            "商品新建",
        ),
        Spec(
            GOODS_UPDATED, "ItemService.update()",
            listOf("item_id", "item_bn", "item_name", "price", "store", "approve_status"),
            "商品修改",
        ),
        Spec(
            GOODS_DELETED, "ItemService.delete()",
            listOf("item_id", "item_bn"),
            "商品删除",
        ),
        Spec(
            REFUND_FINISHED, "RefundService.confirmByCallback()",
            listOf("refund_id", "order_id", "refund_fee", "finished_at"),
            "退款到账（回调确认成功）",
        ),
        Spec(TEST, "只有 WH-A9", listOf("echo", "sent_at"), "连通性自检事件"),
    )

    val all: Set<String> get() = specs.map { it.topic }.toSet()

    fun isKnown(topic: String): Boolean = all.contains(topic)

    /** `WH-A8` 出参：topic + 挂点 + 载荷字段名 + 一句说明 */
    fun catalog(): List<Map<String, Any?>> = specs.map { s ->
        linkedMapOf<String, Any?>(
            "topic" to s.topic,
            "summary" to s.summary,
            "hook" to s.hook,
            "data_fields" to s.fields,
        )
    }
}
