package com.ecshopx.kotlin.service.goods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ItemQuery 归一化单测（元/分、is_sku 展平、distributor/special_type 等易错点）。
 * 与 docs/04 文末勘误对齐：只有价格边界 ×100，库存边界原样。
 */
class ItemQueryTest {

    private fun f(vararg pairs: Pair<String, String?>): MutableMap<String, Any?> =
        ItemQuery.toFilter(mapOf(*pairs), 1L, front = false).second

    @Test
    fun `价格边界按元转分 库存边界按原值`() {
        val m = f("price_gt" to "19.9", "price_lt" to "1000", "store_gt" to "5", "store_lt" to "50")
        assertEquals(1990L, m["priceGt"])
        assertEquals(100000L, m["priceLt"])
        assertEquals(5L, m["storeGt"])
        assertEquals(50L, m["storeLt"])
    }

    @Test
    fun `默认只看主行 is_sku=true 才放开`() {
        assertEquals(true, f()["defaultOnly"])
        assertNull(f("is_sku" to "true")["defaultOnly"])
    }

    @Test
    fun `未指定 item_type 时排除 services`() {
        assertEquals(true, f()["excludeServices"])
        assertNull(f("item_type" to "services")["excludeServices"])
        assertEquals("services", f("item_type" to "services")["itemType"])
    }

    @Test
    fun `分页双写法与上限`() {
        val (pq, _) = ItemQuery.toFilter(mapOf("page" to "2", "page_size" to "30"), 1L, false)
        assertEquals(2, pq.page)
        assertEquals(30, pq.pageSize)
        val (pq2, _) = ItemQuery.toFilter(mapOf("page" to "1", "page_size" to "9999"), 1L, false)
        assertEquals(100, pq2.pageSize)
    }

    @Test
    fun `rebate 0 与正数分支`() {
        assertEquals(true, f("rebate" to "0")["rebateZero"])
        assertEquals(true, f("rebate" to "2")["rebatePos"])
        assertFalse(f("rebate" to "9").containsKey("rebateZero"))
    }

    @Test
    fun `distributor_id 三态`() {
        assertNull(f("distributor_id" to "all_distributor")["distributorIds"])
        assertEquals(listOf(7L), f("distributor_id" to "7")["distributorIds"])
        assertEquals(listOf(7L, 8L), f("distributor_id" to "7, 8")["distributorIds"])
        assertNull(f()["distributorIds"])
    }

    @Test
    fun `预警与特殊类目走布尔或枚举开关`() {
        assertEquals(true, f("is_warning" to "true")["isWarning"])
        assertEquals(false, f("is_warning" to "false")["isWarning"])
        assertEquals("drug", f("special_type" to "drug")["specialType"])
        assertEquals(1L, f("type" to "1")["type"])
    }

    @Test
    fun `category 展开交给注入的展开器 与 CategoryService 解耦`() {
        val expanded = ItemQuery.toFilter(mapOf("category" to "1,3"), 1L, front = false) { setOf(it, it * 10) }.second
        assertEquals(listOf(1L, 10L, 3L, 30L), expanded["categoryIds"])
        val none = ItemQuery.toFilter(emptyMap(), 1L, front = false).second
        assertNull(none["categoryIds"])
    }

    @Test
    fun `前台强制只出在售`() {
        val back = ItemQuery.toFilter(mapOf("approve_status" to "instock"), 1L, front = false).second
        val front = ItemQuery.toFilter(mapOf("approve_status" to "instock"), 1L, front = true).second
        assertEquals("instock", back["approveStatus"], "后台尊重入参")
        assertEquals("onsale", front["approveStatus"], "前台一律在售")
    }

    @Test
    fun `非法 id 抛业务校验异常`() {
        val ex = runCatching { ItemQuery.requireId("abc") }.exceptionOrNull()
        assertTrue(ex is com.ecshopx.kotlin.common.api.BizException)
        assertEquals(123L, ItemQuery.requireId("123"))
    }
}
