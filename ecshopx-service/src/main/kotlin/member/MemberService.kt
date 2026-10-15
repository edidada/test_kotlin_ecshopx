package com.ecshopx.kotlin.service.member

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MemberDO
import com.ecshopx.kotlin.dao.auth.MemberGradeDO
import com.ecshopx.kotlin.dao.auth.MembersGradeMapper
import com.ecshopx.kotlin.dao.auth.MembersMapper
import com.ecshopx.kotlin.dao.member.MemberInfoDO
import com.ecshopx.kotlin.dao.member.MembersInfoMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.pay.DepositMapper
import com.ecshopx.kotlin.dao.pay.PointMapper
import com.ecshopx.kotlin.service.auth.MemberAuthService
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * M0-8 会员域服务（MEM-1/2/3/7）。
 *
 * 与源系统一致的三条硬规矩：
 *  1. 手机号**只出掩码**（`138****0001`），改绑走 MEM-3 验证码，不存在"资料接口直接改 mobile"；
 *  2. 资料接口收到的 `mobile` / `company_id` 一律拒绝（防越权改租户归属），掩码值本身静默忽略；
 *  3. 等级不是存出来的，是**按累计实付算出来的**——`members_grade.growth_value` 是门槛（分），
 *     取不超过累计实付的最高一级；`next_grade_info` 给出门槛与差额。
 */
@Component
class MemberService(
    private val members: MembersMapper,
    private val info: MembersInfoMapper,
    private val grades: MembersGradeMapper,
    private val points: PointMapper,
    private val deposits: DepositMapper,
    private val orders: OrdersMapper,
    private val db: Db,
    private val mapper: ObjectMapper,
    @Value("\${ecx.security.sms-mock-code:8888}") private val smsMockCode: String,
) {

    /** MEM-1 我的信息（脱敏）—— 资料 + 等级 + 资产 + 消费统计一次给齐 */
    fun profile(companyId: Long, userId: Long): Map<String, Any?> {
        val m = member(companyId, userId)
        val i = info.findByUserId(userId) ?: MemberInfoDO().apply { this.userId = userId }
        val c = orders.consumption(companyId, userId)
        val spent = c?.totalConsumption ?: 0L
        val paidOrders = c?.paidOrders ?: 0L
        val list = grades.listByCompany(companyId)
        val current = list.firstOrNull { it.gradeId == m.gradeId }
            ?: list.firstOrNull()
            ?: grades.findById(m.gradeId)
        val next = list.filter { it.level > (current?.level ?: 0) }.minByOrNull { it.level }
        val acc = deposits.find(companyId, userId)
        val pt = points.find(companyId, userId)
        return linkedMapOf(
            "user_id" to m.userId,
            "grade_id" to m.gradeId,
            "mobile" to mask(m.mobile),
            "nickname" to (i.username ?: ""),
            "avatar" to (i.avatar ?: ""),
            "sex" to i.sex,
            "birthday" to (i.birthday ?: ""),
            "address" to (i.address ?: ""),
            "email" to (i.email ?: ""),
            "industry" to (i.industry ?: ""),
            "income" to (i.income ?: ""),
            "edu_background" to (i.eduBackground ?: ""),
            "habbit" to habbitList(i.habbit),
            "have_consume" to if (i.haveConsume) 1 else 0,
            "gradeInfo" to gradeView(current, spent, null),
            "nextGradeInfo" to gradeView(next, spent, spent),
            "totalConsumption" to spent,
            "point" to (pt?.point ?: 0L),
            "deposit" to linkedMapOf(
                "deposit_amount" to (acc?.depositAmount ?: 0L),
                "gift_amount" to (acc?.giftAmount ?: 0L),
                "balance" to ((acc?.depositAmount ?: 0L) + (acc?.giftAmount ?: 0L)),
            ),
            // 源系统 is_new = 还没有过任何已付订单的新客标记（首页引导用）
            "is_new" to if (paidOrders == 0L) 1 else 0,
        )
    }

    /**
     * MEM-2 修改资料。白名单外的键静默丢弃，但 `mobile` / `company_id` 是**显式拒绝**（422），
     * 因为源系统这里曾出过越权改归属的线上事故；掩码手机号（含 `*`）视为前端回显，直接忽略。
     */
    fun updateProfile(
        companyId: Long,
        userId: Long,
        raw: Map<String, Any?>,
    ): Map<String, Any?> {
        val m = member(companyId, userId)
        // 掩码回显（`138****0001`）与"原号重传"是个人中心保存时的常态，静默忽略；
        // 只有试图改成另一个真实号码才按越权改登录凭证拒绝。
        val wantsOtherMobile = raw["mobile"]?.toString()?.let { it != m.mobile && !it.contains("*") } == true
        val forbiddenHit = FORBIDDEN.filter { it != "mobile" }.firstOrNull { raw.containsKey(it) }
        if (forbiddenHit != null || wantsOtherMobile) {
            throw BizException(ErrorCode.VALIDATION, "该字段不允许修改", mapOf("mobile" to listOf("请通过换绑手机修改")))
        }
        val cur = info.findByUserId(userId) ?: MemberInfoDO().apply { this.userId = userId }
        val next = MemberInfoDO().apply {
            this.userId = userId
            username = str(raw, "username") ?: cur.username
            name = str(raw, "name") ?: cur.name
            avatar = str(raw, "avatar") ?: cur.avatar
            address = str(raw, "address") ?: cur.address
            email = str(raw, "email") ?: cur.email
            industry = str(raw, "industry") ?: cur.industry
            income = str(raw, "income") ?: cur.income
            eduBackground = str(raw, "edu_background") ?: cur.eduBackground
            habbit = if (raw.containsKey("habbit")) json(raw["habbit"]) else cur.habbit
            sex = raw["sex"]?.toString()?.toIntOrNull() ?: cur.sex
            birthday = if (raw.containsKey("birthday")) str(raw, "birthday") ?: "" else cur.birthday
            // have_consume 由首单结清时落库（M1 售后/复购链路写），资料接口不得改
            haveConsume = cur.haveConsume
            customData = cur.customData
        }
        db.transactional { info.upsert(next) }
        return profile(companyId, userId)
    }

    /**
     * MEM-3 换绑手机：验证码走 M0 mock 恒码（配置 `ecx.security.sms-mock-code`），
     * 新号已被同租户他人占用直接 422，条件更新（WHERE mobile = 旧值）兜住并发重复提交。
     */
    fun rebindMobile(companyId: Long, userId: Long, mobile: String?, vcode: String?): Map<String, Any?> {
        val to = (mobile ?: "").trim()
        if (MemberAuthService.MOBILE.matchEntire(to) == null) {
            throw BizException(ErrorCode.VALIDATION, "手机号格式错误", mapOf("mobile" to listOf("格式不正确")))
        }
        if (vcode != smsMockCode) {
            throw BizException(ErrorCode.VALIDATION, "验证码错误", mapOf("vcode" to listOf("不正确")))
        }
        val m = member(companyId, userId)
        if (m.mobile == to) throw BizException(ErrorCode.BIZ, "与新手机号相同")
        members.findByMobile(to)?.let { throw BizException(ErrorCode.BIZ, "该手机号已被使用") }
        if (members.updateMobile(userId, companyId, m.mobile, to, TimeSec.now()) == 0) {
            throw BizException(ErrorCode.BIZ, "手机号已变更，请重试")
        }
        return mapOf("user_id" to userId, "mobile" to mask(to))
    }

    /**
     * GOODS-8 前台详情的 `member_price`：按会员等级折扣折后价（分，向下取整）。
     * 与购物车那条 MemberDiscountStrategy 同一口径（`discount` 是百分比，100=无折扣），
     * 两处若漂移会出现"详情页显示价与结算价不一致"，所以算法只在这里和策略里各存一份，测试互校。
     */
    fun memberPrice(companyId: Long, userId: Long, price: Long): Long {
        val g = grades.findById(member(companyId, userId).gradeId) ?: return price
        if (g.discount >= 100) return price
        val rate = (100 - g.discount).coerceIn(0, 100)
        return price - price * rate / 100
    }

    /** MEM-7 订单角标 + 资产（个人中心首屏，不含资料） */    fun statistical(
        companyId: Long,
        userId: Long,
        counts: Map<String, Any?>,
    ): Map<String, Any?> {
        val acc = deposits.find(companyId, userId)
        val pt = points.find(companyId, userId)
        val c = orders.consumption(companyId, userId)
        return linkedMapOf(
            "point" to (pt?.point ?: 0L),
            "deposit" to linkedMapOf(
                "deposit_amount" to (acc?.depositAmount ?: 0L),
                "gift_amount" to (acc?.giftAmount ?: 0L),
                "balance" to ((acc?.depositAmount ?: 0L) + (acc?.giftAmount ?: 0L)),
            ),
            "totalConsumption" to (c?.totalConsumption ?: 0L),
            "orderscount" to counts,
        )
    }

    private fun member(companyId: Long, userId: Long): MemberDO =
        members.findById(userId)?.takeIf { it.companyId == companyId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "用户不存在")

    /** 等级视图：门槛与实际消费一起给，前端自行画进度条；next 档额外给 need_growth（差额，分） */
    private fun gradeView(g: MemberGradeDO?, spent: Long, needAt: Long?): Map<String, Any?>? = g?.let {
        linkedMapOf(
            "grade_id" to it.gradeId,
            "grade_name" to it.gradeName,
            "level" to it.level,
            "growth_value" to it.growthValue,
            "discount" to it.discount,
            "need_growth" to (if (needAt != null) (it.growthValue - needAt).coerceAtLeast(0L) else 0L),
        )
    }

    private fun habbitList(raw: String?): List<Map<String, Any?>> = when {
        raw.isNullOrBlank() -> emptyList()
        else -> runCatching {
            mapper.readValue(raw, List::class.java).filterIsInstance<Map<String, Any?>>()
        }.getOrDefault(emptyList())
    }

    private fun str(raw: Map<String, Any?>, key: String): String? = raw[key]?.toString()

    private fun json(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        else -> mapper.writeValueAsString(v)
    }

    companion object {
        /** 资料接口显式拒改的字段（其余未知键静默忽略，与源系统行为一致） */
        val FORBIDDEN = listOf("mobile", "company_id", "user_id", "have_consume")

        /** 138****0001；短号（历史脏数据）原样返回，不抛 */
        fun mask(mobile: String?): String = when {
            mobile.isNullOrBlank() -> ""
            mobile.length < 7 -> mobile
            else -> mobile.take(3) + "****" + mobile.takeLast(4)
        }
    }
}
