package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.AftersalesContract
import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.service.aftersales.AftersalesService
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
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1-1 DoD：售后域端到端（AF-F1..7 + AF-A1..10）与「申请→审核→回寄→确认收货→退款确认→原路退」全链路。
 *
 * 门槛用例（docs/05 §M1-1）在第 10 例：**部分退款多次申请** + **剩余可退额守恒**，
 * 两道闸门（`trade.refunded_fee ≤ pay_fee`、`行 refunded_fee ≤ 行 total_fee`）都用改窄额度的方式各撞一次，
 * 撞完之后额度必须回到原位（事务整体回滚），退款单留在 READY 等重试。
 *
 * 演示数据：会员 user_id=1、商品 item_id=3（每日坚果礼盒 8900 分）、地址 address_id=1、默认包邮模板。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccafs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AftersalesEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var aftersalesService: AftersalesService

    private var tok = ""
    private var other = ""
    private var admin = ""
    private var viewer = ""

    private var orderA = ""
    private var bnA = ""
    private var refundBnA = ""
    private var orderMulti = ""
    private var bnMultiSecond = ""
    private var orderDeposit = ""
    private var orderGate = ""
    private var orderGoods = ""
    private var bnGoods = ""
    private var refundBnGoods = ""
    private var adminApplyBn = ""

    // ---------- 调用件 ----------

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            else -> put(url)
        }
        token?.let { req.header("Authorization", "Bearer $it") }
        body?.let { req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)) }
        return mvc.perform(req).andReturn()
    }

    private fun data(res: MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun ok(url: String, body: Map<String, Any?>? = null, asMember: String = tok): JsonNode =
        data(call(if (body == null) "GET" else "POST", url, asMember, body), url)

    private fun okAdmin(method: String, url: String, body: Map<String, Any?>? = null): JsonNode =
        data(call(method, url, admin, body), "admin $method $url")

    /** 非 200 断言：默认按面挑令牌（后台面 operator JWT，其余会员 JWT） */
    private fun expect(method: String, url: String, body: Map<String, Any?>?, code: Int, token: String? = adminTokenFor(url)): JsonNode {
        val res = call(method, url, token, body)
        assertEquals(code, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }

    private fun adminTokenFor(url: String): String =
        if (url.startsWith("/api/aftersales") || url.startsWith("/api/refund")) admin else tok

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    // ---------- 业务便捷件 ----------

    private fun newOrder(num: Int = 1): String = ok(
        "/api/h5app/wxapp/order_new",
        mapOf(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to 1,
            "items" to listOf(mapOf("item_id" to 3, "num" to num)),
        ),
    ).path("order_id").asText()

    private fun notify(tradeId: String, fee: Long): String = utf8(
        mvc.perform(
            post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId).param("total_fee", fee.toString())
                .param("transaction_id", "WX-$tradeId").param("trade_state", "SUCCESS"),
        ).andReturn().response.contentAsByteArray,
    )

    /** wxpay 结清：不依赖账户余额，作为售后用例的默认成交方式 */
    private fun paidOrder(num: Int = 1): String {
        val o = newOrder(num)
        val d = ok("/api/h5app/wxapp/payment", mapOf("order_id" to o, "pay_type" to "wxpay"))
        assertTrue(notify(d.path("trade_id").asText(), d.path("total_fee").asLong()).contains("SUCCESS"))
        return o
    }

    /** 给订单挂上佣金/返利，退款时按行货款占比冲销 */
    private fun giveCommission(orderId: String, commission: Long, rebate: Long) {
        jdbc.update(
            "UPDATE orders_normal_orders_items SET commission_fee = ?, total_rebate = ? WHERE order_id = ?",
            commission, rebate, orderId.toLong(),
        )
        jdbc.update(
            "UPDATE orders_normal_orders SET commission_fee = ?, total_rebate = ? WHERE order_id = ?",
            commission, rebate, orderId.toLong(),
        )
    }

    private fun applyAf(
        orderId: String,
        num: Int = 1,
        type: String = "ONLY_REFUND",
        fee: Long? = null,
        extra: Map<String, Any?> = emptyMap(),
        asMember: String = tok,
    ): JsonNode {
        val body = linkedMapOf<String, Any?>(
            "order_id" to orderId, "item_id" to 3, "num" to num, "aftersales_type" to type,
            "reason" to "质量问题", "description" to "开箱即损",
        )
        fee?.let { body["refund_fee"] = it }
        body.putAll(extra)
        return ok("/api/h5app/wxapp/aftersales", body, asMember)
    }

    private fun approve(bn: String, fee: Long? = null): JsonNode {
        val body = linkedMapOf<String, Any?>("aftersales_bn" to bn, "is_approved" to 1)
        fee?.let { body["refund_fee"] = it }
        return okAdmin("POST", "/api/aftersales/review", body)
    }

    private fun refuse(bn: String, reason: String = "凭证不足"): JsonNode =
        okAdmin("POST", "/api/aftersales/review", mapOf("aftersales_bn" to bn, "is_approved" to 0, "refuse_reason" to reason))

    private fun checkRefund(bn: String, check: Int = 1, fee: Long? = null, memo: String? = null): JsonNode {
        val body = linkedMapOf<String, Any?>("aftersales_bn" to bn, "check_refund" to check)
        fee?.let { body["refund_fee"] = it }
        memo?.let { body["refunds_memo"] = memo }
        return okAdmin("POST", "/api/aftersales/refundCheck", body)
    }

    /** 申请 → 同意 → 退款确认 一条龙（仅退款单） */
    private fun refundThrough(bn: String): JsonNode {
        approve(bn)
        return checkRefund(bn)
    }

    /** 退款确认自己的守门：未到待退款不能确认，金额不能超过申请额 */
    private fun checkRefundGuard(bn: String, orderId: String) {
        expect("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 1), 422)
        approve(bn)
        expect("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 1, "refund_fee" to 99999), 422)
        assertEquals(0L, tradeVal(orderId, "refunded_fee").toLong(), "被拒的确认不占额度")
    }

    private fun applyReadFee(bn: String): Long = ok("/api/h5app/wxapp/aftersales/$bn").path("refund_fee").asLong()

    // ---------- DB 读口径 ----------

    private fun afVal(bn: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM aftersales WHERE aftersales_bn = ?", Any::class.java, bn.toLong()).toString()

    private fun orderVal(orderId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM orders_normal_orders WHERE order_id = ?", Any::class.java, orderId.toLong()).toString()

    private fun itemVal(orderId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM orders_normal_orders_items WHERE order_id = ?", Any::class.java, orderId.toLong()).toString()

    private fun tradeVal(orderId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM trade WHERE order_id = ? AND trade_state = 'SUCCESS'", Any::class.java, orderId).toString()

    private fun latestRefundBn(bn: String): String =
        jdbc.queryForObject(
            "SELECT refund_bn FROM aftersales_refund WHERE aftersales_bn = ? ORDER BY created DESC, refund_bn DESC LIMIT 1",
            String::class.java, bn.toLong(),
        )!!

    private fun refundVal(rbn: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM aftersales_refund WHERE refund_bn = ?", Any::class.java, rbn.toLong()).toString()

    private fun detailVal(bn: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM aftersales_detail WHERE aftersales_bn = ?", Any::class.java, bn.toLong()).toString()

    private fun balance(): Long = jdbc.queryForObject(
        "SELECT COALESCE(SUM(deposit_amount + gift_amount), 0) FROM user_deposit WHERE user_id = 1", Long::class.java,
    )!!

    private fun point(): Long = jdbc.queryForObject("SELECT point FROM point_member WHERE user_id = 1", Long::class.java)!!

    // ---------- 用例 ----------

    @Test @Order(1) fun `令牌就绪与原因字典`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        other = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "other").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null, mapOf("username" to "admin", "password" to "admin" + "123")), "admin")
            .path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        assertTrue(tok.isNotBlank() && other.isNotBlank() && admin.isNotBlank() && viewer.isNotBlank())
        // 演示账户要有钱有分，后面的余额/积分退款用例才有时序意义
        jdbc.update("UPDATE user_deposit SET deposit_amount = 500000, gift_amount = 0 WHERE user_id = 1")
        jdbc.update("UPDATE point_member SET point = 200000, total_point = 200000 WHERE user_id = 1")

        val r = ok("/api/h5app/wxapp/aftersales/reason/list")
        assertEquals(listOf("质量问题", "商品与描述不符", "不想要了", "其他原因"), r.path("list").map { it.asText() }, "AF-F7 读 sys_config")
        expect("GET", "/api/h5app/wxapp/aftersales/reason/list", null, 401, token = null)
    }

    @Test @Order(2) fun `AF-F1 申请：未付款拒、成交后双表落地`() {
        val unpaid = newOrder()
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to unpaid, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND"), 422)

        orderA = paidOrder()
        val row = applyAf(orderA)
        assertContract(row, AftersalesContract.APPLY_MIN, "AF-F1 申请出参")
        bnA = row.path("aftersales_bn").asText()
        assertEquals(16, bnA.length, "AS 号段单号是 16 位串")
        assertEquals(0, row.path("aftersales_status").asInt(), "0=待处理")
        assertEquals(0, row.path("progress").asInt(), "0=等待商家处理")
        assertEquals(8900L, row.path("refund_fee").asLong(), "缺省退款额=按件分摊值")
        assertEquals(1, row.path("can_apply").asInt())

        assertEquals(8900L, detailVal(bnA, "refund_fee").toLong(), "明细与主表同额")
        assertEquals(afVal(bnA, "refund_fee"), detailVal(bnA, "refund_fee"), "主表-明细同一份账")
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_detail WHERE aftersales_bn = ?", Int::class.java, bnA.toLong()))
        assertEquals(afVal(bnA, "aftersales_status"), detailVal(bnA, "aftersales_status"), "明细与主表同状态")
        assertEquals(0, orderVal(orderA, "left_aftersales_num").toInt(), "1 件的行占用 1 件后镜像归零")
        assertEquals("WAIT_SELLER_AGREE", itemVal(orderA, "aftersales_status"), "订单行售后态回写")
        assertEquals(
            1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders_operate_logs WHERE order_id = ? AND behavior = 'aftersales_apply'",
                Int::class.java, orderA.toLong(),
            ), "申请必须留痕",
        )
    }

    @Test @Order(3) fun `AF-F1 入参边界`() {
        val o = paidOrder(2)
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "EXCHANGING_GOODS"), 501)
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 0, "aftersales_type" to "ONLY_REFUND"), 400)
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "UNKNOWN"), 400)
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 999999, "num" to 1, "aftersales_type" to "ONLY_REFUND"), 404)
        // 件数守恒：2 件的行申请 3 件
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 3, "aftersales_type" to "ONLY_REFUND"), 422)
        // 金额守恒：2 件行总额 17800，申请退 17801
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 2, "aftersales_type" to "ONLY_REFUND", "refund_fee" to 17801), 422)
        // 退货退款 + 上门取件必须给联系人和电话
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "REFUND_GOODS", "return_type" to "offline"), 400)
        // 同一行可以有多笔在途申请（额度没吃完就不该拦）
        val first = applyAf(o, num = 1).path("aftersales_bn").asText()
        val second = applyAf(o, num = 1).path("aftersales_bn").asText()
        assertTrue(first != second, "两次申请两张单")
        assertEquals(0, orderVal(o, "left_aftersales_num").toInt(), "2 件已全部占用")
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND"), 403, token = other)
        orderMulti = o
        bnMultiSecond = second
        assertEquals(17800L, ok("/api/h5app/wxapp/aftersales/$first").path("refund_fee").asLong() +
            ok("/api/h5app/wxapp/aftersales/$second").path("refund_fee").asLong(), "两笔申请合计正好等于行货款")
    }

    @Test @Order(4) fun `AF-F6 单件可退额与守恒读口径`() {
        val d = ok("/api/h5app/wxapp/aftersales/item/price?order_id=$orderMulti&item_id=3")
        assertContract(d, AftersalesContract.ITEM_PRICE, "AF-F6")
        assertEquals(8900L, d.path("price").asLong(), "floor(17800/2)")
        assertEquals(2L, d.path("num").asLong())
        assertEquals(2L, d.path("applied_num").asLong(), "两张在途单各占 1 件")
        assertEquals(0L, d.path("can_num").asLong())
        assertEquals(0L, d.path("can_fee").asLong(), "额度已被两笔申请吃完")
        expect("GET", "/api/h5app/wxapp/aftersales/item/price?order_id=$orderA&item_id=3", null, 403, token = other)
        expect("GET", "/api/h5app/wxapp/aftersales/item/price?order_id=999999999999999&item_id=3", null, 404)
    }

    @Test @Order(5) fun `AF-F2 列表与 AF-F3 详情契约`() {
        val l = ok("/api/h5app/wxapp/aftersales?page=1&pageSize=20")
        assertContract(l, AftersalesContract.LIST, "AF-F2 信封")
        assertTrue(l.path("total_count").asLong() >= 2, l.path("total_count").asText())
        assertContract(l.path("list")[0], AftersalesContract.AF_ROW, "AF-F2 行")
        // 掩码：出参不得回显完整手机号（D9）
        val mobile = l.path("list")[0].path("mobile").asText()
        assertTrue(mobile.contains("*") && !mobile.matches(Regex("1[3-9]\\d{9}")), "手机号必须掩码：$mobile")

        val d = ok("/api/h5app/wxapp/aftersales/$bnA")
        assertContract(d, AftersalesContract.FRONT_DETAIL, "AF-F3 详情")
        assertContract(d.path("detail")[0], AftersalesContract.DETAIL_ROW, "AF-F3 detail[]")
        assertContract(d.path("order_info"), AftersalesContract.ORDER_INFO, "AF-F3 order_info")
        assertTrue(d.path("refund_info").isNull, "审核前不得有退款单（未审先退）")
        assertEquals(8900L, d.path("order_info").path("pay_fee").asLong())

        val ad = okAdmin("GET", "/api/aftersales/$bnA")
        assertContract(ad, AftersalesContract.ADMIN_DETAIL, "AF-A2 详情")
        assertEquals(listOf("review", "remark"), ad.path("buttons").map { it.asText() }, "待处理单只有审核与备注可点")
    }

    @Test @Order(6) fun `AF-A3 驳回：额度与件数回滚`() {
        expect("POST", "/api/aftersales/review", mapOf("aftersales_bn" to bnMultiSecond, "is_approved" to 0), 400)
        val r = refuse(bnMultiSecond)
        assertEquals(3, r.path("aftersales_status").asInt(), "3=已驳回")
        assertEquals(3, r.path("progress").asInt())
        assertEquals("已驳回", r.path("status_msg").asText())
        assertEquals(1, orderVal(orderMulti, "left_aftersales_num").toInt(), "驳回把件数还回订单")
        assertEquals(3, detailVal(bnMultiSecond, "aftersales_status").toInt(), "明细与主表同状态")
        // 已驳回不占额度：同一行可以重新申请（OCCUPYING_IN 口径）
        val again = applyAf(orderMulti, num = 1).path("aftersales_bn").asText()
        assertEquals(0, orderVal(orderMulti, "left_aftersales_num").toInt())
        assertTrue(again.isNotBlank() && again != bnMultiSecond, "驳回后重新申请是新单")
    }

    @Test @Order(7) fun `AF-A3 同意仅退款：进入待退款并生成 READY 退款单`() {
        val d = approve(bnA)
        assertEquals(1, d.path("aftersales_status").asInt(), "1=处理中（钱没退完不能写已处理）")
        assertEquals(8, d.path("progress").asInt(), "8=商家已收货，待退款")
        val rbn = latestRefundBn(bnA)
        assertEquals("READY", refundVal(rbn, "refund_status"))
        assertEquals(8900L, refundVal(rbn, "refund_fee").toLong())
        assertEquals(0L, refundVal(rbn, "refunded_fee").toLong(), "生成退款单不等于退钱")
        assertEquals(orderA, refundVal(rbn, "order_id"))
        assertEquals("original", refundVal(rbn, "refund_channel"))
        assertEquals(0L, tradeVal(orderA, "refunded_fee").toLong(), "审核阶段不动支付单")
        assertEquals("WAIT_SELLER_CONFIRM_GOODS", itemVal(orderA, "aftersales_status"))
        assertEquals(
            1, jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_refund WHERE aftersales_bn = ?", Int::class.java, bnA.toLong()),
            "同意退款只生成一张退款单",
        )
        // 重复审核：终态保护，给出 422 而不是第二张退款单
        expect("POST", "/api/aftersales/review", mapOf("aftersales_bn" to bnA, "is_approved" to 1), 422)
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_refund WHERE aftersales_bn = ?", Int::class.java, bnA.toLong()))
        refundBnA = rbn
    }

    @Test @Order(8) fun `AF-A4 余额渠道原路退：账户回补与订单终态`() {
        val o = newOrder()
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to o, "pay_type" to "deposit"))
        assertEquals("PAYED", orderVal(o, "pay_status"))
        val before = balance()
        assertEquals(
            8900L, jdbc.queryForObject("SELECT amount + gift_amount FROM deposit_trade WHERE rel_id = ? AND trade_type = 'consume'", Long::class.java, o)!!,
            "余额消费必须留痕，退款按这条留痕分本金/赠金",
        )
        val bn = applyAf(o).path("aftersales_bn").asText()
        checkRefundGuard(bn, o)   // 里面已经把本单审核进待退款
        giveCommission(o, 800, 300)
        checkRefund(bn)
        assertEquals(before + 8900, balance(), "余额原路回补")
        assertEquals(0L, jdbc.queryForObject("SELECT gift_amount FROM deposit_trade WHERE rel_id = ? AND trade_type = 'refund'", Long::class.java, o).toString().toLong(), "无赠金消费则全额回本金")
        assertEquals(2, afVal(bn, "aftersales_status").toInt(), "2=已处理")
        assertEquals(4, afVal(bn, "progress").toInt(), "4=已处理终态")
        assertEquals("REFUND", orderVal(o, "pay_status"), "整单退完 → REFUND")
        assertEquals("SUCCESS", refundVal(latestRefundBn(bn), "refund_status"))
        assertEquals(8900L, refundVal(latestRefundBn(bn), "refunded_fee").toLong())
        assertEquals("DEPOSITREF${latestRefundBn(bn)}", refundVal(latestRefundBn(bn), "refund_id"))
        assertEquals(8900L, tradeVal(o, "refunded_fee").toLong())
        assertEquals("SUCCESS", tradeVal(o, "trade_state"), "D10：售后不改支付单状态")
        assertEquals(8900L, itemVal(o, "refunded_fee").toLong())
        assertEquals("REFUND_SUCCESS", itemVal(o, "aftersales_status"))
        assertEquals(0L, orderVal(o, "commission_fee").toLong(), "整行退完 → 佣金全额冲销")
        assertEquals(0L, orderVal(o, "total_rebate").toLong(), "返利同比例冲销")
        orderDeposit = o
    }

    @Test @Order(9) fun `AF-A4 拒绝退款：售后 3-5 且退款单 REFUSE`() {
        val o = paidOrder()
        val bn = applyAf(o).path("aftersales_bn").asText()
        approve(bn)
        expect("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 0), 400)
        val r = checkRefund(bn, check = 0, memo = "商品完好，不符合退款条件")
        assertEquals("REFUSE", r.path("refund_status").asText())
        assertEquals(3, afVal(bn, "aftersales_status").toInt())
        assertEquals(5, afVal(bn, "progress").toInt(), "5=退款驳回（确认收货后拒退）")
        assertEquals(1, orderVal(o, "left_aftersales_num").toInt(), "拒退把件数还回去")
        assertEquals("SELLER_REFUSE_BUYER", itemVal(o, "aftersales_status"))
        assertEquals(0L, tradeVal(o, "refunded_fee").toLong(), "拒退不动额度")
        expect("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 1), 422, token = admin)
    }

    @Test @Order(10) fun `门槛：部分退款多次申请与剩余可退额两道闸门`() {
        val o = paidOrder(3)
        orderGate = o
        giveCommission(o, 900, 600)
        // 第 1 笔：1 件 8900，全额退成功（行货款 26700，佣金按 1/3 冲销）
        val b1 = applyAf(o, num = 1).path("aftersales_bn").asText()
        refundThrough(b1)
        assertEquals(8900L, tradeVal(o, "refunded_fee").toLong())
        assertEquals(600L, orderVal(o, "commission_fee").toLong(), "冲 300 = 900*8900/26700")
        assertEquals(400L, orderVal(o, "total_rebate").toLong())
        // 第 2 笔：再来 1 件
        val b2 = applyAf(o, num = 1).path("aftersales_bn").asText()
        refundThrough(b2)
        assertEquals(17800L, tradeVal(o, "refunded_fee").toLong(), "两次部分退款累加")
        assertEquals(17800L, itemVal(o, "refunded_fee").toLong())
        assertEquals(300L, orderVal(o, "commission_fee").toLong(), "两次各冲 300，行佣金还没吃完")
        assertEquals(1, orderVal(o, "left_aftersales_num").toInt(), "3 件已退 2 件")
        // 申请侧：剩 1 件、可退 8900，超一分都不行
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND", "refund_fee" to 8901), 422)
        val b3 = applyAf(o, num = 1).path("aftersales_bn").asText()
        approve(b3)

        // 闸门一（支付侧）：把实付压到已退金额，第 3 笔退不出去
        jdbc.update("UPDATE trade SET pay_fee = 17800 WHERE order_id = ? AND trade_state = 'SUCCESS'", o)
        val g1 = expect("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to b3, "check_refund" to 1), 422)
        assertTrue(g1.path("message").asText().contains("超出本单剩余可退额"), g1.toString())
        assertEquals(17800L, tradeVal(o, "refunded_fee").toLong(), "撞闸门后额度回到原位")
        assertEquals("READY", refundVal(latestRefundBn(b3), "refund_status"), "失败的确认退回 READY，可重试")
        assertEquals(1, afVal(b3, "aftersales_status").toInt(), "售后单留在处理中")
        assertEquals(300L, orderVal(o, "commission_fee").toLong(), "失败的确认不冲佣金")

        // 闸门二（订单行侧）：支付侧有余量，行货款不够
        jdbc.update("UPDATE trade SET pay_fee = 26700 WHERE order_id = ? AND trade_state = 'SUCCESS'", o)
        jdbc.update("UPDATE orders_normal_orders_items SET total_fee = 25000 WHERE order_id = ?", o.toLong())
        val g2 = expect("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to b3, "check_refund" to 1), 422)
        assertTrue(g2.path("message").asText().contains("超出本行剩余可退额"), g2.toString())
        assertEquals(17800L, tradeVal(o, "refunded_fee").toLong(), "行闸门失败同样整体回滚，支付侧不多占一分")

        // 放行：恢复额度后第 3 笔成功，累计正好等于实付
        jdbc.update("UPDATE orders_normal_orders_items SET total_fee = 26700 WHERE order_id = ?", o.toLong())
        checkRefund(b3)
        assertEquals(26700L, tradeVal(o, "refunded_fee").toLong())
        assertEquals(26700L, itemVal(o, "refunded_fee").toLong(), "三次部分退款正好退完")
        assertEquals(0L, orderVal(o, "commission_fee").toLong(), "三次冲完佣金归零，GREATEST 兜底不为负")
        assertEquals(0L, orderVal(o, "total_rebate").toLong())
        assertEquals("REFUND", orderVal(o, "pay_status"), "全部行退完 → REFUND")
        // 件数已用完：第 4 笔申请被拒
        expect("POST", "/api/h5app/wxapp/aftersales",
            mapOf("order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND"), 422)
        assertEquals(
            3, jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_refund WHERE order_id = ? AND refund_status = 'SUCCESS'", Int::class.java, o),
            "同一行三次部分退款各一张成功单",
        )
    }

    @Test @Order(11) fun `积分单退款走积分池：refund_fee 为 0 且只加积分`() {
        val o = newOrder()
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to o, "pay_type" to "point"))
        val before = point()
        assertEquals("point", orderVal(o, "pay_type"))
        val bn = applyAf(o).path("aftersales_bn").asText()
        assertEquals(0L, applyReadFee(bn), "积分单的申请额记在 refund_point，不记 refund_fee")
        assertEquals(8900L, ok("/api/h5app/wxapp/aftersales/$bn").path("refund_point").asLong())
        approve(bn)
        val rbn = latestRefundBn(bn)
        assertEquals(8900L, refundVal(rbn, "refund_point").toLong())
        checkRefund(bn)
        assertEquals(before + 8900, point(), "积分原路回补")
        assertEquals(8900L, jdbc.queryForObject("SELECT change_point FROM point_member_log WHERE order_id = ? ORDER BY id DESC LIMIT 1", Long::class.java, o), "回补留痕")
        assertEquals(0L, tradeVal(o, "refunded_fee").toLong(), "积分不进现金池")
        assertEquals("POINTREF$rbn", refundVal(rbn, "refund_id"))
        assertEquals(0L, refundVal(rbn, "refunded_fee").toLong(), "现金池一分未动")
        assertEquals(8900L, refundVal(rbn, "refunded_point").toLong())
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aftersales_refund WHERE order_id = ? AND (pay_type IS NULL OR pay_type <> 'point') AND refunded_fee > 0",
                Int::class.java, o,
            ), "积分单不得计入现金守恒",
        )
    }

    @Test @Order(12) fun `退货退款全链路：待回寄→回寄→确认收货→退款`() {
        val o = paidOrder()
        val bn = applyAf(o, type = "REFUND_GOODS").path("aftersales_bn").asText()
        approve(bn)
        assertEquals(1, afVal(bn, "progress").toInt(), "1=接受申请待买家回寄")
        assertTrue(afVal(bn, "auto_refuse_time").toLong() > 0, "写回寄截止时间")
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_refund WHERE aftersales_bn = ?", Int::class.java, bn.toLong()), "待回寄阶段没有退款单")
        // 回寄校验：公司编码 6..30、单号必填
        expect("POST", "/api/h5app/wxapp/aftersales/sendback", mapOf("aftersales_bn" to bn, "corp_code" to "zto", "logi_no" to "A1"), 400)
        ok("/api/h5app/wxapp/aftersales/sendback", mapOf("aftersales_bn" to bn, "corp_code" to "zhongtong", "logi_no" to "ZTO-99887766"))
        assertEquals(2, afVal(bn, "progress").toInt(), "2=买家已回寄")
        val sd = ok("/api/h5app/wxapp/aftersales/$bn").path("sendback_data")
        assertEquals("ZTO-99887766", sd.path("logi_no").asText(), "sendback_data 回对象")
        assertEquals("buyer", sd.path("filled_by").asText())
        // D11：确认收货 = 对 progress=2 的单再审核一次
        approve(bn)
        assertEquals(8, afVal(bn, "progress").toInt())
        assertEquals(1L, detailVal(bn, "refunded_num").toLong(), "按申请件数整笔入库")
        checkRefund(bn)
        assertEquals(2, afVal(bn, "aftersales_status").toInt())
        assertEquals("SUCCESS", refundVal(latestRefundBn(bn), "refund_status"))
        assertEquals("MOCKREF${latestRefundBn(bn)}", refundVal(latestRefundBn(bn), "refund_id"), "mock 退款号稳定可重放")
        orderGoods = o
        bnGoods = bn
        refundBnGoods = latestRefundBn(bn)
    }

    @Test @Order(13) fun `AF-F5 撤销与超时自动关闭`() {
        val o = paidOrder(2)
        val bn = applyAf(o, num = 2).path("aftersales_bn").asText()
        val d = ok("/api/h5app/wxapp/aftersales/close", mapOf("aftersales_bn" to bn, "memo" to "不想了"))
        assertEquals(4, d.path("aftersales_status").asInt(), "4=已撤销")
        assertEquals(7, d.path("progress").asInt(), "7=已撤销终态")
        assertEquals(2, orderVal(o, "left_aftersales_num").toInt(), "撤销把 2 件全部还回")
        expect("POST", "/api/h5app/wxapp/aftersales/close", mapOf("aftersales_bn" to bn), 422)

        // 待回寄超期 → 系统关闭（定时任务口径，直接调服务）
        val o2 = paidOrder()
        val bn2 = applyAf(o2, type = "REFUND_GOODS").path("aftersales_bn").asText()
        approve(bn2)
        jdbc.update("UPDATE aftersales SET auto_refuse_time = 1 WHERE aftersales_bn = ?", bn2.toLong())
        assertTrue(aftersalesService.autoRefuse(50) >= 1, "超时未回寄必须关闭")
        assertEquals(4, afVal(bn2, "aftersales_status").toInt())
        assertEquals(7, afVal(bn2, "progress").toInt())
        assertEquals(1, orderVal(o2, "left_aftersales_num").toInt())
        // 已进入待退款的单不许撤
        val o3 = paidOrder()
        val bn3 = applyAf(o3).path("aftersales_bn").asText()
        approve(bn3)
        expect("POST", "/api/h5app/wxapp/aftersales/close", mapOf("aftersales_bn" to bn3), 422)
        assertEquals(1, afVal(bn3, "aftersales_status").toInt(), "退款在途，售后单仍在处理中")
        // 待退款阶段的退款单不能被撤销动作顺手作废（撤销被拒 ⇒ 退款单原地留着）
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_refund WHERE aftersales_bn = ? AND refund_status = 'READY'", Int::class.java, bn3))
    }

    @Test @Order(14) fun `AF-A6 代客申请：归属买家且当场通过`() {
        val o = paidOrder()
        val d = okAdmin("POST", "/api/aftersales/apply", mapOf(
            "order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND", "reason" to "质量问题",
        ))
        val bn = d.path("aftersales_bn").asText()
        assertEquals(1L, d.path("user_id").asLong(), "售后单归属订单买家，不是操作员")
        assertEquals(1, d.path("aftersales_status").asInt(), "仅退款当场通过 → 处理中")
        assertEquals(8, d.path("progress").asInt(), "直接进待退款")
        assertEquals("READY", refundVal(latestRefundBn(bn), "refund_status"))
        assertEquals("商家代客申请，当场通过", afVal(bn, "memo"))
        adminApplyBn = bn
    }

    @Test @Order(15) fun `AF-A7 代填回寄与 AF-A5 备注`() {
        val o = paidOrder()
        val bn = applyAf(o, type = "REFUND_GOODS").path("aftersales_bn").asText()
        approve(bn)
        okAdmin("POST", "/api/aftersales/sendback", mapOf("aftersales_bn" to bn, "corp_code" to "shunfeng", "logi_no" to "SF-1234567"))
        assertEquals(2, afVal(bn, "progress").toInt())
        assertEquals("seller", okAdmin("GET", "/api/aftersales/$bn").path("sendback_data").path("filled_by").asText(), "后台代填记 seller")

        assertEquals("缺货已联系", okAdmin("PUT", "/api/aftersales/remark", mapOf("aftersales_bn" to bn, "memo" to "缺货已联系")).path("memo").asText())
        assertEquals(1, afVal(bn, "aftersales_status").toInt(), "备注不动状态")
        expect("PUT", "/api/aftersales/remark", mapOf("aftersales_bn" to bn, "memo" to "x".repeat(151)), 400)
    }

    @Test @Order(16) fun `AF-A8 A9 退款列表与详情守恒读`() {
        val l = okAdmin("GET", "/api/refund?refund_status=READY&page=1&pageSize=50")
        assertContract(l, AftersalesContract.LIST, "AF-A8 信封")
        assertTrue(l.path("total_count").asLong() >= 1, "待确认的退款单要能筛出来")
        assertContract(l.path("list")[0], AftersalesContract.REFUND_ROW, "AF-A8 行")
        assertTrue(l.path("list").all { it.path("refund_status").asText() == "READY" }, "状态筛选必须生效")
        val all = okAdmin("GET", "/api/refund?page=1&pageSize=50")
        assertContract(all.path("list")[0], AftersalesContract.REFUND_ROW, "AF-A8 全量行")
        assertTrue(all.path("total_count").asLong() > l.path("total_count").asLong(), "全量比 READY 多（已退成功/拒退的单也在表里）")

        // 在途单：额度没占，支付侧余量就是实付
        val ready = okAdmin("GET", "/api/refund/detail/$refundBnA")
        assertContract(ready, AftersalesContract.REFUND_DETAIL, "AF-A9 详情")
        assertEquals("READY", ready.path("refund_status").asText())
        assertEquals("待财务确认", ready.path("status_msg").asText())
        assertEquals(8900L, ready.path("trade_can_refund").asLong(), "未确认退款的单不占支付侧额度")
        assertEquals(0L, ready.path("order_refunded_fee").asLong())
        assertEquals(0L, ready.path("order_refunded_point").asLong())
        assertEquals(bnA, ready.path("aftersales").path("aftersales_bn").asText())
        assertEquals(1, ready.path("aftersales").path("aftersales_status").asInt())
        assertEquals(8, ready.path("aftersales").path("progress").asInt())

        // 已结案的退货退款单：支付侧余量归零
        val done = okAdmin("GET", "/api/refund/detail/$refundBnGoods")
        assertEquals("SUCCESS", done.path("refund_status").asText())
        assertEquals("已退款", done.path("status_msg").asText())
        assertEquals(0L, done.path("trade_can_refund").asLong(), "整单退完还能退 0")
        assertEquals(8900L, done.path("order_refunded_fee").asLong())
        assertEquals(2, done.path("aftersales").path("aftersales_status").asInt())
        expect("GET", "/api/refund/detail/9999999999999999", null, 404, token = admin)
    }

    @Test @Order(17) fun `AF-A10 线下打款：deferred 停在 AUDIT_SUCCESS 再登记落 SUCCESS`() {
        val o = paidOrder()
        // 成交路径用 wxpay，再把成功支付单标成线下转账 —— 等价于"这笔单的钱是柜台收的"
        jdbc.update("UPDATE trade SET pay_type = 'offline_pay' WHERE order_id = ? AND trade_state = 'SUCCESS'", o)
        val bn = applyAf(o).path("aftersales_bn").asText()
        approve(bn)
        val rbn = latestRefundBn(bn)
        assertEquals("offline", refundVal(rbn, "refund_channel"), "线下支付单的退款通道是 offline")
        val d = checkRefund(bn)
        assertEquals("AUDIT_SUCCESS", d.path("refund_status").asText(), "线下要人工打款，不当场成功")
        assertEquals(true, d.path("deferred").asBoolean())
        assertEquals(9, afVal(bn, "progress").toInt(), "9=退款处理中")
        assertEquals(8900L, tradeVal(o, "refunded_fee").toLong(), "额度在确认时占用，之后不再重复加")
        expect("POST", "/api/refund/offline", mapOf("refund_bn" to rbn, "payee_name" to "张三"), 400, token = admin)
        expect("POST", "/api/refund/offline", mapOf("refund_bn" to refundBnA, "payee_name" to "张三", "bank_account" to "6222"), 422, token = admin)

        okAdmin("POST", "/api/refund/offline", mapOf(
            "refund_bn" to rbn, "payee_name" to "张三", "bank_name" to "招商银行", "bank_account" to "6222020000000000000",
            "bank_branch" to "深圳分行", "bank_province" to "广东省", "bank_city" to "深圳市", "memo" to "对公已打款",
        ))
        assertEquals("SUCCESS", refundVal(rbn, "refund_status"))
        assertEquals(8900L, refundVal(rbn, "refunded_fee").toLong())
        assertEquals("OFFLINE$rbn", refundVal(rbn, "refund_id"))
        assertEquals(2, afVal(bn, "aftersales_status").toInt(), "登记即结案")
        assertEquals(4, afVal(bn, "progress").toInt())
        assertEquals("对公已打款", okAdmin("GET", "/api/refund/detail/$rbn").path("memo").asText(), "登记原文不能被收尾备注盖掉")
        assertEquals("线下打款完成", afVal(bn, "memo"), "售后单记流程，退款单记登记原文")
        assertEquals("深圳分行", okAdmin("GET", "/api/refund/detail/$rbn").path("bank_branch").asText())
        assertEquals(8900L, tradeVal(o, "refunded_fee").toLong(), "打款登记不再累加额度")
        expect("POST", "/api/refund/offline", mapOf("refund_bn" to rbn, "payee_name" to "张三", "bank_account" to "1"), 422)
    }

    @Test @Order(18) fun `面鉴权：401 403 与越权 404`() {
        expect("POST", "/api/h5app/wxapp/aftersales", mapOf("order_id" to orderA, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND"), 401, token = null)
        expect("GET", "/api/h5app/wxapp/aftersales/$bnA", null, 401, token = null)
        // 反向：operator JWT 打会员面同样进不了（两条链各认各的令牌）
        expect("POST", "/api/h5app/wxapp/aftersales/close", mapOf("aftersales_bn" to bnA), 401, token = admin)
        expect("GET", "/api/aftersales", null, 401, token = null)
        // 会员 JWT 打到后台面：鉴权链不认，一律 401（不是 403），避免泄露端点存在性
        expect("GET", "/api/aftersales/$bnA", null, 401, token = tok)
        expect("GET", "/api/aftersales", null, 403, token = viewer)
        expect("POST", "/api/aftersales/review", mapOf("aftersales_bn" to adminApplyBn, "is_approved" to 1), 403, token = viewer)
        expect("GET", "/api/refund", null, 403, token = viewer)
        // 别人的售后单在会员面按不存在处理（404 而非 403）
        expect("GET", "/api/h5app/wxapp/aftersales/$bnA", null, 404, token = other)
        expect("POST", "/api/h5app/wxapp/aftersales/close", mapOf("aftersales_bn" to bnA), 404, token = other)
        expect("GET", "/api/h5app/wxapp/aftersales/9999999999999999", null, 404)
        expect("GET", "/api/h5app/wxapp/aftersales/not-a-number", null, 404)
        expect("GET", "/api/aftersales/9999999999999999", null, 404, token = admin)
    }

    @Test @Order(19) fun `全局守恒审计：五条不变式一条都不许破`() {
        // 1. 申请侧：同一订单行上"仍占额度"的申请金额合计 ≤ 行 total_fee
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT a.order_id, a.item_id, SUM(a.refund_fee + a.refund_point) f, MAX(i.total_fee) tf " +
                    "FROM aftersales a JOIN orders_normal_orders_items i ON i.order_id = a.order_id AND i.item_id = a.item_id " +
                    "WHERE a.aftersales_status IN (0, 5, 1, 2) GROUP BY a.order_id, a.item_id " +
                    "HAVING SUM(a.refund_fee + a.refund_point) > MAX(i.total_fee)) t", Int::class.java,
            ), "占额度申请不得超过行货款",
        )
        // 2. 申请侧：占额度件数 ≤ 订单行件数（镜像列与权威口径一致）
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT a.order_id, a.item_id, SUM(a.num) n, MAX(i.num) tn " +
                    "FROM aftersales a JOIN orders_normal_orders_items i ON i.order_id = a.order_id AND i.item_id = a.item_id " +
                    "WHERE a.aftersales_status IN (0, 5, 1, 2) GROUP BY a.order_id, a.item_id HAVING SUM(a.num) > MAX(i.num)) t", Int::class.java,
            ), "占额度件数不得超过行件数",
        )
        // 3. 执行侧：非积分退款合计 ≤ 支付单实付
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT r.trade_id, SUM(r.refunded_fee) f, MAX(t.pay_fee) pf " +
                    "FROM aftersales_refund r JOIN trade t ON t.trade_id = r.trade_id " +
                    "WHERE r.pay_type IS NULL OR r.pay_type <> 'point' GROUP BY r.trade_id HAVING SUM(r.refunded_fee) > MAX(t.pay_fee)) t", Int::class.java,
            ), "累计退款不得超过实付",
        )
        // 4. 行侧与主表-明细一致性
        assertEquals(
            0, jdbc.queryForObject("SELECT COUNT(*) FROM orders_normal_orders_items WHERE refunded_fee > total_fee", Int::class.java),
            "行已退金额不得超过行货款",
        )
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aftersales a JOIN aftersales_detail d ON d.aftersales_bn = a.aftersales_bn " +
                    "WHERE a.aftersales_status <> d.aftersales_status OR a.progress <> d.progress", Int::class.java,
            ), "主表与明细永远同步",
        )
        // 5. 终态与退款单一致：SUCCESS 的退款单必须对应已结案售后单；一张售后单只退一次
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aftersales_refund r JOIN aftersales a ON a.aftersales_bn = r.aftersales_bn " +
                    "WHERE r.refund_status = 'SUCCESS' AND a.aftersales_status <> 2", Int::class.java,
            ), "退款成功即售后结案",
        )
        assertEquals(
            0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT aftersales_bn FROM aftersales_refund WHERE refund_status = 'SUCCESS' " +
                    "GROUP BY aftersales_bn HAVING COUNT(*) > 1) t", Int::class.java,
            ), "一张售后单只退一次",
        )
        assertTrue(balance() >= 0 && point() >= 0, "账户不得为负")
        assertEquals(
            0, jdbc.queryForObject("SELECT COUNT(*) FROM trade WHERE refunded_fee > pay_fee", Int::class.java),
            "任何支付单的已退都不得超过实付",
        )
        assertEquals(
            0, jdbc.queryForObject("SELECT COUNT(*) FROM orders_normal_orders WHERE commission_fee < 0 OR total_rebate < 0", Int::class.java),
            "冲销只减不借，账户不得为负",
        )
    }

    @Test @Order(20) fun `退款中的单不逃出 ORDER-8 角标：分桶求和恒等 total 且页签同口径`() {
        val o = paidOrder()
        val bn = applyAf(o).path("aftersales_bn").asText()
        refundThrough(bn)
        assertEquals("REFUND", orderVal(o, "pay_status"), "整单退完只改 pay_status")
        assertEquals("PAYED", orderVal(o, "order_status"), "单头态保持已付款（源系统不改 order_status，退款靠 pay_status 标注）")

        val c = ok("/api/h5app/wxapp/orderscount")
        val sum = c.path("notpay").asLong() + c.path("pending_delivery").asLong() + c.path("pending_receive").asLong() +
            c.path("done").asLong() + c.path("cancel").asLong()
        assertEquals(c.path("total").asLong(), sum, "C43：五桶求和恒等 total，退款单也要有归属（$c）")
        assertTrue(c.path("pending_delivery").asLong() >= 1, "未发货就退完的单留在待发货桶")

        val tab = ok("/api/h5app/wxapp/orders?status=6&page=1&pageSize=100")
        assertTrue(tab.path("list").any { it.path("order_id").asText() == o }, "角标与 status=6 页签同口径：$o 查不到就是两套 SQL 漂移")
    }
}
