package com.ecshopx.kotlin.common

import com.ecshopx.kotlin.common.api.ApiError
import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.MoneyFen
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.domain.toYuanText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M0-2 覆盖率补齐：错误契约、单号枚举、金额/时间工具全成员 */
class ContractSupplementTest {

    @Test fun `ErrorCode 全枚举可用`() {
        ErrorCode.values().forEach { ec ->
            assertTrue(ec.code > 0 && ec.http in 400..599, "$ec 状态非法")
        }
        assertEquals(401, ErrorCode.UNAUTH.http)
        assertEquals(501, ErrorCode.NOT_IMPLEMENTED.code)
    }

    @Test fun `BizException 携带 errors 与 message`() {
        val e = BizException(ErrorCode.BIZ, "库存不足", mapOf("num" to listOf("超出可售")))
        assertEquals(ErrorCode.BIZ, e.ec)
        assertEquals("库存不足", e.message)
        assertEquals(listOf("超出可售"), e.errors["num"])
    }

    @Test fun `ApiError 与 PHP EspierErrorRespones 同形`() {
        val a = ApiError("库存不足", mapOf("num" to listOf("超出可售")), 422, 422)
        assertEquals("库存不足", a.message)
        assertEquals(422, a.status_code)
        assertEquals(a, a.copy())
        assertEquals(a.hashCode(), a.copy().hashCode())
    }

    @Test fun `ApiResponse ok EMPTY 与 data 成员`() {
        assertEquals(7, ApiResponse.ok(7).data)
        assertTrue(ApiResponse.EMPTY.data!!.isEmpty())
        val direct = ApiResponse(mapOf("k" to "v"))
        assertEquals(direct, direct.copy())
        assertTrue(direct.toString().contains("k"))
    }

    @Test fun `SeqName dbValue 与 prefix`() {
        SeqName.values().forEach { assertEquals(it.name.lowercase(), it.dbValue) }
        assertEquals("", SeqName.ORDER_NO.prefix)
        assertEquals("T", SeqName.TRADE_NO.prefix)
        assertEquals("R", SeqName.REFUND_NO.prefix)
        assertEquals("AS", SeqName.AFTERSALES_NO.prefix)
        assertEquals(3, SeqName.valueOf("AFTERSALES_NO").ordinal)
    }

    @Test fun `订单与支付状态常量`() {
        assertEquals("NOTPAY", OrderStatus.NOTPAY)
        assertEquals("WAIT_BUYER_CONFIRM", OrderStatus.WAIT_BUYER_CONFIRM)
        assertEquals("REFUND_PART", PayStatus.REFUND_PART)
    }

    @Test fun `MoneyFen 四则与比较`() {
        val a = MoneyFen.fromYuanText("12.34")
        val b = MoneyFen(100L)
        assertEquals(MoneyFen(1334L), a + b)
        assertEquals(MoneyFen(1134L), a - b)
        assertEquals(MoneyFen(2468L), a.times(2L))
        assertTrue(a > b)
        assertEquals(-1, MoneyFen(1).compareTo(MoneyFen(2)))
        assertEquals("12.34", a.toYuanText())
        assertEquals("0.00", MoneyFen.ZERO.toYuanText())
    }

    @Test fun `TimeSec now 与 of`() {
        val t = TimeSec.now()
        assertTrue(t > 1_700_000_000L)
        assertEquals(1_700_000_000L, TimeSec.of(1_700_000_000L).epochSecond)
        val stamp = TimeSec.nowStamp14()
        assertTrue(stamp in 100_000_000_000L..999_999_999_999L, "应为 12 位 yyMMddHHmmss，实际 $stamp")
        assertEquals("Asia/Shanghai", TimeSec.TZ.id)
    }
}
