package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.UserCheckinDO
import com.ecshopx.kotlin.dao.promotion.UserCheckinMapper
import com.ecshopx.kotlin.service.asset.PointAdjustForm
import com.ecshopx.kotlin.service.asset.PointService
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * M2-3 互动域 · 签到（docs/04 §29.1 `IC-S1..S6`）。
 *
 * 四条纪律：
 *  1. **幂等担保长在 uk(group,user,day) 上**：同一天第二次签是唯一键失败，不是先查后插——
 *     并发双击时先查后插的"假幂等"会放两行、送两份分；
 *  2. **送分走 [PointService.adjust]（唯一"无订单来源"动账口）**，`remark` 恒为 `签到`，
 *     与 M1-5 的积分对账恒等式保持同一来源口径（remark 区分来源那次划分的延伸）；
 *  3. **连签数是每行落库的**（`continuous`）：昨天断了就从 1 重来，判据只有"昨天那一行存不存在"，
 *     不做 SUM 倒推 —— 倒推会把"签到又取消"（源系统语义中没有，但后台调账可能制造）这种洞算成长签；
 *  4. **加成的分界是 7 的倍数**：第 7、14、21…天送 `checkin.bonus`，配置缺省 0 分时恰好退化为纯 base。
 */
@Service
class CheckInService(
    private val checkins: UserCheckinMapper,
    private val points: PointService,
    private val configs: com.ecshopx.kotlin.dao.goods.SysConfigMapper,
    private val db: Db,
) {

    companion object {
        const val KEY_CHECKIN_POINT = "checkin.point"
        const val KEY_CHECKIN_BONUS = "checkin.bonus"
        const val BONUS_PERIOD = 7

        /** 供 e2e 与对账脚本认账的 remark 词表 */
        const val REMARK_SIGN = "签到"
    }

    /** yyyymmdd 整数（Asia/Shanghai 业务日界） */
    private fun today(): LocalDate = LocalDate.now(TimeSec.TZ)

    private fun ymd(d: LocalDate): Long = (d.year * 10_000L) + (d.monthValue * 100L) + d.dayOfMonth

    /** 每日基础送分（分值即积分个数；缺省 10） */
    private fun basePoint(companyId: Long): Long =
        (configs.getValue(companyId, KEY_CHECKIN_POINT)?.toLongOrNull() ?: 10L).coerceAtLeast(0L)

    /** 连签加成（第 7/14/…天，缺省 20） */
    private fun bonusPoint(companyId: Long): Long =
        (configs.getValue(companyId, KEY_CHECKIN_BONUS)?.toLongOrNull() ?: 20L).coerceAtLeast(0L)

    // ===================== IC-S1 签到 =====================

    /**
     * 今日签到：幂等 + 送分同事务。返回出参同 IC-S3 周列表的单日子项 + 汇总，
     * 让 H5 端"签完刷周面板"一步到位（与源"signIn 再 list"两调用位等价但少一跳）。
     */
    fun sign(companyId: Long, userId: Long): Map<String, Any?> {
        val t = today()
        val day = ymd(t)
        val yesterday = ymd(t.minusDays(1))
        val existing = checkins.find(companyId, userId, day)
        if (existing != null) throw BizException(ErrorCode.BIZ, "今日已签到")
        return db.transactional {
            // 先读昨天的连续基数，再插（uk 兜住竞态：双击并发时后来者以重复键失败）
            val prev = checkins.find(companyId, userId, yesterday)
            val continuous = (prev?.continuous ?: 0) + 1
            val base = basePoint(companyId)
            val bonus = if (continuous % BONUS_PERIOD == 0) bonusPoint(companyId) else 0L
            val gain = base + bonus
            checkins.insert(
                UserCheckinDO().apply {
                    this.companyId = companyId
                    this.userId = userId
                    this.day = day
                    point = gain
                    this.continuous = continuous
                },
            )
            val afterPoint = if (gain > 0L) {
                val adj = points.adjust(
                    companyId,
                    PointAdjustForm(userId = userId, changePoint = gain, remark = REMARK_SIGN),
                )
                adj["after_point"] as Long
            } else {
                (points.overview(userId, companyId)["point"] as? Long) ?: 0L
            }
            signed(companyId, userId, day, continuous, gain, afterPoint)
        }
    }

    // ===================== IC-S2/S3 查询面板 =====================

    /** 周面板：近 7 天（含今日）逐日子项 + 汇总。`week_start` 供"从周一开始数"的对端只作展示，不参与判界 */
    fun weeklyList(companyId: Long, userId: Long): Map<String, Any?> {
        val t = today()
        val start = ymd(t.minusDays(6))
        val end = ymd(t)
        val rows = checkins.rowsBetween(companyId, userId, start, end).associateBy { it.day }
        val days = (0L until 7L).map { i ->
            val d = t.minusDays(6 - i)
            val day = ymd(d)
            val r = rows[day]
            linkedMapOf(
                "day" to day,
                "week" to (d.dayOfWeek.value),          // ISO：1=周一 … 7=周日
                "signed" to (r != null),
                "point" to (r?.point ?: 0L),
                "continuous" to (r?.continuous ?: 0),
            )
        }
        val cur = rows[ymd(t)]
        return linkedMapOf(
            "days" to days,
            "signed_days" to checkins.countBetween(companyId, userId, start, end),
            "week_point" to checkins.pointSumBetween(companyId, userId, start, end),
            "continuous" to (cur?.continuous ?: 0),
            "signed_total" to checkins.countByUser(companyId, userId),
            "signed_point_total" to checkins.pointSumByUser(companyId, userId),
            "today_signed" to (cur != null),
        )
    }

    /** IC-S5 前台别名 `/promotion/checkin/getlist` 与 `/sign/weekly/list` 同一契约 */
    fun weeklyListAlias(companyId: Long, userId: Long): Map<String, Any?> = weeklyList(companyId, userId)

    /** IC-S6 后台翻页：user_id/起止日界（0 = 不设界） */
    fun adminList(
        companyId: Long,
        userId: Long?,
        dayStart: Long?,
        dayEnd: Long?,
        pq: PageQuery,
    ): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { checkins.countAdmin(companyId, userId ?: 0, dayStart ?: 0, dayEnd ?: 0) },
        query = { off, lim -> checkins.pageAdmin(companyId, userId ?: 0, dayStart ?: 0, dayEnd ?: 0, off, lim) },
        map = { rows -> rows.map { row(it) } },
    )

    // ===================== 出参件 =====================

    private fun signed(
        companyId: Long,
        userId: Long,
        day: Long,
        continuous: Int,
        gain: Long,
        afterPoint: Long,
    ): Map<String, Any?> = linkedMapOf(
        "user_id" to userId,
        "day" to day,
        "point" to gain,
        "continuous" to continuous,
        "signed_total" to checkins.countByUser(companyId, userId),
        "signed_point_total" to checkins.pointSumByUser(companyId, userId),
        "after_point" to afterPoint,
    )

    private fun row(r: UserCheckinDO): Map<String, Any?> = linkedMapOf(
        "user_id" to r.userId,
        "day" to r.day,
        "point" to r.point,
        "continuous" to r.continuous,
        "created" to r.created,
    )
}
