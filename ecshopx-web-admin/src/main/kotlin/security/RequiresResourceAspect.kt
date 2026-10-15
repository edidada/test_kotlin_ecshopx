package com.ecshopx.kotlin.web.admin.security

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Kind
import com.ecshopx.kotlin.service.auth.OperatorAuthService
import com.ecshopx.kotlin.service.auth.Principal
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component

/**
 * 复刻 PHP CheckActivedMiddleWare::checkPermission（详细设计 §6）：
 * 未登录 401；OPERATOR 之外 403；operator_type=admin 全放行；其余查 operator→role→resource 闭包（缓存）。
 * 切点用全限定名形式而非参数绑定 —— Kotlin 默认不写 MethodParameters 属性，绑定名解析不到会静默失配。
 */
@Aspect
@Component
class RequiresResourceAspect(private val auth: OperatorAuthService) {

    @Around("@annotation(com.ecshopx.kotlin.common.security.RequiresResource)")
    fun check(jp: ProceedingJoinPoint): Any? {
        val method = (jp.signature as MethodSignature).method
        val rc = method.getAnnotation(RequiresResource::class.java) ?: return jp.proceed()
        val principal = SecurityContextHolder.getContext().authentication?.principal as? Principal
            ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
        if (principal.kind != Kind.OPERATOR) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        if (!auth.hasResource(principal, rc.value)) throw BizException(ErrorCode.NO_PERM, "无权访问该资源")
        return jp.proceed()
    }
}
