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
}
