package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 骨架冒烟端点（M0-3 起被 JwtFilter/SecurityFilterChain 接管）。
 * 返回形状即全局约定：{"data": …}
 */
@RestController
class PingController {
    @GetMapping("/api/ping")
    fun ping(): ApiResponse<Map<String, String>> =
        ApiResponse.ok(mapOf("surface" to "admin", "module" to "ecshopx-web-admin"))
}
