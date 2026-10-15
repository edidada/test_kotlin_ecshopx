package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.CartContract
import com.ecshopx.kotlin.boot.contract.ContractDiff
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0-5 DoD：购物车端到端 + 契约 diff + 「预览价==下单价」的 HTTP 层复现。
 *
 * 演示数据（sql/seed.sql）：会员 user_id=1（grade 1 折扣 100）、
 * 商品 2=X1 白色128G 199900 分 / 3=每日坚果 8900 分，均属 distributor_id=1「商城总部」、模板 1 包邮。
 * 金额断言全部按分逐分核对；行号顺序即业务时序（前一案的后置状态被后一案复用）。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccart;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CartEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var memberToken: String = ""
    private var cartIdPhone: Long = 0L
    private var cartIdNut: Long = 0L

    private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun loginMember(): String {
        val res = mvc.perform(
            post("/api/h5app/wxapp/new_login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        mapOf(
                            "auth_type" to "local", "username" to "13800000001",
                            "check_type" to "mobile", "vcode" to "8888",
                        ),
                    ),
                ),
        ).andReturn()
        assertEquals(200, res.response.status, utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data").path("token").asText()
    }

    private fun req(method: String, url: String, content: Map<String, Any?>? = null) =
        when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
            else -> delete(url)
        }.also { it.header("Authorization", "Bearer $memberToken") }
            .let {
                if (content != null) it.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(content))
                it
            }

    private fun ok(method: String, url: String, content: Map<String, Any?>? = null): JsonNode {
        val res = mvc.perform(req(method, url, content)).andReturn()
        assertEquals(200, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun fail(method: String, url: String, content: Map<String, Any?>, expect: Int) {
        val res = mvc.perform(req(method, url, content)).andReturn()
        assertEquals(expect, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    private fun lineOf(data: JsonNode, itemId: Long): JsonNode =
        data.path("valid_cart").flatMap { it.path("list").toList() }
            .firstOrNull { it.path("item_id").asLong() == itemId }
            ?: throw AssertionError("车内未命中 item_id=$itemId，实际=${data.path("valid_cart")}")

    private fun groupOf(data: JsonNode): JsonNode = data.path("valid_cart")[0]

    @Test @Order(1) fun `CART-2 空车出参结构齐备`() {
        memberToken = loginMember()
        val data = ok("GET", "/api/h5app/wxapp/cart")
        assertContract(data, CartContract.CART_DATA, "CART-2 data")
        assertEquals(0, data.path("valid_cart").size())
        assertEquals(0, data.path("invalid_cart").size())
        assertEquals(false, data.path("is_check_store").asBoolean())
    }

    @Test @Order(2) fun `CART-1 加购单件后全量回吐并逐分算对`() {
        val data = ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 2, "num" to 2))
        assertContract(data, CartContract.CART_DATA, "CART-1 data")
        val g = groupOf(data)
        assertContract(g, CartContract.SHOP_GROUP, "CART-1 店铺组")
        val line = lineOf(data, 2)
        assertContract(line, CartContract.CART_LINE, "CART-1 行")
        assertEquals(1L, g.path("shop_id").asLong(), "商品 distributor_id=1")
        assertEquals("商城总部", g.path("shop_name").asText(), "店铺名取 distribution_distributor")
        assertEquals("13900000000", g.path("mobile").asText())
        assertEquals(399800L, g.path("item_fee").asLong(), "199900×2，单位分")
        assertEquals(0L, g.path("freight").asLong(), "模板 1 是包邮模板")
        assertEquals(399800L, g.path("cart_total_price").asLong())
        assertEquals(399800L, g.path("total_fee").asLong())
        assertEquals(2L, g.path("cart_total_num").asLong())
        assertEquals(1, g.path("cart_total_count").asInt())
        assertEquals(199900L, line.path("price").asLong())
        assertEquals(false, line.path("is_last_price").asBoolean(), "快照价=现价")
        assertEquals("白色、128G", line.path("item_spec_desc").asText(), "规格描述与商品域同源")
        assertEquals(1L, line.path("parent_id").asLong(), "SKU 子行回指主行")
        assertEquals(1, line.path("pics").size())
        cartIdPhone = line.path("cart_id").asLong()
        assertTrue(cartIdPhone > 0)
    }

    @Test @Order(3) fun `CART-1 批量与重复加购 isAccumulate 累加 覆盖`() {
        val batch = ok(
            "POST", "/api/h5app/wxapp/cart",
            mapOf("items" to listOf(mapOf("item_id" to 3, "num" to 1), mapOf("item_id" to 2, "num" to 2))),
        )
        cartIdNut = lineOf(batch, 3).path("cart_id").asLong()
        assertEquals(1, batch.path("valid_cart").size(), "同一 distributor 只一组")
        assertEquals(2, groupOf(batch).path("cart_total_count").asInt(), "同店铺两行")
        assertEquals(399800L + 8900L, groupOf(batch).path("item_fee").asLong(), "批量里 item_id=2 覆盖为 2 件")

        val acc = ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 4, "isAccumulate" to true))
        assertEquals(5L, lineOf(acc, 3).path("num").asLong(), "isAccumulate=true 累加")

        val over = ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 2))
        assertEquals(2L, lineOf(over, 3).path("num").asLong(), "默认覆盖数量（与 PHP 一致）")
        assertEquals(cartIdNut, lineOf(over, 3).path("cart_id").asLong(), "同一唯一键复用原行")
        // 快照价校准：改主表价后 is_last_price=true（购物车不静默改价，只提示）
        jdbc.update("UPDATE items SET price = 9000 WHERE item_id = 3")
        val last = ok("GET", "/api/h5app/wxapp/cart")
        assertEquals(true, lineOf(last, 3).path("is_last_price").asBoolean(), "车内快照 8900 ≠ 现价 9000")
        assertEquals(18000L, lineOf(last, 3).path("total_fee").asLong(), "算价以现价为准")
        jdbc.update("UPDATE items SET price = 8900 WHERE item_id = 3")
    }

    @Test @Order(4) fun `CART-4 改数量与 CART-5 勾选联动金额`() {
        val upd = ok("PUT", "/api/h5app/wxapp/cartupdate/num", mapOf("cart_id" to cartIdNut, "num" to 10))
        assertEquals(89000L, lineOf(upd, 3).path("total_fee").asLong(), "8900×10")
        val g = groupOf(upd)
        assertEquals(399800L + 89000L, g.path("item_fee").asLong())
        assertEquals(12L, g.path("cart_total_num").asLong())

        val uncheck = ok(
            "PUT", "/api/h5app/wxapp/cartupdate/checkstatus",
            mapOf("cart_ids" to listOf(cartIdNut), "is_checked" to false),
        )
        val g2 = groupOf(uncheck)
        assertEquals(399800L, g2.path("item_fee").asLong(), "未勾选不计入合计")
        assertEquals(2L, g2.path("cart_total_num").asLong())
        assertEquals(1, g2.path("cart_total_count").asInt(), "行数只数勾选")
        assertEquals(false, lineOf(uncheck, 3).path("is_checked").asBoolean())
        assertEquals(2, g2.path("list").size(), "未勾选行仍在 list 里展示，只是不计金额")

        val all = ok("PUT", "/api/h5app/wxapp/cartupdate/checkstatus", mapOf("all" to true, "is_checked" to true))
        assertEquals(488800L, groupOf(all).path("item_fee").asLong(), "全车勾选")
        assertEquals(2, groupOf(all).path("cart_total_count").asInt())
    }

    @Test @Order(5) fun `CART-6 角标与会员等级折扣`() {
        val cnt = ok("GET", "/api/h5app/wxapp/cartcount")
        assertContract(cnt, CartContract.CART_COUNT, "CART-6")
        assertEquals(2, cnt.path("count").asInt(), "行数")
        assertEquals(12L, cnt.path("num").asLong(), "手机 2 件 + 坚果 10 件")

        jdbc.update("UPDATE members SET grade_id = 3 WHERE user_id = 1")     // 金卡 95 折
        val data = ok("GET", "/api/h5app/wxapp/cart")
        val g = groupOf(data)
        val nut = lineOf(data, 3)
        assertEquals("金卡会员", nut.path("grade_name").asText())
        assertEquals(4450L, nut.path("member_discount").asLong(), "89000×5% 逐行向下取整")
        assertEquals(488800L - 24440L, g.path("cart_total_price").asLong(), "折让后进商品应付")
        assertEquals(24440L, g.path("discount_fee").asLong(), "19990+4450")
        assertEquals(24440L, g.path("member_discount").asLong(), "M0 优惠全部来自会员折扣")
        assertEquals(488800L - 24440L, g.path("total_fee").asLong(), "包邮 → 运费 0")
    }

    @Test @Order(6) fun `ORDER-1 cart 模式预览价==购物车价`() {
        val cart = ok("GET", "/api/h5app/wxapp/cart")
        val g = groupOf(cart)
        val p = ok("POST", "/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "cart", "order_type" to "normal"))
        assertContract(p, CartContract.PREVIEW, "ORDER-1 data")
        assertContract(p.path("items")[0], CartContract.PREVIEW_ITEM, "ORDER-1 items 行")
        assertEquals(g.path("item_fee").asLong(), p.path("item_fee").asLong(), "商品合计逐分相等")
        assertEquals(g.path("freight").asLong(), p.path("freight_fee").asLong())
        assertEquals(g.path("discount_fee").asLong(), p.path("discount_fee").asLong())
        assertEquals(g.path("member_discount").asLong(), p.path("member_discount").asLong())
        assertEquals(g.path("total_fee").asLong(), p.path("total_fee").asLong(), "预览应付==车内应付")
        assertEquals(12L, p.path("totalItemNum").asLong())
        assertEquals(1, p.path("group_count").asInt())
        assertEquals(0L, p.path("coupon_discount").asLong(), "M0 券为开关位")
        assertEquals(2, p.path("items").size())
        assertTrue(p.path("point").asLong() >= 0)
        // 同一请求重放两次必须逐分一致（幂等，无随机/时间参与算价）
        val p2 = ok("POST", "/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "cart", "order_type" to "normal"))
        assertEquals(p.toString(), p2.toString(), "预览重放应完全一致")
    }

    @Test @Order(7) fun `ORDER-1 券开关位不产生优惠 fastbuy 直购同样过链路`() {
        val withCoupon = ok(
            "POST", "/api/h5app/wxapp/getFreightFee",
            mapOf("cart_type" to "cart", "coupon_code" to "CP123"),
        )
        val noCoupon = ok("POST", "/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "cart"))
        assertEquals(noCoupon.path("total_fee").asLong(), withCoupon.path("total_fee").asLong(), "M0 券不落金额，字段先就位")
        assertEquals(0, withCoupon.path("coupon_info").size())

        val p = ok(
            "POST", "/api/h5app/wxapp/getFreightFee",
            mapOf("cart_type" to "fastbuy", "order_type" to "normal", "items" to listOf(mapOf("item_id" to 3, "num" to 2))),
        )
        assertEquals(17800L, p.path("item_fee").asLong(), "8900×2，不读购物车")
        assertEquals(890L, p.path("member_discount").asLong(), "金卡 95 折")
        assertEquals(17800L - 890L, p.path("total_fee").asLong())
        assertEquals(2L, p.path("totalItemNum").asLong())

        val single = ok(
            "POST", "/api/h5app/wxapp/getFreightFee",
            mapOf("cart_type" to "fastbuy", "item_id" to 3, "item_num" to 1),
        )
        assertEquals(8900L, single.path("item_fee").asLong(), "单品写法 item_id+item_num")
        assertEquals(445L, single.path("member_discount").asLong())
    }

    @Test @Order(8) fun `运费 按件计价 与满额包邮门槛`() {
        jdbc.update(
            "INSERT IGNORE INTO shipping_templates (template_id, company_id, name, is_free, valuation, status, minprice, fee_conf, created, updated) " +
                "VALUES (2, 1, '按件8元', 'n', '1', TRUE, 0, '{\"first_fee\":8}', 0, 0)",
        )
        jdbc.update("UPDATE items SET templates_id = 2 WHERE item_id = 3")
        ok("DELETE", "/api/h5app/wxapp/cartdel", mapOf("cart_ids" to listOf(cartIdPhone)))
        val data = ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 3))
        val g = groupOf(data)
        assertEquals(1, data.path("valid_cart").size())
        assertEquals(26700L, g.path("item_fee").asLong(), "只剩坚果 3 件")
        assertEquals(2400L, g.path("freight").asLong(), "3 件 × 800 分")
        assertEquals(1335L, g.path("discount_fee").asLong(), "金卡 95 折：26700×5% 取整")
        assertEquals(26700L - 1335L + 2400L, g.path("total_fee").asLong(), "商品应付+运费")
        assertEquals(2400L, ok("POST", "/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "cart")).path("freight_fee").asLong(), "预览运费==车内运费")

        jdbc.update("UPDATE shipping_templates SET minprice = 100 WHERE template_id = 2")   // 满 100 元包邮
        assertEquals(0L, groupOf(ok("GET", "/api/h5app/wxapp/cart")).path("freight").asLong(), "商品应付 25365 分 ≥ 10000 分门槛 → 包邮")
        jdbc.update("UPDATE shipping_templates SET minprice = 5000 WHERE template_id = 2")  // 满 5000 元
        assertEquals(2400L, groupOf(ok("GET", "/api/h5app/wxapp/cart")).path("freight").asLong(), "未达门槛仍按件计")
    }

    @Test @Order(9) fun `失效行落入 invalid_cart 且不参与算价`() {
        jdbc.update("UPDATE items SET approve_status = 'instock' WHERE item_id = 3")
        val data = ok("GET", "/api/h5app/wxapp/cart")
        assertEquals(0, data.path("valid_cart").size(), "下架后无有效组")
        val iv = data.path("invalid_cart")
        assertEquals(1, iv.size())
        val row = iv[0].path("list")[0]
        assertContract(row, CartContract.CART_LINE, "invalid 行")
        assertEquals("instock", row.path("approve_status").asText())
        assertEquals(1L, iv[0].path("shop_id").asLong(), "失效行仍按店铺分组")

        val p = ok("POST", "/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "cart"))
        assertEquals(0L, p.path("item_fee").asLong())
        assertEquals(0L, p.path("total_fee").asLong())
        assertEquals(1, p.path("invalid_items").size())
        assertEquals("商品已下架", p.path("invalid_items")[0].path("reason").asText())

        val fb = ok("POST", "/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "fastbuy", "item_id" to 3, "item_num" to 1))
        assertEquals(0L, fb.path("item_fee").asLong(), "fastbuy 同样拦失效行")
        jdbc.update("UPDATE items SET approve_status = 'onsale' WHERE item_id = 3")
    }

    @Test @Order(10) fun `库存不足行判失效 勾选超库存触发校验提示`() {
        ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 3, "num" to 10))
        jdbc.update("UPDATE items SET store = 0 WHERE item_id = 3")
        val data = ok("GET", "/api/h5app/wxapp/cart")
        assertEquals(0, data.path("valid_cart").size(), "store=0 视为失效")
        assertEquals(1, data.path("invalid_cart").size())
        assertEquals(
            "库存不足",
            ok("POST", "/api/h5app/wxapp/getFreightFee", mapOf("cart_type" to "cart"))
                .path("invalid_items")[0].path("reason").asText(),
        )
        jdbc.update("UPDATE items SET store = 2 WHERE item_id = 3")
        val low = ok("GET", "/api/h5app/wxapp/cart")
        assertEquals(true, low.path("is_check_store").asBoolean(), "行内 10 件 > 库存 2 → 提示校验")
        assertEquals(2L, lineOf(low, 3).path("store").asLong())
    }

    @Test @Order(11) fun `CART-3 删除回吐全量并更新角标`() {
        val after = ok("DELETE", "/api/h5app/wxapp/cartdel", mapOf("cart_id" to cartIdNut))
        assertEquals(0, after.path("valid_cart").size())
        assertEquals(0, after.path("invalid_cart").size())
        assertEquals(0, ok("GET", "/api/h5app/wxapp/cartcount").path("count").asInt())
        fail("DELETE", "/api/h5app/wxapp/cartdel", mapOf("cart_ids" to emptyList<Any>()), 400)
    }

    @Test @Order(12) fun `鉴权与校验 匿名 401 入参非法 400 商品不存在 404`() {
        mvc.perform(get("/api/h5app/wxapp/cart")).andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Unauthenticated."))
        fail("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 2, "num" to 0), 400)
        fail("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 999999, "num" to 1), 404)
        fail("PUT", "/api/h5app/wxapp/cartupdate/num", mapOf("cart_id" to 999999, "num" to 1), 404)
        fail("PUT", "/api/h5app/wxapp/cartupdate/checkstatus", mapOf("cart_id" to 1), 400)
        fail("PUT", "/api/h5app/wxapp/cartupdate/checkstatus", mapOf("is_checked" to true), 400)
        val id = run {
            ok("POST", "/api/h5app/wxapp/cart", mapOf("item_id" to 2, "num" to 1))
            lineOf(ok("GET", "/api/h5app/wxapp/cart"), 2).path("cart_id").asLong()
        }
        fail("PUT", "/api/h5app/wxapp/cartupdate/num", mapOf("cart_id" to id, "num" to -1), 400)
        ok("PUT", "/api/h5app/wxapp/cartupdate/num", mapOf("cart_id" to id, "num" to 1))
        // 跨租户隔离：另一会员删不到这条行
        assertEquals(1, ok("GET", "/api/h5app/wxapp/cartcount").path("count").asInt())
    }
}
