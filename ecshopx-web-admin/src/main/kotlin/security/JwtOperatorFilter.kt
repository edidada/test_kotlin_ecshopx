package com.ecshopx.kotlin.web.admin.security

import com.ecshopx.kotlin.dao.auth.OperatorsMapper
import com.ecshopx.kotlin.service.auth.JwtService
import com.ecshopx.kotlin.service.auth.Kind
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.fasterxml.jackson.databind.ObjectMapper
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
 *
 * M1-4 起这里还要把 `operators.distributor_ids` 解出来塞进 [Principal.distributorIds]：
 * 数据权限的判据只有这一个来源 —— 请求参数里的 `distributor_id` 是"想看哪家"，
 * 令牌里这份才是"能看哪几家"，两者相与才是最终可见集合（docs/04 §18.8 门槛用例②）。
 */
class JwtOperatorFilter(
    private val jwt: JwtService,
    private val operators: OperatorsMapper,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val token = req.getHeader("Authorization")?.removePrefix("Bearer ")?.trim()
        try {
            val parsed = token?.takeIf { it.isNotBlank() }?.let { jwt.parse(it) }?.first
            if (parsed != null && parsed.kind == Kind.OPERATOR) {
                val op = operators.findById(parsed.id)
                if (op != null && !op.disabled) {
                    val principal = parsed.copy(
                        operatorType = op.operatorType,
                        distributorIds = parseDistributorIds(op.distributorIds),
                    )
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

    /**
     * `distributor_ids` 是源系统留下来的 CLOB 文本，形态不唯一：
     * `[2,3]`（本项目 seed 的写法）、`2,3`（PHP serialize 时代的历史写法）都要认，
     * 解析不出来就当**没绑**（空集合）—— 宁可不给看，也不能因为一列脏数据放开全场。
     */
    private fun parseDistributorIds(raw: String?): List<Long> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "[]" || s == "null") return emptyList()
        runCatching {
            val n = mapper.readTree(s)
            if (n.isArray) return n.mapNotNull { e -> e.asText().trim().toLongOrNull() }.filter { it > 0 }.distinct()
        }
        return s.trim('[', ']').split(',').mapNotNull { it.trim().trim('"').toLongOrNull() }.filter { it > 0 }.distinct()
    }
}
