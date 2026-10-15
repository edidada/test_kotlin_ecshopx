package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.domain.CancelStatus
import com.ecshopx.kotlin.common.domain.DeliveryStatus
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M0-6 状态机门槛（docs/05）：全迁移表白名单驱动 + 非法迁移必拒 + 文案表可读。
 * 状态机是纯对象，不碰 DB/时间，所以这里可以穷举断言而不是抽样。
 */
class OrderStatusMachineTest {

    /** 迁移表的完整期望集合：加/减箭头都必须显式改这里，防止悄悄扩表 */
    private val expectedTable = setOf(
        OrderStatus.NOTPAY to OrderEvent.PAY,
        OrderStatus.PAYED to OrderEvent.PACK,
        OrderStatus.PAYED to OrderEvent.SHIP,
        OrderStatus.PACKING to OrderEvent.SHIP,
        OrderStatus.WAIT_BUYER_CONFIRM to OrderEvent.SHIP,
        OrderStatus.WAIT_BUYER_CONFIRM to OrderEvent.CONFIRM_RECEIPT,
        OrderStatus.NOTPAY to OrderEvent.APPLY_CANCEL,
        OrderStatus.PAYED to OrderEvent.APPLY_CANCEL,
        OrderStatus.PACKING to OrderEvent.APPLY_CANCEL,
        OrderStatus.PAYED to OrderEvent.AGREE_CANCEL,
        OrderStatus.PACKING to OrderEvent.AGREE_CANCEL,
        OrderStatus.PAYED to OrderEvent.REFUSE_CANCEL,
        OrderStatus.NOTPAY to OrderEvent.CANCEL,
        OrderStatus.PAYED to OrderEvent.CANCEL,
        OrderStatus.NOTPAY to OrderEvent.TIMEOUT_CANCEL,
    )

    @Test fun `迁移表与文档箭头集合一致`() {
        assertEquals(expectedTable, OrderStatusMachine.transitions())
    }

    @Test fun `待付款 支付后进入待发货并清定时器`() {
        val t = OrderStatusMachine.require(OrderStatus.NOTPAY, OrderEvent.PAY)
        assertEquals(OrderStatus.PAYED, t.orderStatus)
        assertEquals(PayStatus.PAYED, t.payStatus)
        assertEquals("payed", t.behavior)
        assertTrue(t.clearTimers, "已付款单不该再被超时任务扫到")
        // 支付不改发货态
        assertNull(t.deliveryStatus)
    }

    @Test fun `打包与发货两跳都到待收货`() {
        assertEquals(OrderStatus.PACKING, OrderStatusMachine.require(OrderStatus.PAYED, OrderEvent.PACK).orderStatus)
        assertEquals(DeliveryStatus.PACKED, OrderStatusMachine.require(OrderStatus.PAYED, OrderEvent.PACK).deliveryStatus)
        // SHIP 的 delivery_status 由业务判定（是否发齐），机器故意留空
        assertNull(OrderStatusMachine.require(OrderStatus.PAYED, OrderEvent.SHIP).deliveryStatus)
        assertEquals(OrderStatus.WAIT_BUYER_CONFIRM, OrderStatusMachine.require(OrderStatus.PAYED, OrderEvent.SHIP).orderStatus)
        assertEquals(OrderStatus.WAIT_BUYER_CONFIRM, OrderStatusMachine.require(OrderStatus.PACKING, OrderEvent.SHIP).orderStatus)
    }

    @Test fun `待收货补发不丢单 状态不回退`() {
        val t = OrderStatusMachine.require(OrderStatus.WAIT_BUYER_CONFIRM, OrderEvent.SHIP)
        assertEquals(OrderStatus.WAIT_BUYER_CONFIRM, t.orderStatus, "分包补发仍是待收货")
        assertEquals("delivery", t.behavior)
        assertNull(t.deliveryStatus, "整单发货态看明细是否发齐，机器不给")
        assertFalse(OrderStatusMachine.can(OrderStatus.CANCEL, OrderEvent.SHIP), "已关单不能再发货")
    }

    @Test fun `确认收货置 DONE 且发货态收口`() {
        val t = OrderStatusMachine.require(OrderStatus.WAIT_BUYER_CONFIRM, OrderEvent.CONFIRM_RECEIPT)
        assertEquals(OrderStatus.DONE, t.orderStatus)
        assertEquals(DeliveryStatus.DONE, t.deliveryStatus)
        assertEquals("confirm_receipt", t.behavior)
    }

    @Test fun `取消申请只动 cancel_status 不动订单态`() {
        listOf(OrderStatus.NOTPAY, OrderStatus.PAYED, OrderStatus.PACKING).forEach { from ->
            val t = OrderStatusMachine.require(from, OrderEvent.APPLY_CANCEL)
            assertNull(t.orderStatus, "申请取消不能改 order_status：$from")
            assertEquals(CancelStatus.CANCEL_APPLY, t.cancelStatus)
        }
        // 同意取消才真的关单
        assertEquals(OrderStatus.CANCEL, OrderStatusMachine.require(OrderStatus.PAYED, OrderEvent.AGREE_CANCEL).orderStatus)
        // 拒绝取消：订单继续走，只回写 cancel_status
        val refuse = OrderStatusMachine.require(OrderStatus.PAYED, OrderEvent.REFUSE_CANCEL)
        assertNull(refuse.orderStatus)
        assertEquals(CancelStatus.REFUSE_CANCEL, refuse.cancelStatus)
    }

    @Test fun `未付款与超时关单都到 CANCEL 且行为不同名`() {
        assertEquals("cancel", OrderStatusMachine.require(OrderStatus.NOTPAY, OrderEvent.CANCEL).behavior)
        assertEquals("timeout_cancel", OrderStatusMachine.require(OrderStatus.NOTPAY, OrderEvent.TIMEOUT_CANCEL).behavior)
        assertEquals(CancelStatus.CANCEL_AGREE, OrderStatusMachine.require(OrderStatus.NOTPAY, OrderEvent.CANCEL).cancelStatus)
    }

    @Test fun `非法迁移一律拒绝`() {
        val illegal = setOf(
            OrderStatus.DONE to OrderEvent.CANCEL,
            OrderStatus.CANCEL to OrderEvent.PAY,
            OrderStatus.NOTPAY to OrderEvent.SHIP,
            OrderStatus.PAYED to OrderEvent.CONFIRM_RECEIPT,
            OrderStatus.WAIT_BUYER_CONFIRM to OrderEvent.PAY,
            OrderStatus.PACKING to OrderEvent.PACK,
            OrderStatus.DONE to OrderEvent.SHIP,
        )
        illegal.forEach { (from, ev) ->
            assertFalse(OrderStatusMachine.can(from, ev), "$from + $ev 应非法")
            val ex = assertThrows<BizException> { OrderStatusMachine.require(from, ev) }
            assertEquals(422, ex.ec.http)
            assertTrue(ex.errors.containsKey("order_status"), "错误体要带字段名：${ex.errors}")
        }
        // 未知状态同样拒绝
        assertFalse(OrderStatusMachine.can("WHATEVER", OrderEvent.PAY))
        assertFalse(OrderStatusMachine.can(null, OrderEvent.PAY))
    }

    @Test fun `文案表覆盖六个主态且未知态原样返回`() {
        assertEquals("待付款", OrderStatusMachine.desOf(OrderStatus.NOTPAY))
        assertEquals("待发货", OrderStatusMachine.desOf(OrderStatus.PAYED))
        assertEquals("打包中", OrderStatusMachine.desOf(OrderStatus.PACKING))
        assertEquals("待收货", OrderStatusMachine.desOf(OrderStatus.WAIT_BUYER_CONFIRM))
        assertEquals("交易成功", OrderStatusMachine.desOf(OrderStatus.DONE))
        assertEquals("交易关闭", OrderStatusMachine.desOf(OrderStatus.CANCEL))
        assertEquals("WHATEVER", OrderStatusMachine.desOf("WHATEVER"))
        assertEquals("", OrderStatusMachine.desOf(null))
        assertEquals("请会员及时完成付款，超时订单将自动关闭", OrderStatusMachine.msgOf(OrderStatus.NOTPAY))
        assertEquals("卖家已发货，等待买家确认收货", OrderStatusMachine.msgOf(OrderStatus.WAIT_BUYER_CONFIRM))
    }

    @Test fun `申请取消中优先给处理中文案`() {
        assertEquals("会员已申请取消，等待卖家处理", OrderStatusMachine.msgOf(OrderStatus.PAYED, CancelStatus.CANCEL_APPLY))
        assertEquals("买家已付款，等待卖家发货", OrderStatusMachine.msgOf(OrderStatus.PAYED, CancelStatus.NOCANCEL))
        // 拒绝取消后回到正常话术
        assertEquals("买家已付款，等待卖家发货", OrderStatusMachine.msgOf(OrderStatus.PAYED, CancelStatus.REFUSE_CANCEL))
    }
}
