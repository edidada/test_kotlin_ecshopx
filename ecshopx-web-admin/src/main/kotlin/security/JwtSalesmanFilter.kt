package com.ecshopx.kotlin.web.admin.security

import com.ecshopx.kotlin.service.auth.JwtService
import com.ecshopx.kotlin.service.auth.Kind
import com.ecshopx.kotlin.service.auth.SalesmanContext
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.SalesmanService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 员工端（第三条链 `staffChain`）的会话过滤器（docs/04 §18.3）。
 *
 * 令牌来源按源系统那个 header 名收：**`x-wxapp-session` 优先，`Authorization: Bearer` 兜底**。
 * 留兜底是因为本项目所有冒烟脚本、测试夹具和前端封装早就统一发 `Authorization`，
 * 让员工端单独只认一个非标准头只会让"同一个 token 换个 key 就 401"变成新的坑；
 * 两个都认、且都只认 `Kind.SALESMAN`，跨面拒绝这条不受影响。
 *
 * 三件事必须在同一个过滤器里做完，顺序不能换：
 *  1. `TenantContext` 先落 —— 后面那次回查是按租户查的（`salesman_id` 全局唯一但 `company_id` 必须对上，
 *     否则 A 租户的令牌能读出 B 租户同 id 那个人的档案）；
 *  2. **每个请求回查 `is_valid`**（D44）—— JWT 是无状态的，光验签名意味着"停用一个人要等他的令牌自然过期"，
 *     而员工手里的令牌能开单、能核销，那 12 小时里发生的每一单都要退款才能收场；
 *  3. 回查通过才把 [SalesmanContext.current] 与 `SecurityContext` 一起设上。
 *
 * 校验不过**不写 401 就往下放行**：与运营/会员两条链同一口径，
 * 401 由链上的 `authenticationEntryPoint` 统一出（那里才有 `Unauthenticated.` 这个源系统文案），
 * 过滤器自己写会绕开全局错误体的信封形状。
 */
class JwtSalesmanFilter(
    private val jwt: JwtService,
    private val salesmen: SalesmanService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val token = req.getHeader(HEADER)?.takeIf { it.isNotBlank() }
            ?: req.getHeader("Authorization")?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }
        try {
            val parsed = token?.let { jwt.parse(it) }?.first
            if (parsed != null && parsed.kind == Kind.SALESMAN) {
                TenantContext.companyId = parsed.companyId
                val p = salesmen.principalOf(parsed.id)
                if (p != null && p.companyId == parsed.companyId) {
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(p, token, listOf(SimpleGrantedAuthority("ROLE_SALESMAN")))
                    SalesmanContext.current = p
                }
            }
            chain.doFilter(req, res)
        } finally {
            SecurityContextHolder.clearContext()
            SalesmanContext.clear()
            TenantContext.clear()
        }
    }

    companion object {
        /** 源系统 `adminwxapp` -provider 用的那个头，本项目沿用同名（§18.3） */
        const val HEADER = "x-wxapp-session"
    }
}
