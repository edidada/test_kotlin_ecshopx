package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.service.cart.CartItemNum
import com.ecshopx.kotlin.service.order.OrderCreateForm
import com.ecshopx.kotlin.service.order.OrderCreateService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0-6 门槛①：并发 50 单抢同一库存，绝不超卖。
 *
 * 直接压服务层（HTTP 层并发要额外起容器，验证的是同一件事：CAS 扣减 + 同事务回滚）。
 * 库存 30、并发 50 → 恰好 30 单成功，其余拿到 422；订单表也只有 30 行（失败事务整体回滚，
 * 不会留下"扣了库存没落单"的半状态）。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccstock;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
    ],
)
class OrderStockConcurrencyTest {

    @Autowired lateinit var create: OrderCreateService
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun form() = OrderCreateForm(
        cartType = "fastbuy",
        orderType = "normal",
        items = listOf(CartItemNum(3L, 1L)),
        addressId = 1,
    )

    @Test
    fun `并发 50 单抢 30 库存 不超卖且事务干净`() {
        jdbc.update("UPDATE items SET store = 30, sales = 0 WHERE item_id = 3")
        val threads = 50
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val results = (1..threads).map {
                pool.submit<Boolean> {
                    start.await()
                    try {
                        create.create(1L, 1L, form()); true
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            start.countDown()
            val ok = results.count { it.get(60, TimeUnit.SECONDS) }
            assertEquals(30, ok, "库存 30 → 恰好 30 单成功")
            assertEquals(0L, jdbc.queryForObject("SELECT store FROM items WHERE item_id = 3", Long::class.java))
            assertEquals(30L, jdbc.queryForObject("SELECT sales FROM items WHERE item_id = 3", Long::class.java), "销量不重复计")
            assertEquals(
                30L,
                jdbc.queryForObject("SELECT COUNT(*) FROM orders_normal_orders_items WHERE item_id = 3", Long::class.java),
                "失败事务不留明细行",
            )
            val orders = jdbc.queryForObject("SELECT COUNT(*) FROM orders_normal_orders", Long::class.java) ?: 0L
            val assoc = jdbc.queryForObject("SELECT COUNT(*) FROM orders_associations", Long::class.java) ?: 0L
            val logs = jdbc.queryForObject("SELECT COUNT(*) FROM orders_operate_logs", Long::class.java) ?: 0L
            assertTrue(orders >= 30, "本轮至少 30 单：$orders")
            assertEquals(orders, assoc, "主表与路由表一一对应")
            assertEquals(orders, logs, "每单必有下单留痕")
            // 库存继续为 0 时再来一单：直接 422，不产生脏行
            val before = jdbc.queryForObject("SELECT COUNT(*) FROM orders_normal_orders", Long::class.java) ?: 0L
            val rejected = runCatching { create.create(1L, 1L, form()) }.isFailure
            assertTrue(rejected, "无货下单必须失败")
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM orders_normal_orders", Long::class.java))
        } finally {
            pool.shutdownNow()
        }
    }
}
