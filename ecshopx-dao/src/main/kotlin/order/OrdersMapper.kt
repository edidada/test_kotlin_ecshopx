package com.ecshopx.kotlin.dao.order

import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 订单主表 Mapper（ORDER-2..9）。
 *
 * 状态迁移一律走**条件更新（CAS）**：`WHERE order_id=? AND order_status=?`，
 * 返回 0 即表示并发下已被别的请求迁移（重复支付回调、用户取消与超时关单同时命中），
 * Service 依据受影响行数决定后续动作，不做"读—判—写"。
 */
@Mapper
interface OrdersMapper {

    companion object {
        const val COLS =
            "order_id, title, company_id, shop_id, user_id, act_id, mobile, order_class, order_type, " +
            "order_status, pay_status, delivery_status, cancel_status, order_source, order_holder, order_from, " +
            "item_fee, total_fee, pay_fee, market_fee, cost_fee, commission_fee, freight_fee, discount_fee, member_discount, " +
            "coupon_discount, point_fee, point_use, item_point, get_points, total_rebate, distributor_id, salesman_id, " +
            "supplier_id, merchant_id, original_order_id, type, taxable_fee, total_tax, fee_type, fee_rate, fee_symbol, " +
            "pay_type, pay_channel, receipt_type, ziti_code, ziti_status, invoice, is_invoiced, invoice_status, " +
            "auto_cancel_time, auto_finish_time, end_time, delivery_time, delivery_corp, delivery_code, " +
            "receiver_name, receiver_mobile, receiver_state, receiver_city, receiver_district, receiver_address, " +
            "is_rate, left_aftersales_num, source_from, is_online_order, remark, created, updated"

        /** 前台与后台共用的筛选字典（键缺省即不启用；status 三态在服务层归一化） */
        const val WHERE = """
            WHERE company_id = #{f.companyId}
            <if test="f.userId != null">AND user_id = #{f.userId}</if>
            <if test="f.orderId != null">AND order_id = #{f.orderId}</if>
            <if test="f.orderStatus != null">AND order_status = #{f.orderStatus}</if>
            <if test="f.orderStatusIn != null">AND order_status IN <foreach item="s" collection="f.orderStatusIn" open="(" separator="," close=")">#{s}</foreach></if>
            <if test="f.deliveryStatusIn != null">AND delivery_status IN <foreach item="d" collection="f.deliveryStatusIn" open="(" separator="," close=")">#{d}</foreach></if>
            <if test="f.notDelivered == true">AND delivery_status IN ('PENDING','PART_DONE','PACKED')</if>
            <if test="f.unrated == true">AND is_rate = FALSE</if>
            <if test="f.zitiOnly == true">AND receipt_type = 'ziti'</if>
            <if test="f.payStatus != null">AND pay_status = #{f.payStatus}</if>
            <if test="f.deliveryStatus != null">AND delivery_status = #{f.deliveryStatus}</if>
            <if test="f.cancelStatus != null">AND cancel_status = #{f.cancelStatus}</if>
            <if test="f.orderClass != null">AND order_class = #{f.orderClass}</if>
            <if test="f.orderType != null">AND order_type = #{f.orderType}</if>
            <if test="f.distributorId != null">AND distributor_id = #{f.distributorId}</if>
            <if test="f.shopId != null">AND shop_id = #{f.shopId}</if>
            <if test="f.supplierId != null">AND supplier_id = #{f.supplierId}</if>
            <if test="f.salesmanId != null">AND salesman_id = #{f.salesmanId}</if>
            <if test="f.receiptType != null">AND receipt_type = #{f.receiptType}</if>
            <if test="f.payType != null">AND pay_type = #{f.payType}</if>
            <if test="f.mobile != null">AND (mobile LIKE CONCAT('%', #{f.mobile}, '%') OR receiver_mobile LIKE CONCAT('%', #{f.mobile}, '%'))</if>
            <if test="f.receiverName != null">AND receiver_name LIKE CONCAT('%', #{f.receiverName}, '%')</if>
            <if test="f.createdStart != null">AND created &gt;= #{f.createdStart}</if>
            <if test="f.createdEnd != null">AND created &lt;= #{f.createdEnd}</if>
            <if test="f.unpaidTimeout != null">AND order_status = 'NOTPAY' AND auto_cancel_time &lt; #{f.unpaidTimeout}</if>
        """
    }

    @Insert(
        "INSERT INTO orders_normal_orders(order_id, title, company_id, shop_id, user_id, act_id, mobile, order_class, " +
            "order_type, order_status, pay_status, delivery_status, cancel_status, order_source, order_holder, order_from, " +
            "item_fee, total_fee, market_fee, cost_fee, commission_fee, freight_fee, discount_fee, member_discount, " +
            "coupon_discount, point_fee, point_use, item_point, get_points, total_rebate, distributor_id, salesman_id, " +
            "supplier_id, merchant_id, original_order_id, type, taxable_fee, total_tax, fee_type, fee_rate, fee_symbol, " +
            "pay_type, pay_channel, receipt_type, ziti_code, ziti_status, invoice, is_invoiced, invoice_status, " +
            "auto_cancel_time, auto_finish_time, end_time, delivery_time, delivery_corp, delivery_code, receiver_name, " +
            "receiver_mobile, receiver_state, receiver_city, receiver_district, receiver_address, is_rate, " +
            "left_aftersales_num, source_from, is_online_order, remark, created, updated) " +
            "VALUES(#{orderId}, #{title}, #{companyId}, #{shopId}, #{userId}, #{actId}, #{mobile}, #{orderClass}, " +
            "#{orderType}, #{orderStatus}, #{payStatus}, #{deliveryStatus}, #{cancelStatus}, #{orderSource}, #{orderHolder}, #{orderFrom}, " +
            "#{itemFee}, #{totalFee}, #{marketFee}, #{costFee}, #{commissionFee}, #{freightFee}, #{discountFee}, #{memberDiscount}, " +
            "#{couponDiscount}, #{pointFee}, #{pointUse}, #{itemPoint}, #{getPoints}, #{totalRebate}, #{distributorId}, #{salesmanId}, " +
            "#{supplierId}, #{merchantId}, #{originalOrderId}, #{type}, #{taxableFee}, #{totalTax}, #{feeType}, #{feeRate}, #{feeSymbol}, " +
            "#{payType}, #{payChannel}, #{receiptType}, #{zitiCode}, #{zitiStatus}, #{invoice}, #{isInvoiced}, #{invoiceStatus}, " +
            "#{autoCancelTime}, #{autoFinishTime}, #{endTime}, #{deliveryTime}, #{deliveryCorp}, #{deliveryCode}, #{receiverName}, " +
            "#{receiverMobile}, #{receiverState}, #{receiverCity}, #{receiverDistrict}, #{receiverAddress}, #{isRate}, " +
            "#{leftAftersalesNum}, #{sourceFrom}, #{isOnlineOrder}, #{remark}, #{created}, #{updated})",
    )
    fun insert(o: NormalOrderDO): Int

    @Select("SELECT $COLS FROM orders_normal_orders WHERE order_id = #{orderId}")
    fun findById(@Param("orderId") orderId: Long): NormalOrderDO?

    @Select("<script>SELECT $COLS FROM orders_normal_orders " + WHERE +
        " ORDER BY order_id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    fun list(
        @Param("f") f: Map<String, Any?>,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<NormalOrderDO>

    @Select("<script>SELECT COUNT(*) FROM orders_normal_orders " + WHERE + "</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    /**
     * 状态迁移（CAS）：只写非 null 字段，`fromStatus` 非空时作为乐观条件。
     * 返回 0 = 当前状态不是 fromStatus（并发已迁移），调用方据此放弃后续动作。
     */
    @Update(
        "<script>UPDATE orders_normal_orders SET updated = #{updated}" +
            "<if test='patch.orderStatus != null'>, order_status = #{patch.orderStatus}</if>" +
            "<if test='patch.payStatus != null'>, pay_status = #{patch.payStatus}</if>" +
            "<if test='patch.deliveryStatus != null'>, delivery_status = #{patch.deliveryStatus}</if>" +
            "<if test='patch.cancelStatus != null'>, cancel_status = #{patch.cancelStatus}</if>" +
            "<if test='patch.payType != null'>, pay_type = #{patch.payType}</if>" +
            "<if test='patch.payChannel != null'>, pay_channel = #{patch.payChannel}</if>" +
            "<if test='patch.payFee != null'>, pay_fee = #{patch.payFee}</if>" +
            "<if test='patch.endTime != null'>, end_time = #{patch.endTime}</if>" +
            "<if test='patch.deliveryTime != null'>, delivery_time = #{patch.deliveryTime}</if>" +
            "<if test='patch.deliveryCorp != null'>, delivery_corp = #{patch.deliveryCorp}</if>" +
            "<if test='patch.deliveryCode != null'>, delivery_code = #{patch.deliveryCode}</if>" +
            "<if test='patch.autoCancelTime != null'>, auto_cancel_time = #{patch.autoCancelTime}</if>" +
            "<if test='patch.autoFinishTime != null'>, auto_finish_time = #{patch.autoFinishTime}</if>" +
            "<if test='patch.clearAutoCancelTime'>, auto_cancel_time = NULL, auto_finish_time = NULL</if>" +
            "<if test='patch.receiverName != null'>, receiver_name = #{patch.receiverName}</if>" +
            "<if test='patch.receiverMobile != null'>, receiver_mobile = #{patch.receiverMobile}</if>" +
            "<if test='patch.receiverState != null'>, receiver_state = #{patch.receiverState}</if>" +
            "<if test='patch.receiverCity != null'>, receiver_city = #{patch.receiverCity}</if>" +
            "<if test='patch.receiverDistrict != null'>, receiver_district = #{patch.receiverDistrict}</if>" +
            "<if test='patch.receiverAddress != null'>, receiver_address = #{patch.receiverAddress}</if>" +
            "<if test='patch.mobile != null'>, mobile = #{patch.mobile}</if>" +
            "<if test='patch.title != null'>, title = #{patch.title}</if>" +
            "<if test='patch.remark != null'>, remark = #{patch.remark}</if>" +
            "<if test='patch.zitiCode != null'>, ziti_code = #{patch.zitiCode}</if>" +
            "<if test='patch.zitiStatus != null'>, ziti_status = #{patch.zitiStatus}</if>" +
            " WHERE order_id = #{orderId}" +
            "<if test='fromStatus != null'> AND order_status = #{fromStatus}</if>" +
            "<if test='fromPayStatus != null'> AND pay_status = #{fromPayStatus}</if>" +
            "<if test='userId != null'> AND user_id = #{userId}</if>" +
            "</script>",
    )
    fun transit(
        @Param("orderId") orderId: Long,
        @Param("patch") patch: TransitPatch,
        @Param("fromStatus") fromStatus: String?,
        @Param("fromPayStatus") fromPayStatus: String?,
        @Param("userId") userId: Long?,
        @Param("updated") updated: Long,
    ): Int

    /** 路由表状态同步（orders_associations 与主表同名状态列） */
    @Update("UPDATE orders_associations SET order_status = #{orderStatus}, cancel_status = #{cancelStatus}, " +
        "delivery_status = #{deliveryStatus}, end_time = #{endTime}, updated = #{updated} WHERE order_id = #{orderId}")
    fun syncAssoc(
        @Param("orderId") orderId: Long,
        @Param("orderStatus") orderStatus: String,
        @Param("cancelStatus") cancelStatus: String,
        @Param("deliveryStatus") deliveryStatus: String,
        @Param("endTime") endTime: Long?,
        @Param("updated") updated: Long,
    ): Int

    /** 后台改备注（备注只走这一条，避免与状态迁移混在一起） */
    @Update("UPDATE orders_normal_orders SET remark = #{remark}, updated = #{updated} WHERE order_id = #{orderId}")
    fun updateRemark(@Param("orderId") orderId: Long, @Param("remark") remark: String, @Param("updated") updated: Long): Int

    /**
     * ORDER-8 角标：一次条件聚合（口径与 docs/04 §2.4 的状态映射一致）。
     * 别名用下划线式，靠 map-underscore-to-camel-case 映射——H2 会把大写别名整段小写，驼峰别名会丢。
     */
    @Select("SELECT " +
        "COALESCE(SUM(CASE WHEN order_status = 'NOTPAY' THEN 1 ELSE 0 END), 0) AS notpay, " +
        "COALESCE(SUM(CASE WHEN pay_status = 'PAYED' AND delivery_status IN ('PENDING','PART_DONE','PACKED') THEN 1 ELSE 0 END), 0) AS pending_delivery, " +
        "COALESCE(SUM(CASE WHEN order_status = 'WAIT_BUYER_CONFIRM' THEN 1 ELSE 0 END), 0) AS pending_receive, " +
        "COALESCE(SUM(CASE WHEN order_status = 'DONE' THEN 1 ELSE 0 END), 0) AS done, " +
        "COALESCE(SUM(CASE WHEN order_status = 'DONE' AND is_rate = FALSE THEN 1 ELSE 0 END), 0) AS evaluation, " +
        "COALESCE(SUM(CASE WHEN order_status = 'CANCEL' THEN 1 ELSE 0 END), 0) AS cancel, " +
        "COUNT(*) AS total FROM orders_normal_orders WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun countByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): OrderCountRow?

    /**
     * MEM-1/MEM-7 消费聚合：累计实付与已付单数。
     * 只认结清态（M0 无售后，REFUND* 不出现）；别名用下划线式，H2 会把驼峰别名整段小写。
     */
    @Select("SELECT COALESCE(SUM(pay_fee), 0) AS total_consumption, COUNT(*) AS paid_orders " +
        "FROM orders_normal_orders WHERE company_id = #{companyId} AND user_id = #{userId} AND pay_status = 'PAYED'")
    fun consumption(@Param("companyId") companyId: Long, @Param("userId") userId: Long): ConsumptionRow?

    /** 超时未支付（扫描用；主键升序限量，处理完自然滚过） */
    @Select("SELECT order_id FROM orders_normal_orders " +
        "WHERE order_status = 'NOTPAY' AND pay_status = 'NOTPAY' AND auto_cancel_time IS NOT NULL AND auto_cancel_time < #{now} " +
        "ORDER BY order_id LIMIT #{limit}")
    fun listTimeout(@Param("now") now: Long, @Param("limit") limit: Int): List<Long>
}

/** orders_associations：按单号路由 + 跨类列表查询（状态由 OrdersMapper.syncAssoc 维护） */
@Mapper
interface OrderAssocMapper {

    @Insert(
        "INSERT INTO orders_associations(order_id, company_id, user_id, order_class, order_type, order_status, " +
            "cancel_status, delivery_status, total_fee, member_discount, coupon_discount, total_rebate, is_distribution, " +
            "promoter_user_id, source_id, monitor_id, salesman_id, wxa_appid, authorizer_appid, end_time, created, updated) " +
            "VALUES(#{orderId}, #{companyId}, #{userId}, #{orderClass}, #{orderType}, #{orderStatus}, #{cancelStatus}, " +
            "#{deliveryStatus}, #{totalFee}, #{memberDiscount}, #{couponDiscount}, #{totalRebate}, #{isDistribution}, " +
            "#{promoterUserId}, #{sourceId}, #{monitorId}, #{salesmanId}, #{wxaAppid}, #{authorizerAppid}, #{endTime}, " +
            "#{created}, #{updated})",
    )
    fun insert(a: OrderAssocDO): Int
}

/** orders_operate_logs：全流转留痕（下单/支付/发货/取消/超时/收货/备注） */
@Mapper
interface OrderOperateLogMapper {

    @Insert("INSERT INTO orders_operate_logs(order_id, operator_id, operator, operator_role, behavior, log_text, created, updated) " +
        "VALUES(#{orderId}, #{operatorId}, #{operator}, #{operatorRole}, #{behavior}, #{logText}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(l: OrderOperateLogDO): Int

    @Select("SELECT id, order_id, operator_id, operator, operator_role, behavior, log_text, created, updated " +
        "FROM orders_operate_logs WHERE order_id = #{orderId} ORDER BY created, id")
    fun listByOrder(@Param("orderId") orderId: Long): List<OrderOperateLogDO>
}

/** orders_cancel_orders：取消申请单 */
@Mapper
interface OrderCancelOrderMapper {

    @Insert("INSERT INTO orders_cancel_orders(order_id, company_id, user_id, shop_id, distributor_id, supplier_id, " +
        "total_fee, progress, cancel_from, cancel_reason, shop_reject_reason, refund_status, point, pay_type, created, updated) " +
        "VALUES(#{orderId}, #{companyId}, #{userId}, #{shopId}, #{distributorId}, #{supplierId}, #{totalFee}, #{progress}, " +
        "#{cancelFrom}, #{cancelReason}, #{shopRejectReason}, #{refundStatus}, #{point}, #{payType}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "cancelId", keyColumn = "cancel_id")
    fun insert(c: OrderCancelDO): Int

    @Select("SELECT cancel_id, order_id, company_id, user_id, shop_id, distributor_id, supplier_id, total_fee, progress, " +
        "cancel_from, cancel_reason, shop_reject_reason, refund_status, point, pay_type, created, updated " +
        "FROM orders_cancel_orders WHERE order_id = #{orderId} ORDER BY cancel_id DESC LIMIT 1")
    fun findLatest(@Param("orderId") orderId: Long): OrderCancelDO?
}

/** orders_delivery(_items)：发货单；delivery 表只有 created，不参与 Stampable */
@Mapper
interface OrderDeliveryMapper {

    @Insert("INSERT INTO orders_delivery(order_id, company_id, supplier_id, delivery_corp, delivery_corp_name, " +
        "delivery_code, package_type, delivery_time, delivery_pics, created) " +
        "VALUES(#{orderId}, #{companyId}, #{supplierId}, #{deliveryCorp}, #{deliveryCorpName}, #{deliveryCode}, " +
        "#{packageType}, #{deliveryTime}, #{deliveryPics}, #{created})")
    @Options(useGeneratedKeys = true, keyProperty = "ordersDeliveryId", keyColumn = "orders_delivery_id")
    fun insert(d: OrderDeliveryDO): Int

    @Select("SELECT orders_delivery_id, order_id, company_id, supplier_id, delivery_corp, delivery_corp_name, " +
        "delivery_code, package_type, delivery_time, delivery_pics, created FROM orders_delivery " +
        "WHERE order_id = #{orderId} ORDER BY orders_delivery_id")
    fun listByOrder(@Param("orderId") orderId: Long): List<OrderDeliveryDO>
}

@Mapper
interface OrderDeliveryItemMapper {

    @Insert("INSERT INTO orders_delivery_items(orders_delivery_id, order_id, order_items_id, item_id, num) " +
        "VALUES(#{ordersDeliveryId}, #{orderId}, #{orderItemsId}, #{itemId}, #{num})")
    fun insert(d: OrderDeliveryItemDO): Int

    @Select("SELECT orders_delivery_items_id, orders_delivery_id, order_id, order_items_id, item_id, num " +
        "FROM orders_delivery_items WHERE order_id = #{orderId} ORDER BY orders_delivery_items_id")
    fun listByOrder(@Param("orderId") orderId: Long): List<OrderDeliveryItemDO>
}

/** 订单明细 */
@Mapper
interface OrderItemsMapper {

    companion object {
        const val COLS =
            "id, order_id, company_id, user_id, goods_id, item_id, item_bn, goods_bn, shop_id, distributor_id, act_id, " +
            "item_name, pic, num, price, total_fee, item_fee, cost_fee, commission_fee, market_price, cost_price, " +
            "rebate, total_rebate, member_discount, coupon_discount, discount_fee, discount_info, order_item_type, " +
            "delivery_status, delivery_time, aftersales_status, refunded_fee, templates_id, is_total_store, item_point, " +
            "point, point_fee, share_points, item_spec_desc, tax_rate, cross_border_tax, weight, volume, is_invoice, " +
            "cancel_item_num, delivery_item_num, created, updated"
    }

    @Insert(
        "INSERT INTO orders_normal_orders_items(order_id, company_id, user_id, goods_id, item_id, item_bn, goods_bn, " +
            "shop_id, distributor_id, act_id, item_name, pic, num, price, total_fee, item_fee, cost_fee, commission_fee, " +
            "market_price, cost_price, rebate, total_rebate, member_discount, coupon_discount, discount_fee, discount_info, " +
            "order_item_type, delivery_status, delivery_time, aftersales_status, refunded_fee, templates_id, is_total_store, " +
            "item_point, point, point_fee, share_points, item_spec_desc, tax_rate, cross_border_tax, weight, volume, " +
            "is_invoice, cancel_item_num, delivery_item_num, created, updated) " +
            "VALUES(#{orderId}, #{companyId}, #{userId}, #{goodsId}, #{itemId}, #{itemBn}, #{goodsBn}, " +
            "#{shopId}, #{distributorId}, #{actId}, #{itemName}, #{pic}, #{num}, #{price}, #{totalFee}, #{itemFee}, #{costFee}, #{commissionFee}, " +
            "#{marketPrice}, #{costPrice}, #{rebate}, #{totalRebate}, #{memberDiscount}, #{couponDiscount}, #{discountFee}, #{discountInfo}, " +
            "#{orderItemType}, #{deliveryStatus}, #{deliveryTime}, #{aftersalesStatus}, #{refundedFee}, #{templatesId}, #{isTotalStore}, " +
            "#{itemPoint}, #{point}, #{pointFee}, #{sharePoints}, #{itemSpecDesc}, #{taxRate}, #{crossBorderTax}, #{weight}, #{volume}, " +
            "#{isInvoice}, #{cancelItemNum}, #{deliveryItemNum}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(i: OrderItemDO): Int

    @Select("SELECT $COLS FROM orders_normal_orders_items WHERE order_id = #{orderId} ORDER BY id")
    fun listByOrder(@Param("orderId") orderId: Long): List<OrderItemDO>

    @Select("<script>SELECT $COLS FROM orders_normal_orders_items WHERE order_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach> ORDER BY order_id, id</script>")
    fun listByOrders(@Param("ids") ids: List<Long>): List<OrderItemDO>

    /** 发货时按明细推进度 */
    @Update("UPDATE orders_normal_orders_items SET delivery_status = #{deliveryStatus}, delivery_time = #{deliveryTime}, " +
        "delivery_item_num = #{deliveryItemNum}, updated = #{updated} WHERE id = #{id}")
    fun markDelivered(
        @Param("id") id: Long,
        @Param("deliveryStatus") deliveryStatus: String,
        @Param("deliveryTime") deliveryTime: Long,
        @Param("deliveryItemNum") deliveryItemNum: Int,
        @Param("updated") updated: Long,
    ): Int

    /** 取消：逐行回写取消件数（delivery_status 保持原样，售后态属 M1-1） */
    @Update("UPDATE orders_normal_orders_items SET cancel_item_num = #{num}, updated = #{updated} WHERE id = #{id}")
    fun markCancelled(@Param("id") id: Long, @Param("num") num: Int, @Param("updated") updated: Long): Int
}
