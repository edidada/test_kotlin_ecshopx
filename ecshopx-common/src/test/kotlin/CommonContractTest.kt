package com.ecshopx.kotlin.common

import com.ecshopx.kotlin.common.api.PageParam
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.domain.MoneyFen
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.domain.toYuanText
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageQueryTest {
    @Test fun `默认值与越界截断`() {
        assertEquals(PageQuery(1, 10), PageQuery.of(null, null))
        assertEquals(1, PageQuery.of(0, 10).page)
        assertEquals(PageQuery.MAX_SIZE, PageQuery.of(1, 9999).pageSize)
    }

    @Test fun `offset 计算`() {
        assertEquals(10L, PageQuery(2, 10).offset)   // page 1 基：第 2 页偏移 10
        assertEquals(20L, PageQuery(3, 10).offset)
    }

    @Test fun `page_size 优先于 pageSize`() {
        val p = PageParam(page = 3, pageSizeCamel = 20, pageSizeSnake = 5)
        assertEquals(PageQuery(3, 5), p.toQuery())
    }
}

class MoneyTimeTest {
    @Test fun `元分互转`() {
        assertEquals(199900L, MoneyFen.fromYuanText("1999.00").fen)
        assertEquals("1999.00", MoneyFen(199900).toYuanText())
        assertEquals(0L, (MoneyFen(100) - MoneyFen(100)).fen)
    }

    @Test fun `14位时间戳前缀可排序`() {
        assertTrue(TimeSec.nowStamp14() in 1L..99_9999_9999_99L)
    }
}

class ApiEnvelopeTest {
    @Test fun `JSON 形状与 PHP data 包络及 total_count list 对齐`() {
        val json = ObjectMapper().writeValueAsString(
            com.ecshopx.kotlin.common.api.PageResult(2, listOf(mapOf("item_id" to 1)))
        )
        assertEquals("""{"total_count":2,"list":[{"item_id":1}]}""", json)
    }
}
