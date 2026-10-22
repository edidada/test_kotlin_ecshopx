package com.ecshopx.kotlin.service.wx

import com.ecshopx.kotlin.common.domain.TimeSec
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * 微信开放平台 HTTP 客户端 + 两条凭据链的 access_token 获取。
 *
 * §27 盘点 6：两条链**不能混**——
 *  - **component 链**（第三方平台）：`component_access_token` 由 `component_verify_ticket` 换来
 *    （ticket 只能从 WX-G2 的事件回调拿，是"回调先到、调用才能通"的顺序），
 *    authorizer 的 access_token 再由 refresh token + component token 换。
 *  - **直连链**（租户自己抄 app_id + app_secret 的 oa/wxa 应用）：`/cgi-bin/token` 一个请求搞定。
 *
 * 票据只在进程内缓存（scratchpad）：组件 token 2 小时、authorizer token 按返回的过期时间。
 * 重启后从 refresh token 重拿一次，源系统同样不落库。
 */
@Component
class WxApiClient(
    private val state: WxComponentState,
    private val mapper: ObjectMapper,
    @Value("\${ecx.wx.api-base:https://api.weixin.qq.com}") private val apiBase: String,
    @Value("\${ecx.wx.component-app-id:}") private val componentAppId: String,
    @Value("\${ecx.wx.component-app-secret:}") private val componentAppSecret: String,
    @Value("\${ecx.wx.profile:mock}") private val profile: String,
) {

    val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun isReal(): Boolean = profile.equals("real", ignoreCase = true)

    /** component 路径需要四件套；缺任一 → 一次性说清缺什么（不供启动失败，配置晚到也能用） */
    fun requireComponentConfig() {
        val missing = listOf(
            "component-app-id" to componentAppId,
            "component-app-secret" to componentAppSecret,
        ).filter { it.second.isBlank() } +
            if (state.latestTicket() == null) listOf("component_verify_ticket（还未收到一次微信开放平台事件）" to "") else emptyList()
        if (missing.isNotEmpty()) {
            throw WxConfigException("微信开放平台配置不全：缺少 " + missing.joinToString("、") { it.first })
        }
    }

    // ===================== component 链 =====================

    /** `POST /cgi-bin/component/api_component_token`：ticket 换组件 token（2 小时，进程内缓存） */
    @Volatile
    private var componentTokenCache: Pair<String, Long>? = null // token to expiry(sec)

    @Synchronized
    fun componentAccessToken(): String {
        val cached = componentTokenCache
        if (cached != null && cached.second > TimeSec.now()) return cached.first
        requireComponentConfig()
        val r = postJson(
            path = "/cgi-bin/component/api_component_token",
            token = null,
            body = mapper.writeValueAsString(
                mapOf(
                    "component_appid" to componentAppId,
                    "component_app_secret" to componentAppSecret,
                    "component_verify_ticket" to state.latestTicket(),
                ),
            ),
        )
        val token = r.path("access_token").asText(null)
            ?: throw WxConfigException("取 component_access_token 失败：${r.path("errmsg").asText("")}")
        // 微信返回 expires_in（秒，通常 7200），提前 60 秒过期防边界撞车
        componentTokenCache = token to (TimeSec.now() + r.path("expires_in").asLong(7200L) - 60L)
        return token
    }

    /** `POST /cgi-bin/component/api_create_preauthcode`：预授权码（WX-A1 的真链） */
    fun createPreAuthCode(): String {
        val r = postJson(
            path = "/cgi-bin/component/api_create_preauthcode",
            token = componentAccessToken(),
            body = mapper.writeValueAsString(mapOf("component_appid" to componentAppId)),
        )
        return r.path("pre_auth_code").asText(null)
            ?: throw WxConfigException("取 pre_auth_code 失败：${r.path("errmsg").asText("")}")
    }

    /**
     * 授权码票据（WX-A2 的真链）。返回的是**明文票据四元组**，落库动作归调用方——
     * 这个方法只对微信说话，不碰租户表（渠道不碰订单表的同一纪律在这里同样成立）。
     */
    fun queryAuth(code: String): JsonNode {
        val r = postJson(
            path = "/cgi-bin/component/api_query_auth",
            token = componentAccessToken(),
            body = mapper.writeValueAsString(
                mapOf("component_appid" to componentAppId, "authorization_code" to code),
            ),
        )
        return r.path("authorization_info")
    }

    /** 看 authorizer 的最新票据（wx_template / wx_code / 权限集都从这里拿） */
    fun authorizerAccessToken(app: com.ecshopx.kotlin.dao.wx.WxAppDO): String {
        // 第三方授权链：refresh token 换 authorizer token
        val rt = app.authorizerRefreshToken
        if (!rt.isNullOrBlank()) {
            val r = postJson(
                path = "/cgi-bin/component/api_authorizer_token",
                token = componentAccessToken(),
                body = mapper.writeValueAsString(
                    mapOf(
                        "component_appid" to componentAppId,
                        "authorizer_appid" to app.appId,
                        "authorizer_refresh_token" to rt,
                    ),
                ),
            )
            return r.path("authorizer_access_token").asText(null)
                ?: throw WxConfigException("取 authorizer_access_token 失败：${r.path("errmsg").asText("")}")
        }
        // 直连链：app_secret 换 access_token
        val secret = app.appSecret?.takeIf { it.isNotBlank() }
            ?: throw WxConfigException("微信应用未配置 app_secret，无法获取接口凭据")
        val r = getJson(
            path = "/cgi-bin/token?grant_type=client_credential&appid=${enc(app.appId)}&secret=${enc(secret)}",
        )
        return r.path("access_token").asText(null)
            ?: throw WxConfigException("取 access_token 失败：${r.path("errmsg").asText("")}")
    }

    // ===================== HTTP =====================

    fun postJson(path: String, token: String?, body: String): JsonNode {
        val url = (apiBase.trimEnd('/') + path + (if (token != null) "?access_token=${enc(token)}" else ""))
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(WX_HTTP_TIMEOUT_SEC))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return mapper.readTree(res.body())
    }

    fun getJson(path: String): JsonNode {
        val req = HttpRequest.newBuilder(URI.create(apiBase.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(WX_HTTP_TIMEOUT_SEC))
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return mapper.readTree(res.body())
    }

    /** 二进制接口（小程序码一族）：微信对失败回 JSON、成功回图片字节——按 Content-Type 分流 */
    fun postBinary(path: String, token: String, body: String): Pair<ByteArray, String> {
        val url = apiBase.trimEnd('/') + path + "?access_token=${enc(token)}"
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(WX_HTTP_TIMEOUT_SEC))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        return res.body() to (res.headers().firstValue("content-type").orElse(""))
    }

    companion object {
        const val WX_HTTP_TIMEOUT_SEC = 5L

        fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8)
        fun dec(v: String): String = URLDecoder.decode(v, StandardCharsets.UTF_8)
    }
}

/** 调用微信 API 的配置性失败（不是业务码）：HTTP 5xx 不合适，422 才是"运营要去看配置"的答案 */
class WxConfigException(message: String) : RuntimeException(message)
