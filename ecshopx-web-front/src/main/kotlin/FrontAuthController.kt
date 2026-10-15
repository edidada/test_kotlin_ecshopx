package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.service.auth.MemberAuthService
import com.ecshopx.kotlin.service.auth.Principal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** AUTH-4/5 —— 消费者小程序/公众号登录（M0：local 手机号 + wxapp Mock 通道） */
@RestController
class FrontAuthController(private val members: MemberAuthService) {

    // 字段名保持源系统 snake_case 直连（Jackson 反射 Kotlin 属性名）
    data class WxappLoginRequest(
        val auth_type: String = "wxapp",
        val username: String? = null,
        val check_type: String? = "mobile",
        val vcode: String? = null,
        val password: String? = null,
        val auto_register: Int = 1,
        val silent: Int = 0,
        val appid: String? = null,
        val code: String? = null,
        val iv: String? = null,
        val encryptedData: String? = null,
        val rawData: String? = null,
        val signature: String? = null,
        val salesperson_id: Long? = null,
        val cloudID: String? = null,
    )

    @PostMapping("/api/h5app/wxapp/new_login")
    fun login(@RequestBody req: WxappLoginRequest): ApiResponse<Map<String, Any?>> {
        val r = if (req.auth_type == "local") {
            members.localLogin(req.username, req.check_type, req.vcode, req.password, req.auto_register)
        } else {
            members.wxappLogin(req.code, req.appid)
        }
        return ApiResponse.ok(
            linkedMapOf(
                "token" to r.token,
                "pre_login_data" to r.preLoginData,
                "status" to r.status,
                "error_message" to r.errorMessage,
                "is_new" to r.isNew,
            ),
        )
    }

    @GetMapping("/api/h5app/wxapp/token/refresh")
    fun refresh(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, String>>? =
        p?.let { ApiResponse.ok(mapOf("token" to members.refresh(it))) }
}
