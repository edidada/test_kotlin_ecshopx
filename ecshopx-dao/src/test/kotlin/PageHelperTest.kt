package com.ecshopx.kotlin.dao

import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.dao.paging.PageHelper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 分页三态（M0-2 门槛用例）：带总数 / 总数 0 短路 / 无 count 游标估计 */
class PageHelperTest {

    private val rows = (1L..25L).toList()

    @Test fun `带 count 正常分页`() {
        val r = PageHelper.paginate(
            PageQuery(2, 10),
            count = { 25L },
            query = { offset, limit -> rows.drop(offset.toInt()).take(limit) },
        )
        assertEquals(25L, r.total_count)
        assertEquals((11L..20L).toList(), r.list)
    }

    @Test fun `count 为 0 时不发数据查询`() {
        var queried = false
        val r = PageHelper.paginate(PageQuery(1, 10), count = { 0L }) { _, _ -> queried = true; rows }
        assertEquals(0L, r.total_count)
        assertEquals(emptyList(), r.list)
        assertFalse(queried)
    }

    @Test fun `无 count 游标估计 本页满则多 1`() {
        val full = PageHelper.paginate(PageQuery(1, 10), count = null) { _, limit -> rows.take(limit) }
        assertEquals(11L, full.total_count) // 10 条 + 提示"可能还有"
        val last = PageHelper.paginate(PageQuery(3, 10), count = null) { _, limit -> rows.drop(20).take(limit) }
        assertEquals(25L, last.total_count) // offset 20 + 5 条，末页不加 1
    }

    @Test fun `DO 到 VO 映射钩子`() {
        val r = PageHelper.paginate(
            PageQuery(1, 5),
            count = { 25L },
            query = { o, l -> rows.drop(o.toInt()).take(l) },
            map = { list -> list.map { mapOf("item_id" to it) } },
        )
        assertEquals(5, r.list.size)
        assertEquals(1L, r.list.first()["item_id"])
    }
}
