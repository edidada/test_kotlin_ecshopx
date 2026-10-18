package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.promotion.GroupsTeamMapper
import com.ecshopx.kotlin.service.cart.CartItemNum
import com.ecshopx.kotlin.service.order.OrderCreateForm
import com.ecshopx.kotlin.service.order.OrderCreateService
import com.ecshopx.kotlin.service.order.OrderFlowService
import com.ecshopx.kotlin.service.promotion.GroupsService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1-3 #58 门槛②：**并发成团 / 超员 / 抢池**（docs/05「拼团并发成团/超员」，把 #57 的秒杀池一起压）。
 *
 * 与 M0-6 扣库存、#55 领券同一套断言口径：**恰好 N 笔成功，其余 422，且库里不多留一行**。
 * 三个 CAS 各有自己要防的那条"抢输了一半"的腿：
 *  1. 秒杀 `hold_store += num`（守卫 `活动量 − 已售 − 占用 >= num`）——抢输不能把池子抢穿；
 *  2. 拼团活动 `store -= num` ——抢输不能把额度吃掉；
 *  3. 团席位 `join_person_num < person_num` ——一个席位只坐一个人，而抢输的那笔是在
 *     `deductStore` **之后**才失败的，所以额度必须靠下单事务还回来（D25：不能留下"名额被占、团里却没这人"）。
 *
 * 抢量走服务层（[OrderCreateService.create]，一线程一事务），活动与场次仍从后台端点建，压的是配置出来的真实数据。
 * 失败原因一律收集下来：只统计成功数会把「连接池饥饿」和「CAS 抢输」混成同一个数字
 * （与 [OrderStockConcurrencyTest] 同理由，池子放宽到 64、H2 给 30 秒锁等待）。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccpromooc;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=30000",
        "spring.datasource.hikari.maximum-pool-size=64",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.coupon-expire-initial-delay-ms=3600000",
        "ecx.schedule.coupon-expire-ms=3600000",
        "ecx.schedule.seckill-settle-initial-delay-ms=3600000",
        "ecx.schedule.seckill-settle-ms=3600000",
        "ecx.schedule.groups-settle-initial-delay-ms=3600000",
        "ecx.schedule.groups-settle-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GroupsSeckillConcurrencyTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var create: OrderCreateService
    @Autowired lateinit var flow: OrderFlowService
    @Autowired lateinit var groups: GroupsService
    @Autowired lateinit var teams: GroupsTeamMapper

    private var admin = ""

    /** 拼团：席位团（2 人）/ 限量团（额度 5）/ 结清团（2 人）；秒杀：池子 3 件 */
    private var gSeatAct = 0L
    private var gCapAct = 0L
    private var gSettleAct = 0L
    private var sk = 0L

    private var seatTeam = ""
    private var settleTeam = ""

    // ===== 调用件 =====

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    /**
     * 后台建活动走端点（PUT 是 SK-A4/A6 的正字，用 POST 打会 405）。
     * 只压下单，不压配置，所以这里保持"发一次就得 200"的严格断言。
     */
    private fun adminOf(url: String, body: Map<String, Any?>, m: String = "POST"): JsonNode {
        val req = when (m) {
            "PUT" -> put(url)
            else -> post(url)
        }
        val res = mvc.perform(
            req.header("Authorization", "Bearer $admin")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)),
        ).andReturn()
        assertEquals(200, res.response.status, "admin $m $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun longOf(sql: String, vararg v: Any): Long = jdbc.queryForObject(sql, Long::class.java, *v) ?: -1L

    private fun rows(sql: String, vararg v: Any): Int = (longOf(sql, *v)).toInt()

    private fun gStoreOf(act: Long): Long =
        longOf("SELECT store FROM promotion_groups_activity WHERE groups_activity_id = ?", act)

    private fun teamCol(teamId: String, col: String): Long =
        longOf("SELECT $col FROM promotion_groups_team WHERE team_id = ?", teamId)

    private fun pool(col: String): Long =
        longOf("SELECT $col FROM promotions_seckill_rel_goods WHERE seckill_id = ? AND item_id = ?", sk, ITEM)

    /** 团况一览：超员这种问题必须一眼看出是哪个团坐了几个人 */
    private fun teamsDump(act: Long): String =
        jdbc.queryForList(
            "SELECT team_id, team_status, join_person_num, robot_num FROM promotion_groups_team WHERE act_id = ? ORDER BY id", act,
        ).joinToString(" ") { m -> "${m["TEAM_ID"]}:${m["TEAM_STATUS"]}/n${m["JOIN_PERSON_NUM"]}" }

    // ===== 造数与下单 =====

    private fun createGp(name: String, personNum: Int, store: Long): Long =
        adminOf("/api/groups/activities", linkedMapOf(
            "act_name" to name, "goods_id" to GOODS, "act_price" to 6900, "person_num" to personNum,
            "store" to store, "limit_buy_num" to 0, "limit_time" to 24,
            "begin_time" to TimeSec.now() - 3600, "end_time" to TimeSec.now() + 86400,
            "robot" to false, "instruction" to "并发门槛",
        )).path("groups_activity_id").asLong()

    private fun form(userId: Long, cls: String, act: Long, team: String? = null) = OrderCreateForm(
        cartType = "fastbuy",
        orderType = "normal",
        orderClass = cls,
        actId = act,
        teamId = team,
        items = listOf(CartItemNum(ITEM, 1)),
        receiverName = if (userId == 1L) "张三" else "并发员$userId",
        receiverMobile = "13800000001",
        receiverState = "浙江省", receiverCity = "杭州市", receiverDistrict = "西湖区",
        receiverAddress = "文三路 1 号",
    )

    /** 下单出参里的两个号：`order_id` 是字符串（PHP 同口径），`team_id` 只有拼团有 */
    private fun orderId(res: Map<String, Any?>): Long = (res["order_id"] as String).toLong()

    private fun teamIdOf(res: Map<String, Any?>): String = res["team_id"] as String

    /**
     * 并发压 N 路：返回**成功那几路的产出**（含各自的 uid，取消时要按归属回查）。
     * 失败一律要求是业务拒绝 —— 出现连接池饥饿或 SQL 异常说明压测本身不成立，得先修环境。
     */
    private fun <T : Any> race(times: Int, block: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(times)
        val start = CountDownLatch(1)
        val errs = ConcurrentLinkedQueue<String>()
        val ok = ConcurrentLinkedQueue<T>()
        try {
            val tasks = (0 until times).map { i ->
                executor.submit {
                    start.await()
                    try {
                        ok.add(block(i))
                    } catch (e: Exception) {
                        errs.add("${e::class.simpleName}: ${e.message}")
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(90, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertTrue(errs.all { it.startsWith(BizException::class.simpleName!!) }, "失败必须全是业务拒绝：${errs.take(3)}")
        return ok.toList()
    }

    // ===================== 用例 =====================

    @Test @Order(1) fun `令牌与活动就绪`() {
        val res = mvc.perform(post("/api/operator/login").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(mapOf("username" to "admin", "password" to "admin" + "123"))))
            .andReturn()
        admin = mapper.readTree(utf8(res.response.contentAsByteArray)).path("data").path("token").asText()
        assertTrue(admin.isNotBlank())
        jdbc.update("UPDATE items SET store = 5000, sales = 0 WHERE item_id = ?", ITEM)

        gSeatAct = createGp("并发门槛两人团", personNum = 2, store = 20)
        gCapAct = createGp("并发门槛限量团", personNum = 3, store = 5)
        gSettleAct = createGp("并发门槛成团团", personNum = 2, store = 20)

        sk = adminOf("/api/seckill/activities", linkedMapOf(
            "activity_name" to "并发门槛秒杀", "activity_type" to 1, "ad_pic" to "/img/s.jpg", "distributor_id" to "1",
            "activity_start_time" to TimeSec.now() - 3600, "activity_end_time" to TimeSec.now() + 86400,
            "validity_period" to 10, "limit_money" to 0,
        )).path("seckill_id").asLong()
        // SK-A6 圈品是 PUT（docs/04 §14.7），POST 打会 405
        adminOf("/api/seckill/activities/$sk/goods", mapOf("items" to listOf(linkedMapOf(
            "item_id" to ITEM, "item_name" to "并发圈品", "activity_price" to 5900,
            "activity_store" to 3, "limit_num" to 0, "is_show" to true,
        ))), m = "PUT")
        assertEquals(3L, pool("activity_store"), "池子就 3 件，抢完为止")
    }

    @Test @Order(2) fun `12 人并发挤 2 人团 只坐满 2 个席位 抢输的把额度还回来`() {
        val full = gStoreOf(gSeatAct)
        val head = create.create(1L, 1L, form(1L, "groups", gSeatAct))
        val headOrder = orderId(head)
        seatTeam = teamIdOf(head)
        assertEquals(1L, teamCol(seatTeam, "join_person_num"), "开团者自己坐第一席")
        assertEquals(full - 1, gStoreOf(gSeatAct))

        // 11 个会员并发挤同一个 2 人团：只剩 1 个空席 → 恰好 1 笔成功
        val joined = race(11) { i ->
            val uid = 101L + i
            orderId(create.create(uid, 1L, form(uid, "groups", gSeatAct, seatTeam)))
        }
        assertEquals(1, joined.size, "两人团只再进 1 人｜团况 ${teamsDump(gSeatAct)}")
        assertEquals(2L, teamCol(seatTeam, "join_person_num"), "席位绝不超员")
        assertEquals(2, rows("SELECT COUNT(*) FROM promotion_groups_team_member WHERE team_id = ?", seatTeam),
            "成员行与席位一一对应")
        assertEquals(full - 2, gStoreOf(gSeatAct),
            "抢输席位的 10 笔必须把活动额度回滚，否则会出现「名额用完、团里却没人」｜团况 ${teamsDump(gSeatAct)}")
        assertEquals(2, rows("SELECT COUNT(*) FROM orders_normal_orders WHERE order_class = 'groups' AND act_id = ?", gSeatAct),
            "失败事务不落订单行")

        // 席位已满：再来一个人直接拒，额度与席位都不动
        val storeNow = gStoreOf(gSeatAct)
        val late = runCatching { create.create(300L, 1L, form(300L, "groups", gSeatAct, seatTeam)) }
        assertTrue(late.isFailure, "满员团不能继续进人：${late.exceptionOrNull()?.message}")
        assertTrue((late.exceptionOrNull()?.message ?: "").contains("名额已满"), late.exceptionOrNull()?.message)
        assertEquals(2L, teamCol(seatTeam, "join_person_num"), "被拒的那笔没把席位拱上去")
        assertEquals(storeNow, gStoreOf(gSeatAct), "被拒的那笔也没吃掉额度")
        flow.cancelByUser(1L, headOrder, "并发门槛收尾")
    }

    @Test @Order(3) fun `20 人并发各开新团 额度 5 就只成 5 个团`() {
        val before = gStoreOf(gCapAct)
        val opened = race(20) { i ->
            val uid = 401L + i
            orderId(create.create(uid, 1L, form(uid, "groups", gCapAct)))
        }
        assertEquals(5, opened.size, "活动额度 5 → 恰好 5 单成功｜团况 ${teamsDump(gCapAct)}")
        assertEquals(0L, gStoreOf(gCapAct), "额度抢光后归零，不留下「多占了却没开团」的额度｜团况 ${teamsDump(gCapAct)}")
        assertEquals(5, rows("SELECT COUNT(*) FROM promotion_groups_team WHERE act_id = ?", gCapAct), "一人一团")
        assertEquals(5, rows("SELECT COUNT(*) FROM promotion_groups_team WHERE act_id = ? AND join_person_num = 1", gCapAct),
            "每个新团都只有开团者 1 人")
        assertEquals(5, rows("SELECT COUNT(*) FROM promotion_groups_team_member WHERE act_id = ?", gCapAct), "成员行不重复")
        assertEquals(5, rows("SELECT COUNT(*) FROM orders_normal_orders WHERE order_class = 'groups' AND act_id = ?", gCapAct))

        // 额度为 0 时继续开团：直接拒，团数与订单数不动。
        // 拒单有两条路，都算对：串行到这步时 `activityOffer.available` 预检先挡（"活动名额不足"），
        // 只有在临界并发里预检漏过、`acts.deductStore` CAS 拿 0 行才报"拼团名额已用完"。
        val teamsNow = rows("SELECT COUNT(*) FROM promotion_groups_team WHERE act_id = ?", gCapAct)
        val dry = runCatching { create.create(900L, 1L, form(900L, "groups", gCapAct)) }
        assertTrue(dry.isFailure, "额度用完不能开团")
        assertTrue((dry.exceptionOrNull()?.message ?: "").contains("名额"), dry.exceptionOrNull()?.message)
        assertEquals(before - 5, gStoreOf(gCapAct))
        assertEquals(teamsNow, rows("SELECT COUNT(*) FROM promotion_groups_team WHERE act_id = ?", gCapAct))
    }

    @Test @Order(4) fun `12 单并发抢 3 件秒杀 恰好 3 件占用 取消后全部回池`() {
        val itemStoreBefore = longOf("SELECT store FROM items WHERE item_id = ?", ITEM)
        val won = race(12) { i ->
            val uid = 601L + i
            uid to orderId(create.create(uid, 1L, form(uid, "seckill", sk)))
        }
        assertEquals(3, won.size, "池子 3 件 → 恰好 3 单成功")
        assertEquals(3L, pool("hold_store"), "占用 == 成功笔数")
        assertEquals(0L, pool("sales_store"), "没结清就不算销量")
        assertEquals(0L, pool("activity_store") - pool("sales_store") - pool("hold_store"), "可购归零")
        assertEquals(3, rows("SELECT COUNT(*) FROM orders_normal_orders WHERE order_class = 'seckill' AND act_id = ?", sk),
            "抢输的 9 笔不留订单行")
        assertEquals(itemStoreBefore - 3, longOf("SELECT store FROM items WHERE item_id = ?", ITEM), "商品库存同步只扣 3 件")

        // 抢输之后再单独下一笔：串行时 `activityOffer.available` 预检先挡（"活动名额不足"），
        // 临界并发里预检漏过才由 `seckillGoods.hold` 的 CAS 报"已被抢完"，两条都算拒得对。
        val again = runCatching { create.create(999L, 1L, form(999L, "seckill", sk)) }
        assertTrue(again.isFailure, "池子空了不能再下单")
        val againMsg = again.exceptionOrNull()?.message ?: ""
        assertTrue(againMsg.contains("已被抢完") || againMsg.contains("名额"), againMsg)
        assertEquals(3L, pool("hold_store"))

        // 并发取消：释放走 `hold_store >= num` 守卫，三笔各放一件
        race(won.size) { flow.cancelByUser(won[it].first, won[it].second, "并发门槛取消") }
        assertEquals(0L, pool("hold_store"), "取消后占用清零，池子回满")
        assertEquals(3L, pool("activity_store") - pool("sales_store") - pool("hold_store"), "又能在池子里卖出 3 件")
    }

    @Test @Order(5) fun `两笔尾单并发结清 团只成一次`() {
        val a = create.create(701L, 1L, form(701L, "groups", gSettleAct))
        settleTeam = teamIdOf(a)
        val b = create.create(702L, 1L, form(702L, "groups", gSettleAct, settleTeam))
        val ids = listOf(orderId(a), orderId(b))
        assertEquals(2L, teamCol(settleTeam, "join_person_num"))
        assertEquals(1L, teamCol(settleTeam, "team_status"), "都还没付，团还在进行中")

        // 两笔回调同时到：各自置付，成团判定撞在 transit(1→2) 的 CAS 上
        race(2) { groups.onOrderPaid(ids[it]) }
        assertEquals(2L, teamCol(settleTeam, "team_status"), "凑够人立刻成团")
        assertEquals(2, rows("SELECT COUNT(*) FROM promotion_groups_team_member WHERE team_id = ? AND paid = TRUE", settleTeam),
            "两名成员各自置付，没有谁被漏掉")

        // 重复结清：团状态不回摆，成员行不翻倍
        race(3) { groups.onOrderPaid(ids[0]) }
        assertEquals(2L, teamCol(settleTeam, "team_status"), "已成团的团不因重复回调改动")
        assertEquals(2, rows("SELECT COUNT(*) FROM promotion_groups_team_member WHERE team_id = ?", settleTeam))
        assertTrue(groups.canShip(ids[0]), "成团后放行发货（§14.6 末行的卡口）")
    }

    @Test @Order(6) fun `成团 CAS 十路并发只有一路推得动门`() {
        // 团已是 2：`team_status = 1` 的守卫让 transit(1→2) 一行也改不到
        assertEquals(0L, race(10) { teams.transit(settleTeam, 1, 2).toLong() }.sum(), "已成团的团推不动第二道门")
        assertEquals(2L, teamCol(settleTeam, "team_status"))

        // 现造一个进行中的团：十路并发推同一道门，恰好一行受影响
        val fresh = create.create(703L, 1L, form(703L, "groups", gSeatAct))
        val freshTeam = teamIdOf(fresh)
        val hit = race(10) { teams.transit(freshTeam, 1, 2).toLong() }
        assertEquals(1L, hit.sum(), "两笔同时结清只有一笔能把团推到 2，其余拿 0 行走已成团分支")
        assertEquals(2L, teamCol(freshTeam, "team_status"))
        assertEquals(1L, teamCol(freshTeam, "join_person_num"), "成团不改席位")
        assertEquals(0, teams.leaveSeat(freshTeam), "退席位只在进行中的团上做")
        assertEquals(1L, teamCol(freshTeam, "join_person_num"))
    }

    private companion object {
        const val ITEM = 3L
        const val GOODS = 3L
    }
}
