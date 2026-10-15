package com.ecshopx.kotlin.boot

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0-3 DoD：AUTH 5 端点全通 + 越权用例。
 * 与 BootSmokeTest 同一 datasource 属性以共享缓存 context。
 */
@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:ecxtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ],
)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AuthEndToEndTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate

    private fun login(username: String, password: String): String? {
        val res = mvc.perform(
            post("/api/operator/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(mapOf("username" to username, "password" to password))),
        ).andReturn()
        if (res.response.status != 200) return null
        return mapper.readTree(res.response.contentAsString).path("data").path("token").asText(null)
    }

    private fun memberLogin(content: String): String =
        mvc.perform(
            post("/api/h5app/wxapp/new_login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content),
        ).andReturn().response.contentAsString
            .let { mapper.readTree(it).path("data").path("token").asText() }

    @Test @Order(1) fun `AUTH-1 admin 登录拿到 JWT 三段式`() {
        val res = mvc.perform(
            post("/api/operator/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"admin","password":"admin123"}"""),
        ).andReturn()
        assertEquals(200, res.response.status, "login body=" + res.response.contentAsString)
        val token = mapper.readTree(res.response.contentAsString).path("data").path("token").asText()
        assertTrue(token.count { it == '.' } == 2, "应为 JWT 三段式: $token")
    }

    @Test @Order(2) fun `AUTH-1 密码错误 401 且文案与 PHP 一致`() {
        mvc.perform(
            post("/api/operator/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"admin","password":"wrong-pw"}"""),
        )
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("用户名或密码错误"))
            .andExpect(jsonPath("$.status_code").value(401))
    }

    @Test @Order(3) fun `AUTH-1 空用户名走校验映射`() {
        mvc.perform(
            post("/api/operator/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"","password":"x"}"""),
        )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errors.username[0]").value("用户名不能为空"))
    }

    @Test @Order(4) fun `AUTH-2 未带 token 401 Unauthenticated`() {
        mvc.perform(get("/api/token/refresh"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Unauthenticated."))
    }

    @Test @Order(5) fun `AUTH-2 刷新换发新 token 并可继续访问`() {
        val t = login("admin", "admin123")!!
        val newT = mvc.perform(get("/api/token/refresh").header("Authorization", "Bearer $t"))
            .andExpect(status().isOk).andReturn().response.contentAsString
            .let { mapper.readTree(it).path("data").path("token").asText() }
        mvc.perform(get("/api/test/perm-probe").header("Authorization", "Bearer $newT"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.ok").value(1))
    }

    @Test @Order(6) fun `AUTH-3 登出返回成功`() {
        val t = login("admin", "admin123")!!
        mvc.perform(get("/api/token/invalidate").header("Authorization", "Bearer $t"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.result").value(1))
    }

    @Test @Order(7) fun `越权 viewer 403 admin 200 匿名 401`() {
        val v = login("viewer", "viewer123")!!
        mvc.perform(get("/api/test/perm-probe").header("Authorization", "Bearer $v"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.message").value("无权访问该资源"))
        mvc.perform(get("/api/test/other-probe").header("Authorization", "Bearer $v"))
            .andExpect(status().isForbidden())
        mvc.perform(get("/api/token/refresh").header("Authorization", "Bearer ${login("admin", "admin123")}"))
            .andExpect(status().isOk)
        mvc.perform(get("/api/test/perm-probe"))
            .andExpect(status().isUnauthorized())
    }

    @Test @Order(8) fun `跨面 后台 token 打消费者刷新 401 反之亦然`() {
        val adminTok = login("admin", "admin123")!!
        mvc.perform(get("/api/h5app/wxapp/token/refresh").header("Authorization", "Bearer $adminTok"))
            .andExpect(status().isUnauthorized())
        val memberTok = memberLogin(
            """{"auth_type":"local","username":"13800000001","check_type":"mobile","vcode":"8888"}""",
        )
        mvc.perform(get("/api/token/refresh").header("Authorization", "Bearer $memberTok"))
            .andExpect(status().isUnauthorized())
    }

    @Test @Order(9) fun `AUTH-4 local 手机号 mock 验证码登录成功`() {
        mvc.perform(
            post("/api/h5app/wxapp/new_login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"auth_type":"local","username":"13800000001","check_type":"mobile","vcode":"8888"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value(1))
            .andExpect(jsonPath("$.data.token").exists())
    }

    @Test @Order(10) fun `AUTH-4 local 错误验证码 status0 不落 HTTP 错`() {
        mvc.perform(
            post("/api/h5app/wxapp/new_login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"auth_type":"local","username":"13800000001","check_type":"mobile","vcode":"0000"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value(0))
            .andExpect(jsonPath("$.data.error_message").value("验证码错误"))
    }

    @Test @Order(11) fun `AUTH-4 local 缺号 auto_register is_new 1 后为 0`() {
        fun isNew(content: String): Int =
            mvc.perform(
                post("/api/h5app/wxapp/new_login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(content),
            ).andReturn().response.contentAsString
                .let { mapper.readTree(it).path("data").path("is_new").asInt() }
        val c = """{"auth_type":"local","username":"13912345678","check_type":"mobile","vcode":"8888"}"""
        assertEquals(1, isNew(c))
        assertEquals(0, isNew(c))
    }

    @Test @Order(12) fun `AUTH-4 wxapp mock 同 code 两次 第二次 is_new 0`() {
        fun isNew(): Int =
            mvc.perform(
                post("/api/h5app/wxapp/new_login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"auth_type":"wxapp","code":"demo_code_1","appid":"wxdemo"}"""),
            ).andReturn().response.contentAsString
                .let { mapper.readTree(it).path("data").path("is_new").asInt() }
        assertEquals(1, isNew())
        assertEquals(0, isNew())
    }

    @Test @Order(13) fun `AUTH-5 会员 token 刷新成功`() {
        val t = memberLogin("""{"auth_type":"wxapp","code":"demo_code_1","appid":"wxdemo"}""")
        mvc.perform(get("/api/h5app/wxapp/token/refresh").header("Authorization", "Bearer $t"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.token").exists())
    }

    @Test @Order(14) fun `登录审计写入 operator_logs`() {
        login("admin", "admin123")
        val n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM operator_logs WHERE route_name = 'operator.login'", Integer::class.java,
        )
        assertTrue(n!! >= 3, "登录审计行数=$n")
    }
}
