package com.ecshopx.kotlin.service.member

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.MemberAuthService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * MEM-8 短信验证码（M0 mock）。
 *
 * 源系统走阿里云短信，M0 不接真实通道：本服务只做**入参校验与语义占位**，
 * 校验码恒等于配置 `ecx.security.sms-mock-code`（默认 8888），登录/换绑两处共用同一个值，
 * 因此这里不把码写进任何表——校验方一律回落到配置比对（见 MemberAuthService / MemberService）。
 * P1 接真通道时只需在此落 `sms_codes` 表 + 发送计数，接口出参形状不变。
 */
@Component
class VcodeService(
    @Value("\${ecx.security.sms-mock-code:8888}") private val mockCode: String,
) {

    /** type 只认源系统在册的几种，防前端乱传 */
    private val types = setOf("login", "register", "rebind_mobile", "reset_password", "bind_mobile")

    fun send(mobile: String?, type: String?): Map<String, Any?> {
        val m = (mobile ?: "").trim()
        if (MemberAuthService.MOBILE.matchEntire(m) == null) {
            throw BizException(ErrorCode.VALIDATION, "手机号格式错误", mapOf("mobile" to listOf("格式不正确")))
        }
        val t = (type ?: "login").trim()
        if (t !in types) throw BizException(ErrorCode.VALIDATION, "验证码类型不支持", mapOf("type" to listOf(t)))
        return linkedMapOf(
            "mobile" to MemberService.mask(m),
            "type" to t,
            "status" to 1,
            "expire_seconds" to EXPIRE_SECONDS,
            // mock 通道显式声明，前端据此提示"演示环境固定验证码"
            "mock" to true,
            "mock_code" to mockCode,
        )
    }

    companion object {
        const val EXPIRE_SECONDS = 300L
    }
}
