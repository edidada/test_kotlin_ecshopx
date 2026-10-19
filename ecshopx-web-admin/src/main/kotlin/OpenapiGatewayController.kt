package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.service.openapi.OpenapiGatewayService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * OA-G1 / OA-G2 开放平台单入口（M2-1 §25.1）。
 *
 * 为什么放在 web-admin 而不是 web-front：这一面和 `NotifyController` 是同一类东西——
 * **机器对机器的匿名面**，认的是 `app_key + sign`，不是任何人的令牌。
 * `web-front` 那一批全在 h5app 前缀下（会员令牌面），把开放面混进去会让人以为它也吃 JWT。
 *
 * 两条入口共用一个方法：源系统就是 `ANY /api/openapi` 与 `ANY /api/openapi/{method}` 两条注册、
 * 同一个 `OpenapiCheck` + 同一张路由表。**不写第二条链、不写第二个分发函数**——
 * 两处闸门迟早漂开，而漂移的症状是"对端换了一种写法就报签名错误"。
 *
 * 出参刻意是裸 `Map`：`ApiEnvelopeAdvice.beforeBodyWrite` 只把 `null` 换成 `ApiResponse.EMPTY`，
 * 非 null 的 body 原样透出，所以网关那四个键的信封不会被站内的 `{status_code,...}` 包络再套一层。
 * 这也意味着**这里绝不能让异常漏出去**——一旦漏出去，接住它的是 `GlobalExceptionHandler`，
 * 对端拿到的就是站内错误体（§25.1 盘点第 3 条）。
 */
@RestController
class OpenapiGatewayController(private val gateway: OpenapiGatewayService) {

    /**
     * `@RequestMapping` 不带 `method` = 任意 HTTP 动词都进这里。
     *
     * 这不是偷懒：源系统这两条就是 `ANY`，动词与 method 是否匹配由注册表判（第 10 道闸门），
     * 判出来一律 `E4005`。如果在这里按 `@GetMapping/@PostMapping` 分流，Spring 会先回 405，
     * 而 405 带的是 HTML/空体，对端的信封解析当场炸——错误语义就从 `E4005` 漂成了"网络故障"。
     */
    @RequestMapping(value = ["/api/openapi", "/api/openapi/{method}"])
    fun invoke(
        @PathVariable(name = "method", required = false) pathMethod: String?,
        @RequestParam params: Map<String, String>,
        @RequestBody(required = false) raw: String?,
        request: HttpServletRequest,
    ): Any? = gateway.handle(
        pathMethod = pathMethod,
        verb = request.method,
        query = params,
        body = raw,
    )
}
