package com.ecshopx.kotlin.web.front.security

import com.ecshopx.kotlin.service.auth.JwtService
import com.ecshopx.kotlin.service.auth.Kind
import com.ecshopx.kotlin.service.auth.TenantContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/** 消费者面 Bearer JWT（kind=MEMBER）。同 JwtOperatorFilter，非组件，由 SecurityConfig 构造。 */
class JwtMemberFilter(private val jwt: JwtService) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val token = req.getHeader("Authorization")?.removePrefix("Bearer ")?.trim()
        try {
            val parsed = token?.takeIf { it.isNotBlank() }?.let { jwt.parse(it) }?.first
            if (parsed != null && parsed.kind == Kind.MEMBER) {
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(parsed, token, listOf(SimpleGrantedAuthority("ROLE_MEMBER")))
                TenantContext.companyId = parsed.companyId
            }
            chain.doFilter(req, res)
        } finally {
            SecurityContextHolder.clearContext()
            TenantContext.clear()
        }
    }
}
