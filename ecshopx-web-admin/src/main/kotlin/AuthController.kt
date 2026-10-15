package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.service.auth.OperatorAuthService
import com.ecshopx.kotlin.service.auth.Principal
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** AUTH-1/2/3 —— 后台登录 / 刷新 / 登出（docs/04 §AUTH） */
@RestController
class AuthController(private val auth: OperatorAuthService) {

    data class LoginRequest(
        @field:NotBlank(message = "用户名不能为空") val username: String = "",
        @field:NotBlank(message = "密码不能为空") val password: String = "",
        val logintype: String? = null,
        val token: String? = null,
        val yzm: String? = null,
    )

    @Audited(route = "operator.login")
    @PostMapping("/api/operator/login")
    fun login(@Valid @RequestBody req: LoginRequest, request: HttpServletRequest): ApiResponse<Map<String, String>> =
        ApiResponse.ok(mapOf("token" to auth.login(req.username, req.password, req.logintype, req.yzm, clientIp(request))))

    @GetMapping("/api/token/refresh")
    fun refresh(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, String>>? =
        p?.let { ApiResponse.ok(mapOf("token" to auth.refresh(it))) }

    @GetMapping("/api/token/invalidate")
    fun invalidate(): ApiResponse<Map<String, Int>> {
        auth.invalidate()
        return ApiResponse.ok(mapOf("result" to 1))
    }

    private fun clientIp(req: HttpServletRequest): String =
        req.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim() ?: req.remoteAddr ?: ""
}
