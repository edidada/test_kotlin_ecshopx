package com.ecshopx.kotlin.service.order

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.DeliveryStatus
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.distribution.PickupLocationMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrderOperateLogDO
import com.ecshopx.kotlin.dao.order.OrderOperateLogMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.stereotype.Service

/**
 * M1-4 自提核销（docs/04 §18.6，WO-A1/A2 = SO-E4 的同一个 service 方法）。
 *
 * 四条闸门的顺序不是随便排的，是"从最不像错误到最像错误"：
 *  1. **不是自提单**（`receipt_type != 'ziti'`）—— 这是调用方用错端点，422 说清"这单是物流单"；
 *  2. **没付款/已关闭** —— 未付款的单没有取货资格，已关闭的单货都退回池子了，两样都拦；
 *  3. **码不对** —— 逐字比（只 trim 两侧空白，不做大小写与包含匹配）；
 *  4. **已核销** —— 这一条与下面的 CAS 是一对：读到的 `ziti_status` 只是给人看的判据，
 *     真正防双核的是 `UPDATE … WHERE ziti_status='false'` 的受影响行数。
 *
 * 核销即完成：自提单没有发货环节，`order_status` 直接进 `DONE`、`delivery_status` 同步 `DONE`、
 * `end_time` 落此刻，两个自动时间清空（不然后台定时器会去动一个已经完成的单）。
 * 后台（WO-A2）与员工端（SO-E4）打的是**同一个方法**，只带各自的 `distributor_id` 数据权限（§18.3），
 * 不复制第二套核销逻辑 —— 核销是"承认货已到手"，两套实现迟早漂，漂了就说不清谁该为这单负责。
 */
@Service
class WriteOffService(
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val logs: OrderOperateLogMapper,
    private val locations: PickupLocationMapper,
    private val db: Db,
) {

    /** WO-A1 核销查询（后台/员工端"输码前先看清这单"）；给了 `code` 就顺比一次 */
    fun query(
        companyId: Long,
        orderId: Long,
        code: String?,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val o = load(companyId, orderId, scope)
        if (code != null && code.isNotBlank()) requireCode(o, code)
        return view(o, withItems = true)
    }

    /**
     * WO-A2 / SO-E4 核销执行。
     *
     * `operator`/`operatorRole` 由调用方给（运营 = `operator:{id}`/`admin`，员工端第三批给 `salesman:{id}`），
     * 留痕进 `orders_operate_logs`：核销是"承认买家已拿到货"，这张单之后不能再退、不能再改，
     * 所以**谁点的这一下必须可追**。
     */
    fun writeOff(
        companyId: Long,
        orderId: Long,
        code: String,
        scope: DistributorScope,
        operatorId: Long,
        operator: String,
        operatorRole: String,
    ): Map<String, Any?> {
        val o = load(companyId, orderId, scope)
        if (o.receiptType != ZITI) throw BizException(ErrorCode.BIZ, "该订单不是自提订单")
        if (o.orderStatus == OrderStatus.CANCEL) throw BizException(ErrorCode.BIZ, "订单已关闭，不能核销")
        if (o.payStatus != PayStatus.PAYED) throw BizException(ErrorCode.BIZ, "订单未付款，不能核销")
        if (o.zitiStatus == TRUE) throw BizException(ErrorCode.BIZ, "该单已核销")
        requireCode(o, code)
        val now = TimeSec.now()
        db.transactional {
            if (orders.writeOff(orderId, now) == 0) throw BizException(ErrorCode.BIZ, "该单已核销，请勿重复操作")
            orders.syncAssoc(orderId, OrderStatus.DONE, o.cancelStatus, DeliveryStatus.DONE, now, now)
            logs.insert(
                OrderOperateLogDO().apply {
                    this.orderId = orderId
                    this.operatorId = operatorId
                    this.operator = operator
                    this.operatorRole = operatorRole
                    behavior = "writeoff"
                    logText = "自提核销（码 ${o.zitiCode ?: "-"}），交易完成（实付 ${o.payFee} 分）"
                },
            )
            Unit
        }
        return view(orders.findById(orderId)!!, withItems = true)
    }

    /**
     * ZT-F1 会员查自己的取货码。
     *
     * 与 WO-A1 的区别只在**归属判据**：这里按 `user_id` 认（不是本人的单 404），
     * 那里按数据权限认。码只给本人看（§18.6："码不给别人看"），所以这条是会员面端点、不是匿名面。
     */
    fun myCode(
        companyId: Long,
        userId: Long,
        orderId: Long,
    ): Map<String, Any?> {
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.companyId != companyId || o.userId != userId) throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.receiptType != ZITI) throw BizException(ErrorCode.BIZ, "该订单不是自提订单")
        return linkedMapOf(
            "order_id" to o.orderId.toString(),
            "ziti_code" to (o.zitiCode ?: ""),
            "ziti_status" to (o.zitiStatus ?: FALSE),
            "pickup" to pickupView(companyId, o.pickupLocationId),
            "valid_text" to if (o.zitiStatus == TRUE) "已于 ${DF.format(TimeSec.of(o.updated))} 核销，本码已失效" else "到店出示本码，核销后当场生效",
        )
    }

    private fun load(
        companyId: Long,
        orderId: Long,
        scope: DistributorScope,
    ): NormalOrderDO {
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        // 资源引用面越权 = 404，不给"这单存在"的信息（C145）
        scope.require(o.distributorId)
        return o
    }

    private fun requireCode(
        o: NormalOrderDO,
        code: String,
    ) {
        if (o.zitiCode.isNullOrBlank()) throw BizException(ErrorCode.BIZ, "该单没有取货码")
        if (o.zitiCode != code.trim()) throw BizException(ErrorCode.BIZ, "取货码不正确", mapOf("ziti_code" to listOf("与订单不符")))
    }

    private fun view(
        o: NormalOrderDO,
        withItems: Boolean,
    ): Map<String, Any?> = linkedMapOf(
        "order_id" to o.orderId.toString(),
        "order_no" to o.orderId.toString(),
        "ziti_code" to (o.zitiCode ?: ""),
        "ziti_status" to (o.zitiStatus ?: FALSE),
        "ziti_status_text" to if (o.zitiStatus == TRUE) "已核销" else "待核销",
        "order_status" to o.orderStatus,
        "pay_status" to o.payStatus,
        "receipt_type" to o.receiptType,
        "pickup_location_id" to o.pickupLocationId,
        "distributor_id" to o.distributorId,
        "user_id" to o.userId,
        "receiver_name" to o.receiverName,
        "receiver_mobile" to o.receiverMobile,
        "total_fee" to o.totalFee,
        "pay_fee" to o.payFee,
        "created" to o.created,
        "updated" to o.updated,
        "pickup" to pickupView(o.companyId, o.pickupLocationId),
        "items" to if (withItems) orderItems.listByOrder(o.orderId).map {
            linkedMapOf(
                "item_id" to it.itemId, "item_name" to it.itemName, "num" to it.num,
                "price" to it.price, "total_fee" to it.totalFee,
            )
        } else emptyList<Map<String, Any?>>(),
    )

    /** 点的地址块：单上只存 id，名字与地址读现值（点改了地址，核销页要显示"现在去哪儿取"） */
    private fun pickupView(
        companyId: Long,
        id: Long,
    ): Map<String, Any?> {
        if (id <= 0) return emptyMap()
        val p = locations.findById(id, companyId) ?: return emptyMap()
        return linkedMapOf(
            "id" to p.id,
            "name" to (p.name ?: ""),
            "province" to (p.province ?: ""),
            "city" to (p.city ?: ""),
            "area" to (p.area ?: ""),
            "address" to (p.address ?: ""),
            "full_address" to listOf(p.province, p.city, p.area, p.address).filter { !it.isNullOrBlank() }.joinToString(""),
            "contract_phone" to (p.contractPhone ?: ""),
            "status" to p.status,
        )
    }

    companion object {
        const val ZITI = "ziti"
        const val TRUE = "true"
        const val FALSE = "false"

        /** 给人看的那一行时刻必须有分有秒，"1792296000" 这种数没法读（文案位，不参与任何判据） */
        private val DF = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(TimeSec.TZ)
    }
}

/** WO-A2 核销执行入参（`ziti_code` 必填；空串不当"没传"处理，直接进逐字比对失败） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class WriteOffForm(
    val zitiCode: String? = null,
)
