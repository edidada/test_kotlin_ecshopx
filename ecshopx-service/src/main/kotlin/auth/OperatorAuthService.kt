package com.ecshopx.kotlin.service.auth

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.OperatorLogsMapper
import com.ecshopx.kotlin.dao.auth.OperatorsMapper
import com.ecshopx.kotlin.dao.auth.PermissionsMapper
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 后台运营账号认证 + 资源权限（AUTH-1/2/3 与 @RequiresResource 数据源）。
 * 权限集合 Caffeine 缓存 TTL 5m —— 收敛 PHP 每请求回查 roles/resources。
 * M0 无 Redis：登出(invalidate)为无状态丢弃；PHP jwt 黑名单语义 P1 补。
 */
@Component
class OperatorAuthService(
    private val operators: OperatorsMapper,
    private val permissions: PermissionsMapper,
    private val jwt: JwtService,
    private val logs: OperatorLogsMapper,
    @Value("\${ecx.security.login-captcha:false}") private val captchaEnabled: Boolean,
) {
    private val cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(10_000)
        .build<Long, Set<String>>()

    fun login(username: String, password: String, loginType: String?, yzm: String?, ip: String?): String {
        if (captchaEnabled && yzm != SMS_MOCK_CODE) {
            throw BizException(ErrorCode.VALIDATION, "验证码错误", mapOf("yzm" to listOf("验证码错误")))
        }
        if ((loginType ?: "localadmin") in OAUTH_LOGIN_TYPES) {
            throw BizException(ErrorCode.NOT_IMPLEMENTED, "该登录方式未实现: $loginType")
        }
        val op = operators.findByUsername(username)
        if (op == null || op.disabled || !BCrypt.checkpw(password, op.password)) {
            logs.log(1L, op?.operatorId, username, "operator.login", "POST", null, ip, TimeSec.now())
            throw BizException(ErrorCode.UNAUTH, "用户名或密码错误")
        }
        operators.touchLastLogin(op.operatorId, TimeSec.now())
        logs.log(op.companyId, op.operatorId, op.username, "operator.login", "POST", null, ip, TimeSec.now())
        return jwt.issue(Kind.OPERATOR, op.operatorId, op.companyId)
    }

    /** refresh：以当前主体重新签发（源系统 closure(jwt.refresh) 等价） */
    fun refresh(p: Principal): String = jwt.issue(p.kind, p.id, p.companyId, p.openId)

    fun invalidate() { /* M0 无状态登出：客户端丢弃 token 即生效 */ }

    /** admin 类型全量放行（PHP isSuperAdmin 语义）；其余按 operator→role→resource_code 闭包 */
    fun hasResource(p: Principal, code: String): Boolean =
        p.operatorType == "admin" || code in resourceCodes(p.id)

    fun resourceCodes(operatorId: Long): Set<String> =
        cache.get(operatorId) { id -> permissions.codesByOperator(id).toSet() }

    companion object {
        const val SMS_MOCK_CODE = "8888"
        val OAUTH_LOGIN_TYPES = setOf("wechat", "qq", "alipay", "b2b2c_seller")
    }
}
