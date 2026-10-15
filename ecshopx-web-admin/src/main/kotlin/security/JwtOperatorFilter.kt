package com.ecshopx.kotlin.web.admin.security

import com.ecshopx.kotlin.dao.auth.OperatorsMapper
import com.ecshopx.kotlin.service.auth.JwtService
import com.ecshopx.kotlin.service.auth.Kind
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Bearer JWT → SecurityContext（kind 必须为 OPERATOR，admin 面与 wxapp 后台面共用）。
 * 账号明细（operator_type/绑定店铺）按 PHP getUserData 语义回查；独立部署无 Redis 缓存，
 * 权限集缓存见 OperatorAuthService.Caffeine。非组件 Bean：由 SecurityConfig 显式 new，避免 Servlet 容器重复注册。
 */
class JwtOperatorFilter(
    private val jwt: JwtService,
    private val operators: OperatorsMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val token = req.getHeader("Authorization")?.removePrefix("Bearer ")?.trim()
        try {
            val parsed = token?.takeIf { it.isNotBlank() }?.let { jwt.parse(it) }?.first
            if (parsed != null && parsed.kind == Kind.OPERATOR) {
                val op = operators.findById(parsed.id)
                if (op != null && !op.disabled) {
                    val principal = parsed.copy(operatorType = op.operatorType)
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(principal, token, listOf(SimpleGrantedAuthority("ROLE_OPERATOR")))
                    TenantContext.companyId = principal.companyId
                }
            }
            chain.doFilter(req, res)
        } finally {
            SecurityContextHolder.clearContext()
            TenantContext.clear()
        }
    }
}
