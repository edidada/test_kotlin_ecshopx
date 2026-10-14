package com.ecshopx.kotlin.common.api

/**
 * 业务错误码。http 字段给出 Kotlin 版默认状态码；
 * 兼容模式 ecx.compat.force-ok-status=true 时由 Advice 统一改 200（对齐 PHP 行为，URL 文档 §1）。
 */
enum class ErrorCode(val code: Int, val http: Int) {
    VALIDATION(400, 400),
    UNAUTH(401, 401),
    NO_PERM(403, 403),
    NOT_FOUND(404, 404),
    BIZ(422, 422),
    NOT_IMPLEMENTED(501, 501),
    INTERNAL(500, 500),
}

/**
 * 业务异常 —— message 直接面向客户端（PHP 侧 trans key 文案的中文原文）。
 * errors 与 Dingo ResourceException 的字段错误结构同形：{"field": ["msg"]}。
 */
class BizException(
    val ec: ErrorCode,
    message: String,
    val errors: Map<String, List<String>> = emptyMap(),
) : RuntimeException(message)

/** 错误响应体，字段名对齐源系统 EspierErrorRespones：{message, errors, code, status_code} */
data class ApiError(
    val message: String,
    val errors: Map<String, List<String>>,
    val code: Int,
    val status_code: Int,
)
