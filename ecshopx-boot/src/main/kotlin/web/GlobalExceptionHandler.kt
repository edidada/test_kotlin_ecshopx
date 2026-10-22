package com.ecshopx.kotlin.boot.web

import com.ecshopx.kotlin.common.api.ApiError
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * 统一异常出口（详细设计 §2）：响应体 {message, errors, code, status_code}，
 * 与 Dingo EspierErrorRespones 同形。
 * 兼容开关 ecx.compat.force-ok-status=true 时非 401 一律 HTTP 200、逻辑状态放 status_code
 * （对齐 PHP "全 200 除 401" 行为，URL 文档 §1；默认关闭=返回真实状态码，见破坏性变更 D2）。
 */
@RestControllerAdvice(basePackages = ["com.ecshopx.kotlin.web"])
class GlobalExceptionHandler(
    @Value("\${ecx.compat.force-ok-status:false}") private val forceOk: Boolean,
) {

    @ExceptionHandler(BizException::class)
    fun onBiz(e: BizException): ResponseEntity<ApiError> =
        respond(e.ec, e.message ?: "error", e.errors)

    /** Bean Validation：@Valid 失败 → 400 + 字段错误 map（与 PHP validator errors 同形） */
    @ExceptionHandler(MethodArgumentNotValidException::class, BindException::class)
    fun onValidation(e: BindException): ResponseEntity<ApiError> {
        val errors = e.bindingResult.fieldErrors.groupBy({ it.field }, { it.defaultMessage ?: "invalid" })
        return respond(ErrorCode.VALIDATION, "validation failed", errors)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun onNoResource(e: NoResourceFoundException): ResponseEntity<ApiError> =
        respond(ErrorCode.NOT_FOUND, "resource not found", emptyMap())

    /**
     * M2-2 追加：微信配置性失败 → 422（运营要去看配置，不是程序要去看栈）。
     * 与 BizException 同一出口，只是来源是"微信侧凭据/票据"这一族。
     */
    @ExceptionHandler(com.ecshopx.kotlin.service.wx.WxConfigException::class)
    fun onWxConfig(e: com.ecshopx.kotlin.service.wx.WxConfigException): ResponseEntity<ApiError> =
        respond(ErrorCode.BIZ, e.message ?: "微信配置不完整", emptyMap())

    @ExceptionHandler(Exception::class)
    fun onOther(e: Exception): ResponseEntity<ApiError> {
        // 500 一律留栈：响应体不暴露内部异常，排障靠日志
        log.error("unhandled exception", e)
        return respond(ErrorCode.INTERNAL, e.message ?: "internal error", emptyMap())
    }

    private companion object {
        val log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    private fun respond(ec: ErrorCode, message: String, errors: Map<String, List<String>>): ResponseEntity<ApiError> {
        val body = ApiError(message, errors, ec.code, ec.http)
        val status = if (forceOk && ec.http != 401) HttpStatus.OK else HttpStatus.valueOf(ec.http)
        return ResponseEntity.status(status).body(body)
    }
}
