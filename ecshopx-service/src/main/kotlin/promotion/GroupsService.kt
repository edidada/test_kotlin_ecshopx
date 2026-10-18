package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.OrderClass
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.aftersales.AftersalesType
import com.ecshopx.kotlin.dao.auth.MembersMapper
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.GroupsActivityDO
import com.ecshopx.kotlin.dao.promotion.GroupsActivityMapper
import com.ecshopx.kotlin.dao.promotion.GroupsMemberDO
import com.ecshopx.kotlin.dao.promotion.GroupsMemberMapper
import com.ecshopx.kotlin.dao.promotion.GroupsTeamDO
import com.ecshopx.kotlin.dao.promotion.GroupsTeamMapper
import com.ecshopx.kotlin.dao.promotion.MyTeamRow
import com.ecshopx.kotlin.dao.promotion.TeamStatus
import com.ecshopx.kotlin.service.aftersales.AftersalesApplyForm
import com.ecshopx.kotlin.service.aftersales.AftersalesRefundCheckForm
import com.ecshopx.kotlin.service.aftersales.AftersalesService
import com.ecshopx.kotlin.service.aftersales.RefundService
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Service

/**
 * M1-3 拼团（GP-A1..A6 + GP-F1..F4 + 下单开团/参团 + 成团与失败结算，docs/04 §14.1/§14.2/§14.6）。
 *
 * 四件事只有这里做，别处都别做：
 *  1. **名额是抢出来的**（D25）：`joinSeat` 的条件更新带 `join_person_num < person_num`，
 *     抢不到就 422。源系统是 `findOneBy` + `+1` 的读—改—写，2 人团能被并发坐成 3 人；
 *  2. **未付不占成团人数**：成员行 `paid` 才是成团计数的分子，所以取消/超时关单要 [releaseMember]
 *     （删行 + 名额回退，同事务），否则一个团会被一堆待付款单占死永远成不了；
 *  3. **失败团的钱从 M1-1 的链路退**（D26）：不新开退款通道，直接
 *     `AftersalesService.apply(autoApprove=true)` + `RefundService.checkRefund(checkRefund=1)`，
 *     于是"成团失败退款"和"商家同意退款"共用同一套额度守恒闸门与渠道幂等；
 *     只退款不补库存是 M1-1 的既定口径，所以这里额外补 `items.store` 与活动 `store`；
 *  4. **未成团不得发货**（§14.6 末行）：本服务给 [canShip]，发货卡口在 `OrderFlowService` 里读它，
 *     订单状态本身不新增（钱进来就是 `PAYED`，"待成团"是团队状态不是订单状态）。
 *
 * 一活动一商品（`goods_id`），一團一名额一行（`uk_team_member`）；
 * 机器人成员用**负数** `user_id`（源系统用 0，唯一索引下 0 只能坐一行）。
 */
@Service
class GroupsService(
    private val acts: GroupsActivityMapper,
    private val teams: GroupsTeamMapper,
    private val members: GroupsMemberMapper,
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val goodsItems: ItemsMapper,
    private val memberRepo: MembersMapper,
    private val aftersales: AftersalesService,
    private val refundService: RefundService,
    private val seq: SeqGenerator,
    private val db: Db,
) {

    // ===================== GP-A1 / GP-A4 建与改 =====================

    fun save(companyId: Long, form: GroupsForm): Map<String, Any?> {
        val id = form.groupsActivityId?.takeIf { it > 0 }
        val cur = id?.let { act(it, companyId) }
        val draft = GroupsActivityDO().also { d ->
            cur?.let { c ->
                d.groupsActivityId = c.groupsActivityId
                d.actName = c.actName
                d.goodsId = c.goodsId
                d.actPrice = c.actPrice
                d.personNum = c.personNum
                d.store = c.store
                d.limitBuyNum = c.limitBuyNum
                d.limitTime = c.limitTime
                d.beginTime = c.beginTime
                d.endTime = c.endTime
                d.robot = c.robot
                d.instruction = c.instruction
                d.disabled = c.disabled
                d.created = c.created
                d.updated = c.updated
            }
            d.companyId = companyId
            form.actName?.trim()?.takeIf { it.isNotEmpty() }?.let { d.actName = it }
            form.goodsId?.let { d.goodsId = it }
            form.actPrice?.let { d.actPrice = it }
            form.personNum?.let { d.personNum = it }
            form.limitBuyNum?.let { d.limitBuyNum = it }
            form.limitTime?.let { d.limitTime = it }
            form.beginTime?.let { d.beginTime = it }
            form.endTime?.let { d.endTime = it }
            form.robot?.let { d.robot = it }
            form.instruction?.takeIf { it.isNotBlank() }?.let { d.instruction = it }
            form.disabled?.let { d.disabled = it }
        }
        validate(draft, companyId)
        val now = TimeSec.now()
        if (cur == null) {
            draft.store = form.store ?: 0
            if (draft.store < 0) throw BizException(ErrorCode.VALIDATION, "store 不能为负", mapOf("store" to listOf("不能为负")))
            acts.insert(draft)
        } else {
            draft.updated = now
            acts.update(draft)
            // 活动库存只走"加"这条 CAS（`#{num} > 0` 在 SQL 里再守一次），改小等于把已成团的货退没
            val add = (form.store ?: 0) - cur.store
            if (add > 0) acts.addStore(cur.groupsActivityId, companyId, add, now)
            if (add < 0) throw BizException(ErrorCode.BIZ, "拼团活动库存只能加不能减")
        }
        return detail(companyId, draft.groupsActivityId)
    }

    private fun validate(a: GroupsActivityDO, companyId: Long) {
        val e = linkedMapOf<String, List<String>>()
        if (a.actName.isBlank()) e["act_name"] = listOf("活动名称不能为空")
        if (a.actName.length > 128) e["act_name"] = listOf("最长 128 字")
        if (a.goodsId <= 0) e["goods_id"] = listOf("必须指定拼团商品")
        // 本项目 goods_id 即主行 item_id（GoodsDos 的自指约定），限购与补库存都要按它取商品行
        val item = if (a.goodsId > 0) goodsItems.findById(a.goodsId) else null
        if (a.goodsId > 0 && (item == null || item.companyId != companyId)) e["goods_id"] = listOf("商品不存在或不属于本租户")
        if (a.actPrice <= 0) e["act_price"] = listOf("拼团价必须大于 0")
        if (item != null && a.actPrice > 0 && a.actPrice >= item.price) e["act_price"] = listOf("拼团价必须低于商品现价 ${item.price} 分")
        if (a.personNum < 2 || a.personNum > 1000) e["person_num"] = listOf("成团人数 2..1000")
        if (a.limitBuyNum < 0) e["limit_buy_num"] = listOf("限购次数不能为负")
        if (a.limitTime <= 0 || a.limitTime > 24 * 30) e["limit_time"] = listOf("成团时效 1..720 小时")
        if (a.beginTime <= 0) e["begin_time"] = listOf("开始时间必填")
        if (a.endTime <= a.beginTime) e["end_time"] = listOf("结束时间必须晚于开始时间")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "拼团活动配置不合法", e)
    }

    // ===================== GP-A2 / GP-A3 / GP-A5 / GP-A6 后台 =====================

    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = adminFilters(companyId, raw)
        return PageHelper.paginate(
            pq,
            count = { acts.count(f) },
            query = { offset, limit -> acts.page(f, offset, limit) },
            map = { rows -> rows.map { row(it) } },
        )
    }

    /** GP-A3 详情 + 团统计：运营要看"开了几团、成了几团、砸了几团" */
    fun detail(companyId: Long, groupsActivityId: Long): Map<String, Any?> {
        val a = act(groupsActivityId, companyId)
        val all = teams.listByAct(groupsActivityId)
        return LinkedHashMap(row(a)).apply {
            put("team_total", all.size)
            put("team_doing", all.count { it.teamStatus == TeamStatus.DOING })
            put("team_success", all.count { it.teamStatus == TeamStatus.SUCCESS })
            put("team_fail", all.count { it.teamStatus == TeamStatus.FAIL })
            put("teams", all.take(20).map { teamRow(it, a.personNum) })
        }
    }

    /**
     * GP-A5 停用 / 强制结束。
     *
     * 两个动作差别很大，所以一个端点两个开关：
     *  - `disabled` 只是"不能再开新团、不能再参团"，在途的团继续按时间结算；
     *  - `end=true` 是**立即把该活动所有进行中的团判失败并退款**（PHP `finish/{id}` 的语义），
     *    先停用再结算，避免结算过程中还有人挤进来开新团。
     */
    fun status(companyId: Long, groupsActivityId: Long, form: GroupsStatusForm): Map<String, Any?> {
        val a = act(groupsActivityId, companyId)
        val now = TimeSec.now()
        form.disabled?.let { acts.updateDisabled(a.groupsActivityId, companyId, it, now) }
        var failed = 0
        if (form.end == true) {
            acts.updateDisabled(a.groupsActivityId, companyId, true, now)
            failed = teams.listByActAndStatus(a.groupsActivityId, TeamStatus.DOING).count { failTeam(it, now, "活动被强制结束") }
        }
        return detail(companyId, groupsActivityId).let { LinkedHashMap(it).apply { put("failed_teams", failed) } }
    }

    fun teamList(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = linkedMapOf<String, Any?>(
            "actId" to raw["act_id"]?.toLongOrNull()?.takeIf { it > 0 },
            "status" to raw["team_status"]?.toIntOrNull()?.takeIf { it in 1..3 },
        )
        return PageHelper.paginate(
            pq,
            count = { teams.count(f) },
            query = { offset, limit -> teams.page(f, offset, limit) },
            map = { rows ->
                val actIds = rows.map { it.actId }.distinct()
                val person = actIds.associateWith { id -> acts.findById(id, companyId)?.personNum ?: 2 }
                rows.map { teamRow(it, person[it.actId] ?: 2) }
            },
        )
    }

    // ===================== GP-F1..F4 会员面 =====================

    fun frontActivities(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "disabled" to false,
            "goodsId" to raw["goods_id"]?.toLongOrNull()?.takeIf { it > 0 },
            "dateStatus" to (raw["date_status"]?.takeIf { it.isNotBlank() } ?: "doing"),
            "now" to TimeSec.now(),
        )
        return PageHelper.paginate(
            pq,
            count = { acts.count(f) },
            query = { offset, limit -> acts.page(f, offset, limit) },
            map = { rows ->
                rows.map { a ->
                    LinkedHashMap(frontRow(a)).apply {
                        put("team_count", teams.listByActAndStatus(a.groupsActivityId, TeamStatus.DOING).size)
                    }
                }
            },
        )
    }

    /** GP-F2 详情 + 可进行中的团（"去参团"的入口列表） */
    fun frontDetail(companyId: Long, groupsActivityId: Long): Map<String, Any?> {
        val a = act(groupsActivityId, companyId)
        if (a.disabled) throw BizException(ErrorCode.NOT_FOUND, "活动不存在")
        val open = teams.listByActAndStatus(groupsActivityId, TeamStatus.DOING)
        return LinkedHashMap(frontRow(a)).apply {
            put("teams", open.map { t -> teamRow(t, a.personNum) + linkedMapOf("head_nickname" to headNick(t)) })
            put("instruction", a.instruction)
        }
    }

    /** GP-F3 团成员（昵称脱敏、机器人标记） */
    fun teamDetail(companyId: Long, teamId: String): Map<String, Any?> {
        val t = team(teamId)
        val a = act(t.actId, companyId)
        val rows = members.listByTeam(teamId)
        return linkedMapOf(
            "team_id" to teamId,
            "act_id" to a.groupsActivityId,
            "act_name" to a.actName,
            "goods_id" to a.goodsId,
            "act_price" to a.actPrice,
            "team_status" to t.teamStatus,
            "team_status_text" to statusText(t.teamStatus),
            "person_num" to a.personNum,
            "join_person_num" to t.joinPersonNum,
            "lack_num" to (a.personNum - t.joinPersonNum).coerceAtLeast(0),
            "robot_num" to t.robotNum,
            "begin_time" to t.beginTime,
            "end_time" to t.endTime,
            "members" to rows.map { m ->
                linkedMapOf(
                    "user_id" to m.userId,
                    "nickname" to (m.userNick ?: maskMobile(m.userId)),
                    "avatar" to null,
                    "join_time" to m.joinTime,
                    "paid" to m.paid,
                    "is_head" to (m.userId == t.headUserId),
                    "is_robot" to (m.userId < 0),
                    "order_id" to m.orderId,
                )
            },
        )
    }

    /** GP-F4 我的团 */
    fun myTeams(companyId: Long, userId: Long): List<Map<String, Any?>> {
        val person = acts.listRunning(companyId, TimeSec.now()).associate { it.groupsActivityId to it.personNum }
        return members.listMyTeams(companyId, userId).map { r: MyTeamRow ->
            linkedMapOf<String, Any?>(
                "team_id" to r.teamId,
                "act_id" to r.actId,
                "team_status" to r.teamStatus,
                "team_status_text" to statusText(r.teamStatus),
                "join_person_num" to r.joinPersonNum,
                "person_num" to (person[r.actId] ?: 2),
                "lack_num" to ((person[r.actId] ?: 2) - r.joinPersonNum).coerceAtLeast(0),
                "end_time" to r.endTime,
                "order_id" to r.orderId,
                "paid" to r.paid,
                "join_time" to r.joinTime,
                "is_head" to (r.headUserId == userId),
            )
        }
    }

    // ===================== 下单侧：开团 / 参团 =====================

    /**
     * 下单事务内调用：占一个成团名额，返回团行。
     *
     * 六层校验，顺序按"贵→贱"（先查活动与限购，最后才动 CAS）：
     *  1. 活动在窗且未停用；
     *  2. **每件占一个名额**：拼团的成团语义是"人数"，一单买 3 件只算 3 个人头是荒谬的，
     *     所以一次参团固定 1 件（docs/04 §16 的 C 系列口径）；
     *  3. `limit_buy_num`：本人在该活动下"失败团以外"的参团次数（[GroupsMemberMapper.countJoinedInAct]）；
     *  4. **同活动不得有第二笔待付款参团单**（源系统同口径，[GroupsMemberMapper.countUnpaidInAct]）；
     *  5. 参团时团必须存在、属本活动、进行中、未到期、本人不在团内；
     *  6. 两个 CAS：活动 `store`（[GroupsActivityMapper.deductStore]）与团名额（[GroupsTeamMapper.joinSeat]），
     *     任一拿 0 行就抛错整笔回滚 —— 名额回滚由事务负责，不靠补偿代码。
     */
    fun occupySeat(
        companyId: Long,
        userId: Long,
        actId: Long,
        teamId: String?,
        orderId: Long,
        num: Long,
        now: Long,
    ): GroupsTeamDO {
        val a = act(actId, companyId)
        if (a.disabled) throw BizException(ErrorCode.BIZ, "拼团活动已停用")
        if (now < a.beginTime) throw BizException(ErrorCode.BIZ, "拼团活动尚未开始")
        if (now > a.endTime) throw BizException(ErrorCode.BIZ, "拼团活动已结束")
        if (num != 1L) throw BizException(ErrorCode.VALIDATION, "拼团一次只能参 1 件", mapOf("num" to listOf("一个名额对应一件商品")))

        if (a.limitBuyNum > 0 && members.countJoinedInAct(actId, userId) >= a.limitBuyNum) {
            throw BizException(ErrorCode.BIZ, "已达本活动限购次数（${a.limitBuyNum} 次）")
        }
        if (members.countUnpaidInAct(actId, userId) > 0) {
            throw BizException(ErrorCode.BIZ, "你还有未付款的参团订单，请先付款或取消")
        }

        val target = teamId?.trim()?.takeIf { it.isNotEmpty() }
        val team = if (target == null) null else {
            val t = team(target)
            when {
                t.actId != actId -> throw BizException(ErrorCode.BIZ, "该团不属于本拼团活动")
                t.teamStatus == TeamStatus.SUCCESS -> throw BizException(ErrorCode.BIZ, "该团已成团")
                t.teamStatus == TeamStatus.FAIL -> throw BizException(ErrorCode.BIZ, "该团已解散")
                t.endTime in 1..now -> throw BizException(ErrorCode.BIZ, "该团已到成团时限")
                members.find(target, userId) != null -> throw BizException(ErrorCode.BIZ, "你已参加过该团")
                else -> t
            }
        }
        // 开新团不占额外额度：团行只是"待人来凑"，额度按件走
        if (acts.deductStore(actId, companyId, num) == 0) throw BizException(ErrorCode.BIZ, "拼团名额已用完")

        val nick = maskMobile(userId)
        val created: GroupsTeamDO = if (team == null) {
            val newId = seq.nextBn(SeqName.GROUP_TEAM_ID)
            val t = GroupsTeamDO().apply {
                this.teamId = newId
                this.actId = a.groupsActivityId
                headUserId = userId
                joinPersonNum = 1
                teamStatus = TeamStatus.DOING
                beginTime = now
                endTime = teamDeadline(a, now)
                robotNum = 0
                created = now
            }
            teams.insert(t)
            insertMember(newId, a.groupsActivityId, userId, orderId, now, nick)
            t
        } else {
            val tid = target ?: throw BizException(ErrorCode.BIZ, "参团 id 非法")
            if (teams.joinSeat(tid, a.personNum) == 0) throw BizException(ErrorCode.BIZ, "该团名额已满，请重新开团")
            insertMember(tid, a.groupsActivityId, userId, orderId, now, nick)
            team
        }
        return created
    }

    /** 团的成团时限：`min(now + limit_time, 活动结束)` —— 活动收尾时不该留一个永远结不了的团 */
    private fun teamDeadline(a: GroupsActivityDO, now: Long): Long {
        val byLimit = now + a.limitTime * 3600L
        return if (a.endTime > 0) minOf(byLimit, a.endTime) else byLimit
    }

    private fun insertMember(teamId: String, actId: Long, userId: Long, orderId: Long, now: Long, nick: String?) {
        members.insert(
            GroupsMemberDO().apply {
                this.teamId = teamId
                this.actId = actId
                this.userId = userId
                this.orderId = orderId.toString()
                joinTime = now
                paid = false
                userNick = nick
            },
        )
    }

    // ===================== 结清 / 关单 / 发货卡口 =====================

    /**
     * 订单结清（PAY-4/回调侧调用）：成员置已付 → 数够人就把团推到成功。
     *
     * 成团判定用 `transit(1→2)` 的 CAS：两笔同时结清的回调只有一笔能改团状态，
     * 另一笔拿 0 行、什么都不用做（团本来就已经是 2 了）。
     */
    fun onOrderPaid(orderId: Long): Boolean {
        val m = members.findByOrder(orderId.toString()) ?: return false
        // 置付走 CAS；即便 0 行（重复回调）也要再数一次人数 —— 上一笔结清的团可能正好差这一个人
        members.markPaid(m.teamId, m.userId, orderId.toString())
        val t = teams.findByTeamId(m.teamId) ?: return false
        if (t.teamStatus != TeamStatus.DOING) return false
        val need = actsById(t.actId)?.personNum ?: return false
        if (members.countPaid(m.teamId) >= need) teams.transit(m.teamId, TeamStatus.DOING, TeamStatus.SUCCESS)
        return true
    }

    /** 未付订单取消/超时关单：成员退团（同事务），成团的团不动 */
    fun onOrderClosed(orderId: Long) {
        val m = members.findByOrder(orderId.toString()) ?: return
        if (m.paid) return
        releaseMember(m, TimeSec.now())
    }

    /**
     * 成员退团：删成员行 + 团名额回退 + **活动额度回补**，三件事同事务。
     *
     * 额度回补不能省：[occupySeat] 抢的是 `promotion_groups_activity.store`，
     * 订单取消后不回补就会出现"活动显示名额用完、团里却一个人都没有"。
     * 已付成员不走这里（她们的额度由失败团的 [refundFailedOrder] 统一回补，避免退两次）。
     */
    private fun releaseMember(m: GroupsMemberDO, now: Long) {
        val orderId = m.orderId?.toLongOrNull() ?: 0L
        val o = if (orderId > 0) orders.findById(orderId) else null
        val num = if (o == null) 1L else orderItems.listByOrder(orderId).sumOf { it.num }.coerceAtLeast(1L)
        db.transactional {
            members.dropUnpaid(m.teamId, m.userId)
            teams.leaveSeat(m.teamId)
            acts.restoreStore(m.actId, o?.companyId ?: actsById(m.actId)?.companyId ?: 0L, num)
            Unit
        }
    }

    /** 发货卡口（§14.6）：非拼团单恒可发；拼团单只有 `team_status=2 已成团` 才放行 */
    fun canShip(orderId: Long): Boolean {
        val m = members.findByOrder(orderId.toString()) ?: return true
        val t = teams.findByTeamId(m.teamId) ?: return false
        return t.teamStatus == TeamStatus.SUCCESS
    }

    // ===================== 定时：到期判定 / 机器人凑团 =====================

    /**
     * 到期团结算（`GroupsSettleJob` 第一条腿）。
     *
     * 先看人数够不够（够就成团，兜住"结清回调丢了"的团），不够才判失败。
     * 失败团里**未付的成员直接清掉**（订单已由 `OrderTimeoutJob` 关单），
     * 已付的走 M1-1 退款链全额退 + 补库存。
     */
    fun settleDue(now: Long, limit: Int = 50): Int {
        var done = 0
        teams.listDue(now, limit).forEach { t ->
            val a = actsById(t.actId)
            val need = a?.personNum ?: t.joinPersonNum
            if (members.countPaid(t.teamId) >= need) {
                if (teams.transit(t.teamId, TeamStatus.DOING, TeamStatus.SUCCESS) > 0) done++
            } else if (failTeam(t, now, "到期未成团，系统自动退款")) {
                done++
            }
        }
        return done
    }

    /**
     * 机器人凑团（第二条腿）：临近结束（[horizon] 内）且 `robot=true` 的团补虚拟成员成团。
     *
     * 机器人只在"还差人"时补，补完立刻 CAS 成团；
     * `user_id` 用负数（`-(team_id*100+序号)`）保证同团内唯一且不撞真人 —— 源系统的 0 在唯一索引下只能坐一行。
     */
    fun fillRobots(now: Long, horizon: Long, limit: Int = 50): Int {
        var filled = 0
        teams.listNearEndForRobot(now, horizon, limit).forEach { t ->
            val a = actsById(t.actId) ?: return@forEach
            if (a.disabled) return@forEach
            val lack = a.personNum - t.joinPersonNum
            if (lack <= 0) {
                if (members.countPaid(t.teamId) >= a.personNum) teams.transit(t.teamId, TeamStatus.DOING, TeamStatus.SUCCESS)
                return@forEach
            }
            db.transactional {
                repeat(lack) { i ->
                    members.insert(
                        GroupsMemberDO().apply {
                            teamId = t.teamId
                            actId = a.groupsActivityId
                            userId = -(t.id * 100L + i + 1)
                            orderId = null
                            joinTime = now
                            paid = true
                            userNick = "虚拟成团"
                        },
                    )
                }
                // 补位与"团里多了几个人"必须同时成立，否则人数与成员行会对不上
                teams.addRobots(t.teamId, lack)
                teams.transit(t.teamId, TeamStatus.DOING, TeamStatus.SUCCESS)
                Unit
            }
            filled++
        }
        return filled
    }

    /**
     * 判团失败并善后。`transit(1→3)` 是守卫：并发（结算任务与后台强制结束同时跑）只有一边真的退钱。
     */
    private fun failTeam(t: GroupsTeamDO, now: Long, reason: String): Boolean {
        if (teams.transit(t.teamId, TeamStatus.DOING, TeamStatus.FAIL) == 0) return false
        // 未付成员直接清行 + 回补额度（订单由超时关单处理，这里不重复退款）
        members.listByTeam(t.teamId).filter { !it.paid && it.userId > 0 }.forEach { releaseMember(it, now) }
        members.listPaidHumans(t.teamId).forEach { refundFailedOrder(it, now, reason) }
        return true
    }

    /**
     * 失败团退款：一单一次「仅退款 + 当场确认退款」，全程走 M1-1 的闸门与幂等。
     *
     * 退款链不补库存（M1-1 的既定口径：仅退款 = 货不发出去，库存由商家自己处理），
     * 所以这里显式补 `items.store` 与活动 `store` —— 拼团失败是系统原因，货必须回到池子里。
     * 失败只记日志不回滚团状态：团已经判死了，钱由 AF 面的重试与对账兜。
     */
    private fun refundFailedOrder(m: GroupsMemberDO, now: Long, reason: String) {
        val orderId = m.orderId?.toLongOrNull() ?: return
        val o = orders.findById(orderId) ?: return
        if (o.orderStatus == OrderStatus.CANCEL) return
        val rows = orderItems.listByOrder(orderId)
        if (rows.isEmpty()) return
        val bn: String = try {
            val applied = aftersales.apply(
                o.userId, o.companyId,
                AftersalesApplyForm(
                    orderId = orderId.toString(),
                    itemId = rows.first().itemId,
                    num = rows.sumOf { r -> r.num },
                    aftersalesType = AftersalesType.ONLY_REFUND,
                    reason = "拼团失败",
                    description = "$reason（团号 ${m.teamId}）",
                ),
                autoApprove = true,
            )
            (applied["aftersales_bn"] as? String) ?: return
        } catch (e: BizException) {
            return
        }
        runCatching {
            refundService.checkRefund(
                o.companyId,
                AftersalesRefundCheckForm(aftersalesBn = bn, checkRefund = 1, refundsMemo = reason),
            )
        }
        rows.forEach { r -> goodsItems.restoreStock(r.itemId, r.num, now) }
        acts.restoreStore(m.actId, o.companyId, rows.sumOf { it.num })
    }

    // ===================== 出参装配 =====================

    private fun row(a: GroupsActivityDO): LinkedHashMap<String, Any?> = linkedMapOf(
        "groups_activity_id" to a.groupsActivityId,
        "company_id" to a.companyId,
        "act_name" to a.actName,
        "goods_id" to a.goodsId,
        "goods_name" to (goodsItems.findById(a.goodsId)?.itemName ?: ""),
        "act_price" to a.actPrice,
        "origin_price" to (goodsItems.findById(a.goodsId)?.price ?: a.actPrice),
        "person_num" to a.personNum,
        "store" to a.store,
        "limit_buy_num" to a.limitBuyNum,
        "limit_time" to a.limitTime,
        "begin_time" to a.beginTime,
        "end_time" to a.endTime,
        "robot" to a.robot,
        "instruction" to a.instruction,
        "disabled" to a.disabled,
        "date_status" to dateStatus(a, TimeSec.now()),
        "created" to a.created,
        "updated" to a.updated,
    )

    private fun frontRow(a: GroupsActivityDO): LinkedHashMap<String, Any?> = LinkedHashMap(row(a)).apply {
        remove("disabled")
        put("activity_type", OrderClass.GROUPS)
        put("activity_id", a.groupsActivityId)
    }

    private fun teamRow(t: GroupsTeamDO, personNum: Int): LinkedHashMap<String, Any?> = linkedMapOf(
        "team_id" to t.teamId,
        "act_id" to t.actId,
        "head_user_id" to t.headUserId,
        "team_status" to t.teamStatus,
        "team_status_text" to statusText(t.teamStatus),
        "join_person_num" to t.joinPersonNum,
        "person_num" to personNum,
        "lack_num" to (personNum - t.joinPersonNum).coerceAtLeast(0),
        "robot_num" to t.robotNum,
        "begin_time" to t.beginTime,
        "end_time" to t.endTime,
    )

    private fun headNick(t: GroupsTeamDO): String = maskMobile(t.headUserId)

    private fun maskMobile(userId: Long): String {
        if (userId <= 0) return "虚拟成团"
        val m = memberRepo.findById(userId)?.mobile
        if (m.isNullOrBlank() || m.length < 7) return "用户${userId.toString().takeLast(4)}"
        return "${m.take(3)}****${m.takeLast(4)}"
    }

    private fun statusText(status: Int): String = when (status) {
        TeamStatus.SUCCESS -> "已成团"
        TeamStatus.FAIL -> "未成团"
        else -> "拼团中"
    }

    private fun dateStatus(a: GroupsActivityDO, now: Long): String = when {
        a.beginTime > now -> "pending"
        a.endTime < now -> "end"
        else -> "doing"
    }

    private fun adminFilters(companyId: Long, raw: Map<String, String>): Map<String, Any?> = linkedMapOf(
        "companyId" to companyId,
        "disabled" to raw["disabled"]?.let { it == "true" || it == "1" },
        "goodsId" to raw["goods_id"]?.toLongOrNull()?.takeIf { it > 0 },
        "dateStatus" to raw["date_status"]?.takeIf { it.isNotBlank() },
        "now" to TimeSec.now(),
    )

    private fun act(groupsActivityId: Long, companyId: Long): GroupsActivityDO =
        acts.findById(groupsActivityId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "拼团活动不存在")

    private fun actsById(groupsActivityId: Long): GroupsActivityDO? = acts.findAnyById(groupsActivityId)

    private fun team(teamId: String): GroupsTeamDO =
        teams.findByTeamId(teamId) ?: throw BizException(ErrorCode.NOT_FOUND, "团不存在或已解散")

    private fun pageQuery(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
}
