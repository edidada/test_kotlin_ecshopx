package com.ecshopx.kotlin.web.admin.security

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.dao.auth.OperatorLogsMapper
import com.ecshopx.kotlin.service.auth.Principal
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** operator_logs 审计 AOP（shoplog 等价）：成功返回后记一笔，参数 JSON 截断 2000。切点形式见 RequiresResourceAspect 注释 */
@Aspect
@Component
class AuditedAspect(
    private val logs: OperatorLogsMapper,
    private val mapper: ObjectMapper,
) {

    @Around("@annotation(com.ecshopx.kotlin.common.security.Audited)")
    fun audit(jp: ProceedingJoinPoint): Any? {
        val audited = (jp.signature as MethodSignature).method.getAnnotation(Audited::class.java)
            ?: return jp.proceed()
        val ret = jp.proceed()
        try {
            val p = SecurityContextHolder.getContext().authentication?.principal as? Principal
            val req: HttpServletRequest? = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            val loginName = jp.args.filterIsInstance<com.ecshopx.kotlin.web.admin.AuthController.LoginRequest>()
                .firstOrNull()?.username
            val params = runCatching { mapper.writeValueAsString(jp.args.filterNotNull()).take(2000) }.getOrNull()
            logs.log(
                p?.companyId ?: 1L, p?.id, loginName ?: p?.id?.toString(),
                audited.route, req?.method, params,
                req?.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim() ?: req?.remoteAddr,
                TimeSec.now(),
            )
        } catch (e: RuntimeException) {
            // 审计失败不影响业务
        }
        return ret
    }
}
