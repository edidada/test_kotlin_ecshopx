package com.ecshopx.kotlin.boot.web

import com.ecshopx.kotlin.common.api.ApiError
import com.ecshopx.kotlin.dao.auth.OperatorsMapper
import com.ecshopx.kotlin.service.auth.JwtService
import com.ecshopx.kotlin.web.admin.security.JwtOperatorFilter
import com.ecshopx.kotlin.web.front.security.JwtMemberFilter
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * 4 组面中 M0-3 先落 2 组：h5app（消费者）与 admin（后台），按 securityMatcher 分链。
 * superadmin/systemlink 链在 M1-4 补。响应体与 Dingo 一致：401 时 message 为 Unauthenticated.。
 * 过滤器在链内 new（非 Bean），避免 Servlet 容器二次注册。
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwt: JwtService,
    private val operators: OperatorsMapper,
    private val mapper: ObjectMapper,
) {

    @Bean
    @org.springframework.core.annotation.Order(1)
    fun frontChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/h5app/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/h5app/wxapp/new_login", "/api/h5app/ping").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(JwtMemberFilter(jwt), UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { it.authenticationEntryPoint { _, res, _ -> write(res, 401, "Unauthenticated.") } }
        return http.build()
    }

    @Bean
    @org.springframework.core.annotation.Order(2)
    fun adminChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/api/operator/login", "/api/ping").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(JwtOperatorFilter(jwt, operators), UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { _, res, _ -> write(res, 401, "Unauthenticated.") }
                    .accessDeniedHandler { _, res, _ -> write(res, 403, "无权访问该资源") }
            }
        return http.build()
    }

    private fun write(res: jakarta.servlet.http.HttpServletResponse, status: Int, message: String) {
        res.status = status
        res.contentType = "application/json;charset=UTF-8"
        res.writer.write(mapper.writeValueAsString(ApiError(message, emptyMap(), status, status)))
    }
}
