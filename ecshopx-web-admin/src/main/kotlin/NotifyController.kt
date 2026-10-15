package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.service.pay.PayService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PAY-5 渠道异步回调（`api/notify` 前缀，webhook 组）。
 *
 * 与站内接口最大的不同：**不带 data 包络、不返回 JSON**，应答体是渠道约定的 XML 文本；
 * 匿名放行（见 SecurityConfig adminChain），任何异常都必须回结构化应答，
 * 否则渠道会按"处理失败"无限重试。重复通知靠 `trade` 的 CAS 天然幂等，一律回 SUCCESS。
 */
@RestController
class NotifyController(private val pay: PayService) {

    /**
     * 兼容两种渠道写法：`application/x-www-form-urlencoded`（支付宝系）与
     * `text/xml` 报文（微信支付 v2 系）。XML 只按 `<tag>value</tag>` / CDATA 取扁平字段。
     */
    @PostMapping(
        value = ["/api/notify/wxpay", "/api/notify/{channel}"],
        consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE, MediaType.ALL_VALUE],
    )
    fun notify(
        @PathVariable(name = "channel", required = false) channel: String?,
        @RequestParam params: Map<String, String>,
        @RequestBody(required = false) raw: String?,
    ): String {
        val merged = LinkedHashMap(params)
        if (params.isEmpty() && !raw.isNullOrBlank()) {
            XML_FIELD.findAll(raw).forEach { m -> merged[m.groupValues[1]] = m.groupValues[2].trim() }
        }
        channel?.let { merged.putIfAbsent("channel", it) }
        val res = runCatching { pay.notify(merged) }
            .getOrElse { PayService.NotifyResult(false, "回调处理异常：${it::class.simpleName}") }
        return ack(res.success, res.message)
    }

    private fun ack(success: Boolean, message: String): String =
        "<xml><return_code><![CDATA[${if (success) "SUCCESS" else "FAIL"}]]></return_code>" +
            "<return_msg><![CDATA[${message.replace("]]>", "]]]]><![CDATA[>")}]]></return_msg></xml>"

    companion object {
        private val XML_FIELD = Regex("<([a-zA-Z_][a-zA-Z0-9_]*)>(?:<!\\[CDATA\\[)?([^<]*?)(?:\\]\\]>)?</\\1>")
    }
}
