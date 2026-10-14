package com.ecshopx.kotlin.common.api

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 分页入参模型（详见 docs/03-详细设计文档.md §4.1）。
 * page 1 基；pageSize 上限 MAX_SIZE，越界截断而非报错（源系统 50，放宽至 100）。
 */
data class PageQuery(val page: Int, val pageSize: Int) {
    companion object {
        const val MAX_SIZE = 100
        const val DEFAULT_SIZE = 10

        fun of(page: Int?, pageSize: Int?, defaultSize: Int = DEFAULT_SIZE): PageQuery = PageQuery(
            page?.coerceAtLeast(1) ?: 1,
            (pageSize ?: defaultSize).coerceIn(1, MAX_SIZE),
        )
    }

    val offset: Long get() = (page - 1).toLong() * pageSize
}

/**
 * Controller 绑定用：同时收源系统并存的两种写法 pageSize / page_size（后者优先级见 URL 文档 §1）。
 */
data class PageParam(
    val page: Int? = null,
    @JsonProperty("pageSize") val pageSizeCamel: Int? = null,
    @JsonProperty("page_size") val pageSizeSnake: Int? = null,
) {
    fun toQuery(default: Int = PageQuery.DEFAULT_SIZE): PageQuery =
        PageQuery.of(page, pageSizeSnake ?: pageSizeCamel, default)
}
