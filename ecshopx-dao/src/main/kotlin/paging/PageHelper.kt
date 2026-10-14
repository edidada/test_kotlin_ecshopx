package com.ecshopx.kotlin.dao.paging

import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult

/**
 * 分页执行模型：显式 count + data 双语句（docs/03-详细设计文档.md §4.2）。
 * 不依赖 ThreadLocal，异步/嵌套调用不会丢页；count 与 data 共用同一段 WHERE（Mapper XML <sql> include）。
 */
object PageHelper {

    /** 不做 DO->VO 转换的常用形态 */
    fun <E> paginate(
        pq: PageQuery,
        count: (() -> Long?)? = null,
        query: (offset: Long, limit: Int) -> List<E>,
    ): PageResult<E> = paginate(pq, count, query, map = { it })

    /**
     * @param count 返回总数；传 `null` 表示跳过 count（游标式列表），此时 total_count 给出保守估计
     *              （offset + 本页条数，若本页满载则 +1 提示"可能还有"），与源系统不分页接口行为兼容。
     * @param query offset/limit 数据查询
     * @param map   DO -> VO 转换（批量回捞在此做，防 N+1）
     */
    fun <E, T> paginate(
        pq: PageQuery,
        count: (() -> Long?)? = null,
        query: (offset: Long, limit: Int) -> List<E>,
        map: (List<E>) -> List<T>,
    ): PageResult<T> {
        val total: Long? = count?.invoke()
        if (total == 0L) return PageResult(0L, emptyList())
        val rows = query(pq.offset, pq.pageSize)
        val estimated =
            if (total != null) total
            else pq.offset + rows.size + if (rows.size == pq.pageSize) 1L else 0L
        return PageResult(estimated, map(rows))
    }
}
