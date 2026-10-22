package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.service.wx.WxNotifyService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 微信回调匿名面（WX-G1 / WX-G2，docs/04 §27.3）。
 *
 * **应答体是裸文本**，不是 JSON 包络：微信对接收方的应答校验是字符串级的——
 * 第三方平台事件要看到 `success`、应用侧握手要看到**发来的 echostr 原文**，
 * 任何包裹都会被对方当成"没听明白"然后按它的节奏无限重试。
 * （`ApiEnvelopeAdvice` 只替换 null、非 null 原样透出，所以裸 `String` 可以安全回到裸文本。）
 *
 * GET（URL 验证）与 POST（事件推送）走同一个方法——源系统 `mpSpring` 的回调路由也是一次注册两种方法，
 * method 按 `consumes` 分流在空 body 时会命中歧义（NotifyController 那条注释记过同形状的坑）。
 */
@RestController
class WechatNotifyController(private val notify: WxNotifyService) {

    /** WX-G2：第三方平台事件（`/api/wechat/openplatform/notify/{appid}`，appid 段 = component_appid） */
    @RequestMapping(
        value = ["/api/wechat/openplatform/notify/{appid}"],
        method = [RequestMethod.GET, RequestMethod.POST],
    )
    fun component(
        @PathVariable("appid") appid: String,
        @RequestParam params: Map<String, String>,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<String> {
        val out = if (params.containsKey("echostr")) {
            notify.componentEcho(appid, params)
        } else {
            notify.componentEvent(appid, params, body)
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(out)
    }

    /** WX-G1：公众号/小程序事件（`/api/wechat/notify/{appid}`，appid 段 = 授权应用自己的 app_id） */
    @RequestMapping(
        value = ["/api/wechat/notify/{appid}"],
        method = [RequestMethod.GET, RequestMethod.POST],
    )
    fun app(
        @PathVariable("appid") appid: String,
        @RequestParam params: Map<String, String>,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<String> {
        val out = if (params.containsKey("echostr")) {
            notify.componentEcho(appid, params)
        } else {
            notify.appEvent(appid, params, body)
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(out)
    }
}
