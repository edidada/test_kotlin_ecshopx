package com.ecshopx.kotlin.boot

import com.ecshopx.kotlin.boot.web.GlobalExceptionHandler
import com.ecshopx.kotlin.common.api.ApiError
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.validation.BindException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Advice 形状与 force-ok-status 兼容开关（详细设计 §2，URL 文档 §1/D2） */
class GlobalExceptionHandlerTest {

    private val advice = GlobalExceptionHandler(forceOk = false)
    private val adviceOk = GlobalExceptionHandler(forceOk = true)

    @Test fun `BizException 输出四件套且默认返回真实状态码`() {
        val e = BizException(ErrorCode.BIZ, "库存不足", mapOf("num" to listOf("超出可售库存")))
        val r = advice.onBiz(e)
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, r.statusCode)
        val body = r.body!!
        assertEquals("库存不足", body.message)
        assertEquals(mapOf("num" to listOf("超出可售库存")), body.errors)
        assertEquals(422, body.code)
        assertEquals(422, body.status_code)
    }

    @Test fun `force-ok 时非 401 改 200 且 status_code 保留逻辑值`() {
        val r = adviceOk.onBiz(BizException(ErrorCode.NOT_FOUND, "商品不存在"))
        assertEquals(HttpStatus.OK, r.statusCode)
        assertEquals(404, r.body!!.status_code)
    }

    @Test fun `force-ok 时 401 保持 401`() {
        val r = adviceOk.onBiz(BizException(ErrorCode.UNAUTH, "token 已过期"))
        assertEquals(HttpStatus.UNAUTHORIZED, r.statusCode)
    }

    @Test fun `字段校验错误映射 errors`() {
        val br = BeanPropertyBindingResult(object {}, "cartAdd")
        br.addError(FieldError("cartAdd", "num", null, false, arrayOf("Max"), null, "数量不能超过 999"))
        val r = advice.onValidation(BindException(br))
        assertEquals(HttpStatus.BAD_REQUEST, r.statusCode)
        assertEquals(mapOf("num" to listOf("数量不能超过 999")), (r.body as ApiError).errors)
    }
}
