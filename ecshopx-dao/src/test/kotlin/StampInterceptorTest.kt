package com.ecshopx.kotlin.dao

import com.ecshopx.kotlin.dao.stamp.Stampable
import com.ecshopx.kotlin.dao.stamp.StampInterceptor
import org.apache.ibatis.mapping.SqlCommandType
import kotlin.test.Test
import kotlin.test.assertEquals

class StampInterceptorTest {

    private data class Fake(override var created: Long = 0L, override var updated: Long = 0L) : Stampable

    private val it1 = StampInterceptor()

    @Test fun `INSERT 填 created 与 updated`() {
        val o = Fake()
        it1.stamp(SqlCommandType.INSERT, o, 1000L)
        assertEquals(1000L, o.created)
        assertEquals(1000L, o.updated)
    }

    @Test fun `INSERT 不覆盖已有 created（upsert 语义）`() {
        val o = Fake(created = 7L, updated = 7L)
        it1.stamp(SqlCommandType.INSERT, o, 1000L)
        assertEquals(7L, o.created)
        assertEquals(1000L, o.updated)
    }

    @Test fun `UPDATE 只动 updated`() {
        val o = Fake(created = 7L)
        it1.stamp(SqlCommandType.UPDATE, o, 2000L)
        assertEquals(7L, o.created)
        assertEquals(2000L, o.updated)
    }

    @Test fun `ParamMap 内的 Stampable 被盖章`() {
        val o = Fake()
        it1.stamp(SqlCommandType.INSERT, mapOf("param1" to o, "step" to 100), 3000L)
        assertEquals(3000L, o.updated)
    }

    @Test fun `可变字典参数含 created 键时直接写字典`() {
        val m = HashMap<String, Any?>()
        m["created"] = 0L
        m["updated"] = 0L
        it1.stamp(SqlCommandType.UPDATE, m, 4000L)
        assertEquals(0L, m["created"])   // UPDATE 不动 created
        assertEquals(4000L, m["updated"])
    }

    @Test fun `SELECT 与 DELETE 不处理`() {
        val o = Fake()
        it1.stamp(SqlCommandType.SELECT, o, 9L)
        it1.stamp(SqlCommandType.DELETE, o, 9L)
        assertEquals(0L, o.updated)
    }
}
