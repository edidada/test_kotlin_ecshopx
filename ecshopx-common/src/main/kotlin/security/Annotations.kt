package com.ecshopx.kotlin.common.security

/** 声明式资源权限（对应 PHP route alias as，如 goods.items.lists）；admin 类型绕过 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresResource(val value: String)

/** 写审计日志到 operator_logs（shoplog 等价）；route 为源系统路由别名 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Audited(val route: String)
