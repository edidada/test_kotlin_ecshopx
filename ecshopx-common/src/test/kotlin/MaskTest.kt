package com.ecshopx.kotlin.common

import com.ecshopx.kotlin.common.security.Mask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M1-5 MASK-R1：六处手写掩码收敛到 common 后的形状断言。
 * 这里断的是**位数**，不是"有没有掩"——收敛这批的纪律是行为一字不改，
 * 各调用点在 e2e/冒烟里继续断自己那一处（docs/04 §23.3 第 12 条）。
 */
class MaskTest {

    @Test fun `mobile 固定四位星号`() {
        assertEquals("138****0001", Mask.mobile("13800000001"))
        // 12 位固话走 mobile 仍只挖中间四位，长度会短一位 —— 这是 phone 存在的理由
        assertEquals("057****6666", Mask.mobile("057188886666"))
    }

    @Test fun `mobile 短值与空值原样返回，null 不冒充空串`() {
        assertNull(Mask.mobile(null))
        assertEquals("", Mask.mobile(""))
        assertEquals("123456", Mask.mobile("123456"))
    }

    @Test fun `mobileOrEmpty 保留会员资料面的空串形状`() {
        assertEquals("", Mask.mobileOrEmpty(null))
        assertEquals("", Mask.mobileOrEmpty(""))
        assertEquals("138****0001", Mask.mobileOrEmpty("13800000001"))
    }

    @Test fun `phone 星号位数随长度补齐，掩后长度不变`() {
        val v = "057188886666"
        assertEquals("057*****6666", Mask.phone(v))
        assertEquals(v.length, Mask.phone(v)!!.length)
        assertEquals("138****0001", Mask.phone("13800000001"))
        assertNull(Mask.phone(null))
        assertEquals("", Mask.phone(""))
        assertEquals("12345", Mask.phone("12345"))
    }

    /**
     * M1-5 `INV-A1/A3` 新掩的三支。断的还是位数：
     * 税号与抬头名掩后**必须等长**（运营要对得上纸质发票），邮箱只允许本地部分变短为等长星号，
     * 域名一列字符都不能少 —— 前端拿它判断是不是公司邮箱。
     */
    @Test fun `taxNo 留前4后3，中间等长打星，短值整体打星`() {
        // 18 位统一社会信用代码：前 4（登记管理局区划+机构类别）+ 11 星 + 后 3
        val v = "91310000MA1FL0XY2K"
        assertEquals("9131" + "*".repeat(v.length - 7) + "Y2K", Mask.taxNo(v))
        assertEquals(v.length, Mask.taxNo(v)!!.length)
        // 15 位老税号（纯数字，地区码打头）同样等长
        assertEquals(15, Mask.taxNo("310101199003011")!!.length)
        // 认不出格式（<8 位）不等于可以不掩：整体打星，长度保留
        assertEquals("****", Mask.taxNo("1234"))
        assertEquals("*******", Mask.taxNo("7777777"))
        // 恰好 8 位：只有一颗星，仍是等长（留的是后三位 `678`，不是 `567`）
        assertEquals("1234*678", Mask.taxNo("12345678"))
        assertNull(Mask.taxNo(null))
        assertEquals("", Mask.taxNo(""))
        // blank 原样返回（"没有填"与"填了但脏"是两件事，出参里空串不该被渲染成一串星号）
        assertEquals("   ", Mask.taxNo("   "))
    }

    @Test fun `email 只掩本地部分，域名整留`() {
        assertEquals("z*******@ex.com", Mask.email("zhangsan@ex.com"))
        // 本地部分留**首**字符（与 mobile 留前 2 后 4 同一族：开头那一位才是"谁"的线索），域名一个字符都没动
        assertEquals("a*@qq.com", Mask.email("a1@qq.com"))
        // 单字符本地部分没有可掩的位数，原样返回（不是整体打星，免得看起来像脏值）
        assertEquals("a@ex.com", Mask.email("a@ex.com"))
        // 没有 @ 与 @ 打头这两种历史脏值：整体打星
        assertEquals("********", Mask.email("zhangsan"))
        assertEquals("*****", Mask.email("@ex.c"))
        assertNull(Mask.email(null))
        assertEquals("", Mask.email(""))
    }

    @Test fun `title 留前2后1，三字及以下整体打星`() {
        // 8 个字的企业名：前 2 + 5 星 + 末 1，掩后仍是 8 个字
        assertEquals("上海*****司", Mask.title("上海贸易有限公司"))
        assertEquals("上海贸易有限公司".length, Mask.title("上海贸易有限公司")!!.length)
        assertEquals("***", Mask.title("张三丰"))
        assertEquals("**", Mask.title("张三"))
        assertEquals("*", Mask.title("张"))
        assertNull(Mask.title(null))
        assertEquals("", Mask.title(""))
    }
}
