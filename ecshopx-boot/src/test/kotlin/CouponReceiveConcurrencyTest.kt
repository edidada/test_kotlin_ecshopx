package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.service.promotion.CouponService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1-3 #55 门槛：并发领券绝不超发（docs/04 §14.6 / D22）。
 *
 * 与 M0-6 的并发扣库存同一套断言口径：**恰好 N 张成功，其余 422，且发放数==实例数**。
 * 抢量走 `UPDATE promotions_coupon SET send_num = send_num + 1 WHERE coupon_id=? AND status=TRUE
 * AND (total_num=0 OR send_num<total_num)`，抢不到就是 0 行；
 * 抢到量却落实例失败则整笔事务回滚（`send_num` 一起回落），不会留下"凭空少一张"。
 *
 * 券总量 8、并发 30 → 恰好 8 张。限领在这张券上是 0（不限），
 * 所以判定只压 CAS 一条路；限领那一路在 [CouponEndToEndTest] 的 HTTP 面覆盖。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccouponcc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        // 30 线程各要一条连接：默认池 10 会把并发压成连接池饥饿，那不是 CAS 的结论
        "spring.datasource.hikari.maximum-pool-size=64",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
    ],
)
class CouponReceiveConcurrencyTest {

    @Autowired lateinit var coupons: CouponService
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun couponId(): Long {
        val now = System.currentTimeMillis() / 1000
        jdbc.update(
            "INSERT INTO promotions_coupon(company_id, coupon_type, coupon_name, discount_fee, min_order_fee, " +
                "total_num, send_num, valid_type, begin_time, end_time, valid_days, status, get_limit, created, updated) " +
                "VALUES(1, 'cash', '并发抢8张券', 1000, 0, 8, 0, 'range', ?, ?, 0, TRUE, 0, ?, ?)",
            now - 60, now + 86400, now, now,
        )
        return jdbc.queryForObject(
            "SELECT coupon_id FROM promotions_coupon WHERE coupon_name = '并发抢8张券'", Long::class.java,
        ) ?: error("建券失败")
    }

    @Test
    fun `并发 30 人抢 8 张券 恰好 8 张且不超发`() {
        val id = couponId()
        val threads = 30
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val errs = ConcurrentLinkedQueue<String>()
        val codes = ConcurrentLinkedQueue<String>()
        try {
            val results = (1..threads).map { i ->
                pool.submit<Boolean> {
                    start.await()
                    try {
                        codes.add("${coupons.receive(i.toLong(), 1L, id)["coupon_code"]}")
                        true
                    } catch (e: Exception) {
                        // 「抢输了」与「连不上库」必须能区分：混成一个数字就看不出是不是 CAS 生效
                        errs.add("${e::class.simpleName}: ${e.message}")
                        false
                    }
                }
            }
            start.countDown()
            val ok = results.count { it.get(60, TimeUnit.SECONDS) }
            assertEquals(8, ok, "总量 8 → 恰好 8 张成功；失败样本 ${errs.take(5)}（共 ${errs.size} 例）")
            assertTrue(errs.all { it.startsWith(BizException::class.simpleName!!) }, "失败全是业务拒绝：${errs.take(3)}")

            val sendNum = jdbc.queryForObject("SELECT send_num FROM promotions_coupon WHERE coupon_id = ?", Long::class.java, id) ?: -1L
            val rows = jdbc.queryForObject("SELECT COUNT(*) FROM promotions_coupon_user WHERE coupon_id = ?", Long::class.java, id) ?: -1L
            assertEquals(8L, sendNum, "发放计数与成功次数一致")
            assertEquals(8L, rows, "实例行数与发放计数一致（没有抢到量却没落券的半状态）")
            assertEquals(8, codes.distinct().size, "券码不重复")

            // 库存继续为 0 时再来一人：直接拒绝，不动发放数与实例数
            val again = runCatching { coupons.receive(99L, 1L, id) }.isFailure
            assertTrue(again, "领完之后再领必须失败")
            assertEquals(8L, jdbc.queryForObject("SELECT send_num FROM promotions_coupon WHERE coupon_id = ?", Long::class.java, id) ?: -1L)
            assertEquals(8L, jdbc.queryForObject("SELECT COUNT(*) FROM promotions_coupon_user WHERE coupon_id = ?", Long::class.java, id) ?: -1L)
        } finally {
            pool.shutdownNow()
        }
    }

    /** 不限量券（total_num=0）：并发一律放行，且不会因 `send_num<total_num` 判据被误挡 */
    @Test
    fun `不限量券并发全放行`() {
        val now = System.currentTimeMillis() / 1000
        jdbc.update(
            "INSERT INTO promotions_coupon(company_id, coupon_type, coupon_name, discount_fee, min_order_fee, " +
                "total_num, send_num, valid_type, begin_time, end_time, valid_days, status, get_limit, created, updated) " +
                "VALUES(1, 'cash', '不限量券', 500, 0, 0, 0, 'days', 0, 0, 30, TRUE, 0, ?, ?)",
            now, now,
        )
        val id = jdbc.queryForObject("SELECT coupon_id FROM promotions_coupon WHERE coupon_name = '不限量券'", Long::class.java)!!
        val threads = 12
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val ok = (1..threads).map { i ->
                pool.submit<Boolean> {
                    runCatching { coupons.receive(i.toLong(), 1L, id) }.isSuccess
                }
            }.count { it.get(60, TimeUnit.SECONDS) }
            assertEquals(threads, ok, "total_num=0 表示不限量")
            assertEquals(threads.toLong(), jdbc.queryForObject("SELECT send_num FROM promotions_coupon WHERE coupon_id = ?", Long::class.java, id) ?: -1L)
        } finally {
            pool.shutdownNow()
        }
    }
}
