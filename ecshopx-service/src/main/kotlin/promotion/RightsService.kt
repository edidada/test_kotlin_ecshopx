package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MembersMapper
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.goods.RelTypeMapper
import com.ecshopx.kotlin.dao.order.OrderItemDO
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.UserRightsDO
import com.ecshopx.kotlin.dao.promotion.UserRightsLogDO
import com.ecshopx.kotlin.dao.promotion.UserRightsLogMapper
import com.ecshopx.kotlin.dao.promotion.UserRightsMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.stereotype.Service

/**
 * 次卡 / rights（docs/04 §29.4）：发放 + 会员自办三件（转赠/延期/查）+ 店员核销 + 后台账账。
 *
 * 生成口径（与砍价/换购同一族的事实观）：
 *  1. **结清即发卡**：`OrderFlowService.markPaid` 的 CAS 命中支里调 [issueOnPaid] ——
 *     重复回调第二次 `markPaid` 直接 false，卡不会发两次（"命中那支才发"，§25.6）；
 *  2. **判据收窄**：订单明细行对应商品的 `consume_type='service'` 才发；商品不是服务就 0 行，
 *     普通物购买一百件也不出卡 —— 事实源在商品，不在下单动作；
 *  3. **次数与有效期随发随拷**：`items_rel_type.num / is_not_limit_num / limit_time` 在发放
 *     时刻拷进卡行，之后商品改配置不影响已发的卡 —— "拿到手的东西不被后台悄悄改"。
 *
 * 核销是**员工扫码**动作（源 `Action\Rights@doConsume`，店员面）：每次核销写一行
 * `behavior='consume'` 日志，同时 `used_count` 配额列 CAS +1 —— 两处恒等是本批 e2e 门槛之一。
 */

/** 服务型商品的判据值（items.consume_type；schema 注释里 item_type='services' 是更宽的族，判据走窄的） */
private const val CONSUME_TYPE_SERVICE = "service"

@Service
class RightsService(
    private val rights: UserRightsMapper,
    private val logs: UserRightsLogMapper,
    private val goodsItems: ItemsMapper,
    private val relTypes: RelTypeMapper,
    private val members: MembersMapper,
) {

    // ===================== 发放腿 =====================

    /**
     * 结清发卡（markPaid 事务内调用）：对本单里 `consume_type='service'` 的明细行逐**商品**发卡。
     * 同商品的多行/多件合并成一张卡（次数累加）——"我的次卡"列表是按商品的读模型，
     * 十行十张卡只会把会员页搅乱。
     *
     * `@return` 本单发出的卡数（0=本单没有服务商品），e2e 与冒烟用它说话。
     */
    fun issueOnPaid(companyId: Long, orderId: Long, userId: Long, lines: List<OrderItemDO>, now: Long): Int {
        val itemIds = lines.map { it.itemId }.distinct()
        if (itemIds.isEmpty()) return 0
        val itemById = goodsItems.listByIds(itemIds).associateBy { it.itemId }
        val serviceIds = itemById.filterValues { it.consumeType == CONSUME_TYPE_SERVICE }.keys
        if (serviceIds.isEmpty()) return 0
        // 次数/有效期的事实源（发放时刻拷贝）
        val relByItem = relTypes.listByItems(serviceIds.toList()).associateBy { it.itemId }
        val numByItem = lines.filter { it.itemId in serviceIds }.groupBy { it.itemId }
            .mapValues { (_, rows) -> rows.sumOf { it.num }.coerceAtLeast(1L) }
        serviceIds.forEach { itemId ->
            val item = itemById[itemId] ?: return@forEach
            val rel = relByItem[itemId]
            // is_not_limit_num=1 → 不限次数（total_count=0）；rel 缺失的绑定一律按"1 次长期"兜底
            val unlimited = (rel?.isNotLimitNum ?: 0) == 1
            val total = if (unlimited) 0
            else {
                val per = (rel?.num ?: 0L).takeIf { it > 0 } ?: 1L
                (per * numByItem[itemId]!!).toInt()
            }
            val end = if ((rel?.limitTime ?: 0) > 0) now + rel!!.limitTime * 86400L else 0L
            issue(
                companyId = companyId, userId = userId, itemId = itemId, orderId = orderId,
                labelId = rel?.labelId ?: 0, name = item.itemName,
                total = total, end = end, operator = 0, remark = "订单结清发放", now = now,
            )
        }
        return serviceIds.size
    }

    /** 后台手工发放（补录/客诉补偿；`total_count>=0`，0=不限次；`end_time<=0` = 长期有效） */
    fun adminGrant(companyId: Long, form: RightsGrantForm, operatorId: Long): Map<String, Any?> {
        val userId = form.userId ?: throw BizException(ErrorCode.VALIDATION, "参数不合法",
            mapOf("user_id" to listOf("必填")))
        val itemId = form.itemId ?: throw BizException(ErrorCode.VALIDATION, "参数不合法",
            mapOf("item_id" to listOf("必填")))
        val member = members.findById(userId)
        if (member == null || member.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "会员不存在")
        val item = goodsItems.listByIds(listOf(itemId)).firstOrNull()
            ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        val total = (form.totalCount ?: 1).coerceAtLeast(0)
        val card = issue(
            companyId = companyId, userId = userId, itemId = itemId, orderId = 0,
            labelId = relTypes.listByItems(listOf(itemId)).firstOrNull()?.labelId ?: 0,
            name = form.remark?.takeIf { it.isNotBlank() } ?: item.itemName,
            total = total, end = form.endTime ?: 0, operator = operatorId,
            remark = "后台手工发放", now = TimeSec.now(),
        )
        return view(card)
    }

    /** 建卡 + 一行 grant 日志（每次发放恰好一行，恒等式：grant 行数 == 卡行数） */
    private fun issue(
        companyId: Long,
        userId: Long,
        itemId: Long,
        orderId: Long,
        labelId: Long,
        name: String,
        total: Int,
        end: Long,
        operator: Long,
        remark: String,
        now: Long,
    ): UserRightsDO {
        val card = UserRightsDO().apply {
            this.companyId = companyId; this.userId = userId; this.itemId = itemId
            this.orderId = orderId; this.labelId = labelId; rightsName = name
            totalCount = total; usedCount = 0; endTime = end; disabled = false
            created = now; updated = now
        }
        check(rights.insert(card) == 1) { "次卡行插入失败" }
        logAdd(card, behavior = "grant", userId = userId, toUser = 0, num = 0,
            operator = operator, remark = remark, now = now)
        return card
    }

    // ===================== 会员自办 =====================

    fun belongPage(companyId: Long, userId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { rights.countBelong(companyId, userId) },
        query = { off, lim -> rights.pageBelong(companyId, userId, off, lim) },
        map = { rows -> rows.map { view(it) } },
    )

    /** 我的可用卡简表（源 getdata）：两个数——可用卡数、剩余总次数（有不限次卡时 null） */
    fun getData(companyId: Long, userId: Long): Map<String, Any?> {
        val now = TimeSec.now()
        val rows = rights.pageBelong(companyId, userId, 0, 100).filter { it.endTime == 0L || it.endTime > now }
        val unlimited = rows.any { it.totalCount == 0 }
        return linkedMapOf(
            "cards" to rows.size,
            "times" to if (unlimited) null
            else rows.sumOf { (it.totalCount - it.usedCount).coerceAtLeast(0).toLong() },
        )
    }

    fun detail(companyId: Long, userId: Long, id: Long): Map<String, Any?> {
        val card = act(companyId, id).takeIf { it.userId == userId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "权益不存在")
        val out = LinkedHashMap<String, Any?>(view(card))
        out["logs"] = logs.listByRight(companyId, id).map { logView(it) }
        return out
    }

    /** 我的全部动账流水（含转出的 from、转入的 to 两腿） */
    fun myLogs(companyId: Long, userId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { logs.countBelong(companyId, userId) },
        query = { off, lim -> logs.pageBelong(companyId, userId, off, lim) },
        map = { rows -> rows.map { logView(it) } },
    )

    /** 我的转赠记录（只看 behavior='transfer' 的那腿） */
    fun myTransfers(companyId: Long, userId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { logs.countTransfers(companyId, userId) },
        query = { off, lim -> logs.pageTransfers(companyId, userId, off, lim) },
        map = { rows -> rows.map { logView(it) } },
    )

    /** 延期：有期限的卡 `end_time += days`；永久卡没有"到期日"可延 → 业务拒绝 */
    fun delay(companyId: Long, userId: Long, id: Long, days: Long): Map<String, Any?> {
        if (days <= 0) throw BizException(ErrorCode.VALIDATION, "参数不合法", mapOf("days" to listOf("必须为正整数")))
        val card = act(companyId, id).takeIf { it.userId == userId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "权益不存在")
        if (card.endTime == 0L) {
            throw BizException(ErrorCode.BIZ, "长期有效的权益无需延期", mapOf("rights_id" to listOf("该卡没有到期日")))
        }
        val now = TimeSec.now()
        val hit = rights.delay(companyId, id, days, now)
        if (hit == 0) throw BizException(ErrorCode.BIZ, "权益当前状态不可延期")
        logAdd(card, behavior = "delay", userId = userId, toUser = 0, num = 0,
            operator = 0, remark = "延期 $days 天", now = now)
        return view(rights.find(companyId, id) ?: card)
    }

    /** 转赠：归属 CAS 换人（0 行=并发刚转走/停用），流水 user_id=转出人、to_user_id=收件人 */
    fun transfer(companyId: Long, userId: Long, id: Long, toUserId: Long): Map<String, Any?> {
        if (toUserId == userId) {
            throw BizException(ErrorCode.BIZ, "不能转赠给自己", mapOf("to_user_id" to listOf("等于当前持有人")))
        }
        members.findById(toUserId)?.takeIf { it.companyId == companyId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "受赠会员不存在")
        val card = act(companyId, id).takeIf { it.userId == userId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "权益不存在")
        if (card.disabled) throw BizException(ErrorCode.BIZ, "权益已停用不可转赠")
        if (card.endTime > 0 && card.endTime <= TimeSec.now()) throw BizException(ErrorCode.BIZ, "权益已过期")
        val now = TimeSec.now()
        val hit = rights.transfer(companyId, id, fromUserId = userId, toUserId = toUserId, now = now)
        if (hit == 0) throw BizException(ErrorCode.BIZ, "权益当前状态不可转赠（可能已被并发转出）")
        logAdd(card, behavior = "transfer", userId = userId, toUser = toUserId, num = 0,
            operator = 0, remark = "转赠", now = now)
        return view(rights.find(companyId, id) ?: card)
    }

    // ===================== 店员核销 =====================

    /**
     * 核销一次：先查行拿到**人话文案**（停用/过期/用完），再走 [UserRightsMapper.consumeCas]
     * 与并发竞争 —— 0 行就是并发输了或状态刚变，不是"文案错了"，报同义的第二句。
     */
    fun consume(companyId: Long, id: Long, operatorId: Long): Map<String, Any?> {
        val now = TimeSec.now()
        val card = act(companyId, id)
        if (card.disabled) throw BizException(ErrorCode.BIZ, "权益已停用")
        if (card.endTime > 0 && card.endTime <= now) throw BizException(ErrorCode.BIZ, "权益已过期")
        if (card.totalCount != 0 && card.usedCount >= card.totalCount) {
            throw BizException(ErrorCode.BIZ, "权益次数已用完")
        }
        val hit = rights.consumeCas(companyId, id, now)
        if (hit == 0) throw BizException(ErrorCode.BIZ, "权益当前状态不可核销（可能已在并发中被核销）")
        logAdd(card, behavior = "consume", userId = card.userId, toUser = 0, num = 1,
            operator = operatorId, remark = "门店核销", now = now)
        return view(rights.find(companyId, id) ?: card)
    }

    /** 员工扫码画面：详情 + 该卡的动账史 */
    fun staffDetail(companyId: Long, id: Long): Map<String, Any?> {
        val card = act(companyId, id)
        val out = LinkedHashMap<String, Any?>(view(card))
        out["logs"] = logs.listByRight(companyId, id).map { logView(it) }
        return out
    }

    // ===================== 后台账账 =====================

    fun adminPage(companyId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { rights.countAll(companyId) },
        query = { off, lim -> rights.pageAll(companyId, off, lim) },
        map = { rows -> rows.map { view(it) } },
    )

    fun adminLogs(companyId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { logs.count(companyId) },
        query = { off, lim -> logs.page(companyId, off, lim) },
        map = { rows -> rows.map { logView(it) } },
    )

    // ===================== 内部 =====================

    private fun act(companyId: Long, id: Long): UserRightsDO =
        rights.find(companyId, id) ?: throw BizException(ErrorCode.NOT_FOUND, "权益不存在")

    /** 剩余次数：不限次返回 null；限次 = total − used（CAS 列与 consume 日志行数恒等） */
    private fun remainOf(card: UserRightsDO): Long? =
        if (card.totalCount == 0) null else (card.totalCount.toLong() - card.usedCount).coerceAtLeast(0)

    private fun logAdd(
        card: UserRightsDO,
        behavior: String,
        userId: Long,
        toUser: Long,
        num: Int,
        operator: Long,
        remark: String,
        now: Long,
    ) {
        logs.insert(
            UserRightsLogDO().apply {
                companyId = card.companyId
                rightId = card.id
                this.userId = userId
                toUserId = toUser
                this.behavior = behavior
                this.num = num
                operatorId = operator
                this.remark = remark
                created = now
                updated = now
            },
        )
    }

    fun view(card: UserRightsDO): Map<String, Any?> = linkedMapOf(
        "rights_id" to card.id,
        "company_id" to card.companyId,
        "user_id" to card.userId,
        "item_id" to card.itemId,
        "order_id" to card.orderId,
        "label_id" to card.labelId,
        "rights_name" to card.rightsName,
        "total_count" to card.totalCount,
        "used_count" to card.usedCount,
        "remain" to remainOf(card),
        "end_time" to card.endTime,
        "expired" to (card.endTime > 0 && card.endTime <= TimeSec.now()),
        "disabled" to card.disabled,
        "created" to card.created,
    )

    fun logView(l: UserRightsLogDO): Map<String, Any?> = linkedMapOf(
        "id" to l.id,
        "rights_id" to l.rightId,
        "user_id" to l.userId,
        "to_user_id" to l.toUserId,
        "behavior" to l.behavior,
        "num" to l.num,
        "operator_id" to l.operatorId,
        "remark" to l.remark,
        "created" to l.created,
    )
}

/** 后台手工发放入参（`total_count>=0`，0=不限次；`end_time<=0` 长期有效） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
class RightsGrantForm {
    var userId: Long? = null
    var itemId: Long? = null
    var totalCount: Int? = null
    var endTime: Long? = null
    var remark: String? = null
}
