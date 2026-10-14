package com.ecshopx.kotlin.common.api

/**
 * 全局成功响应包络 —— 与 PHP 侧 Fractal ResultArraySerializer 一致：{"data": …}
 * 列表载荷用 [PageResult]，即 {"data":{"total_count":N,"list":[…]}}。
 */
data class ApiResponse<T>(val data: T?) {
    companion object {
        fun <T> ok(d: T): ApiResponse<T> = ApiResponse(d)
        val EMPTY: ApiResponse<Map<String, Any?>> = ApiResponse(emptyMap())
    }
}

/** 列表分页载荷；字段名保持 snake_case 与源系统 JSON 对齐 */
data class PageResult<T>(
    val total_count: Long,
    val list: List<T>,
)
