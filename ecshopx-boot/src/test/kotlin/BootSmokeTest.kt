package com.ecshopx.kotlin.boot

import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import javax.sql.DataSource
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        // 测试用内存库，独立于 ./data 生产文件
        "spring.datasource.url=jdbc:h2:mem:ecxtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    ],
)
@AutoConfigureMockMvc
class BootSmokeTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var ctx: ApplicationContext

    @Test fun `两个 surface 的 ping 均可达且响应为 data 包络`() {
        mvc.perform(get("/api/ping"))
            .andExpect(status().isOk)
            .andExpect(content().json("""{"data":{"surface":"admin","module":"ecshopx-web-admin"}}"""))
        mvc.perform(get("/api/h5app/ping"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.surface").value("front"))
    }

    @Test fun `Db 事务组件已装配`() {
        assertTrue(ctx.containsBean("db"), "expect com.ecshopx.kotlin.service.tx.Db bean named 'db'")
    }

    @Test fun `sql init 建表 77 张`() {
        val ds = ctx.getBean(DataSource::class.java)
        ds.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    // MODE=MySQL 下 information_schema.tables 含字典基表，必须按 BASE TABLE 过滤
                    "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema='public' AND table_type='BASE TABLE'"
                ).use { rs ->
                    rs.next()
                    // 71 = 原来的 70 张 + M1-4 第三批的 `distribution_distributor_salesman_role`（SM-A6 岗位表）
                    // 74 = 再加 M2-1 的 3 张：`openapi_developer`（密钥行）、`webhook_subscription`（订阅）、
                    //      `webhook_delivery_log`（出站投递 outbox）。
                    // 77 = 再加 M2-2 的 3 张：`systemlink_setting`（两把 token + 白名单）、
                    //      `systemlink_oms_queuelog`（调用留痕）、`company_license`（站点授权）。
                    // 79 = 再加 M2-2 微信族 2 张：`wx_app`（授权应用）、`wx_message_log`（出站消息留痕）。
                    // 80 = 再加 M2-2 支付族 1 张：`pay_channel_config`（三家聚合支付的租户级配置行）。
                    //      计数断言就是"新表有没有进 sql init"的守门，
                    //      改这张表的数量必须同步改这里，否则 M2 的表在真实环境建不出来而测试还绿着。
                    // 83 = 再加 M2-3 互动域 3 张：`user_checkin`（签到，uk(g,u,day) 幂等担保）、
                    //      `user_lottery_times`（每日加次领取态）、`user_lottery_log`（抽奖流水）。
                    // 86 = 再加 M2-3 批B 砍价域 3 张：`bargain_promotions`（活动）、
                    //      `user_bargain`（一份刀，uk(g,a,u)）、`user_bargain_log`（刀史）。
                    assertTrue(rs.getInt(1) == 86, "expect 86 base tables, got ${rs.getInt(1)}")
                }
            }
        }
    }
}
