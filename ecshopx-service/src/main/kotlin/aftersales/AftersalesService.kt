package com.ecshopx.kotlin.service.aftersales

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.aftersales.AftersalesDO
import com.ecshopx.kotlin.dao.aftersales.AftersalesDetailDO
import com.ecshopx.kotlin.dao.aftersales.AftersalesDetailMapper
import com.ecshopx.kotlin.dao.aftersales.AftersalesMapper
import com.ecshopx.kotlin.dao.aftersales.AftersalesProgress
import com.ecshopx.kotlin.dao.aftersales.AftersalesRefundMapper
import com.ecshopx.kotlin.dao.aftersales.AftersalesStatus
import com.ecshopx.kotlin.dao.aftersales.AftersalesType
import com.ecshopx.kotlin.dao.aftersales.AppliedRow
import com.ecshopx.kotlin.dao.aftersales.ItemAftersalesStatus
import com.ecshopx.kotlin.dao.aftersales.RefundStatus
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.order.NormalOrderDO
import com.ecshopx.kotlin.dao.order.OrderItemDO
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrderOperateLogDO
import com.ecshopx.kotlin.dao.order.OrderOperateLogMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.pay.TradeMapper
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * M1-1 售后状态机（docs/04 §11）。
 *
 * 四条不变式，全部落在 SQL 而不是服务层 if：
 *  1. **件数守恒**：`Σ aftersales.num（状态 0/5/1/2） ≤ 订单行 num`，扣减走 `left_aftersales_num` CAS；
 *  2. **金额守恒**：`Σ aftersales.refund_fee（同上状态集） ≤ 订单行 total_fee`，申请时就把超额挡在门外，
 *     执行时再由 `trade.refunded_fee + ? <= pay_fee` 兜一次底（两道门，见 [RefundService]）；
 *  3. **主表与明细永远双写**：状态迁移 = 主表 CAS + 明细同 filter 迁移，明细 CAS 失败说明数据被旁路改过，直接回滚；
 *  4. **终态不可逆**：status ∈ {2 已处理, 3 已驳回, 4 已撤销} 的行不再接受任何迁移，重复点击得到 422 而不是二次退款。
 *
 * 与源系统的两处结构差异（docs/04 §11.5 D11/D12）：
 *  - 「商家确认收货」不单开端点，复用 AF-A3 review 的第二次调用：progress=2 时同意即推进到 8（待退款）并生成退款单；
 *  - 售后不改 `trade.trade_state`，只累加 `trade.refunded_fee`。
 */
@Service
class AftersalesService(
    private val aftersales: AftersalesMapper,
    private val details: AftersalesDetailMapper,
    private val refunds: AftersalesRefundMapper,
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val orderLogs: OrderOperateLogMapper,
    private val trades: TradeMapper,
    private val configs: SysConfigMapper,
    private val refundService: RefundService,
    private val seq: SeqGenerator,
    private val db: Db,
    private val json: ObjectMapper,
) {

    // ===================== AF-F1 / AF-A6 申请 =====================

    /**
     * 申请售后。`autoApprove=true` 是后台代客申请（AF-A6）：仅退款当场审核通过，直接进入待退款。
     *
     * 校验顺序（先贵后贱）：订单可见性 → 订单可售后态 → 行存在 → 类型 → 件数守恒 → 金额守恒 → 回寄必填项。
     */
    fun apply(userId: Long, companyId: Long, form: AftersalesApplyForm, autoApprove: Boolean = false): Map<String, Any?> {
        val orderId = form.orderId?.toLongOrNull()
            ?: throw BizException(ErrorCode.VALIDATION, "order_id 必须是订单号字符串", mapOf("order_id" to listOf("格式非法")))
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.userId != userId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        requireAftersaleable(o)

        val itemId = form.itemId
            ?: throw BizException(ErrorCode.VALIDATION, "item_id 不能为空", mapOf("item_id" to listOf("不能为空")))
        val row = orderItems.listByOrder(orderId).firstOrNull { it.itemId == itemId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "订单明细不存在", mapOf("item_id" to listOf("该订单没有这个商品行")))
        val type = AftersalesGate.normalizeType(form.aftersalesType)

        val num = form.num ?: 1L
        val used = appliedOf(companyId, orderId, itemId)
        val canNum = row.num - used.appliedNum
        if (num < 1) throw BizException(ErrorCode.VALIDATION, "num 必须大于 0", mapOf("num" to listOf("必须大于 0")))
        if (num > canNum) {
            throw BizException(
                ErrorCode.BIZ, "可售后件数不足",
                mapOf("num" to listOf("本行共 ${row.num} 件，已申请 ${used.appliedNum} 件，最多还能申请 $canNum 件")),
            )
        }

        val unit = unitPrice(row)
        // 申请侧守恒：本行的钱池 = 行 total_fee；已占额度 = 其他在途/已完成售后单的 refund_fee + refund_point 合计
        // （积分单退的是积分、现金单退的是钱，两者都从同一个"行金额"里扣，所以合计口径不会放过超退）
        val usedOnRow = used.appliedFee + used.appliedPoint
        val canFee = row.totalFee - usedOnRow
        val fee = when {
            form.refundFee == null -> (unit * num).coerceAtMost(canFee)
            form.refundFee!! < 0 -> throw BizException(ErrorCode.VALIDATION, "refund_fee 不能为负", mapOf("refund_fee" to listOf("不能为负")))
            form.refundFee!! > canFee -> throw BizException(
                ErrorCode.BIZ, "退款金额超过本行剩余可退额",
                mapOf("refund_fee" to listOf("最多可退 $canFee 分")),
            )
            else -> form.refundFee!!
        }
        // 积分单：申请额记在 refund_point 上，钱池保持 0（退的是积分不是现金，池子分开守恒）
        val pointOrder = o.payType == "point"
        val refundFee = if (pointOrder) 0L else fee
        val refundPoint = if (pointOrder) fee else 0L

        if (type == AftersalesType.REFUND_GOODS && form.returnType == "offline") {
            val errs = linkedMapOf<String, List<String>>()
            if (form.contact.isNullOrBlank()) errs["contact"] = listOf("上门取件必须填写联系人")
            if (form.mobile.isNullOrBlank()) errs["mobile"] = listOf("上门取件必须填写联系电话")
            if (errs.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "回寄信息不完整", errs)
        }

        val now = TimeSec.now()
        val bn = seq.next(SeqName.AFTERSALES_NO)
        val evidence = form.evidencePic?.takeIf { it.isNotEmpty() }?.let { json.writeValueAsString(it) }

        val created = db.transactional {
            val d = AftersalesDetailDO().apply {
                aftersalesBn = bn
                this.orderId = orderId.toString()
                goodsId = row.goodsId
                this.itemId = row.itemId
                itemName = row.itemName
                pic = row.pic
                this.num = num
                this.refundFee = fee
                this.refundPoint = refundPoint
                aftersalesType = type
                aftersalesStatus = AftersalesStatus.PENDING
                progress = AftersalesProgress.WAIT_SELLER
                created = now
                updated = now
            }
            details.insert(d)

            aftersales.insert(
                AftersalesDO().apply {
                    aftersalesBn = bn
                    detailId = d.detailId
                    this.orderId = orderId
                    this.companyId = companyId
                    this.userId = userId
                    shopId = o.shopId
                    distributorId = o.distributorId
                    supplierId = o.supplierId
                    merchantId = o.merchantId
                    this.itemId = row.itemId
                    itemBn = row.itemBn
                    itemName = row.itemName
                    pic = row.pic
                    this.num = num
                    aftersalesType = type
                    aftersalesStatus = AftersalesStatus.PENDING
                    progress = AftersalesProgress.WAIT_SELLER
                    this.refundFee = refundFee
                    this.refundPoint = refundPoint
                    reason = form.reason?.takeIf { it.isNotBlank() }
                    description = form.description?.takeIf { it.isNotBlank() }
                    memo = if (autoApprove) "商家代客申请，当场通过" else null
                    evidencePic = evidence
                    returnType = form.returnType?.takeIf { it.isNotBlank() } ?: "logistics"
                    contact = form.contact?.takeIf { it.isNotBlank() } ?: o.receiverName
                    mobile = form.mobile?.takeIf { it.isNotBlank() } ?: o.receiverMobile
                    freight = 0L
                    autoRefuseTime = 0L
                    created = now
                    updated = now
                },
            )

            // 件数镜像扣减：0 行 = 并发下别的申请先抢走了件数（同一事务里回滚，不会留下半笔申请）
            consumeLeftNum(o, num.toInt(), now)
            orderItems.setAftersalesStatus(row.id, ItemAftersalesStatus.WAIT_SELLER_AGREE, now)
            log(o.orderId, userId, if (autoApprove) "seller" else "buyer", behavior = "aftersales_apply",
                "申请售后 $bn：${AftersalesDict.typeMsg(type)} $num 件，退款 ${fee} 分")
            true
        }
        if (!created) error("unreachable")

        return if (autoApprove && type == AftersalesType.ONLY_REFUND) {
            review(bn, companyId, AftersalesReviewForm(aftersalesBn = bn.toString(), isApproved = 1), auto = true)
        } else {
            view(load(bn, companyId, userId))
        }
    }

    // ===================== AF-A3 审核 / 确认收货 =====================

    /**
     * AF-A6 代客申请用：售后单的归属**始终是订单买家**，所以后台不传 user_id，
     * 由这里从订单带出，再走同一个 [apply]（含 ownership 校验，逻辑只有一份）。
     */
    fun ownerOf(orderId: String): Long {
        val id = orderId.toLongOrNull()
            ?: throw BizException(ErrorCode.VALIDATION, "order_id 必须是订单号字符串", mapOf("order_id" to listOf("格式非法")))
        return order(id).userId
    }

    /**
     * 商家审核。两个入口共用一个方法，靠当前 progress 区分语义：
     *  - progress=0（等待商家处理）：同意 → 仅退款进 8（待退款，同时生成退款单），退货退款进 1（待买家回寄，
     *    并写 `auto_refuse_time`，超时由 [autoRefuse] 关闭）；驳回 → 终态 3/3，件数与订单行都回滚。
     *  - progress=2（买家已回寄）：D11「确认收货」——同意即入库（`refunded_num`）并进 8 生成退款单；驳回同样是终态 3。
     */
    fun review(bn: Long, companyId: Long, form: AftersalesReviewForm, auto: Boolean = false): Map<String, Any?> {
        val a = load(bn, companyId, null)
        val approved = form.isApproved == 1
        if (!approved && form.refuseReason.isNullOrBlank()) {
            throw BizException(ErrorCode.VALIDATION, "驳回必须填写理由", mapOf("refuse_reason" to listOf("不能为空")))
        }
        // 只有两个位置可以点"同意/驳回"：待商家处理（首次审核）与买家已回寄（D11 确认收货）
        val reviewable = (a.aftersalesStatus == AftersalesStatus.PENDING && a.progress == AftersalesProgress.WAIT_SELLER) ||
            (a.aftersalesStatus == AftersalesStatus.PROCESSING && a.progress == AftersalesProgress.BUYER_RETURNED)
        if (!reviewable) {
            throw BizException(ErrorCode.BIZ, "当前状态不可审核：${AftersalesDict.progressMsg(a.progress)}")
        }
        val now = TimeSec.now()
        val o = order(a.orderId)

        if (!approved) {
            // 驳回分两种落点：审核阶段驳回 → progress=3；已回寄后拒退 → progress=5（退款驳回，§11.4）
            val rejectProgress = if (a.progress == AftersalesProgress.BUYER_RETURNED) AftersalesProgress.REFUND_REFUSED else AftersalesProgress.REFUSED
            db.transactional {
                val p = AftersalesMapper.TransitParam().apply {
                    this.bn = bn
                    this.companyId = companyId
                    fromStatus = a.aftersalesStatus
                    fromProgress = a.progress
                    toStatus = AftersalesStatus.REFUSED
                    toProgress = rejectProgress
                    refuseReason = form.refuseReason
                    updated = now
                }
                requireHit(aftersales.transit(p), "售后单状态已变更，请刷新后重试")
                val dHit = details.transit(bn, a.aftersalesStatus, a.progress, AftersalesStatus.REFUSED, rejectProgress, now)
                if (dHit == 0) throw BizException(ErrorCode.INTERNAL, "售后明细状态与主表不一致，已回滚")
                refunds.listByAftersales(bn).filter { it.refundStatus == RefundStatus.READY }
                    .forEach { refundService.cancel(it.refundBn, companyId, "售后驳回，退款单同步作废", now) }
                restoreLeftNum(o, now)
                orderItems.setAftersalesStatus(itemRow(a).id, ItemAftersalesStatus.SELLER_REFUSE_BUYER, now)
                log(o.orderId, null, "seller", "aftersales_refuse", "售后 $bn 被驳回：${form.refuseReason}")
                true
            }
            return view(load(bn, companyId, null))
        }

        // 同意：仅退款 / 已回寄的退货退款 → 待退款（8）；退货退款首次同意 → 待回寄（1）。
        // 主表状态一律"处理中"，2=已处理 只由退款成功写（否则会出现"钱没退完但售后已结案"）
        val toReturnGoods = a.aftersalesType == AftersalesType.REFUND_GOODS && a.progress == AftersalesProgress.WAIT_SELLER
        val toStatus = AftersalesStatus.PROCESSING
        val toProgress = if (toReturnGoods) AftersalesProgress.WAIT_BUYER_RETURN else AftersalesProgress.SELLER_CONFIRMED
        val cap = reviewCap(a)
        val fee = (form.refundFee ?: a.refundFee).coerceIn(0L, cap)
        val point = (form.refundPoint ?: a.refundPoint).coerceIn(0L, (cap - fee).coerceAtLeast(0L))
        val deadline = now + returnDays(companyId) * 86400L

        db.transactional {
            val p = AftersalesMapper.TransitParam().apply {
                this.bn = bn
                this.companyId = companyId
                fromStatus = a.aftersalesStatus
                fromProgress = a.progress
                this.toStatus = toStatus
                this.toProgress = toProgress
                refundFee = fee
                refundPoint = point
                if (toReturnGoods) autoRefuseTime = deadline
                if (auto) memo = "商家代客申请，当场通过"
                updated = now
            }
            requireHit(aftersales.transit(p), "售后单状态已变更，请刷新后重试")
            val dHit = details.transit(bn, a.aftersalesStatus, a.progress, toStatus, toProgress, now)
            if (dHit == 0) throw BizException(ErrorCode.INTERNAL, "售后明细状态与主表不一致，已回滚")

            if (a.aftersalesType == AftersalesType.REFUND_GOODS && a.progress == AftersalesProgress.BUYER_RETURNED) {
                // D11：确认收货 = 按申请件数整笔入库（源系统 refunded_num 的唯一写入点）
                details.listByBn(bn).forEach { details.markReceived(it.detailId, it.num, now) }
            }
            val itemStatus = when {
                toProgress == AftersalesProgress.WAIT_BUYER_RETURN -> ItemAftersalesStatus.WAIT_BUYER_RETURN_GOODS
                else -> ItemAftersalesStatus.WAIT_SELLER_CONFIRM_GOODS
            }
            orderItems.setAftersalesStatus(itemRow(a).id, itemStatus, now)

            // 进入待退款才生成退款单（READY）：审核前没有退款单，后台退款列表不会出现"未审先退"
            if (toProgress == AftersalesProgress.SELLER_CONFIRMED && refunds.countReadyByAftersales(bn) == 0L) {
                refundService.createReady(a, fee, point, now)
            }
            log(o.orderId, null, "seller", "aftersales_review", "售后 $bn 审核通过，推进到「${AftersalesDict.progressMsg(toProgress)}」")
            true
        }
        return view(load(bn, companyId, null))
    }

    // ===================== AF-F4 / AF-A7 回寄 =====================

    /** 买家填报回寄物流（或商家代填）。只有"待回寄(1)"能填，填完进 2。 */
    fun sendback(bn: Long, companyId: Long, userId: Long?, form: AftersalesSendbackForm): Map<String, Any?> {
        val a = load(bn, companyId, userId)
        if (a.progress != AftersalesProgress.WAIT_BUYER_RETURN) {
            throw BizException(ErrorCode.BIZ, "当前状态不可填写回寄信息：${AftersalesDict.progressMsg(a.progress)}")
        }
        val corp = form.corpCode?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "corp_code 不能为空", mapOf("corp_code" to listOf("不能为空")))
        if (corp.length !in 6..30) {
            throw BizException(ErrorCode.VALIDATION, "corp_code 长度须为 6..30", mapOf("corp_code" to listOf("长度 ${corp.length}")))
        }
        val no = form.logiNo?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "logi_no 不能为空", mapOf("logi_no" to listOf("不能为空")))
        val now = TimeSec.now()
        val payload = json.writeValueAsString(
            linkedMapOf("corp_code" to corp, "logi_no" to no, "filled_by" to (if (userId == null) "seller" else "buyer"), "filled_at" to now),
        )
        db.transactional {
            val p = AftersalesMapper.TransitParam().apply {
                this.bn = bn
                this.companyId = companyId
                this.userId = userId
                fromStatus = a.aftersalesStatus
                fromProgress = AftersalesProgress.WAIT_BUYER_RETURN
                toStatus = AftersalesStatus.PROCESSING
                toProgress = AftersalesProgress.BUYER_RETURNED
                sendbackData = payload
                if (!form.memo.isNullOrBlank()) memo = form.memo
                updated = now
            }
            requireHit(aftersales.transit(p), "售后单状态已变更，请刷新后重试")
            details.transit(bn, a.aftersalesStatus, AftersalesProgress.WAIT_BUYER_RETURN,
                AftersalesStatus.PROCESSING, AftersalesProgress.BUYER_RETURNED, now)
            orderItems.setAftersalesStatus(itemRow(a).id, ItemAftersalesStatus.WAIT_SELLER_CONFIRM_GOODS, now)
            // 货已寄回，超时自动驳回的闹钟失效
            log(order(a.orderId).orderId, userId, if (userId == null) "seller" else "buyer",
                "aftersales_sendback", "售后 $bn 回寄：$corp / $no")
            true
        }
        return view(load(bn, companyId, userId))
    }

    // ===================== 商家确认收货 / 取消（ERP 回传驱动，批I） =====================

    /**
     * 商家确认收货（源系统 `AftersalesService::confirmReceipt`，由 ERP 回传驱动；批I 收）。
     *
     * 守卫照抄源系统：`aftersales_status ∈ {0,1}`（未结案）、`progress ∈ {1,2}`（已接受申请，
     * 买家还没寄回也允许——ERP 侧的退货物流不经过本站，这是这条链存在的意义）。
     * 落点：`progress=8`（待退款）、明细 `refunded_num=num`（源系统唯一"整笔入库"写入点）。
     *
     * 本仓比源系统多一步：进 8 时**同步生成 READY 退款单**（与 [review] 的 D11 落点同一不变式——
     * "售后在待退款、退款列表里却什么都没有"是本站不允许出现的中间态）。源系统把退款单留到
     * 后续 refundCheck 才建，这里把它提前到进入 8 的那一刻，口径记 docs/04 §30.3 C251。
     */
    fun confirmReceipt(bn: Long, companyId: Long, orderId: Long? = null): Map<String, Any?> {
        val a = load(bn, companyId, null)
        if (orderId != null && a.orderId != orderId) throw BizException(ErrorCode.BIZ, "未获取到售后单信息")
        if (a.aftersalesStatus != AftersalesStatus.PENDING && a.aftersalesStatus != AftersalesStatus.PROCESSING) {
            throw BizException(ErrorCode.BIZ, "售后${bn}已处理")
        }
        if (a.progress != AftersalesProgress.WAIT_BUYER_RETURN && a.progress != AftersalesProgress.BUYER_RETURNED) {
            throw BizException(ErrorCode.BIZ, "售后${bn}商家没有接受申请")
        }
        val now = TimeSec.now()
        val o = order(a.orderId)
        db.transactional {
            val p = AftersalesMapper.TransitParam().apply {
                this.bn = bn
                this.companyId = companyId
                fromStatus = a.aftersalesStatus
                fromProgress = a.progress
                toStatus = AftersalesStatus.PROCESSING
                toProgress = AftersalesProgress.SELLER_CONFIRMED
                updated = now
            }
            requireHit(aftersales.transit(p), "售后单状态已变更，请刷新后重试")
            val dHit = details.transit(
                bn, a.aftersalesStatus, a.progress,
                AftersalesStatus.PROCESSING, AftersalesProgress.SELLER_CONFIRMED, now,
            )
            if (dHit == 0) throw BizException(ErrorCode.INTERNAL, "售后明细状态与主表不一致，已回滚")
            details.listByBn(bn).forEach { details.markReceived(it.detailId, it.num, now) }
            orderItems.setAftersalesStatus(itemRow(a).id, ItemAftersalesStatus.WAIT_SELLER_CONFIRM_GOODS, now)
            if (refunds.countReadyByAftersales(bn) == 0L) refundService.createReady(a, a.refundFee, a.refundPoint, now)
            log(o.orderId, null, "seller", "aftersales_confirm_receipt", "售后 $bn 商家确认收货，推进到「待退款」")
            true
        }
        return view(load(bn, companyId, null))
    }

    /**
     * 商家取消确认收货（源系统 `AftersalesService::cancelReceipt`，批I 收）。
     *
     * 守卫：`aftersales_status=1` 且 `progress=8`；退回 1（待回寄）或 2（已回寄）——
     * 判据是 `sendback_data` 有没有填过（源系统同一条判据），不按"当初从哪一格进来的"倒推。
     * 进 8 时生成的 READY 退款单**同步作废**：售后回到待回寄、退款单还能点，是两条账对不上。
     */
    fun cancelReceipt(bn: Long, companyId: Long, orderId: Long? = null): Map<String, Any?> {
        val a = load(bn, companyId, null)
        if (orderId != null && a.orderId != orderId) throw BizException(ErrorCode.BIZ, "未获取到售后单信息")
        if (a.aftersalesStatus != AftersalesStatus.PROCESSING) {
            throw BizException(ErrorCode.BIZ, "售后${bn}已处理或还未处理")
        }
        if (a.progress != AftersalesProgress.SELLER_CONFIRMED) {
            throw BizException(ErrorCode.BIZ, "售后${bn}商家没有确认收货")
        }
        val back = if (a.sendbackData.isNullOrBlank()) AftersalesProgress.WAIT_BUYER_RETURN else AftersalesProgress.BUYER_RETURNED
        val now = TimeSec.now()
        val o = order(a.orderId)
        db.transactional {
            val p = AftersalesMapper.TransitParam().apply {
                this.bn = bn
                this.companyId = companyId
                fromStatus = a.aftersalesStatus
                fromProgress = a.progress
                toStatus = AftersalesStatus.PROCESSING
                toProgress = back
                updated = now
            }
            requireHit(aftersales.transit(p), "售后单状态已变更，请刷新后重试")
            val dHit = details.transit(bn, a.aftersalesStatus, a.progress, AftersalesStatus.PROCESSING, back, now)
            if (dHit == 0) throw BizException(ErrorCode.INTERNAL, "售后明细状态与主表不一致，已回滚")
            refunds.listByAftersales(bn)
                .filter { it.refundStatus == RefundStatus.READY }
                .forEach { refundService.cancel(it.refundBn, companyId, "商家取消确认收货，退款单同步作废", now) }
            val itemStatus = if (back == AftersalesProgress.BUYER_RETURNED) {
                ItemAftersalesStatus.WAIT_SELLER_CONFIRM_GOODS
            } else {
                ItemAftersalesStatus.WAIT_BUYER_RETURN_GOODS
            }
            orderItems.setAftersalesStatus(itemRow(a).id, itemStatus, now)
            log(o.orderId, null, "seller", "aftersales_cancel_receipt", "售后 $bn 商家取消确认收货，退回「${AftersalesDict.progressMsg(back)}」")
            true
        }
        return view(load(bn, companyId, null))
    }

    // ===================== AF-F5 撤销 =====================

    /** 买家撤销申请：只有还没进入退款的单能撤（待处理 / 待回寄 / 已回寄未确认）。 */
    fun close(bn: Long, companyId: Long, userId: Long, form: AftersalesCloseForm): Map<String, Any?> {
        val a = load(bn, companyId, userId)
        if (a.aftersalesStatus != AftersalesStatus.PENDING && a.aftersalesStatus != AftersalesStatus.PROCESSING) {
            throw BizException(ErrorCode.BIZ, "售后已结案，不能撤销：${AftersalesDict.statusMsg(a.aftersalesStatus)}")
        }
        if (a.progress >= AftersalesProgress.SELLER_CONFIRMED && a.progress != AftersalesProgress.CANCELLED) {
            throw BizException(ErrorCode.BIZ, "商家已进入退款流程，不能撤销，请联系客服")
        }
        val now = TimeSec.now()
        val o = order(a.orderId)
        db.transactional {
            val p = AftersalesMapper.TransitParam().apply {
                this.bn = bn
                this.companyId = companyId
                this.userId = userId
                fromStatus = a.aftersalesStatus
                fromProgress = a.progress
                toStatus = AftersalesStatus.CANCELLED
                toProgress = AftersalesProgress.CANCELLED
                if (!form.memo.isNullOrBlank()) memo = form.memo
                updated = now
            }
            requireHit(aftersales.transit(p), "售后单状态已变更，请刷新后重试")
            details.transit(bn, a.aftersalesStatus, a.progress, AftersalesStatus.CANCELLED, AftersalesProgress.CANCELLED, now)
            refunds.listByAftersales(bn)
                .filter { it.refundStatus == RefundStatus.READY || it.refundStatus == RefundStatus.AUDIT_SUCCESS }
                .forEach { refundService.cancel(it.refundBn, companyId, "买家撤销售后，退款单同步作废", now) }
            restoreLeftNum(o, now)
            orderItems.setAftersalesStatus(itemRow(a).id, ItemAftersalesStatus.REFUND_CLOSED, now)
            log(o.orderId, userId, "buyer", "aftersales_close", "买家撤销售后 $bn")
            true
        }
        return view(load(bn, companyId, userId))
    }

    // ===================== AF-A5 备注 =====================

    fun remark(bn: Long, companyId: Long, memo: String?): Map<String, Any?> {
        val text = memo.orEmpty()
        if (text.length > 150) {
            throw BizException(ErrorCode.VALIDATION, "memo 最长 150 字", mapOf("memo" to listOf("当前 ${text.length} 字")))
        }
        load(bn, companyId, null)
        aftersales.updateMemo(bn, companyId, text, TimeSec.now())
        return linkedMapOf("aftersales_bn" to bn.toString(), "memo" to text)
    }

    // ===================== 超时自动关闭（定时任务） =====================

    /**
     * 商家同意后买家长期不寄回 → 系统关闭。单条一个事务，一条失败不影响批次其余行。
     * @return 实际关闭的条数
     */
    fun autoRefuse(limit: Int = 50): Int {
        val now = TimeSec.now()
        var closed = 0
        aftersales.listExpiredRefuse(now, limit).forEach { a ->
            val ok = db.transactional(propagation = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
                val p = AftersalesMapper.TransitParam().apply {
                    bn = a.aftersalesBn
                    companyId = a.companyId
                    fromStatus = AftersalesStatus.PROCESSING
                    fromProgress = AftersalesProgress.WAIT_BUYER_RETURN
                    toStatus = AftersalesStatus.CANCELLED
                    toProgress = AftersalesProgress.CANCELLED
                    memo = "超过回寄期限，系统自动关闭"
                    updated = TimeSec.now()
                }
                if (aftersales.transit(p) == 0) return@transactional false
                details.transit(a.aftersalesBn, AftersalesStatus.PROCESSING, AftersalesProgress.WAIT_BUYER_RETURN,
                    AftersalesStatus.CANCELLED, AftersalesProgress.CANCELLED, TimeSec.now())
                restoreLeftNum(order(a.orderId), TimeSec.now())
                orderItems.setAftersalesStatus(itemRow(a).id, ItemAftersalesStatus.REFUND_CLOSED, TimeSec.now())
                log(a.orderId, null, "system", "aftersales_auto_close", "售后 ${a.aftersalesBn} 超时未回寄，系统自动关闭")
                true
            }
            if (ok == true) closed++
        }
        return closed
    }

    // ===================== AF-F2 / AF-A1 / AF-F3 / AF-A2 查询 =====================

    fun list(userId: Long?, companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        val f = filterOf(userId, companyId, raw)
        return PageHelper.paginate(
            pq,
            count = { aftersales.count(f) },
            query = { offset, limit -> aftersales.page(f, offset, limit) },
            map = { rows -> rows.map { view(it) } },
        )
    }

    /** AF-F3 / AF-A2：详情带 detail[]、refund_info{}、order_info{} 与两个文案字段 */
    fun info(bn: Long, companyId: Long, userId: Long?): Map<String, Any?> {
        val a = load(bn, companyId, userId)
        val o = order(a.orderId)
        return linkedMapOf<String, Any?>().apply {
            putAll(view(a))
            put("detail", details.listByBn(bn).map {
                linkedMapOf<String, Any?>(
                    "detail_id" to it.detailId,
                    "item_id" to it.itemId,
                    "item_name" to it.itemName,
                    "pic" to it.pic,
                    "num" to it.num,
                    "refunded_num" to it.refundedNum,
                    "refund_fee" to it.refundFee,
                    "aftersales_status" to it.aftersalesStatus,
                    "progress" to it.progress,
                )
            })
            put("refund_info", refunds.listByAftersales(bn).lastOrNull()?.let { refundService.row(it) })
            put("order_info", linkedMapOf<String, Any?>(
                "order_id" to o.orderId.toString(),
                "order_status" to o.orderStatus,
                "pay_status" to o.payStatus,
                "total_fee" to o.totalFee,
                "pay_fee" to o.payFee,
                "item_fee" to o.itemFee,
                "freight_fee" to o.freightFee,
            ))
            put("buttons", buttons(a))
        }
    }

    /** AF-F6：单件可退额 = floor(行 total_fee / 行 num)，不含运费（运费是否可退由商家在审核时决定） */
    fun itemPrice(orderId: Long, itemId: Long, userId: Long?, companyId: Long): Map<String, Any?> {
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (userId != null && o.userId != userId) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        val row = orderItems.listByOrder(orderId).firstOrNull { it.itemId == itemId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "订单明细不存在")
        val used = appliedOf(companyId, orderId, itemId)
        return linkedMapOf(
            "price" to unitPrice(row),
            "num" to row.num,
            "applied_num" to used.appliedNum,
            "can_num" to (row.num - used.appliedNum),
            "can_fee" to (row.totalFee - used.appliedFee - used.appliedPoint),
        )
    }

    /** AF-F7：原因字典（D8：sys_config `aftersales.reason`，读不到回落内置四条） */
    fun reasons(companyId: Long): List<String> {
        val raw = configs.getValue(companyId, KEY_REASON) ?: configs.getValue(0L, KEY_REASON) ?: return AftersalesDict.DEFAULT_REASONS
        return runCatching { json.readValue(raw, OBJECT_LIST) }
            .getOrNull()?.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() } ?: AftersalesDict.DEFAULT_REASONS
    }

    // ===================== 内部件 =====================

    private fun filterOf(userId: Long?, companyId: Long, raw: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "companyId" to companyId,
        "userId" to userId,
        "aftersalesBn" to raw["aftersales_bn"]?.toLongOrNull(),
        "orderId" to raw["order_id"]?.toLongOrNull(),
        "status" to raw["aftersales_status"]?.toIntOrNull(),
        "type" to raw["aftersales_type"]?.takeIf { it.isNotBlank() },
        "mobile" to raw["mobile"]?.takeIf { it.isNotBlank() },
        "from" to raw["start_time"]?.toLongOrNull(),
        "to" to raw["end_time"]?.toLongOrNull(),
    )

    /** 出参行（§11.3）：单号一律串、手机号掩码（D9）、evidence_pic 回数组、sendback_data 回对象 */
    private fun view(a: AftersalesDO): Map<String, Any?> = linkedMapOf(
        "aftersales_bn" to a.aftersalesBn.toString(),
        "order_id" to a.orderId.toString(),
        "item_id" to a.itemId,
        "item_name" to a.itemName,
        "item_bn" to a.itemBn,
        "pic" to a.pic,
        "num" to a.num,
        "refund_fee" to a.refundFee,
        "refund_point" to a.refundPoint,
        "aftersales_type" to a.aftersalesType,
        "aftersales_status" to a.aftersalesStatus,
        "progress" to a.progress,
        "status_msg" to AftersalesDict.statusMsg(a.aftersalesStatus),
        "progress_msg" to AftersalesDict.progressMsg(a.progress),
        "type_msg" to AftersalesDict.typeMsg(a.aftersalesType),
        "reason" to a.reason,
        "description" to a.description,
        "refuse_reason" to a.refuseReason,
        "memo" to a.memo,
        "evidence_pic" to parseList(a.evidencePic),
        "return_type" to a.returnType,
        "contact" to a.contact,
        "mobile" to Mask.phone(a.mobile),
        "freight" to a.freight,
        "sendback_data" to parseObject(a.sendbackData),
        "company_id" to a.companyId,
        "user_id" to a.userId,
        "distributor_id" to a.distributorId,
        "can_apply" to 1,
        "created" to a.created,
        "updated" to a.updated,
    )

    /** 后台详情可执行动作（源系统 buttons 语义）：前端据此渲染按钮 */
    private fun buttons(a: AftersalesDO): List<String> = when {
        a.aftersalesStatus == AftersalesStatus.PENDING && a.progress == AftersalesProgress.WAIT_SELLER ->
            listOf("review", "remark")
        a.progress == AftersalesProgress.WAIT_BUYER_RETURN -> listOf("review", "remark")
        a.progress == AftersalesProgress.BUYER_RETURNED -> listOf("review", "remark")
        a.progress == AftersalesProgress.SELLER_CONFIRMED -> listOf("refundCheck", "remark")
        else -> listOf("remark")
    }

    private fun load(bn: Long, companyId: Long, userId: Long?): AftersalesDO {
        val a = if (userId == null) aftersales.findByBn(bn, companyId)
        else aftersales.findByBnAndUser(bn, companyId, userId)
        return a ?: throw BizException(ErrorCode.NOT_FOUND, "售后单不存在")
    }

    private fun order(orderId: Long): NormalOrderDO =
        orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun itemRow(a: AftersalesDO): OrderItemDO =
        orderItems.listByOrder(a.orderId).firstOrNull { it.itemId == a.itemId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "订单明细不存在")

    /**
     * 可售后窗口：已付款且未取消（源系统口径：DONE 也允许，七天无理由从收货起算）。
     * `REFUND_PART` 必须放行——**部分退款之后还要能继续申请**，这是 M1-1 门槛用例的前提；
     * 全额退完（REFUND）则额度已由 [applyQuotaOf] 归零，不必在这里重复设卡。
     */
    private fun requireAftersaleable(o: NormalOrderDO) {
        if (o.payStatus != PayStatus.PAYED && o.payStatus != PayStatus.REFUND_PART) {
            throw BizException(ErrorCode.BIZ, "订单未支付，不能申请售后")
        }
        if (o.orderStatus == OrderStatus.CANCEL || o.orderStatus == OrderStatus.NOTPAY) {
            throw BizException(ErrorCode.BIZ, "订单当前状态不可申请售后：${o.orderStatus}")
        }
    }

    /**
     * 审核阶段允许下调退款额，但不得高过原申请额，也不得越过本行剩余可退额（防"审核加钱"和跨单超退）。
     * `used` 含本单自己，所以加回 `a.refundFee + a.refundPoint` 才是"其他单占掉的"。
     */
    private fun applyQuotaOf(a: AftersalesDO): Long {
        val row = runCatching { itemRow(a) }.getOrNull() ?: return a.refundFee + a.refundPoint
        val used = appliedOf(a.companyId, a.orderId, a.itemId)
        val others = used.appliedFee + used.appliedPoint - a.refundFee - a.refundPoint
        return (row.totalFee - others).coerceAtLeast(0L)
    }

    /** 审核可改写的合计上限：min(行剩余可退, 原申请合计) */
    private fun reviewCap(a: AftersalesDO): Long = minOf(applyQuotaOf(a), a.refundFee + a.refundPoint)

    /** 行占用合计：SUM 有 COALESCE 兜底，永不返回 null 行；这里只是给可空返回型收口 */
    private fun appliedOf(companyId: Long, orderId: Long, itemId: Long): AppliedRow =
        aftersales.applied(companyId, orderId, itemId) ?: AppliedRow()

    private fun unitPrice(row: OrderItemDO): Long = if (row.num <= 0) 0L else row.totalFee / row.num

    /**
     * 件数镜像扣减。历史订单该列为 0（M0 建单时还没有售后域），先按明细总件数补种再 CAS；
     * CAS 得 0 行 = 并发抢件数，直接报"可售后件数不足"，事务回滚不留半成品。
     */
    private fun consumeLeftNum(o: NormalOrderDO, num: Int, now: Long) {
        var expect = o.leftAftersalesNum
        if (expect <= 0) {
            val total = orderItems.listByOrder(o.orderId).sumOf { it.num }.toInt()
            orders.backfillLeftAftersalesNum(o.orderId, total, now)
            expect = total
        }
        if (expect < num) throw BizException(ErrorCode.BIZ, "可售后件数不足：本单剩余 $expect 件")
        val hit = orders.casLeftAftersalesNum(o.orderId, expect, expect - num, now)
        if (hit == 0) throw BizException(ErrorCode.BIZ, "可售后件数已变动，请重试")
    }

    /**
     * 驳回/撤销/拒退：把件数还回订单。镜像列**不靠增量加减**（历史单该列是 0，加减会越算越偏），
     * 一律按「明细总件数 - 仍占额度的申请件数」重算；调用点必须已经把本单写成终态，
     * 否则重算会把自己算进占用里。
     */
    private fun restoreLeftNum(o: NormalOrderDO, now: Long) {
        val total = orderItems.listByOrder(o.orderId).sumOf { it.num }.toInt()
        val occupied = aftersales.occupiedNum(o.companyId, o.orderId).toInt()
        val to = (total - occupied).coerceAtLeast(0)
        if (o.leftAftersalesNum == to) return
        if (orders.casLeftAftersalesNum(o.orderId, o.leftAftersalesNum, to, now) == 0 && o.leftAftersalesNum == 0) {
            orders.backfillLeftAftersalesNum(o.orderId, to, now)
        }
    }

    private fun log(orderId: Long, operatorId: Long?, role: String, behavior: String, text: String) {
        orderLogs.insert(
            OrderOperateLogDO().apply {
                this.orderId = orderId
                this.operatorId = operatorId
                operator = if (operatorId == null) "system" else "$role:$operatorId"
                operatorRole = role
                this.behavior = behavior
                logText = text
            },
        )
    }

    private fun requireHit(rows: Int, message: String) {
        if (rows == 0) throw BizException(ErrorCode.BIZ, message)
    }

    private fun returnDays(companyId: Long): Long =
        configs.getValue(companyId, KEY_RETURN_DAYS)?.toLongOrNull()
            ?: configs.getValue(0L, KEY_RETURN_DAYS)?.toLongOrNull() ?: 7L

    private fun parseList(raw: String?): List<Any?> =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.readValue(it, ANY_LIST) }.getOrNull() } ?: emptyList<Any?>()

    private fun parseObject(raw: String?): Map<String, Any?>? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.readValue(it, ANY_MAP) }.getOrNull() }


    companion object {
        const val KEY_REASON = "aftersales.reason"
        const val KEY_RETURN_DAYS = "aftersales.return_days"
        private val ANY_LIST = object : TypeReference<List<Any?>>() {}
        private val ANY_MAP = object : TypeReference<LinkedHashMap<String, Any?>>() {}
        private val OBJECT_LIST = object : TypeReference<List<Any?>>() {}
    }
}

