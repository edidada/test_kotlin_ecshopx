package com.ecshopx.kotlin.service.asset

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.goods.SysConfigDO
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.pay.PointAccountRow
import com.ecshopx.kotlin.dao.pay.PointLogDO
import com.ecshopx.kotlin.dao.pay.PointLogRow
import com.ecshopx.kotlin.dao.pay.PointMapper
import com.ecshopx.kotlin.dao.pay.PointStatsDO
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.pay.PointRate
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Service

/**
 * M1-5 积分域（docs/04 §23.1 `PT-F1..F3`、§23.2 `PT-A1..A4`）。
 *
 * 这一族在 M0-7/M0-8 就有**写**的一半（`PointChannel` 扣分、`PayService.accruePoints` 赠分入账、
 * 退款回补），本批补的是**读**的一半 + 后台调账。三条纪律：
 *  1. **账实相符靠日志表可回放**：每一次动账都写 `point_member_log`（`before_point`/`after_point`
 *     都在行上），所以 `Σ change_point == point` 是能被脚本验证的，不是被口头承诺的。
 *     本类的 [adjust] 是唯一一个"没有订单来源"的动账口，因此 `remark` 必填；
 *  2. **钳位与留痕必须一致**：PT-A2 允许负数调账但**不许调成负余额**，
 *     于是流水记的是 `after - before`（实际动账），不是用户请求的 `change_point`（想要的动账）。
 *     记后者会让 `Σ change_point` 与 `point` 差一个"钳掉的那部分"，对账脚本第一个红；
 *  3. **`total_point` 只加不减**（PT-R2）：它是"累计获得"，动它等于动会员等级与榜单，
 *     所以扣分、回补、调减都不碰它，只有调增与赠分入账碰。
 */
@Service
class PointService(
    private val points: PointMapper,
    private val configs: SysConfigMapper,
    private val db: Db,
) {

    /** PT-F1 我的积分总览 */
    fun overview(
        userId: Long,
        companyId: Long,
    ): Map<String, Any?> {
        val acc = points.find(companyId, userId)
        val point = acc?.point ?: 0L
        return linkedMapOf(
            "point" to point,
            "total_point" to (acc?.totalPoint ?: 0L),
            // `user_point` 这一键在源系统语义不清（同一页面上它与 point 长得太像）。
            // 本项目定为"累计扣减"，与 PT-A1 卡头的 total_used 同一条负向口径 —— 见 §24 的实现期记录。
            "user_point" to points.userUsedSum(companyId, userId),
            "ratio" to ratio(companyId),
            // 总览面没有订单上下文，只能给"全额折算"：单笔订单实际可抵还要按 ratio 与应付收窄，
            // 那条判据在 ORDER-1 预览的 limit_point/max_point 里（C117），两处不各算一套
            "max_deduct_fee" to point * PointRate.POINT_PER_FEN,
            "enable" to configs.flag(companyId, KEY_POINT_ENABLE, true),
        )
    }

    /** PT-F2 我的积分流水 */
    fun logs(
        userId: Long,
        companyId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> = paged(
        raw,
        mapOf(
            "companyId" to companyId,
            // 会员面强制认人：这一行写死，入参里没有 user_id 可传（传了也不认）
            "userId" to userId,
            "remark" to blankToNull(raw["remark"]),
        ),
    )

    /** PT-A3 全量流水（后台对账面：`user_id`/`order_id`/时间段/正负向） */
    fun adminLogs(
        companyId: Long,
        raw: Map<String, String>,
        full: Boolean,
    ): PageResult<Map<String, Any?>> = paged(
        raw,
        mapOf(
            "companyId" to companyId,
            "userId" to raw["user_id"]?.toLongOrNull(),
            "orderId" to blankToNull(raw["order_id"]),
            "remark" to blankToNull(raw["remark"]),
            "positive" to (raw["positive"] == "1"),
            "negative" to (raw["negative"] == "1"),
            "createdStart" to raw["time_start_begin"]?.toLongOrNull(),
            "createdEnd" to raw["time_start_end"]?.toLongOrNull(),
        ),
        admin = true,
        full = full,
    )

    /**
     * PT-A1 会员积分列表 + 卡头。
     *
     * 返回的不是 [PageResult] 而是拍平的 Map：spec 要求同一份响应里既有 `list` 又有四个合计数。
     * 卡头与列表**共用同一个筛选字典**（含店铺运营的 `distributorIds` 收窄），
     * 所以受限账号看到的合计就是他列表那个集合的合计，不会出现"总数比明细大"。
     */
    fun adminMembers(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
        full: Boolean,
    ): Map<String, Any?> {
        val pq = pageOf(raw)
        val f = filter(companyId, raw, scope)
        val page = PageHelper.paginate(
            pq,
            count = { points.accountCount(f) },
            query = { offset, limit -> points.accountPage(f, offset, limit) },
            map = { rows -> rows.map { memberView(it, full) } },
        )
        val stats: PointStatsDO = points.accountStats(f) ?: PointStatsDO()
        return linkedMapOf(
            "total_count" to page.total_count,
            "list" to page.list,
            "total_point_sum" to stats.pointSum,
            "total_granted" to stats.grantedSum,
            "total_used" to points.usedSum(f),
            "member_count" to stats.memberCount,
        )
    }

    /**
     * PT-A2 后台调账。
     *
     * 四步走完一个事务：读账户（没有就建空账户）→ 算钳位后的目标值 → CAS 写 → 记流水。
     * `casAdjust` 的 `WHERE point = #{expect}` 回 0 行说明期间有人动过账（会员正好下了一单），
     * 这里选择**报错让调用方重试**而不是自动重算：调账是人工动作，
     * "系统替你把金额重新算了一遍才写进账本"恰恰是审计最不想看到的事。
     */
    fun adjust(
        companyId: Long,
        form: PointAdjustForm,
    ): Map<String, Any?> {
        val userId = form.userId ?: throw BizException(ErrorCode.VALIDATION, "user_id 必填", mapOf("user_id" to listOf("不能为空")))
        val delta = form.changePoint ?: throw BizException(ErrorCode.VALIDATION, "change_point 必填", mapOf("change_point" to listOf("不能为空")))
        if (delta == 0L) throw BizException(ErrorCode.VALIDATION, "change_point 不能为 0", mapOf("change_point" to listOf("不能为 0")))
        val remark = requireText("remark", form.remark)
        return db.transactional {
            val acc = points.find(companyId, userId) ?: run {
                points.insertEmpty(companyId, userId, TimeSec.now())
                points.find(companyId, userId) ?: throw BizException(ErrorCode.NOT_FOUND, "会员不存在")
            }
            val before = acc.point
            val after = maxOf(0L, before + delta)
            if (after == before) throw BizException(ErrorCode.BIZ, "积分不足，无法扣减到 0 以下")
            val real = after - before
            if (points.casAdjust(acc.id, after, maxOf(real, 0L), before, TimeSec.now()) == 0) {
                throw BizException(ErrorCode.BIZ, "积分已变动，请重试")
            }
            points.insertLog(
                PointLogDO().apply {
                    this.companyId = companyId
                    this.userId = userId
                    // 调账没有订单来源，`order_id` 留 NULL —— 对账脚本正是靠"order_id 为空 + remark"
                    // 认出这一笔是人工调整，从而把 Σ 差额的来源讲清楚
                    changePoint = real
                    beforePoint = before
                    afterPoint = after
                    this.remark = remark
                    created = TimeSec.now()
                },
            )
            linkedMapOf("user_id" to userId, "before_point" to before, "after_point" to after, "change_point" to real)
        }
    }

    /** PT-F3 积分规则展示（库里不存文案，按当前配置拼） */
    fun ruleView(companyId: Long): Map<String, Any?> {
        val enable = configs.flag(companyId, KEY_POINT_ENABLE, true)
        val ratio = ratio(companyId)
        return linkedMapOf(
            "enable" to enable,
            "deduct_max_ratio" to ratio,
            "point_per_fen" to PointRate.POINT_PER_FEN,
            // 赠送没有全局倍率：这一行得几分是商品自己标的 items.point，建单时按 item_point 落库
            "get_by_item" to true,
            "text" to if (!enable) {
                "当前未开启积分抵扣。"
            } else {
                "1 积分可抵扣 ${PointRate.POINT_PER_FEN} 分；单笔订单最多抵扣应付金额的 $ratio%。" +
                    "下单赠送的积分按商品标注的积分数计算，支付成功后到账。"
            },
        )
    }

    /** PT-A4 规则读：当前值 + 默认值两张对照 */
    fun configView(companyId: Long): Map<String, Any?> {
        val current = RULE_KEYS.associateWith { configs.read(companyId, it) }
        val defaults = RULE_KEYS.associateWith { DEFAULTS[it] }
        return linkedMapOf(
            "config" to current,
            "defaults" to defaults,
            "effective" to linkedMapOf(
                "pay.point.enable" to configs.flag(companyId, KEY_POINT_ENABLE, true),
                "point.deduct.max_ratio" to ratio(companyId),
                // 括号是必需的：elvis 比中缀 `to` 松，不加就变成 `("键" to 值) ?: 兜底`
                "deposit.recharge.point.rate" to ((configs.read(companyId, KEY_RECHARGE_POINT_RATE)?.toDoubleOrNull()) ?: 0.0),
            ),
        )
    }

    /**
     * PT-A4 规则写（整组写回租户层）。
     *
     * 只落 `company_id = companyId` 的租户行，不改全局行 —— 与 SUP-1 同一条纪律
     * （一个租户的运营动作不该打到所有租户）。
     * 校验只认 `point.deduct.max_ratio` 的区间：它是唯一有硬上下界的键，
     * 越界会让算价链出现"抵扣上限超过订单金额"这种在预览里看不见、结账时才炸的形状。
     */
    fun configWrite(
        companyId: Long,
        form: PointRuleForm,
    ): Map<String, Any?> {
        val errors = linkedMapOf<String, List<String>>()
        val ratio = form.deductMaxRatio?.trim()?.toIntOrNull()
        if (form.deductMaxRatio != null && (ratio == null || ratio !in 0..100)) {
            errors["point.deduct.max_ratio"] = listOf("必须是 0..100 的整数")
        }
        val rechargeRate = form.rechargePointRate?.trim()
        if (rechargeRate != null && rechargeRate.toDoubleOrNull() == null) {
            errors["deposit.recharge.point.rate"] = listOf("必须是数字")
        }
        if (errors.isNotEmpty()) throw BizException(ErrorCode.BIZ, "积分规则参数非法", errors)
        form.pointEnable?.let { write(companyId, KEY_POINT_ENABLE, normalizeFlag(it)) }
        ratio?.let { write(companyId, KEY_POINT_RATIO, it.toString()) }
        rechargeRate?.let { write(companyId, KEY_RECHARGE_POINT_RATE, it) }
        return configView(companyId)
    }

    private fun filter(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): Map<String, Any?> = mapOf(
        "companyId" to companyId,
        "userId" to raw["user_id"]?.toLongOrNull(),
        "keyword" to blankToNull(raw["keyword"]),
        "onlyHasPoint" to (raw["only_has_point"] == "1"),
        // 积分账户表没有店铺归属列（会员是平台资产），受限账号按"在本店下过单"收窄
        "distributorIds" to scope.narrow(null),
    )

    private fun paged(
        raw: Map<String, String>,
        f: Map<String, Any?>,
    ): PageResult<Map<String, Any?>> = paged(raw, f, admin = false, full = false)

    private fun paged(
        raw: Map<String, String>,
        f: Map<String, Any?>,
        admin: Boolean,
        full: Boolean,
    ): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pageOf(raw),
        count = { points.logCount(f) },
        query = { offset, limit -> points.logPage(f, offset, limit) },
        map = { rows -> rows.map { logView(it, admin, full) } },
    )

    /**
     * 流水出参。
     *
     * `admin=false`（会员面 PT-F2）时 `nickname`/`mobile` **整个键都不出** ——
     * 这是自己的流水，出这两键只是把响应变大；
     * `admin=true` 时按 datapass 决定手机号给全值还是掩码（MASK-R1），
     * 无论哪种都额外固定出一键 `mobile_masked`，让"运营要不要申请全值权限"这件事在前端可预判。
     */
    private fun logView(
        r: PointLogRow,
        admin: Boolean,
        full: Boolean,
    ): MutableMap<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "id" to r.id,
            "user_id" to r.userId,
            "order_id" to r.orderId,
            "change_point" to r.changePoint,
            "before_point" to r.beforePoint,
            "after_point" to r.afterPoint,
            "remark" to r.remark,
            "created" to r.created,
        )
        if (admin) {
            m["nickname"] = r.nickname
            m["mobile"] = if (full) r.mobile else Mask.mobile(r.mobile)
            m["mobile_masked"] = Mask.mobile(r.mobile)
        }
        return m
    }

    private fun memberView(
        r: PointAccountRow,
        full: Boolean,
    ) = linkedMapOf<String, Any?>(
        "user_id" to r.userId,
        "nickname" to r.nickname,
        // MASK-R1：掩码只作用在出参，keyword 搜手机号打的仍是原列
        "mobile" to (if (full) r.mobile else Mask.mobile(r.mobile)),
        "mobile_masked" to Mask.mobile(r.mobile),
        "point" to r.point,
        "total_point" to r.totalPoint,
        "updated" to r.updated,
    )

    private fun ratio(companyId: Long): Int =
        configs.read(companyId, KEY_POINT_RATIO)?.toIntOrNull()?.coerceIn(0, 100)
            ?: (DEFAULTS[KEY_POINT_RATIO] ?: "0").toInt()

    private fun write(
        companyId: Long,
        key: String,
        value: String,
    ) {
        configs.upsert(
            SysConfigDO().apply {
                this.companyId = companyId
                configKey = key
                configValue = value
                updated = TimeSec.now()
            },
        )
    }

    private fun normalizeFlag(v: String): String = if (v.trim().lowercase() in setOf("1", "true")) "1" else "0"

    companion object {
        const val KEY_POINT_ENABLE = "pay.point.enable"
        const val KEY_POINT_RATIO = "point.deduct.max_ratio"

        /** 充值送积分倍数（CZ-A3/PT-A4 共用；§23.4 新增键，默认 '0' = 不送） */
        const val KEY_RECHARGE_POINT_RATE = "deposit.recharge.point.rate"
        private val RULE_KEYS = listOf(KEY_POINT_ENABLE, KEY_POINT_RATIO, KEY_RECHARGE_POINT_RATE)
        private val DEFAULTS = mapOf(KEY_POINT_ENABLE to "1", KEY_POINT_RATIO to "50", KEY_RECHARGE_POINT_RATE to "0")
    }
}
