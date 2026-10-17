package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MembersMapper
import com.ecshopx.kotlin.dao.member.MembersInfoMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.CouponCountRow
import com.ecshopx.kotlin.dao.promotion.CouponDO
import com.ecshopx.kotlin.dao.promotion.CouponMapper
import com.ecshopx.kotlin.dao.promotion.CouponStatus
import com.ecshopx.kotlin.dao.promotion.CouponUserDO
import com.ecshopx.kotlin.dao.promotion.CouponUserMapper
import com.ecshopx.kotlin.dao.promotion.TargetedActivityMapper
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * M1-3 优惠券服务（CP-A1..A8 / CP-F1..F4，加上订单链与结算链要用的锁/核销/回退）。
 *
 * 三条不变式（docs/04 §14.6、D22/D23）：
 *  1. **超发不可能**：领量走 `send_num < total_num` 条件更新，0 行即"没抢到"；
 *     券码有唯一索引，一张券同时挂两笔单由 `LOCKED` 态挡住（源系统 `freezeUserCard()` 有调用无实现，
 *     锁定态其实不存在 —— 这里是行为修复，不是照抄）。
 *  2. **快照不回溯**：面额/门槛/名称写在实例行上，后台改模板只影响之后新领的券，
 *     已发出的券按领到手那一刻的面额抵扣 —— CP-A7 的记录因此能与历史订单逐分对上。
 *  3. **状态机只有四条边**：`UNUSED→LOCKED`（下单）、`LOCKED→USED`（结清）、`LOCKED→UNUSED`（取消/关单）、
 *     `UNUSED→EXPIRED`（回收）。每条边都是 CAS，重复调用拿 0 行而不是二次改账。
 *
 * 券模板的 `status` 是 BOOLEAN（在架/停发），实例的 `status` 是 VARCHAR（四态）——
 * 同名列不同型，本服务里所有判据都写成显式常量。
 */
@Service
class CouponService(
    private val coupons: CouponMapper,
    private val mine: CouponUserMapper,
    private val members: MembersMapper,
    private val infos: MembersInfoMapper,
    private val targeted: TargetedActivityMapper,
    private val seq: SeqGenerator,
    private val db: Db,
    private val json: ObjectMapper,
) {

    // ===================== CP-A1 / CP-A4 建券与改券 =====================

    fun save(companyId: Long, form: CouponForm): Map<String, Any?> {
        val draft = CouponDO().apply {
            this.companyId = companyId
            couponType = form.couponType?.takeIf { it.isNotBlank() } ?: "cash"
            couponName = form.couponName?.trim().orEmpty()
            discountFee = form.discountFee ?: 0
            minOrderFee = form.minOrderFee ?: 0
            totalNum = form.totalNum ?: 0
            validType = form.validType?.takeIf { it.isNotBlank() } ?: "range"
            beginTime = form.beginTime ?: 0
            endTime = form.endTime ?: 0
            validDays = form.validDays ?: 0
            getLimit = form.getLimit ?: 0
            instruction = form.instruction?.takeIf { it.isNotBlank() }
            status = true
        }
        validate(draft)
        val id = form.couponId?.takeIf { it > 0 } ?: return coupons.insert(draft).let { detail(companyId, draft.couponId) }
        val cur = template(id, companyId)
        // 总量不许缩到已发数以下：那会让已经发出去的券变成"超发"
        if (draft.totalNum > 0 && draft.totalNum < cur.sendNum) {
            throw BizException(ErrorCode.BIZ, "发放总量不能小于已发放数", mapOf("total_num" to listOf("已发放 ${cur.sendNum} 张")))
        }
        draft.couponId = id
        draft.status = cur.status
        coupons.update(draft)
        return detail(companyId, id)
    }

    /** 表单校验：错误形状与全仓库一致（`{field: [msg]}`） */
    private fun validate(c: CouponDO) {
        val e = linkedMapOf<String, List<String>>()
        if (c.couponName.isBlank()) e["coupon_name"] = listOf("不能为空")
        if (c.couponName.length > 128) e["coupon_name"] = listOf("最长 128 字")
        if (c.discountFee <= 0) e["discount_fee"] = listOf("面额必须大于 0")
        if (c.minOrderFee < 0) e["min_order_fee"] = listOf("门槛不能为负")
        if (c.totalNum < 0) e["total_num"] = listOf("总量不能为负")
        if (c.getLimit < 0) e["get_limit"] = listOf("限领不能为负")
        if ((c.instruction?.length ?: 0) > 255) e["instruction"] = listOf("说明最长 255 字")
        when (c.validType) {
            "range" -> {
                if (c.beginTime <= 0) e["begin_time"] = listOf("固定有效期必须给开始时间")
                if (c.endTime <= 0) e["end_time"] = listOf("固定有效期必须给结束时间")
                if (c.beginTime > 0 && c.endTime > 0 && c.endTime <= c.beginTime) e["end_time"] = listOf("结束时间必须晚于开始时间")
            }
            "days" -> if (c.validDays <= 0) e["valid_days"] = listOf("领后有效天数必须大于 0")
            else -> e["valid_type"] = listOf("只支持 range / days")
        }
        // 面额高于门槛的券等于"倒找钱"，源系统靠运营自觉，本项目挡在配置台（无门槛券 min=0 不受此限）
        if (c.minOrderFee > 0 && c.discountFee > c.minOrderFee) e["discount_fee"] = listOf("面额不得高于使用门槛")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "券配置不合法", e)
    }

    /** CP-A5 停发/恢复：只影响后续领取与发放，已发出的券按自身有效期继续可用 */
    fun setStatus(companyId: Long, couponId: Long, status: Boolean): Map<String, Any?> {
        template(couponId, companyId)
        coupons.updateStatus(couponId, companyId, status, TimeSec.now())
        return detail(companyId, couponId)
    }

    /** CP-A6 删除：发过就不能删（历史券实例会指向空洞），只能停发 */
    fun delete(companyId: Long, couponId: Long): Map<String, Any?> {
        val c = template(couponId, companyId)
        if (c.sendNum > 0 || coupons.deleteIfUnsent(couponId, companyId) == 0) {
            throw BizException(ErrorCode.BIZ, "该券已发放 ${c.sendNum} 张，不能删除，请改为停发")
        }
        return linkedMapOf("coupon_id" to couponId, "deleted" to true)
    }

    // ===================== CP-A2 / CP-A3 / CP-A7 后台查询 =====================

    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "name" to raw["coupon_name"]?.takeIf { it.isNotBlank() },
            "type" to raw["coupon_type"]?.takeIf { it.isNotBlank() },
            "status" to raw["status"]?.let { it == "true" || it == "1" },
        )
        return PageHelper.paginate(
            pq,
            count = { coupons.count(f) },
            query = { offset, limit -> coupons.page(f, offset, limit) },
            map = { rows ->
                val stats = statsOf(companyId, rows.map { it.couponId })
                rows.map { row(it, stats[it.couponId]) }
            },
        )
    }

    fun detail(companyId: Long, couponId: Long): Map<String, Any?> {
        val c = template(couponId, companyId)
        return LinkedHashMap(row(c, mine.stats(companyId, couponId))).apply {
            put("instruction", c.instruction)
            put("begin_time", c.beginTime)
            put("end_time", c.endTime)
            put("valid_days", c.validDays)
        }
    }

    /** CP-A7 领取/使用记录：昵称缺失回落手机号，两者都没有给"会员{id}" */
    fun records(companyId: Long, couponId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        template(couponId, companyId)
        val pq = pageQuery(raw)
        val userId = raw["user_id"]?.toLongOrNull()
        val status = raw["status"]?.takeIf { it.isNotBlank() }?.uppercase()
        return PageHelper.paginate(
            pq,
            count = { mine.countRecords(companyId, couponId, userId, status) },
            query = { offset, limit -> mine.pageRecords(companyId, couponId, userId, status, offset, limit) },
            map = { rows ->
                val nick = nicknames(rows.map { it.userId })
                rows.map {
                    LinkedHashMap(userRow(it)).apply {
                        // 记录面按会员筛，回读时必须能看见筛的是谁
                        put("user_id", it.userId)
                        put("user_nick", nick[it.userId])
                        put("order_id", it.orderId.takeIf { o -> o > 0 }?.toString())
                    }
                }
            },
        )
    }

    // ===================== CP-A8 后台发券 =====================

    /**
     * 后台发券。抢量走与领取完全同一条 CAS，所以"发超"同样不可能。
     *
     * 三种入参形态按 `user_ids` > `grade_id`+`coupon_id` > `grade_id`（读定向活动的等级→券映射）取先者。
     * **逐会员独立成败**并把原因回出来：批量作业里"整批因为第 37 个人失败"是最难排查的形态。
     * 限领在这里不拦（运营主动发券不该被会员历史卡住），总量一定拦。
     */
    fun send(companyId: Long, form: CouponSendForm): Map<String, Any?> {
        val ids = form.userIds.orEmpty().filter { it > 0 }.distinct()
        val now = TimeSec.now()
        val plan: List<Pair<Long, List<Pair<Long, Int>>>> = when {
            ids.isNotEmpty() -> {
                val couponId = form.couponId?.takeIf { it > 0 }
                    ?: throw BizException(ErrorCode.VALIDATION, "coupon_id 必填", mapOf("coupon_id" to listOf("按会员发券必须指定券")))
                template(couponId, companyId)
                ids.map { it to listOf(couponId to 1) }
            }
            else -> {
                val gradeId = form.gradeId?.takeIf { it > 0 }
                    ?: throw BizException(ErrorCode.VALIDATION, "user_ids / grade_id 至少给一个", mapOf("grade_id" to listOf("不能为空")))
                val pairs = form.couponId?.takeIf { it > 0 }?.let { listOf(it to 1) }
                    ?: gradeCouponMap(companyId)[gradeId]
                    ?: throw BizException(
                        ErrorCode.BIZ, "该等级没有配券",
                        mapOf("grade_id" to listOf("定向活动的 discount_config 里没有等级 $gradeId")),
                    )
                pairs.forEach { (couponId, _) -> template(couponId, companyId) }
                members.listUserIdsByGrade(companyId, gradeId, SEND_BATCH_LIMIT).map { it to pairs }
            }
        }

        val sent = mutableListOf<Map<String, Any?>>()
        val failed = mutableListOf<Map<String, Any?>>()
        plan.forEach { (uid, items) ->
            items.forEach { (couponId, count) ->
                repeat(count.coerceIn(1, SEND_MAX_PER_USER)) {
                    val r = sendOne(uid, companyId, couponId, now)
                    if (r.ok) sent += r.row!! else failed += linkedMapOf(
                        "user_id" to uid,
                        "coupon_id" to couponId,
                        "reason" to r.reason,
                    )
                }
            }
        }
        return linkedMapOf("total" to (sent.size + failed.size), "sent_num" to sent.size, "sent" to sent, "failed" to failed)
    }

    /**
     * 单个会员发一张：抢量 + 落实例同事务；抢不到量返回失败原因而不是抛异常（批量里一人失败不该中断）。
     *
     * 会员必须先查存在且属于本租户 —— 券实例的 `user_id` 是外键语义，
     * 发给不存在的会员等于造一条永远领不到的死记录（CP-A8 的 `failed[]` 就是要看见这种人）。
     */
    private fun sendOne(userId: Long, companyId: Long, couponId: Long, now: Long): SendResult {
        val member = members.findById(userId)
        if (member == null || member.companyId != companyId) return SendResult(false, null, "会员不存在")
        val code = seq.nextBn(SeqName.COUPON_CODE)
        val out = db.transactional {
            if (coupons.takeQuota(couponId, companyId, now, now) == 0) SendResult(false, null, "券已停发或已发完")
            else {
                val c = coupons.findById(couponId, companyId) ?: return@transactional SendResult(false, null, "券不存在")
                SendResult(true, insertInstance(c, userId, code, now, SOURCE_SEND), null)
            }
        }
        return out
    }

    private data class SendResult(val ok: Boolean, val row: Map<String, Any?>?, val reason: String?)

    /**
     * 等级→券映射：`promotions_activity.discount_config` 的 `{"coupons":{"<grade_id>":[{"id":..,"count":n}]}}`。
     * 脏 JSON / 未知结构一律当"没配"跳过 —— 一条脏配置不该把整批发券打挂（与满减存量规则同一容错口径）。
     */
    private fun gradeCouponMap(companyId: Long): Map<Long, List<Pair<Long, Int>>> {
        val out = linkedMapOf<Long, MutableList<Pair<Long, Int>>>()
        targeted.listActive(companyId, TimeSec.now()).forEach { act ->
            val raw = act.discountConfig?.takeIf { it.isNotBlank() } ?: return@forEach
            val node = runCatching { json.readTree(raw).path("coupons") }.getOrNull() ?: return@forEach
            if (!node.isObject) return@forEach
            node.fields().forEach { (grade, rows) ->
                val gradeId = grade.toLongOrNull() ?: return@forEach
                if (!rows.isArray) return@forEach
                rows.forEach { r ->
                    val cid = r.path("id").asLong(0)
                    if (cid > 0) out.getOrPut(gradeId) { mutableListOf() } += cid to r.path("count").asInt(1).coerceIn(1, SEND_MAX_PER_USER)
                }
            }
        }
        return out
    }

    // ===================== CP-F1 可领券列表 =====================

    /** `open_only`（缺省 true）只给"在架 + 有量 + 在窗"的模板；后台预览全部模板时传 false */
    fun openList(userId: Long, companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val openOnly = raw["open_only"]?.let { it != "false" && it != "0" } ?: true
        val now = TimeSec.now()
        return PageHelper.paginate(
            // 前台列表不返回精确总数：open_only 的判定含时间，count 与 page 分开算必然打架，用估计数
            pq,
            count = null,
            query = { offset, limit -> coupons.pageOpen(companyId, openOnly, now, offset, limit) },
            map = { rows ->
                val owned = if (rows.isEmpty()) emptyMap() else mine
                    .countOwnedBatch(companyId, userId, rows.map { it.couponId }, now)
                    .associateBy { it.couponId }
                rows.map {
                    LinkedHashMap(row(it, null)).apply {
                        val o = owned[it.couponId]
                        put("received_num", o?.owned ?: 0L)
                        put("usable_num", o?.usable ?: 0L)
                        val why = receiveBlock(it, o?.owned ?: 0L, now)
                        put("can_receive", why == null)
                        put("reason", why)
                    }
                }
            },
        )
    }

    // ===================== CP-F2 领取 =====================

    /**
     * 领取：先做能给出人话原因的判定（停发/领完/未到时间/超窗/限领），再走 CAS 抢量，最后落实例。
     * 抢量与落库同事务 —— 抢到量却没落成券就是"凭空少一张库存"，宁可回滚重来。
     */
    fun receive(userId: Long, companyId: Long, couponId: Long): Map<String, Any?> {
        if (couponId <= 0) throw BizException(ErrorCode.VALIDATION, "coupon_id 非法", mapOf("coupon_id" to listOf("不能为空")))
        val c = template(couponId, companyId)
        val now = TimeSec.now()
        receiveBlock(c, mine.countOwned(companyId, userId, couponId), now)?.let {
            throw BizException(ErrorCode.BIZ, it, mapOf("coupon_id" to listOf(it)))
        }
        val code = seq.nextBn(SeqName.COUPON_CODE)
        return db.transactional {
            if (coupons.takeQuota(couponId, companyId, now, now) == 0) {
                throw BizException(ErrorCode.BIZ, "这张券已经领完了", mapOf("coupon_id" to listOf("库存不足")))
            }
            insertInstance(c, userId, code, now, SOURCE_RECEIVE)
        }
    }

    /** 不可领的原因（null=可以领）。CP-F1 列表与 CP-F2 领取共用这一份判据，文案不会两边漂 */
    private fun receiveBlock(c: CouponDO, owned: Long, now: Long): String? = when {
        !c.status -> "该券已停止发放"
        c.totalNum > 0 && c.sendNum >= c.totalNum -> "该券已领完"
        c.validType == "range" && c.beginTime > now -> "还没到可用时间"
        c.validType == "range" && c.endTime in 1 until now -> "该券已过使用期限"
        c.getLimit > 0 && owned >= c.getLimit -> "每人限领 ${c.getLimit} 张，您已领过"
        else -> null
    }

    private fun insertInstance(c: CouponDO, userId: Long, code: String, now: Long, source: String): Map<String, Any?> {
        val row = CouponUserDO().apply {
            couponId = c.couponId
            this.userId = userId
            companyId = c.companyId
            couponCode = code
            status = CouponStatus.UNUSED
            receiveTime = now
            // days 从领取时刻起算；range 直接用模板结束时间（0=不限时，算价侧按 expire_time=0 放行）
            expireTime = if (c.validType == "days" && c.validDays > 0) now + c.validDays * 86400L else c.endTime
            couponName = c.couponName
            couponType = c.couponType
            discountFee = c.discountFee
            minOrderFee = c.minOrderFee
            this.source = source
        }
        mine.insert(row)
        return linkedMapOf(
            "coupon_user_id" to row.id,
            "coupon_id" to row.couponId,
            "coupon_code" to row.couponCode,
            "coupon_name" to row.couponName,
            "discount_fee" to row.discountFee,
            "min_order_fee" to row.minOrderFee,
            "status" to row.status,
            "expire_time" to row.expireTime,
        )
    }

    // ===================== CP-F3 我的券 =====================

    fun myList(userId: Long, companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val status = raw["status"]?.takeIf { it.isNotBlank() }?.uppercase()?.also {
            if (it !in SHOW_STATUS) {
                throw BizException(ErrorCode.VALIDATION, "status 非法", mapOf("status" to listOf("只支持 UNUSED/LOCKED/USED/EXPIRED")))
            }
        }
        val now = TimeSec.now()
        return PageHelper.paginate(
            pq,
            count = { mine.countByUser(companyId, userId, status, now) },
            query = { offset, limit -> mine.pageByUser(companyId, userId, status, now, offset, limit) },
            map = { rows ->
                rows.map {
                    LinkedHashMap(userRow(it)).apply { put("order_id", it.orderId.takeIf { o -> o > 0 }?.toString()) }
                }
            },
        )
    }

    // ===================== 给订单链/结算链用的生命周期方法 =====================

    /** 按券码定位实例（不校验归属，调用方必须再比 user_id —— 越权与不存在同语义在 [requireMine] 里做） */
    fun byCode(code: String, companyId: Long): CouponUserDO? = mine.findByCode(code, companyId)

    fun byCodeAndUser(code: String, companyId: Long, userId: Long): CouponUserDO? =
        mine.findByCode(code, companyId)?.takeIf { it.userId == userId }

    /**
     * 下单锁券（CAS，D23）。0 行 = 这张券已被别的待付款单占住或已过期，
     * 调用方必须放弃整笔下单 —— 券在算价里已经减过钱，锁不上还继续下单就是"白拿折扣"。
     */
    fun lock(code: String, companyId: Long, userId: Long, orderId: Long, now: Long): Boolean =
        mine.lock(code, companyId, userId, orderId, now) > 0

    /** 结清核销（PayService 调用）：只从 LOCKED 出发，重复回调第二次 0 行 */
    fun consume(orderId: Long, now: Long): Int = mine.consume(orderId, now)

    /** 取消/超时关单回退：LOCKED → UNUSED（券重新可用，快照 order_id 归零） */
    fun release(orderId: Long): Int = mine.release(orderId)

    /** 本单锁住的券（落订单优惠快照与详情回读用） */
    fun unlocked(orderId: Long): List<CouponUserDO> = mine.listUnlocked(orderId)

    /** 过期回收（CouponExpireJob）：一条 UPDATE 全扫，0 行是常态 */
    fun expireBatch(now: Long): Int = mine.expireBatch(now)

    fun expirableCount(now: Long): Long = mine.countExpirable(now)

    /** CP-F4 / 算价预取：本人未过期的可用券（已按面额倒序，抵扣最大的排前面） */
    fun available(companyId: Long, userId: Long, now: Long): List<CouponUserDO> = mine.listAvailable(companyId, userId, now)

    /** 券能不能用在这一组上（门槛只比"组内商品应付"，运费不参与 —— §14.5） */
    fun usable(c: CouponUserDO, groupPayFee: Long, now: Long): String? = when {
        c.status != CouponStatus.UNUSED -> "该券已被其他订单占用"
        c.expireTime in 1..now -> "该券已过期"
        groupPayFee <= 0 -> "没有可抵扣的商品金额"
        c.minOrderFee > 0 && groupPayFee < c.minOrderFee -> "满 ${yuan(c.minOrderFee)} 元可用，当前 ${yuan(groupPayFee)} 元"
        else -> null
    }

    /** 抵扣额：`min(面额, 组应付)`，永不超过应付（§14.5 的钳制在这一处也做一次） */
    fun deductOf(c: CouponUserDO, groupPayFee: Long): Long = c.discountFee.coerceAtMost(groupPayFee.coerceAtLeast(0L))

    fun requireMine(code: String, companyId: Long, userId: Long): CouponUserDO =
        byCodeAndUser(code, companyId, userId)
            ?: throw BizException(ErrorCode.BIZ, "优惠券无效或不属于当前会员", mapOf("coupon_code" to listOf(" unusable")))

    // ===================== 出参装配 =====================

    private fun row(c: CouponDO, st: CouponCountRow?): Map<String, Any?> = linkedMapOf(
        "coupon_id" to c.couponId,
        "coupon_type" to c.couponType,
        "coupon_name" to c.couponName,
        "discount_fee" to c.discountFee,
        "min_order_fee" to c.minOrderFee,
        "total_num" to c.totalNum,
        "sent_num" to c.sendNum,
        "get_limit" to c.getLimit,
        "valid_type" to c.validType,
        "status" to c.status,
        "valid_text" to validText(c),
        "received" to (st?.received ?: 0L),
        "locked" to (st?.locked ?: 0L),
        "used" to (st?.used ?: 0L),
        "expired" to (st?.expired ?: 0L),
        "created" to c.created,
        "updated" to c.updated,
    )

    /** 券实例行：面额/门槛读**快照列**，不回头查模板（模板改了不影响已发的券） */
    fun userRow(u: CouponUserDO): Map<String, Any?> = linkedMapOf(
        "coupon_user_id" to u.id,
        "coupon_id" to u.couponId,
        "coupon_code" to u.couponCode,
        "coupon_name" to u.couponName,
        "coupon_type" to u.couponType,
        "discount_fee" to u.discountFee,
        "min_order_fee" to u.minOrderFee,
        "threshold_fee" to u.minOrderFee,
        "status" to u.status,
        "status_msg" to statusMsg(u.status),
        "source" to u.source,
        "receive_time" to u.receiveTime,
        "use_time" to u.useTime,
        "lock_time" to u.lockTime,
        "expire_time" to u.expireTime,
    )

    private fun statusMsg(status: String): String = when (status) {
        CouponStatus.UNUSED -> "未使用"
        CouponStatus.LOCKED -> "使用中"
        CouponStatus.USED -> "已使用"
        CouponStatus.EXPIRED -> "已过期"
        else -> status
    }

    private fun validText(c: CouponDO): String =
        if (c.validType == "days") "领取后 ${c.validDays} 天内有效"
        else "${date(c.beginTime)} 至 ${if (c.endTime > 0) date(c.endTime) else "长期"}"

    private fun date(ts: Long): String =
        if (ts <= 0) "-" else java.time.Instant.ofEpochSecond(ts).atZone(TimeSec.TZ).toString().take(10)

    private fun statsOf(companyId: Long, ids: List<Long>): Map<Long, CouponCountRow> =
        if (ids.isEmpty()) emptyMap() else mine.listStats(companyId, ids).associateBy { it.couponId }

    /** 昵称（GP-F3 / CP-A7 共用）：`members_info.name` → `username` → 手机号 → "会员{id}" */
    fun nicknames(ids: List<Long>): Map<Long, String> {
        val uniq = ids.filter { it > 0 }.distinct()
        if (uniq.isEmpty()) return emptyMap()
        val info = infos.listByUserIds(uniq).associateBy { it.userId }
        val rows = members.listByIds(uniq).associateBy { it.userId }
        return uniq.associateWith { uid ->
            info[uid]?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.username }
                ?: rows[uid]?.mobile?.takeIf { it.isNotBlank() }
                ?: "会员$uid"
        }
    }

    /** 头像：members_info 有则给，没有回空串保住字段（GP-F3 出参要求 avatar 恒在） */
    fun avatars(ids: List<Long>): Map<Long, String> {
        val uniq = ids.filter { it > 0 }.distinct()
        if (uniq.isEmpty()) return emptyMap()
        return infos.listByUserIds(uniq).associate { it.userId to (it.avatar ?: "") }
    }

    private fun template(couponId: Long, companyId: Long): CouponDO =
        coupons.findById(couponId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "优惠券不存在")

    private fun pageQuery(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    companion object {
        const val SOURCE_RECEIVE = "receive"
        const val SOURCE_SEND = "send"

        /** 后台发券的硬上限：一个等级最多扫这么多人，防运营手滑把整库会员扫一遍 */
        const val SEND_BATCH_LIMIT = 2000
        const val SEND_MAX_PER_USER = 10
        private val SHOW_STATUS = setOf(CouponStatus.UNUSED, CouponStatus.LOCKED, CouponStatus.USED, CouponStatus.EXPIRED)
    }
}

/** 分→元文案（门槛、阶梯提示都用这一处，出参金额仍是分） */
internal fun yuan(fen: Long): String = BigDecimal(fen).movePointLeft(2).stripTrailingZeros().toPlainString()

/** 元→分：存量 JSON 里的金额单位是元字符串，换算只发生在这里（§14.4） */
internal fun yuanToFen(raw: String?): Long = raw?.takeIf { it.isNotBlank() }
    ?.let { runCatching { BigDecimal(it.trim()).movePointRight(2).toLong() }.getOrDefault(0L) }
    ?: 0L
