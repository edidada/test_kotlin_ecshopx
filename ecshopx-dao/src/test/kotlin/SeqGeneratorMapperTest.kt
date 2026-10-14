package com.ecshopx.kotlin.dao

import com.ecshopx.kotlin.dao.seq.SeqGeneratorMapper
import org.apache.ibatis.session.Configuration
import org.apache.ibatis.session.SqlSessionFactoryBuilder
import org.h2.jdbcx.JdbcDataSource
import org.mybatis.spring.transaction.SpringManagedTransactionFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** 两步取段（UPDATE 行锁 → 同事务 SELECT 读回）；50 线程无重号端到端用例见 service 模块 */
class SeqGeneratorMapperTest {

    private fun ds(): JdbcDataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:seqmapper${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=250")
        user = "sa"; password = ""
    }

    private fun init(ds: JdbcDataSource) {
        ds.connection.use { c ->
            c.createStatement().execute(
                "CREATE TABLE seq_generator(seq_name VARCHAR(64) PRIMARY KEY, " +
                    "current_value BIGINT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0)",
            )
            c.createStatement().execute("INSERT INTO seq_generator VALUES('order_no',0,0)")
        }
    }

    private fun mapper(ds: JdbcDataSource): SeqGeneratorMapper {
        val cfg = Configuration(org.apache.ibatis.mapping.Environment("test", SpringManagedTransactionFactory(), ds))
        cfg.addMapper(SeqGeneratorMapper::class.java)
        return org.mybatis.spring.SqlSessionTemplate(SqlSessionFactoryBuilder().build(cfg))
            .getMapper(SeqGeneratorMapper::class.java)
    }

    @Test fun `顺序取段每次加步长并读回上界`() {
        val ds = ds().also(::init)
        val tm = org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)
        val mapper = mapper(ds)
        repeat(3) { i ->
            org.springframework.transaction.support.TransactionTemplate(tm).execute {
                assertEquals(1, mapper.takeSegment("order_no", 100))
                assertEquals(100L * (i + 1), mapper.currentValue("order_no"))
            }
        }
    }

    @Test fun `行缺失时 takeSegment 返回 0（SeqGenerator 会 check 报错）`() {
        val ds = ds().also(::init)
        val tm = org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)
        val mapper = mapper(ds)
        org.springframework.transaction.support.TransactionTemplate(tm).execute {
            assertEquals(0, mapper.takeSegment("no_such_seq", 100))
        }
    }
    // 并发取段互斥由 H2/MySQL 行锁本身保证（同事务 UPDATE→SELECT）；
    // 50 线程无重号的端到端验证在 ecshopx-service SeqGeneratorConcurrencyTest。
}
