package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.contract.ContractDiff
import com.ecshopx.kotlin.boot.contract.SupportContract
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0-8 门槛：支撑面端到端（SUP-1 配置、SUP-2 上传+静态回读、SUP-4 审计日志）。
 *
 * 上传落盘到 `target/test-storage`（构建目录，跑完即弃），
 * 但 `/storage` 静态映射必须真的把刚写入的字节原样吐回来——只测"落库"不测"能访问"等于没测完。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:exccsupport;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "ecx.schedule.order-timeout-initial-delay-ms=3600000",
        "ecx.schedule.order-timeout-ms=3600000",
        "ecx.upload.root=target/test-storage",
    ],
)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SupportEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private var admin = ""
    private var viewer = ""

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun call(method: String, url: String, token: String?, body: Map<String, Any?>?): MvcResult {
        val req = when (method) {
            "GET" -> get(url)
            "POST" -> post(url)
            "PUT" -> put(url)
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

    private fun okAdmin(url: String, body: Map<String, Any?>? = null, m: String = "GET") =
        data(call(m, url, admin, body), "admin $m $url")

    private fun expect(method: String, url: String, body: Map<String, Any?>?, code: Int, token: String? = admin): JsonNode {
        val res = call(method, url, token, body)
        assertEquals(code, res.response.status, "$method $url -> " + utf8(res.response.contentAsByteArray))
        return mapper.readTree(utf8(res.response.contentAsByteArray))
    }

    private fun assertContract(node: JsonNode, expected: Set<String>, label: String) {
        val actual = ContractDiff.keysOf(node)
        val r = ContractDiff.diff(actual, expected, label)
        assertTrue(r.ok, ContractDiff.failMessage(r, actual))
    }

    private fun upload(name: String, type: String, bytes: ByteArray, token: String? = admin): MvcResult =
        mvc.perform(
            multipart("/api/upload/image").file(MockMultipartFile("file", name, type, bytes))
                .also { b -> token?.let { b.header("Authorization", "Bearer $it") } },
        ).andReturn()

    /** PNG 头 8 字节 + 任意载荷，够判定"是图片"，也够验证字节原样回读 */
    private fun png() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64) { (it % 7).toByte() }

    @Test @Order(1) fun `后台令牌就绪`() {
        admin = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "admin", "password" to "admin" + "123")), "admin").path("token").asText()
        viewer = data(call("POST", "/api/operator/login", null,
            mapOf("username" to "viewer", "password" to "viewer" + "123")), "viewer").path("token").asText()
        assertTrue(admin.isNotBlank() && viewer.isNotBlank())
    }

    @Test @Order(2) fun `SUP-1 配置读写 租户层覆盖全局层`() {
        val all = okAdmin("/api/config")
        assertEquals(all.path("total_count").asLong(), all.path("list").size().toLong(), "total_count 与实际行数一致")
        assertContract(all.path("list")[0], SupportContract.CONFIG_ROW, "SUP-1 配置行")
        assertTrue(all.path("list").any { it.path("config_key").asText() == "pay.wxpay.enable" }, "seed 支付开关读得到")

        // 写一个租户键：新增
        val written = okAdmin("/api/config", mapOf("site.contact_phone" to "0755-00000000"), m = "PUT")
            .path("list").first { it.path("config_key").asText() == "site.contact_phone" }
        assertEquals("0755-00000000", written.path("config_value").asText())
        assertEquals(1L, written.path("company_id").asLong(), "写入只落租户层")

        // 覆盖全局键：读到的是本租户覆盖行，全局行分毫未动
        okAdmin("/api/config", mapOf("system.is_saas" to "true"), m = "PUT")
        val saas = okAdmin("/api/config").path("list").first { it.path("config_key").asText() == "system.is_saas" }
        assertEquals("true", saas.path("config_value").asText(), "同键取租户层")
        assertEquals(1L, saas.path("company_id").asLong())
        assertEquals("false", jdbc.queryForObject(
            "SELECT config_value FROM sys_config WHERE company_id = 0 AND config_key = 'system.is_saas'", String::class.java,
        ), "全局默认值不得被单租户写坏")

        // 对象型配置序列化存 JSON 文本；重复 PUT 是 upsert 不是插重行
        okAdmin("/api/config", mapOf("site.tdk.title" to "新标题", "order.auto_cancel_minutes" to 45), m = "PUT")
        assertEquals(1L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM sys_config WHERE company_id = 1 AND config_key = 'site.tdk.title'", Long::class.java,
        ), "同键覆盖而非追加")
        val obj = okAdmin("/api/config", mapOf("ship.cities" to listOf("深圳", "北京")), m = "PUT")
            .path("list").first { it.path("config_key").asText() == "ship.cities" }
        assertEquals("""["深圳","北京"]""", obj.path("config_value").asText(), "数组值存 JSON 文本")
        expect("PUT", "/api/config", emptyMap<String, Any?>(), 400)
    }

    @Test @Order(3) fun `SUP-2 图片上传 落库与静态回读`() {
        val bytes = png()
        val r = data(upload("demo.png", "image/png", bytes), "upload")
        assertContract(r, SupportContract.UPLOAD, "SUP-2 出参")
        val path = r.path("path").asText()
        val url = r.path("url").asText()
        assertTrue(url.startsWith("/storage/$path"), "url = 前缀 + 相对路径：$url")
        assertEquals("png", r.path("file_type").asText())
        assertEquals(bytes.size.toLong(), r.path("file_size").asLong())

        // 静态面：不带令牌也能取回，字节必须完全一致
        val got = mvc.perform(get(url)).andReturn()
        assertEquals(200, got.response.status, "静态资源应可访问：$url")
        assertTrue(bytes.contentEquals(got.response.contentAsByteArray), "回读字节与上传一致")
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM upload_files WHERE file_path = ?", Long::class.java, path), "台账留痕")
        assertTrue(Files.exists(Paths.get("target/test-storage", path)), "确实落盘")
        // 原始文件名进台账但被清洗，落盘名一律是生成的 uuid 名
        assertEquals("demo.png", r.path("file_name").asText())
        assertTrue(!path.contains("demo.png"), "存储名不含原始文件名")

        expect("POST", "/api/upload/image", null, 401, token = null)
        val notImage = upload("evil.txt", "text/plain", "hello".toByteArray())
        assertEquals(400, notImage.response.status, utf8(notImage.response.contentAsByteArray))
        val badExt = upload("evil.exe", "image/png", bytes)
        assertEquals(400, badExt.response.status, utf8(badExt.response.contentAsByteArray))
        val empty = upload("e.png", "image/png", ByteArray(0))
        assertEquals(400, empty.response.status, utf8(empty.response.contentAsByteArray))
        // viewer 无 upload.image 权限 → 403
        assertEquals(403, upload("v.png", "image/png", bytes, token = viewer).response.status)
    }

    @Test @Order(4) fun `SUP-4 审计日志分页与过滤`() {
        val all = okAdmin("/api/operator/logs")
        assertTrue(all.path("total_count").asLong() >= 1, "SUP-1 写入已被 @Audited 记入日志")
        assertContract(all.path("list")[0], SupportContract.OPLOG_ROW, "SUP-4 日志行")
        assertTrue(all.path("list").any { it.path("route_name").asText() == "config.update" }, "config.update 留痕")
        // 倒序分页：第 2 页与第 1 页不重复，pageSize 生效
        val p1 = okAdmin("/api/operator/logs?page=1&pageSize=1")
        val p2 = okAdmin("/api/operator/logs?page=2&pageSize=1")
        assertEquals(1, p1.path("list").size())
        assertTrue(p1.path("list")[0].path("id").asLong() != p2.path("list")[0].path("id").asLong(), "翻页不重复")
        assertTrue(p1.path("list")[0].path("id").asLong() > p2.path("list")[0].path("id").asLong(), "按 id 倒序")
        val filtered = okAdmin("/api/operator/logs?keyword=config")
        assertTrue(filtered.path("list").all { it.path("route_name").asText().contains("config") }, "keyword 过滤")
        assertTrue(filtered.path("total_count").asLong() <= all.path("total_count").asLong())
        // 审计必须带操作人 ip/method
        assertEquals("PUT", all.path("list").first { it.path("route_name").asText() == "config.update" }.path("method").asText())
    }

    @Test @Order(5) fun `支撑面鉴权 未登录 401 无权限 403`() {
        expect("GET", "/api/config", null, 401, token = null)
        expect("PUT", "/api/config", mapOf("a" to "b"), 401, token = null)
        expect("GET", "/api/operator/logs", null, 401, token = null)
        expect("GET", "/api/config", null, 403, token = viewer)
        expect("GET", "/api/operator/logs", null, 403, token = viewer)
    }
}
