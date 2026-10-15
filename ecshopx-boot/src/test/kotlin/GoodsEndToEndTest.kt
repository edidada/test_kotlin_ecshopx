package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.GoodsContract
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M0-4 DoD：商品域端到端 + 出参契约 diff（后台建多规格 → 后台列表/SKU → 前台列表/详情 → 改 → 删）。
 * 注意：MockMvc 的 contentAsString 按 Latin-1 解无 charset 响应会乱码，这里统一按 UTF-8 取字节。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:ecxgoods;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class GoodsEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper

    private var itemId: Long = 0L

    private val adminToken: String
        get() = login("admin", "admin123")

    private fun login(user: String, pw: String): String {
        val res = mvc.perform(
            post("/api/operator/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("username" to user, "password" to pw))),
        ).andReturn()
        assertEquals(200, res.response.status, "login 失败: " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data").path("token").asText()
    }

    private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    private fun getJson(url: String, token: String? = null): JsonNode {
        val req = get(url)
        if (token != null) req.header("Authorization", "Bearer $token")
        val res = mvc.perform(req).andReturn()
        assertEquals(200, res.response.status, "GET $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray)).path("data")
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    private fun rowOf(data: JsonNode, id: Long): JsonNode =
        data.path("list").firstOrNull { it.path("item_id").asLong() == id }
            ?: throw AssertionError("列表未命中 item_id=$id，实际=${data.path("list")}")

    /** GOODS-3 入参：主行 + 2 个 SKU 子行（颜色/内存），带分类/条码/标签/服务标签/参数 */
    private fun newGoodsBody(name: String, bn: String) = mapOf(
        "item_name" to name,
        "item_bn" to bn,
        "price" to 123400,
        "store" to 66,
        "warning_store" to 3,
        "item_unit" to "台",
        "approve_status" to "onsale",
        "category" to listOf(2L),
        "pics" to listOf("/img/ct-1.jpg", "/img/ct-2.jpg"),
        "videos" to listOf("/v/ct.mp4"),
        "intro" to "<p>契约测试富文本</p>",
        "brand_id" to 7,
        "weight" to 1.5,
        "volume" to 2.0,
        "tax_rate" to 13,
        "barcodes" to listOf("6901056999001"),
        "tag_ids" to listOf(1L, 2L),
        "attrs" to listOf(mapOf("attribute_id" to 3, "attribute_value_id" to 5)),
        "type_labels" to listOf(
            mapOf("label_id" to 1, "label_name" to "全国联保", "label_price" to 0, "num_type" to "plus", "num" to 1, "limit_time" to 365),
        ),
        "spec_items" to listOf(
            mapOf(
                "item_name" to "$name 白 64G", "item_bn" to "CT-W64", "price" to 111100, "store" to 40,
                "barcode" to "6901056999018",
                "item_spec" to listOf(
                    mapOf("attribute_id" to 1, "attribute_value_id" to 1),
                    mapOf("attribute_id" to 2, "attribute_value_id" to 4),
                ),
            ),
            mapOf(
                "item_name" to "$name 黑 128G", "item_bn" to "CT-B128", "price" to 133300, "store" to 26,
                "item_spec" to listOf(
                    mapOf("attribute_id" to 1, "attribute_value_id" to 2),
                    mapOf("attribute_id" to 2, "attribute_value_id" to 3),
                ),
            ),
        ),
    )

    @Test @Order(1) fun `GOODS-3 后台新增多规格商品返回自增 item_id`() {
        val res = mvc.perform(
            post("/api/goods/items")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(newGoodsBody("契约平板A", "CT-TAB-A"))),
        ).andReturn()
        assertEquals(200, res.response.status, "create body=" + utf8(res.response.contentAsByteArray))
        itemId = mapper.readTree(utf8(res.response.contentAsByteArray)).path("data").path("item_id").asLong()
        assertTrue(itemId >= 100, "演示行占 1..4，新增应取 RESTART 后的序列: $itemId")
    }

    @Test @Order(2) fun `GOODS-1 列表出参字段与文档一致`() {
        val data = getJson("/api/goods/items?item_bn=CT-TAB-A", adminToken)
        assertContract(data, GoodsContract.LIST_ENVELOPE, "GOODS-1 data")
        assertEquals(10L, data.path("warning_store").asLong(), "sys_config item.warning_store=10")
        assertEquals(1, data.path("total_count").asLong())
        val row = rowOf(data, itemId)
        assertContract(row, GoodsContract.ITEM_ROW, "GOODS-1 商品行")
        assertFalse(row.has("item_spec"), "未开 is_sku 时不吐 item_spec")
        assertEquals(2, row.path("spec_items").size(), "主行聚合 2 个 SKU 子行")
        assertEquals(2, row.path("pics").size())
        assertEquals(1, row.path("type_labels").size())
        assertEquals(2, row.path("tagList").size())
        assertEquals(2L, row.path("item_cat_id").asLong())
        assertEquals(1L, row.path("item_main_cat_id").asLong(), "叶子分类 2 的祖先主分类应为 1")
        assertEquals(123400L, row.path("price").asLong(), "价格单位=分，出参不得换算")
    }

    @Test @Order(3) fun `GOODS-1 is_sku 展平后主行与子行都带 item_spec`() {
        val data = getJson("/api/goods/items?is_sku=true&goods_id=$itemId", adminToken)
        assertEquals(3, data.path("total_count").asLong(), "主行 + 2 子行")
        data.path("list").forEach { assertContract(it, GoodsContract.SKU_ROW, "GOODS-1 is_sku 行") }
        val child = data.path("list").first { !it.path("is_default").asBoolean() }
        assertEquals(2, child.path("item_spec").size())
        val specVals = child.path("item_spec").map { it.path("attribute_value").asText() }.toSet()
        assertTrue(
            specVals == setOf("白色", "256G") || specVals == setOf("黑色", "128G"),
            "子行 item_spec 应为该 SKU 的两根规格值，实际=$specVals",
        )
    }

    @Test @Order(4) fun `GOODS-6 SKU 列表同构且带规格轴`() {
        val data = getJson("/api/goods/sku?goods_id=$itemId", adminToken)
        assertContract(data, GoodsContract.LIST_ENVELOPE, "GOODS-6 data")
        assertEquals(3, data.path("total_count").asLong())
        data.path("list").forEach { assertContract(it, GoodsContract.SKU_ROW, "GOODS-6 行") }
    }

    @Test @Order(5) fun `GOODS-2 后台详情带 intro 与子行`() {
        val row = getJson("/api/goods/items/$itemId", adminToken)
        assertContract(row, GoodsContract.ITEM_ROW + "intro", "GOODS-2 详情")
        assertTrue(row.path("intro").asText().contains("富文本"))
        assertEquals(2, row.path("spec_items").size())
    }

    @Test @Order(6) fun `GOODS-7 前台匿名列表可访问且只出在售`() {
        val data = getJson("/api/h5app/wxapp/goods/items?item_bn=CT-TAB-A", null)
        assertContract(data, GoodsContract.LIST_ENVELOPE, "GOODS-7 data")
        assertEquals(1, data.path("total_count").asLong())
        assertContract(rowOf(data, itemId), GoodsContract.ITEM_ROW, "GOODS-7 商品行")
    }

    @Test @Order(7) fun `GOODS-8 前台详情聚合字段齐备且规格轴可读`() {
        val d = getJson("/api/h5app/wxapp/goods/items/$itemId", null)
        assertContract(d, GoodsContract.FRONT_DETAIL, "GOODS-8 详情")
        val first = d.path("spec_items")[0]
        assertContract(first, GoodsContract.SPEC_ITEM_ROW, "GOODS-8 spec_items 行")
        assertEquals("1-2", first.path("custom_spec_id").asText())
        assertEquals("白色/256G", first.path("item_spec").map { it.path("attribute_value").asText() }.joinToString("/"))
        assertTrue(first.path("custom_spec_name").asText().contains("、"), "规格名以顿号连接")
        assertEquals(2, d.path("item_spec_list").size(), "两根规格轴：颜色 / 内存")
        assertEquals("产地", d.path("item_params_list")[0].path("attribute_name").asText())
        assertEquals("中国大陆", d.path("item_params_list")[0].path("attribute_value").asText())
        assertContract(d.path("cur"), GoodsContract.CUR, "GOODS-8 cur")
        assertContract(d.path("tdk_data"), GoodsContract.TDK, "GOODS-8 tdk_data")
        assertEquals("CNY", d.path("cur").path("currency").asText())
        assertEquals("ECShopX Kotlin 演示商城", d.path("tdk_data").path("title").asText())
        assertEquals(123400L, d.path("member_price").asLong(), "M0-4 无会员折扣，先按售价回填")
        assertTrue(d.path("recommend_items").size() >= 1, "同分类（手机通讯）应有推荐位")
        assertEquals(2, d.path("tagList").size())
        assertEquals("6901056999001", d.path("barcode").asText())
    }

    @Test @Order(8) fun `GOODS-4 更新主行价格并追加第三个 SKU`() {
        val body = newGoodsBody("契约平板A", "CT-TAB-A") + mapOf(
            "price" to 150000,
            "spec_items" to listOf(
                mapOf(
                    "item_name" to "契约平板A 银 512G", "price" to 199900, "store" to 9,
                    "item_spec" to listOf(
                        mapOf("attribute_id" to 1, "custom_attribute_value" to "银色"),
                        mapOf("attribute_id" to 2, "custom_attribute_value" to "512G"),
                    ),
                ),
            ),
        )
        val res = mvc.perform(
            put("/api/goods/items/$itemId")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andReturn()
        assertEquals(200, res.response.status, "update body=" + utf8(res.response.contentAsByteArray))

        val d = getJson("/api/h5app/wxapp/goods/items/$itemId", null)
        assertEquals(150000L, d.path("price").asLong())
        assertEquals(3, d.path("spec_items").size(), "update 追加子行")
        val added = d.path("spec_items")[2]
        assertEquals("银色、512G", added.path("custom_spec_name").asText(), "自定义规格值同样进 custom_spec_name")
    }

    @Test @Order(9) fun `筛选语义 价格按元转分 库存原样 预警与分类展开`() {
        val tok = adminToken
        // price_lt=100 元 → 10000 分：只命中坚果 8900，若按分直传会命中全部
        val cheap = getJson("/api/goods/items?price_lt=100", tok)
        assertEquals(1, cheap.path("total_count").asLong(), "价格边界应以元入参×100")
        assertEquals("SKU-NUT-01", cheap.path("list")[0].path("item_bn").asText())
        // store_gt=150 原样比较：只有坚果 store=200 命中（×100 则无人命中）
        val rich = getJson("/api/goods/items?store_gt=150", tok)
        assertEquals(1, rich.path("total_count").asLong(), "库存边界不应换算")
        // 预警：store <= warning_store
        val warn = getJson("/api/goods/items?is_warning=true", tok)
        assertEquals("SKU-NUT-01", warn.path("list")[0].path("item_bn").asText())
        // 分类展开：父分类 1 命中挂在子分类 2 的商品
        val cat = getJson("/api/goods/items?category=1", tok)
        assertTrue(cat.path("total_count").asLong() >= 2, "父分类应展开到子分类，实际 ${cat.path("total_count")}")
        // 条码命中 SKU 子行（默认列表只看主行，需 is_sku）
        val bc = getJson("/api/goods/items?is_sku=true&barcode=6901234500011", tok)
        assertEquals("SKU-X1-W128", bc.path("list")[0].path("item_bn").asText())
    }

    @Test @Order(10) fun `GOODS-5 删除即逻辑下架且前台不可见`() {
        mvc.perform(delete("/api/goods/items/$itemId").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value(true))
        assertEquals("offline_sale", getJson("/api/goods/items/$itemId", adminToken).path("approve_status").asText())
        mvc.perform(get("/api/h5app/wxapp/goods/items/$itemId")).andExpect(status().isNotFound())
        assertEquals(0, getJson("/api/h5app/wxapp/goods/items?item_bn=CT-TAB-A", null).path("total_count").asLong())
    }

    @Test @Order(11) fun `鉴权 匿名 401 无资源角色 403`() {
        mvc.perform(get("/api/goods/items")).andExpect(status().isUnauthorized())
        mvc.perform(get("/api/goods/items").header("Authorization", "Bearer ${login("viewer", "viewer123")}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("无权访问该资源"))
    }

    @Test @Order(12) fun `校验失败 400 带字段错误文案`() {
        mvc.perform(
            post("/api/goods/items")
                .header("Authorization", "Bearer $adminToken")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("price" to 100, "store" to 1))),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.item_name[0]").value("商品名称不能为空"))
    }
}
