package com.ecshopx.kotlin.boot.web

import com.ecshopx.kotlin.common.api.ApiError
import com.ecshopx.kotlin.common.api.ApiResponse
import org.springframework.core.MethodParameter
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Controller 返回裸对象自动包 {"data": …}（详细设计 §2）；
 * 已是 ApiResponse/ApiError 的原样透出。仅作用于 com.ecshopx.kotlin.web 包，
 * actuator/静态资源不受影响。
 */
@RestControllerAdvice(basePackages = ["com.ecshopx.kotlin.web"])
class ApiEnvelopeAdvice : ResponseBodyAdvice<Any> {

    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>): Boolean =
        returnType.containingClass?.`package`?.name?.startsWith("com.ecshopx.kotlin.web") == true &&
            ApiResponse::class.java != returnType.parameterType &&
            ApiError::class.java != returnType.parameterType

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        mediaType: MediaType,
        converterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any = body ?: ApiResponse.EMPTY
}
