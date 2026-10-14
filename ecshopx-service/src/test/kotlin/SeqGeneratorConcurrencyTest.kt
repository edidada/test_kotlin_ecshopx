package com.ecshopx.kotlin.service

import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.dao.seq.SeqGeneratorMapper
import com.ecshopx.kotlin.service.tx.Db
import com.ecshopx.kotlin.service.seq.SeqGenerator
import org.apache.ibatis.session.Configuration
import org.apache.ibatis.session.SqlSessionFactoryBuilder
import org.h2.jdbcx.JdbcDataSource
import org.mybatis.spring.SqlSessionTemplate
import org.mybatis.spring.transaction.SpringManagedTransactionFactory
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 号段发号器：50 线程并发取号无重号（M0-2 门槛用例），走真实 H2 + MyBatis SqlSessionTemplate（Spring 事务集成） */
class SeqGeneratorConcurrencyTest {

    private fun build(): Pair<SeqGenerator, JdbcDataSource> {
        val ds = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:seqgen${System.nanoTime()};MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"; password = ""
        }
        ds.connection.use { c ->
            c.createStatement().execute(
                "CREATE TABLE seq_generator(seq_name VARCHAR(64) PRIMARY KEY, " +
                    "current_value BIGINT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0)",
            )
            c.createStatement().execute("INSERT INTO seq_generator VALUES('order_no',0,0)")
            c.createStatement().execute("INSERT INTO seq_generator VALUES('trade_no',0,0)")
        }
        // 与生产一致：MyBatis 走 SpringManagedTransactionFactory + 同一 DataSource，mapper 调用加入 Spring 事务
        val cfg = Configuration(org.apache.ibatis.mapping.Environment("test", SpringManagedTransactionFactory(), ds))
        cfg.addMapper(SeqGeneratorMapper::class.java)
        val factory = SqlSessionFactoryBuilder().build(cfg)
        val mapper = SqlSessionTemplate(factory).getMapper(SeqGeneratorMapper::class.java)
        val db = Db(DataSourceTransactionManager(ds))
        return (SeqGenerator(mapper, db, step = 100) to ds)
    }

    @Test fun `50 线程 x 20 次取号 全部唯一`() {
        val (gen, _) = build()
        val nThreads = 50
        val per = 20
        val out = ConcurrentLinkedQueue<Long>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(nThreads)
        repeat(nThreads) {
            Thread {
                start.await()
                repeat(per) { out.add(gen.next(SeqName.ORDER_NO)) }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()
        assertEquals(nThreads * per, out.size, "队列即全部取号")
        assertEquals(out.size, out.toSet().size, "存在重号")
    }

    @Test fun `单号前缀形状 trade 带 T`() {
        val (gen, _) = build()
        val bn = gen.nextBn(SeqName.TRADE_NO)
        assertTrue(bn.startsWith("T"))
        assertEquals(17, bn.length) // T + 16 位数字
    }
}
