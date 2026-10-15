package com.ecshopx.kotlin.web.test

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.security.RequiresResource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** M0-3 越权用例探针：资源 code 沿用 seed（goods.items.lists 已授权给 admin 的角色） */
@RestController
class PermProbeController {

    @GetMapping("/api/test/perm-probe")
    @RequiresResource("goods.items.lists")
    fun probe(): ApiResponse<Map<String, Int>> = ApiResponse.ok(mapOf("ok" to 1))

    @GetMapping("/api/test/other-probe")
    @RequiresResource("orders.delivery")
    fun other(): ApiResponse<Map<String, Int>> = ApiResponse.ok(mapOf("ok" to 1))
}
