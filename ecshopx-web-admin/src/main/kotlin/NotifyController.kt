package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.service.pay.PayService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PAY-5 渠道异步回调（`api/notify` 前缀，webhook 组）。
 *
 * 与站内接口最大的不同：**v2 面不带 data 包络**，应答体是渠道约定的 XML 文本；v3 面是 JSON `{"code","message"}`。
 * 匿名放行（见 SecurityConfig adminChain），任何异常都必须回结构化应答，
 * 否则渠道会按"处理失败"无限重试。重复通知靠 `trade` 的 CAS 天然幂等，一律回 SUCCESS。
 */
@RestController
class NotifyController(private val pay: PayService) {

    /**
     * 同一个 URL 上的**两种到达方式**：v3 的 JSON + `Wechatpay-*` 头，v2/支付宝的表单与 XML。
     *
     * 为什么是一个方法而不是两个 `@PostMapping` 按 `consumes` 分流（M1-2 实测出来的坑）：
     * Spring 的 `consumes` 条件在**请求体为空**时一律视为匹配（Content-Type 只对"有 body 的请求"有意义），
     * 于是 `curl -X POST /api/notify/wxpay`（没有 body）会同时命中两条映射，
     * 抛 `IllegalStateException: Ambiguous handler methods mapped` → 500 且**不是渠道认识的应答体**，
     * 渠道会当成服务端故障无限重试。用 `MockMvc.param()` 发表单也是空 body，测试里同样撞得上。
     * 所以这里只注册一条映射，**分流看报文本身**：`{` 开头就是 v3 信封，其余按表单/XML 处理。
     */
    @PostMapping(value = ["/api/notify/wxpay", "/api/notify/{channel}"])
    fun notify(
        @PathVariable(name = "channel", required = false) channel: String?,
        @RequestHeader headers: Map<String, String>,
        @RequestParam params: Map<String, String>,
        @RequestBody(required = false) raw: String?,
    ): Any {
        val body = raw?.takeIf { it.isNotBlank() }
        if (body != null && body.trimStart().startsWith("{")) {
            // v3：验签与解密在渠道里做（PayChannel.parseNotification），这里只管应答码与体
            val res = runCatching { pay.notifyV3(channel, headers, body) }
                .getOrElse { PayService.NotifyResult(false, "回调处理异常：${it::class.simpleName}") }
            return ResponseEntity
                .status(if (res.success) HttpStatus.OK else HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(v3Json(res.success, res.message))
        }

        val merged = LinkedHashMap(params)
        if (params.isEmpty() && body != null) {
            XML_FIELD.findAll(body).forEach { m -> merged[m.groupValues[1]] = m.groupValues[2].trim() }
        }
        // **报文一个字都不能改**：渠道的签名覆盖"收到的全部参数"（支付宝 V1 是除 sign/sign_type 外
        // 按 key 升序拼 k=v&，微信 v2 的 MD5 同理），控制器往 map 里塞一个自己的 `channel` 键，
        // 渠道验签就必然过不去——真通知会被当成伪造报文一直重投。
        // 渠道身份通过参数传给 `notifyForm`，它在**验签之后**才把 `channel` 补进结清用的参数里。
        val res = runCatching { pay.notifyForm(channel, merged) }
            .getOrElse { PayService.NotifyResult(false, "回调处理异常：${it::class.simpleName}") }
        // 应答体优先用渠道自己给的格式（支付宝要纯文本 success），只有渠道没解析出来
        // （未知渠道、异常兜底）才退回微信 v2 的 XML 口径
        return res.formAck ?: ack(res.success, res.message)
    }

    /** 微信 v2 口径的兜底应答：能走到这里的渠道都还没注册成功，格式只能按最老的约定来 */
    private fun ack(success: Boolean, message: String): String =
        "<xml><return_code><![CDATA[${if (success) "SUCCESS" else "FAIL"}]]></return_code>" +
            "<return_msg><![CDATA[${message.replace("]]>", "]]]]><![CDATA[>")}]]></return_msg></xml>"

    /** 手工拼而不走 ObjectMapper：应答体必须逐字节稳定，且不依赖任何全局 Jackson 配置 */
    private fun v3Json(success: Boolean, message: String): String {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"code\":\"${if (success) "SUCCESS" else "FAIL"}\",\"message\":\"$escaped\"}"
    }

    companion object {
        private val XML_FIELD = Regex("<([a-zA-Z_][a-zA-Z0-9_]*)>(?:<!\\[CDATA\\[)?([^<]*?)(?:\\]\\]>)?</\\1>")
    }
}
