package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.SysConfigDO
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.UserLotteryLogDO
import com.ecshopx.kotlin.dao.promotion.UserLotteryLogMapper
import com.ecshopx.kotlin.dao.promotion.UserLotteryTimesDO
import com.ecshopx.kotlin.dao.promotion.UserLotteryTimesMapper
import com.ecshopx.kotlin.service.asset.PointAdjustForm
import com.ecshopx.kotlin.service.asset.PointService
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.Random

/**
 * M2-3 互动域 · 大转盘（docs/04 §29.1 `IC-T1..T8`）。
 *
 * 与源系统的结构差异（§29.1 差异表 D-IC1）：源系统是"多活动表 + 每活动一页配置"，
 * 本项目收敛为**每租户一个转盘配置**（`activity_id` 恒 1，配置存 `sys_config.turntable.config` JSON）。
 * 收敛的理由：源系统那套多活动表的全部能力（多期转盘、导出、第 N 期归档）在 M2-3 的承诺清单里
 * 一条都没有，建表就是在建承诺清单之外的表；`activity_id` 列留在流水上，将来真要拆多活动时流水不用迁。
 *
 * 四条纪律：
 *  1. **次数是派生值**：`residue = base + add − 当日流水数`，三个数各有所长——base 是"人人有份"的配置、
 *     add 是 loginaddtimes 每日发一次的领取态、流水数是已花掉的。总剩余不落列（DAO 注记 3）；
 *  2. **每日 loginaddtimes 只发一次**：`claim` 靠 uk(g,u,day) 冲突即放弃，不发第二回；
 *  3. **中奖积分走 [PointService.adjust]**（remark=`抽奖`），流水行落在同一事务里，
 *     "抽奖流水说中了积分但 point_member_log 没动"不可能发生；
 *  4. **抽取是确定性随机**：Random 种子由 (company,user,day,当日第几抽) 推导，同一状态重放同一结果——
 *     e2e 与对账重放都钉得住；真随机反而让"抽中积分奖"这条用例没法稳定钉死。
 */
@Service
class TurntableService(
    private val logs: UserLotteryLogMapper,
    private val times: UserLotteryTimesMapper,
    private val configs: SysConfigMapper,
    private val points: PointService,
    private val db: Db,
) {

    companion object {
        const val KEY_CONFIG = "turntable.config"
        const val ACTIVITY_ID = 1L
        const val TYPE_POINT = "point"
        const val TYPE_NONE = "none"
        const val REMARK_DRAW = "抽奖"
    }

    // ===================== 配置件 =====================

    /** 读配置（缺省即关闭态）；解析失败的行是脏行，关闭兜底而不是 500 */
    private fun rawConfig(companyId: Long): Map<String, Any?> {
        val text = configs.getValue(companyId, KEY_CONFIG) ?: return LINKED_EMPTY
        val parsed = runCatching { MAPPER.readTree(text) }.getOrNull() ?: return LINKED_EMPTY
        val prizes = parsed.get("prizes")?.map { sub(it) } ?: emptyList()
        return linkedMapOf(
            "enable" to (parsed.get("enable")?.asBoolean(false) ?: false),
            "name" to (parsed.get("name")?.asText() ?: "幸运大转盘"),
            "base_times" to (parsed.get("base_times")?.asInt(1) ?: 1).coerceAtLeast(0),
            "login_add_times" to (parsed.get("login_add_times")?.asInt(1) ?: 1).coerceAtLeast(0),
            "prizes" to prizes,
        )
    }

    /** 配置满足"开着就得有可抽的奖品池"，写口与管理读口共用这一条校验 */
    private fun validate(cfg: Map<String, Any?>) {
        val prizes = (cfg["prizes"] as List<Map<String, Any?>>)
        if (cfg["enable"] == true && prizes.none { (it["weight"] as Int) > 0 }) {
            throw BizException(ErrorCode.VALIDATION, "启用中的转盘必须有 weight > 0 的奖品", mapOf("prizes" to listOf("奖品池为空")))
        }
    }

    // ===================== IC-T1/T2 写配置（后台） =====================

    fun adminSaveConfig(companyId: Long, body: Map<String, Any?>): Map<String, Any?> {
        val prizesRaw = body["prizes"] as? List<*> ?: throw BizException(
            ErrorCode.VALIDATION,
            "prizes 必填", mapOf("prizes" to listOf("不能为空")),
        )
        val prizes = prizesRaw.map { r ->
            val m = r as? Map<*, *> ?: throw BizException(ErrorCode.VALIDATION, "prizes 项必须是对象", mapOf("prizes" to listOf("对象")))
            val type = (m["type"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: TYPE_NONE
            if (type !in setOf(TYPE_POINT, TYPE_NONE)) {
                throw BizException(ErrorCode.VALIDATION, "prize.type 只认 point/none", mapOf("type" to listOf("词表外")))
            }
            linkedMapOf(
                "id" to ((m["id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
                    ?: "p" + kotlin.math.abs((m["name"] ?: "").hashCode())),
                "name" to ((m["name"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "奖品"),
                "type" to type,
                "point" to (((m["point"] as? Number)?.toLong() ?: m["point"]?.toString()?.toLongOrNull() ?: 0L).coerceAtLeast(0L)),
                "weight" to (((m["weight"] as? Number)?.toInt() ?: m["weight"]?.toString()?.toIntOrNull() ?: 0).coerceAtLeast(0)),
            )
        }
        val cfg = linkedMapOf(
            "enable" to (body["enable"] as? Boolean ?: body["enable"]?.toString()?.let { it == "1" || it == "true" } ?: false),
            "name" to ((body["name"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: "幸运大转盘"),
            "base_times" to (((body["base_times"] as? Number)?.toInt() ?: body["base_times"]?.toString()?.toIntOrNull() ?: 1).coerceAtLeast(0)),
            "login_add_times" to (((body["login_add_times"] as? Number)?.toInt() ?: body["login_add_times"]?.toString()?.toIntOrNull() ?: 1).coerceAtLeast(0)),
            "prizes" to prizes,
        )
        validate(cfg)
        configs.upsert(
            SysConfigDO().apply {
                this.companyId = companyId
                configKey = KEY_CONFIG
                configValue = MAPPER.writeValueAsString(cfg)
            },
        )
        return activityView(companyId)
    }

    /** 下架 = 关开关，配置其余字段保持原值（`activity_id` 只认 1，其余是 §29.1 差异表 D-IC1 之外的请求） */
    fun adminDown(companyId: Long, activityId: Long): Map<String, Any?> {
        if (activityId != ACTIVITY_ID) throw BizException(ErrorCode.NOT_FOUND, "大转盘活动不存在")
        val cur = rawConfig(companyId)
        val cfg = if (cur.isEmpty()) {
            linkedMapOf("enable" to false, "name" to "幸运大转盘", "base_times" to 0, "login_add_times" to 0, "prizes" to emptyList<Any?>())
        } else {
            linkedMapOf(
                "enable" to false,
                "name" to cur["name"],
                "base_times" to cur["base_times"],
                "login_add_times" to cur["login_add_times"],
                "prizes" to cur["prizes"],
            )
        }
        configs.upsert(
            SysConfigDO().apply {
                this.companyId = companyId
                configKey = KEY_CONFIG
                configValue = MAPPER.writeValueAsString(cfg)
            },
        )
        return activityView(companyId)
    }

    // ===================== IC-T3/T4/T5 前台 =====================

    /** 后台快照视图（列表/详情/下架共用同一形状） */
    fun activityView(companyId: Long): Map<String, Any?> {
        val cfg = rawConfig(companyId)
        return linkedMapOf(
            "activity_id" to ACTIVITY_ID,
            "enable" to cfg["enable"],
            "name" to cfg["name"],
            "base_times" to cfg["base_times"],
            "login_add_times" to cfg["login_add_times"],
            "prizes" to cfg["prizes"],
            "log_total" to logs.countAll(companyId, 0),
            "point_total" to logs.pointSum(companyId, 0),
            "drawn_users" to logs.countUsers(companyId),
            "last_draw_at" to logs.lastDrawAt(companyId),
        )
    }

    /** 前台配置页（与后台一致，出敏感什么都没有，就直接同形状） */
    fun frontConfig(companyId: Long): Map<String, Any?> {
        val cfg = rawConfig(companyId)
        return linkedMapOf(
            "enable" to cfg["enable"],
            "name" to cfg["name"],
            "base_times" to cfg["base_times"],
            "login_add_times" to cfg["login_add_times"],
            "prizes" to cfg["prizes"],
        )
    }

    /** 状态页：配置 + 我当日剩余/已抽/领取态 */
    fun frontData(companyId: Long, userId: Long): Map<String, Any?> {
        val cfg = rawConfig(companyId)
        val day = todayYmd()
        val drawn = logs.countByDay(companyId, userId, day)
        val claimed = times.find(companyId, userId, day)
        val residue = residue(cfg, claimed?.addTimes ?: 0, drawn)
        return linkedMapOf(
            "enable" to cfg["enable"],
            "name" to cfg["name"],
            "base_times" to cfg["base_times"],
            "login_add_times" to cfg["login_add_times"],
            "prizes" to cfg["prizes"],
            "residue" to residue,
            "drawn_today" to drawn,
            "login_add_claimed" to (claimed != null),
        )
    }

    /** IC-T4 抽奖：领次数状态 → 派生剩余 → 确定性抽取 → 流水与送分同事务 */
    fun draw(companyId: Long, userId: Long): Map<String, Any?> {
        val cfg = rawConfig(companyId)
        val day = todayYmd()
        val drawn = logs.countByDay(companyId, userId, day)
        val claimed = times.find(companyId, userId, day)
        val residue = residue(cfg, claimed?.addTimes ?: 0, drawn)
        if (cfg["enable"] != true) throw BizException(ErrorCode.BIZ, "大转盘未开启")
        if (residue <= 0) throw BizException(ErrorCode.BIZ, "今日抽奖次数已用完")
        val prize = pick(companyId, userId, day, drawn, cfg["prizes"] as List<Map<String, Any?>>)
        db.transactional {
            if (prize["type"] == TYPE_POINT && (prize["point"] as Long) > 0) {
                points.adjust(
                    companyId,
                    PointAdjustForm(userId = userId, changePoint = prize["point"] as Long, remark = REMARK_DRAW),
                )
            }
            logs.insert(
                UserLotteryLogDO().apply {
                    this.companyId = companyId
                    this.userId = userId
                    activityId = ACTIVITY_ID
                    prizeId = prize["id"].toString()
                    prizeName = prize["name"].toString()
                    prizeType = prize["type"].toString()
                    this.point = prize["point"] as Long
                    this.day = day
                },
            )
            linkedMapOf(
                "prize_id" to prize["id"],
                "prize_name" to prize["name"],
                "prize_type" to prize["type"],
                "point" to (prize["point"] as Long),
                "residue" to (residue - 1),
                "drawn_today" to (drawn + 1),
            )
        }
    }

    /** IC-T6 我的抽奖记录（翻页） */
    fun myLog(companyId: Long, userId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { logs.countUser(companyId, userId) },
        query = { off, lim -> logs.pageUser(companyId, userId, off, lim) },
        map = { rows -> rows.map { logRow(it) } },
    )

    /** IC-T7 loginaddtimes：每日一次 +N（N 来自配置），重复领不再加、也不报错（回到状态页口径） */
    fun loginAddTimes(companyId: Long, userId: Long): Map<String, Any?> {
        val cfg = rawConfig(companyId)
        val day = todayYmd()
        val n = ((cfg["login_add_times"] as? Int) ?: 0).coerceAtLeast(0)
        val had = times.find(companyId, userId, day) != null
        if (n > 0 && !had) {
            times.claim(
                UserLotteryTimesDO().apply {
                    this.companyId = companyId
                    this.userId = userId
                    this.day = day
                    addTimes = n
                },
            )
        }
        val claimed = times.find(companyId, userId, day)?.addTimes ?: 0
        return linkedMapOf(
            "add_times" to claimed,
            "claimed" to (claimed > 0),
            "residue" to residue(cfg, claimed, logs.countByDay(companyId, userId, day)),
        )
    }

    // ===================== IC-T8 后台留痕面 =====================

    fun adminLog(companyId: Long, userId: Long?, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { logs.countAll(companyId, userId ?: 0) },
        query = { off, lim -> logs.pageUserAll(companyId, userId ?: 0, off, lim) },
        map = { rows -> rows.map { logRow(it) } },
    )

    fun adminCount(companyId: Long): Map<String, Any?> = linkedMapOf(
        "log_total" to logs.countAll(companyId, 0),
        "point_total" to logs.pointSum(companyId, 0),
        "drawn_users" to logs.countUsers(companyId),
    )

    // ===================== 内部件 =====================

    private fun residue(cfg: Map<String, Any?>, addTimes: Int, drawnToday: Long): Int =
        (((cfg["base_times"] as? Int) ?: 0) + addTimes - drawnToday.toInt()).coerceAtLeast(0)

    /** 确定性抽取：权重累加，种子由 (company,user,day,本次第几抽) 推导 */
    private fun pick(
        companyId: Long,
        userId: Long,
        day: Long,
        drawn: Long,
        prizes: List<Map<String, Any?>>,
    ): Map<String, Any?> {
        val total = prizes.sumOf { it["weight"] as Int }
        if (total <= 0) throw BizException(ErrorCode.BIZ, "大转盘未开启")
        val seed = (companyId * 1_000_003L + userId * 10_007L + day * 101L + drawn)
        val r = Random(seed)
        var p = r.nextInt(total)
        for (prize in prizes) {
            p -= prize["weight"] as Int
            if (p < 0) return prize
        }
        return prizes.last()
    }

    private fun todayYmd(): Long {
        val d = LocalDate.now(TimeSec.TZ)
        return (d.year * 10_000L) + (d.monthValue * 100L) + d.dayOfMonth
    }

    private fun logRow(r: UserLotteryLogDO): Map<String, Any?> = linkedMapOf(
        "prize_id" to r.prizeId,
        "prize_name" to r.prizeName,
        "prize_type" to r.prizeType,
        "point" to r.point,
        "user_id" to r.userId,
        "created" to r.created,
    )

    private fun sub(n: com.fasterxml.jackson.databind.JsonNode): Map<String, Any?> = linkedMapOf(
        "id" to (n.get("id")?.asText() ?: ""),
        "name" to (n.get("name")?.asText() ?: ""),
        "type" to (n.get("type")?.asText() ?: TYPE_NONE),
        "point" to (n.get("point")?.asLong() ?: 0L),
        "weight" to (n.get("weight")?.asInt() ?: 0),
    )

    private val MAPPER = com.fasterxml.jackson.databind.ObjectMapper()

    private val LINKED_EMPTY: Map<String, Any?> = emptyMap()
}
