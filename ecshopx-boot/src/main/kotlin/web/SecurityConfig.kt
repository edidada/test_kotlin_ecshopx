package com.ecshopx.kotlin.boot.web

import com.ecshopx.kotlin.common.api.ApiError
import com.ecshopx.kotlin.dao.auth.OperatorsMapper
import com.ecshopx.kotlin.service.auth.JwtService
import com.ecshopx.kotlin.service.distribution.SalesmanService
import com.ecshopx.kotlin.web.admin.security.JwtOperatorFilter
import com.ecshopx.kotlin.web.admin.security.JwtSalesmanFilter
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
 *
 * M1-4 第三批补第三条：`staffChain`（员工端 `/api/admin/wxapp` 前缀，header `x-wxapp-session`）。
 * 它必须排在 `@Order(0)` —— `/api/admin` 那一层是 `/api` 的子集，
 * 排在 `adminChain` 之后这条链永远轮不到，员工令牌会掉进运营链里被 `JwtOperatorFilter` 认成"不是 OPERATOR"，
 * 结果是 401 而不是"链没匹配上"，症状一模一样、排查起来极费时间（顺序就是这条链的全部难点）。
 *
 * （顺带一条 Kotlin 才有的坑：块注释里不能出现"斜杠紧跟两个星号"的那种 ant 风格路径字面量 ——
 *  Kotlin 的块注释可以嵌套，那一段会被当成又开了一个注释，编译器只报 "Unclosed comment"，
 *  不告诉你是哪一行开的头。M2-1 写网关链时又撞了它的孪生：**斜杠紧跟一个星号**同样会开嵌套，
 *  所以这一族的注释里只写 `单段子路径`，不写字面量。）
 *
 * M2-1 第四批补第零条：`openapiChain`（`/api/openapi` 与它的单段子路径，鉴权 = `app_key + sign`）。
 * 它必须排在 `@Order(0)` 之前 —— 理由与 `staffChain` 那一条**完全同构**：
 * `/api/openapi` 是 `adminChain` 那个 `/api` 前缀的子集，排在它后面就永远轮不到，
 * 症状还是 401「Unauthenticated.」而不是「这条链没匹配上」，排查成本一模一样（C174 在同一文件里重演）。
 * 这条链放行的是**开放面本身**；后台管理开发者的那六条在 `/api/setting/openapi` 前缀下，
 * 靠路径本身就分开了，不需要任何豁免表（§25.3 那段裁定就是为这件事写的）。
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwt: JwtService,
    private val operators: OperatorsMapper,
    private val salesmen: SalesmanService,
    private val mapper: ObjectMapper,
) {

    /**
     * M2-2 追加：SystemLink 入站（`/api/systemlink/openapi` 与它的单段子路径、`/api/systemlink/ome` 与它的单段子路径）。
     *
     * 位次理由与 `openapiChain` 完全同构（`/api/systemlink` 也是 `/api` 的子集），但**匹配范围更窄**：
     * 只放行入站那三个路径，不是整个前缀 —— `SL-A3` 的 `/api/systemlink/logs` 就长在同一个前缀下，
     * 而那张表里是对端发来的全部业务参数。放行整段等于把调用留痕挂到公网上，
     * 症状还是"列表页 200 但数据不对"，没人会往安全配置上想。
     *
     * 这一面的鉴权由 `SystemLinkInboundService` 自己判（两把 token、两支 `assemble` 语义），
     * 链内不挂任何 `Jwt*Filter`。
     */
    /**
     * M2-2 追加：微信回调匿名面（`/api/wechat/notify` 与 `/api/wechat/openplatform/notify` 各自的单段子路径）。
     *
     * 与 `systemLinkChain` 同构：`/api/wechat` 也是 `/api` 的子集，而这条链的前缀下
     * **同时住着 WX-A1..A9 的后台面**——放行不能到整个 `/api/wechat`，只能精确到这两段回调后缀。
     *
     * 微信侧的通知没有 JWT，身份由"签名 + （真链）加密体"证明 —— 守卫在 `WxNotifyService`：
     * 签名与解密判据先于一切分发，解不开回 400 且不落库（§27.6 门槛⑤）。
     */
    @Bean
    @org.springframework.core.annotation.Order(-3)
    fun wechatNotifyChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(
                "/api/wechat/notify/*",
                "/api/wechat/openplatform/notify/*",
            )
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .exceptionHandling { it.authenticationEntryPoint { _, res, _ -> write(res, 401, "Unauthenticated.") } }
        return http.build()
    }

    @Bean
    @org.springframework.core.annotation.Order(-2)
    fun systemLinkChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/systemlink/openapi/*", "/api/systemlink/ome", "/api/systemlink/ome/*")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .exceptionHandling { it.authenticationEntryPoint { _, res, _ -> write(res, 401, "Unauthenticated.") } }
        return http.build()
    }

    @Bean
    @org.springframework.core.annotation.Order(-1)
    fun openapiChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 两条入口各写一条 matcher，不用 `/**`：这一面只有 `/api/openapi` 与它的**单段**子路径
            // （`/api/openapi/ecx.order.get`），`*` 恰好只吃一段，多段的 `/api/openapi/a/b` 落回 adminChain。
            // 后台那六条在 `/api/setting/openapi` 下（§25.3），永远不会被这两条 matcher 命中。
            .securityMatcher("/api/openapi", "/api/openapi/*")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            // 不挂任何 Jwt*Filter：这一面的身份是 `app_key + sign`，由 OpenapiGatewayService 自己判
            .exceptionHandling { it.authenticationEntryPoint { _, res, _ -> write(res, 401, "Unauthenticated.") } }
        return http.build()
    }

    @Bean
    @org.springframework.core.annotation.Order(0)
    fun staffChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/admin/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // 只有登录那一条匿名；其余每一条都要 `Kind.SALESMAN` 会话（§18.3）
                it.requestMatchers("/api/admin/wxapp/login").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(JwtSalesmanFilter(jwt, salesmen), UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { _, res, _ -> write(res, 401, "Unauthenticated.") }
                    .accessDeniedHandler { _, res, _ -> write(res, 403, "无权访问该资源") }
            }
        return http.build()
    }

    @Bean
    @org.springframework.core.annotation.Order(1)
    fun frontChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/h5app/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/h5app/wxapp/new_login",
                    "/api/h5app/ping",
                    "/api/h5app/vcode",
                    "/api/h5app/wxapp/goods/items",
                    "/api/h5app/wxapp/goods/items/*",
                    // M1-4：进店与看店铺商品是匿名行为（DS-F1/F2、SI-F1/F2 标 * 的那四条）
                    "/api/h5app/wxapp/distributors",
                    "/api/h5app/wxapp/distributors/*",
                    "/api/h5app/wxapp/goods/shopitems",
                    "/api/h5app/wxapp/goods/items_price_store/*",
                    // M1-4 第三批：选自提点发生在下单之前，这一步小程序还没有令牌（PL-F1 在 §18.1 里标 *）
                    "/api/h5app/wxapp/distributor/pickuplocation",
                    // SM-F1 扫码进店看"这是谁的店"，同样是登录前的动作
                    "/api/h5app/wxapp/salesperson",
                ).permitAll()
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
                it.requestMatchers("/api/operator/login", "/api/ping", "/api/notify/**").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(JwtOperatorFilter(jwt, operators, mapper), UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint { _, res, _ -> write(res, 401, "Unauthenticated.") }
                    .accessDeniedHandler { _, res, _ -> write(res, 403, "无权访问该资源") }
            }
        return http.build()
    }

    /**
     * M0-8 追加：上传件的静态面。`/storage` 前缀下的资源不属于任何 API 面（没有 JSON 包络、没有令牌语义），
     * 显式建一条 permitAll 链，而不是依赖"没有链匹配就放行"这种隐式行为。
     * 图片 URL 会进商品主图/头像等公开字段，浏览器 <img> 请求不会带令牌。
     */
    @Bean
    @org.springframework.core.annotation.Order(3)
    fun storageChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/storage/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }

    private fun write(res: jakarta.servlet.http.HttpServletResponse, status: Int, message: String) {        res.status = status
        res.contentType = "application/json;charset=UTF-8"
        res.writer.write(mapper.writeValueAsString(ApiError(message, emptyMap(), status, status)))
    }
}
