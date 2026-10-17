package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.service.pay.PayChannelRegistry
import com.ecshopx.kotlin.service.pay.PayReconcileJob
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * M1-2 门槛用例（docs/05 §M1-2）：**真签真验的支付全链路 + 回调乱序/重复/伪造**。
 *
 * 这一类跑在 `ecx.pay.active-channel-profile=wechatpay` 上，注册的是 [com.ecshopx.kotlin.service.pay.WxPayV3Channel]（SDK 实现），
 * 其余 profile 的用例一律不受影响（mock 渠道仍按 M0-7 的口径跑）。渠道地址由 `base-url` 指到本地
 * 假服务器 [SimWxPayServer]，密钥与证书来自测试密钥库 [SimWx]——签名、验签、AES-GCM 解密全是真的，
 * 只有"谁持有平台私钥"这一件事是仿的（沙箱真单的等价物：缺的是商户号，不是代码）。
 *
 * 覆盖到的门槛项：
 *  1. 真单全链路：JSAPI 下单 → 前端六键调起参数（`paySign` 能被商户证书验过）→ v3 回调结清 → 后台发货 → 收货；
 *  2. 回调重复：同一笔通知连发两次，只结清一次、积分只入一次；
 *  3. 回调乱序：未支付报文先到 → 拒，随后 SUCCESS 才结清；订单不会被"未付"报文推开；
 *  4. 伪造：假签名 / 假证书号 / 改明文包 / 改密文 / 过期时间戳 / 表单回调，六种都拿不到结清；
 *  5. 掉单补单：查单作业走同一套签名应答，收敛路径与回调共用同一段代码；
 *  6. 渠道侧关单：本地 `CLOSED` 之后由补单作业向渠道补关，`channel_closed` 是唯一收敛位；
 *  7. 退款 `PROCESSING` → `REFUND.SUCCESS` 回执：退款单停在 AUDIT_SUCCESS 等回执，额度全程占用；
 *  8. SUP-6 线下转账审核流：通过即结清、驳回不动钱、重复审核与少收都拒。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exctxwx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        // 真渠道 profile：mock 渠道此时不注册，registry 里 wxpay 只可能是 SDK 实现
        "ecx.pay.active-channel-profile=wechatpay",
        // 定时一律停表：补单/关单由用例直接调 reconcileNow()，时序不能靠等待
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.schedule.reconcile-enabled=false",
        "ecx.schedule.reconcile-initial-delay-ms=3600000",
        "ecx.schedule.reconcile-ms=3600000",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WxPayV3SimulationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var registry: PayChannelRegistry
    @Autowired lateinit var reconcile: PayReconcileJob

    private var tok = ""
    private var other = ""
    private var admin = ""

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

    private fun expect(url: String, body: Map<String, Any?>?, code: Int, token: String?, contains: String? = null) {
        val res = call("POST", url, token, body)
        assertEquals(code, res.response.status, "$url -> " + utf8(res.response.contentAsByteArray))
        contains?.let {
            assertTrue(utf8(res.response.contentAsByteArray).contains(it), "错误体要说明原因：" + utf8(res.response.contentAsByteArray))
        }
    }

    /** v3 回调：头与体都来自夹具，逐字节原样投给 `/api/notify/wxpay` */
    private fun postNotify(n: SimWxNotification): MvcResult {
        val req = post("/api/notify/wxpay")
            .contentType(MediaType.APPLICATION_JSON)
            .content(n.body.toByteArray(Charsets.UTF_8))
        n.headers.forEach { (k, v) -> req.header(k, v) }
        return mvc.perform(req).andReturn()
    }

    /** v3 约定 200=SUCCESS、5xx=FAIL（渠道据此重试），两种应答都断言 code */
    private fun ackSuccess(res: MvcResult, label: String): JsonNode {
        assertEquals(200, res.response.status, "$label 应 ACK -> " + utf8(res.response.contentAsByteArray))
        val node = mapper.readTree(utf8(res.response.contentAsByteArray))
        assertEquals("SUCCESS", node.path("code").asText(), label)
        return node
    }

    private fun ackFail(res: MvcResult, label: String): JsonNode {
        assertEquals(500, res.response.status, "$label 应回 FAIL 让渠道重试 -> " + utf8(res.response.contentAsByteArray))
        val node = mapper.readTree(utf8(res.response.contentAsByteArray))
        assertEquals("FAIL", node.path("code").asText(), label)
        return node
    }

    private fun orderVal(orderId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM orders_normal_orders WHERE order_id = ?", Any::class.java, orderId.toLong()).toString()

    private fun tradeVal(tradeId: String, col: String): String =
        jdbc.queryForObject("SELECT $col FROM trade WHERE trade_id = ?", Any::class.java, tradeId).toString()

    /** 积分账户可能还没建行（首笔结清才入账），读不到就当 0 */
    private fun pointOf(userId: Long): Long =
        jdbc.query("SELECT point FROM point_member WHERE user_id = ?", { rs, _ -> rs.getLong(1) }, userId).firstOrNull() ?: 0L

    private fun newOrder(num: Int = 1, asMember: String = tok, addressId: Int = 1): String = ok(
        "/api/h5app/wxapp/order_new",
        mapOf(
            "cart_type" to "fastbuy", "order_type" to "normal", "address_id" to addressId,
            "items" to listOf(mapOf("item_id" to 3, "num" to num)),
        ),
        asMember = asMember,
    ).path("order_id").asText()

    /** 发起支付即拿渠道参数：真渠道下这一步会真的打 v3 JSAPI 下单 */
    private fun prepay(orderId: String, asMember: String = tok): JsonNode =
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to orderId, "pay_type" to "wxpay"), asMember)

    /** 前置动作：下一笔已结清真单（回执走真验签解密），不断言细节 */
    private fun paidOrder(): Pair<String, String> {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()
        // 渠道侧也记成已付：夹具的查单/关单分支都据此判断，与"钱真进了渠道"一致
        simServer.markPaid(tradeId, total.toInt())
        ackSuccess(postNotify(SimWxNotification.paid(tradeId, total.toInt(), "SIMTX$tradeId")), "前置结清回执")
        return o to tradeId
    }

    // ---------- 用例 ----------

    @Test @Order(1) fun `profile 生效：wxpay 换成了 SDK 实现，密钥与证书到位`() {
        tok = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000001", "check_type" to "mobile", "vcode" to "8888",
        )), "member").path("token").asText()
        other = data(call("POST", "/api/h5app/wxapp/new_login", null, mapOf(
            "auth_type" to "local", "username" to "13800000002", "check_type" to "mobile", "vcode" to "8888",
        )), "other").path("token").asText()
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()

        assertEquals("WxPayV3Channel", registry.get("wxpay")::class.simpleName, "profile=wechatpay 必须换掉 mock 实现")
        // 渠道清单仍按固定顺序给出，且没有 mock 那份假参数：换 profile 不换契约
        val types = ok("/api/h5app/wxapp/payment/config?order_id=${newOrder()}").path("pay_types").map { it.asText() }
        assertTrue(types.containsAll(listOf("wxpay", "point", "deposit", "offline_pay")), "PAY-1 渠道清单：$types")
        assertTrue(SimWx.merchantSerial.isNotBlank() && SimWx.platformSerial.isNotBlank(), "两张证书的序列号都要能读出来")
        assertNotEquals(SimWx.merchantSerial, SimWx.platformSerial, "商户证书与平台证书是两个身份")
    }

    @Test @Order(2) fun `JSAPI 下单：六键出参、paySign 验得过、渠道收到真签请求`() {
        // openid 走 members_associations 真查（不配兜底 openid，逼它必须知道钱是谁付的）
        jdbc.update(
            "INSERT INTO members_associations(user_id, company_id, unionid, open_id, user_type) " +
                "VALUES(1, 1, 'sim-union-0001', 'sim-openid-0001', 'wechat')",
        )
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()

        assertEquals("NOTPAY", d.path("trade_state").asText(), "真渠道下单只是拿参数，钱没进来")
        assertEquals("NOTPAY", tradeVal(tradeId, "trade_state"))
        assertEquals("NOTPAY", orderVal(o, "order_status"), "下单动作不许推订单状态")
        listOf("appId", "timeStamp", "nonceStr", "package", "signType", "paySign").forEach {
            assertTrue(d.hasNonNull(it) && d[it].asText().isNotBlank(), "PAY-2 缺前端调起键 $it：$d")
        }
        assertEquals(SimWx.APP_ID, d["appId"].asText())
        assertEquals("RSA", d["signType"].asText(), "JSAPI 调起固定 RSA(SHA256) 签名类型")
        assertTrue(!d["paySign"].asText().startsWith("MOCK-"), "真 profile 下不可能出现 mock 假签名")
        assertTrue(d["package"].asText().startsWith("prepay_id=SIMWXP"), "prepay_id 来自渠道应答：${d["package"].asText()}")

        // 前端调起串：appId\ntimeStamp\nnonceStr\npackageVal\n —— SDK 用商户私钥签，这里用商户证书验
        val message = "${d["appId"].asText()}\n${d["timeStamp"].asText()}\n${d["nonceStr"].asText()}\n${d["package"].asText()}\n"
        assertTrue(SimWx.verify(SimWx.merchantCert, message, d["paySign"].asText()), "paySign 必须能被商户证书验过")

        assertTrue(simServer.paths.contains("POST /v3/pay/transactions/jsapi"), "没打渠道：${simServer.paths}")
        assertEquals(true, simServer.lastAuthVerified, "渠道侧验不过商户签名 = SDK 签名没接上")
        assertEquals("NOTPAY", simServer.stateOf(tradeId), "渠道只记下这笔单，状态仍是未支付")

        // 没绑微信的会员不能发起微信支付（绝不退化成"无主支付"）：
        // 先给 2 号会员一组自己的收货地址，让失败点落在"渠道缺 openid"而不是"地址不存在"
        val now = com.ecshopx.kotlin.common.domain.TimeSec.now()
        jdbc.update(
            "INSERT INTO members_address(address_id, user_id, company_id, username, telephone, adrdetail, is_def, created, updated) " +
                "VALUES(9001, 2, 1, '仿真收货人', '13800000002', '仿真路 2 号', TRUE, ?, ?)", now, now,
        )
        val o2 = newOrder(asMember = other, addressId = 9001)
        expect("/api/h5app/wxapp/payment", mapOf("order_id" to o2, "pay_type" to "wxpay"), 422, other, "未绑定微信")
    }

    @Test @Order(3) fun `v3 回调结清：验签解密→PAYED→重复幂等→整链可走通`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()
        val p0 = pointOf(1L)

        // 乱序之一：钱还没进渠道，先来一封"未支付"报文——绝不能把订单推成已付
        ackFail(postNotify(SimWxNotification.paid(tradeId, total.toInt(), "SIMTX$tradeId", "NOTPAY")), "未支付报文")
        assertEquals("NOTPAY", orderVal(o, "order_status"), "非 SUCCESS 报文不结清")

        val node = ackSuccess(postNotify(SimWxNotification.paid(tradeId, total.toInt(), "SIMTX$tradeId")), "结清报文")
        assertTrue(node.path("message").asText().contains("OK"))
        assertEquals("PAYED", orderVal(o, "order_status"))
        assertEquals("SUCCESS", tradeVal(tradeId, "trade_state"))
        assertEquals(total, tradeVal(tradeId, "pay_fee").toLong(), "实付记的是订单应付，不是报文里的数")
        assertEquals("SIMTX$tradeId", tradeVal(tradeId, "transaction_id"))
        val p1 = pointOf(1L)
        assertTrue(p1 >= p0, "赠送积分只可能不减：$p0 -> $p1")

        // 重复通知（同报文再来一次）：trade 的 NOTPAY CAS 吃掉第二次，积分不会二次入账
        ackSuccess(postNotify(SimWxNotification.paid(tradeId, total.toInt(), "SIMTX$tradeId")), "重复回执幂等")
        assertEquals(p1, pointOf(1L), "重复通知不得二次入账")
        assertEquals(
            1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade WHERE trade_id = ? AND trade_state = 'SUCCESS'", Int::class.java, tradeId,
            ), "结清只有一行",
        )

        assertEquals("SUCCESS", ok("/api/h5app/wxapp/payment/query", mapOf("trade_id" to tradeId)).path("trade_state").asText(), "PAY-3 轮询到已结清")

        // 结清后的整链仍按 M0 口径走得通（真渠道不影响发货收货）
        okAdmin("PUT", "/api/orders/delivery", mapOf(
            "order_id" to o.toLong(), "delivery_corp" to "zto", "delivery_code" to "SIM-M12",
        ))
        assertEquals("WAIT_BUYER_CONFIRM", orderVal(o, "order_status"))
        assertEquals("DONE", ok("/api/h5app/wxapp/order/confirmReceipt", mapOf("order_id" to o)).path("order_status").asText())
    }

    @Test @Order(4) fun `伪造与篡改：假签名、假证书号、改明文包、改密文、过期时间戳、表单回调都结不清`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()
        val real = SimWxNotification.paid(tradeId, total.toInt(), "SIMTX$tradeId")

        ackFail(postNotify(SimWxNotification.forgedSignature(real)), "假签名")
        ackFail(postNotify(SimWxNotification.forgedSerial(real)), "证书号对不上平台证书")
        ackFail(postNotify(SimWxNotification.expired(real)), "超过 5 分钟新鲜期")
        // 改信封明文（签名覆盖整个 body，动一个字节就验不过）
        ackFail(postNotify(SimWxNotification.tampered(real) { it.replace("\"summary\":\"仿真回执\"", "\"summary\":\"改单报文\"") }), "改明文包")
        // 改密文：签名与 GCM tag 都还是原样，但解出来的内容变了 → SDK 解密阶段就失败
        ackFail(postNotify(SimWxNotification.tampered(real) { it.replaceRange(it.length - 3, it.length - 1, "ab") }), "改密文")
        // 金额不符：验签解密在渠道，金额核对在 PayService，两道闸门各拦一次
        ackFail(postNotify(SimWxNotification.paid(tradeId, (total + 1).toInt(), "SIMTX$tradeId")), "金额不符")
        // 表单回调：v3 不发表单。真 profile 下面试必须拒，否则任何人 curl 一个单号就能把订单标成已付
        val form = mvc.perform(
            post("/api/notify/wxpay").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("out_trade_no", tradeId).param("total_fee", total.toString()).param("trade_state", "SUCCESS"),
        ).andReturn()
        assertTrue(utf8(form.response.contentAsByteArray).contains("FAIL"), "表单面必须拒：" + utf8(form.response.contentAsByteArray))

        assertEquals("NOTPAY", orderVal(o, "order_status"), "七次尝试一次都没结清")
        assertEquals("NOTPAY", tradeVal(tradeId, "trade_state"))
        // 真报文最后一次仍能把这笔收回来（证明前面的失败不是"订单坏了"）
        ackSuccess(postNotify(SimWxNotification.paid(tradeId, total.toInt(), "SIMTX$tradeId")), "真报文结清")
        assertEquals("PAYED", orderVal(o, "order_status"))
    }

    @Test @Order(5) fun `掉单补单：回调没来，查单作业以同一套签名应答结清`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        val total = d.path("total_fee").asLong()

        // 钱在渠道侧扣了、回调丢了：本地仍 NOTPAY
        assertEquals("NOTPAY", orderVal(o, "order_status"))
        simServer.markPaid(tradeId, total.toInt())
        val r = reconcile.reconcileNow(TimeSec.now() + 3600)
        assertTrue(r.getValue("settled") >= 1, "补单没结清：$r")
        assertEquals("PAYED", orderVal(o, "order_status"))
        assertEquals("SUCCESS", tradeVal(tradeId, "trade_state"))
        assertEquals(total, tradeVal(tradeId, "pay_fee").toLong())
        assertTrue(simServer.paths.contains("GET /v3/pay/transactions/out-trade-no/$tradeId"), "查单没发生：${simServer.paths}")
        // 再跑一轮：CAS 已结清，不产生第二笔动作
        assertEquals(0, reconcile.reconcileNow(TimeSec.now() + 3600).getValue("settled"))
    }

    @Test @Order(6) fun `渠道侧关单：本地 CLOSED 后由补单作业补关，channel_closed 收敛`() {
        val o = newOrder()
        val d = prepay(o)
        val tradeId = d.path("trade_id").asText()
        ok("/api/h5app/wxapp/order/cancel", mapOf("order_id" to o, "cancel_reason" to "不想要了"))
        assertEquals("CANCEL", orderVal(o, "order_status"))
        assertEquals("CLOSED", tradeVal(tradeId, "trade_state"), "取消时本地先收掉支付单")
        assertEquals("0", tradeVal(tradeId, "channel_closed"), "渠道侧那张单还没发过关单请求")
        assertEquals("NOTPAY", simServer.stateOf(tradeId), "本地 CLOSED 不等于渠道 CLOSED：二维码还活着")

        val r = reconcile.reconcileNow(TimeSec.now() + 3600)
        assertTrue(r.getValue("closed") >= 1, "没关掉渠道单：$r")
        assertEquals("CLOSED", simServer.stateOf(tradeId), "渠道侧必须是已关闭，扫码不能再付款")
        assertEquals("1", tradeVal(tradeId, "channel_closed"))
        val closePath = "POST /v3/pay/transactions/out-trade-no/$tradeId/close"
        assertTrue(simServer.paths.contains(closePath), "关单请求没发生：${simServer.paths}")

        // 已确认的不再重发：channel_closed=1 让它退出扫描集
        reconcile.reconcileNow(TimeSec.now() + 3600)
        assertEquals(1, simServer.paths.count { it == closePath }, "已确认的支付单不该再打渠道")

        // 渠道已付的单关不掉：close 拿到"已支付"这一终局结论也只置确认位，订单不复活（钱归对账）
        val o2 = newOrder()
        val d2 = prepay(o2)
        val t2 = d2.path("trade_id").asText()
        ok("/api/h5app/wxapp/order/cancel", mapOf("order_id" to o2, "cancel_reason" to "不想要了"))
        simServer.markPaid(t2, d2.path("total_fee").asLong().toInt())
        assertEquals(1, reconcile.reconcileNow(TimeSec.now() + 3600).getValue("closed"))
        assertEquals("SUCCESS", simServer.stateOf(t2), "已付的渠道单关不掉")
        assertEquals("1", tradeVal(t2, "channel_closed"), "渠道已给终局结论，停止重发")
        assertEquals("CANCEL", orderVal(o2, "order_status"), "关单结论不复活订单")
    }

    @Test @Order(7) fun `退款受理中：PROCESSING 停在 AUDIT_SUCCESS，退款成功回执才收尾`() {
        val (o, tradeId) = paidOrder()
        val bn = ok("/api/h5app/wxapp/aftersales", mapOf(
            "order_id" to o, "item_id" to 3, "num" to 1, "aftersales_type" to "ONLY_REFUND",
            "reason" to "质量问题", "description" to "开箱即损",
        )).path("aftersales_bn").asText()
        okAdmin("POST", "/api/aftersales/review", mapOf("aftersales_bn" to bn, "is_approved" to 1, "refuse_reason" to ""))
        val before = tradeVal(tradeId, "refunded_fee").toLong()
        assertEquals(0L, before, "退款前额度未被占用")

        val r = okAdmin("POST", "/api/aftersales/refundCheck", mapOf("aftersales_bn" to bn, "check_refund" to 1))
        val refundBn = r.path("refund_bn").asText()
        assertEquals(false, r.path("settled").asBoolean())
        assertEquals(true, r.path("pending_callback").asBoolean(), "真微信退款的中间态就是等回执")
        assertEquals("AUDIT_SUCCESS", r.path("refund_status").asText())
        assertTrue(r.path("refund_id").asText().startsWith("SIMREF"), "退款号来自渠道：${r.path("refund_id").asText()}")
        assertEquals(
            "AUDIT_SUCCESS",
            jdbc.queryForObject("SELECT refund_status FROM aftersales_refund WHERE refund_bn = ?", Any::class.java, refundBn.toLong()).toString(),
        )
        val fee = jdbc.queryForObject("SELECT refund_fee FROM aftersales_refund WHERE refund_bn = ?", Any::class.java, refundBn.toLong()).toString().toLong()
        assertTrue(fee > 0L, "退款额由行货款算出：$fee")
        assertEquals(before + fee, tradeVal(tradeId, "refunded_fee").toLong(), "额度从受理起就占用，不是回执才占")
        assertTrue(tradeVal(tradeId, "refunded_fee").toLong() <= tradeVal(tradeId, "pay_fee").toLong(), "整单守恒：已退不得超实付")
        assertTrue(simServer.paths.contains("POST /v3/refund/domestic/refunds"), "退款没打到渠道：${simServer.paths}")
        // 中间态是渠道给的，不是我们猜的：应答体里就是 status=PROCESSING
        assertEquals(200, simServer.lastRes.first, "渠道退款应答码：${simServer.lastRes}")
        assertTrue(simServer.lastRes.second.contains(""""status":"PROCESSING""""), "渠道退款应答体：${simServer.lastRes}")

        // 人工打款登记（AF-A10）在等回执期间必须拒绝，否则一笔钱退两次
        expect("/api/refund/offline", mapOf("refund_bn" to refundBn, "payee_name" to "张三", "bank_account" to "6222"), 422, admin, "渠道回执")

        // 回执：退款单推到 SUCCESS
        ackSuccess(postNotify(SimWxNotification.refund(tradeId, refundBn, "SIMREF$refundBn", "SUCCESS", fee.toInt())), "退款回执")
        assertEquals(
            "SUCCESS",
            jdbc.queryForObject("SELECT refund_status FROM aftersales_refund WHERE refund_bn = ?", Any::class.java, refundBn.toLong()).toString(),
        )
        val afterSuccess = tradeVal(tradeId, "refunded_fee").toLong()
        // 重复回执：终态幂等，不再动账
        ackSuccess(postNotify(SimWxNotification.refund(tradeId, refundBn, "SIMREF$refundBn", "SUCCESS", fee.toInt())), "退款回执重复")
        assertEquals(afterSuccess, tradeVal(tradeId, "refunded_fee").toLong(), "重复回执不重复占用额度")
        assertEquals(
            1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders_operate_logs WHERE order_id = ? AND behavior = 'refund_success'", Int::class.java, o.toLong(),
            ), "回执只留一次终局流水",
        )
    }

    @Test @Order(8) fun `线下转账审核流：通过即结清、驳回不动钱、重复审核与少收都拒`() {
        val o = newOrder()
        val d = ok("/api/h5app/wxapp/payment", mapOf("order_id" to o, "pay_type" to "offline_pay"))
        val paySn = d.path("trade_id").asText()
        assertEquals("NOTPAY", orderVal(o, "order_status"), "M0-7 口径：提交凭证只落待审核")
        assertEquals("NOTPAY", tradeVal(paySn, "trade_state"))
        val row = jdbc.queryForObject("SELECT id FROM offline_payment WHERE order_id = ?", Any::class.java, o.toLong()).toString()

        val list = okAdmin("GET", "/api/offline-payments?check_status=0")
        assertTrue(list.path("list").size() >= 1, "SUP-6a 看不到待审凭证：$list")
        assertTrue(list.path("list")[0].has("voucher_pic"), "列表行要有凭证图字段：${list.path("list")[0]}")

        // 驳回：只标凭证，订单继续待付款
        okAdmin("POST", "/api/offline-payments/check", mapOf("id" to row, "check_status" to 2, "remark" to "转账凭证看不清"))
        assertEquals("NOTPAY", orderVal(o, "order_status"), "驳回不动钱")
        assertEquals(
            2, jdbc.queryForObject("SELECT check_status FROM offline_payment WHERE id = ?", Any::class.java, row.toLong()).toString().toInt(),
        )

        // 另一笔走"通过"：审核入口自己结清，走的是与回调同一条 trade NOTPAY CAS
        val o2 = newOrder()
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to o2, "pay_type" to "offline_pay"))
        val row2 = jdbc.queryForObject("SELECT id FROM offline_payment WHERE order_id = ?", Any::class.java, o2.toLong()).toString()
        val fee = orderVal(o2, "total_fee").toLong()
        val r = okAdmin("POST", "/api/offline-payments/check", mapOf("id" to row2, "check_status" to 1, "pay_fee" to fee, "remark" to "柜台点收"))
        assertEquals("PAYED", orderVal(o2, "order_status"))
        assertEquals("SUCCESS", r.path("trade_state").asText())
        assertEquals(fee, tradeVal(r.path("trade_id").asText(), "pay_fee").toLong())
        assertEquals("offline_pay", tradeVal(r.path("trade_id").asText(), "pay_type"))

        // 重复审核：check_status 的 CAS 只从 0 出发
        expect("/api/offline-payments/check", mapOf("id" to row2, "check_status" to 1, "pay_fee" to fee), 422, admin, "已审核")
        // 少收不放行：线下按应付全额结清
        val o3 = newOrder()
        ok("/api/h5app/wxapp/payment", mapOf("order_id" to o3, "pay_type" to "offline_pay"))
        val row3 = jdbc.queryForObject("SELECT id FROM offline_payment WHERE order_id = ?", Any::class.java, o3.toLong()).toString()
        expect("/api/offline-payments/check", mapOf("id" to row3, "check_status" to 1, "pay_fee" to 1), 422, admin, "全额")
        assertEquals("NOTPAY", orderVal(o3, "order_status"))

        // 只读权限看不到审核入口：viewer 有列表权限没有审核权限
        val viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        expect("/api/offline-payments/check", mapOf("id" to row3, "check_status" to 1, "pay_fee" to 1), 403, viewer)
    }

    companion object {
        /** 本地假渠道：第一次被读配置时就起来，端口由系统分配，跑完整类再停 */
        val simServer: SimWxPayServer by lazy { SimWxPayServer().start() }

        @JvmStatic
        @DynamicPropertySource
        fun simProps(registry: DynamicPropertyRegistry) {
            registry.add("ecx.pay.wxpay.mch-id") { SimWx.MCH_ID }
            registry.add("ecx.pay.wxpay.app-id") { SimWx.APP_ID }
            registry.add("ecx.pay.wxpay.api-v3-key") { SimWx.API_V3_KEY }
            registry.add("ecx.pay.wxpay.private-key") { SimWx.merchantPrivateKeyPem }
            registry.add("ecx.pay.wxpay.certificate-serial") { SimWx.merchantSerial }
            registry.add("ecx.pay.wxpay.platform-certificate") { SimWx.platformCertPem }
            registry.add("ecx.pay.wxpay.notify-url") { "http://127.0.0.1/api/notify/wxpay" }
            registry.add("ecx.pay.wxpay.refund-notify-url") { "http://127.0.0.1/api/notify/wxpay" }
            // 唯一的接缝：只换 host+port，路径与签名照旧
            registry.add("ecx.pay.wxpay.base-url") { simServer.baseUrl }
            // 故意不给兜底 openid：真渠道必须知道钱是谁付的
            registry.add("ecx.pay.wxpay.fallback-open-id") { "" }
        }

        @JvmStatic
        @AfterAll
        fun stopSim() {
            simServer.stop()
        }
    }
}
