package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class FrontPingController {
    @GetMapping("/api/h5app/ping")
    fun ping(): ApiResponse<Map<String, String>> =
        ApiResponse.ok(mapOf("surface" to "front", "module" to "ecshopx-web-front"))
}
