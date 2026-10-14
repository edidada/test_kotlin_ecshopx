package com.ecshopx.kotlin.service

import com.ecshopx.kotlin.service.tx.Db
import org.h2.jdbcx.JdbcDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Db.transactional DSL 回滚 + afterCommit 语义（M0-2 门槛用例） */
class DbTest {

    private fun newDb(): Pair<Db, JdbcDataSource> {
        val ds = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:dbtest${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        DataSourceUtils.getConnection(ds).use { it.createStatement().execute("CREATE TABLE t(id INT PRIMARY KEY)") }
        DataSourceUtils.releaseConnection(DataSourceUtils.getConnection(ds), ds)
        return Db(DataSourceTransactionManager(ds)) to ds
    }

    private fun insert(ds: JdbcDataSource, id: Int) {
        // 走 Spring 事务绑定的连接，才能被回滚
        DataSourceUtils.getConnection(ds).createStatement().execute("INSERT INTO t VALUES($id)")
    }

    private fun count(ds: JdbcDataSource): Int =
        DataSourceUtils.getConnection(ds).use { conn ->
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM t").let { q -> q.next(); q.getInt(1) }
        }

    @Test fun `块内异常触发回滚 已写入行丢失`() {
        val (db, ds) = newDb()
        assertFailsWith<IllegalStateException> {
            db.transactional {
                insert(ds, 1)
                throw IllegalStateException("boom")
            }
        }
        assertEquals(0, count(ds))
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "事务资源应已清理")
    }

    @Test fun `正常结束提交`() {
        val (db, ds) = newDb()
        db.transactional { insert(ds, 2) }
        assertEquals(1, count(ds))
    }

    @Test fun `REQUIRES_NEW 独立提交 外层回滚不影响`() {
        val (db, ds) = newDb()
        assertFailsWith<IllegalStateException> {
            db.transactional {
                db.transactional(TransactionDefinition.PROPAGATION_REQUIRES_NEW) { insert(ds, 3) }
                throw IllegalStateException("outer rollback")
            }
        }
        assertEquals(1, count(ds), "内层新事务应已提交")
    }

    @Test fun `afterCommit 仅在提交后执行`() {
        val (db, _) = newDb()
        var fired = false
        db.transactional {
            db.afterCommit { fired = true }
            assertFalse(fired, "提交前不得触发")
        }
        assertTrue(fired, "提交后应触发")

        var fired2 = false
        assertFailsWith<IllegalStateException> {
            db.transactional {
                db.afterCommit { fired2 = true }
                throw IllegalStateException("boom")
            }
        }
        assertFalse(fired2, "回滚不得触发 afterCommit")
    }
}
