package com.ecshopx.kotlin.service.auth

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.AssocDO
import com.ecshopx.kotlin.dao.auth.MemberDO
import com.ecshopx.kotlin.dao.auth.MembersAssociationsMapper
import com.ecshopx.kotlin.dao.auth.MembersMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * 消费者登录（AUTH-4，源 LoginController@login 的 M0 分支）：
 * - auth_type=local：check_type=mobile（短信码走 mock 8888）/ password（BCrypt）
 * - auth_type=wxapp：M0 Mock 通道——openId 由 code 确定性派生，真实 jscode2session P1
 * - 缺号 auto_register=1 自动注册（is_new=1）；silent=1 错误进 body 不抛 HTTP 错（源行为）
 */
@Component
class MemberAuthService(
    private val members: MembersMapper,
    private val associations: MembersAssociationsMapper,
    private val jwt: JwtService,
    @Value("\${ecx.security.sms-mock-code:8888}") private val smsMockCode: String,
) {
    data class Result(
        val token: String?,
        val status: Int,
        val isNew: Int,
        val errorMessage: String?,
        val preLoginData: Map<String, Any?>,
    )

    fun localLogin(mobile: String?, checkType: String?, vcode: String?, password: String?, autoRegister: Int): Result {
        if (mobile == null || MOBILE.matchEntire(mobile) == null) return fail("手机号格式错误")
        val m = members.findByMobile(mobile)
        val ok = when (checkType) {
            "password" -> {
                val hash = m?.password
                hash != null && BCrypt.checkpw(password ?: "", hash)
            }
            else -> vcode == smsMockCode
        }
        if (!ok) return fail(if (checkType == "password") "用户名或密码错误" else "验证码错误")
        if (m == null) {
            if (autoRegister != 1) return fail("用户不存在")
            val now = TimeSec.now()
            val m2 = MemberDO().apply {
                companyId = 1; gradeId = 1; this.mobile = mobile; sourceFrom = "h5"; created = now; updated = now
            }
            members.insert(m2)
            return success(m2.userId, mobile, isNew = 1, openId = null)
        }
        if (m.disabled) return fail("账号已禁用")
        return success(m.userId, mobile, isNew = 0, openId = null)
    }

    fun wxappLogin(code: String?, appId: String?): Result {
        val openId = "mock_" + sha1Hex((code ?: "anon") + (appId ?: "")).take(24)
        val assoc = associations.findByOpenId(openId)
        if (assoc != null) {
            val m = members.findById(assoc.userId)
            if (m != null && !m.disabled) return success(m.userId, m.mobile, isNew = 0, openId = openId)
        }
        val now = TimeSec.now()
        val m = MemberDO().apply {
            companyId = 1; gradeId = 1; sourceFrom = "wxapp"; created = now; updated = now
        }
        members.insert(m)
        associations.insert(
            AssocDO().apply {
                userId = m.userId; companyId = 1; this.openId = openId; unionid = null
            },
        )
        return success(m.userId, null, isNew = 1, openId = openId)
    }

    fun refresh(p: Principal): String = jwt.issue(p.kind, p.id, p.companyId, p.openId)

    private fun success(userId: Long, mobile: String?, isNew: Int, openId: String?) = Result(
        token = jwt.issue(Kind.MEMBER, userId, 1L, openId),
        status = 1,
        isNew = isNew,
        errorMessage = null,
        preLoginData = mapOf("open_id" to openId, "mobile" to mobile, "user_id" to userId),
    )

    private fun fail(msg: String) = Result(null, 0, 0, msg, emptyMap())

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val MOBILE = Regex("""1[3-9]\d{9}""")
    }
}
