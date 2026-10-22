package com.ecshopx.kotlin.service.wx

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.dao.wx.WxAppMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.util.Base64

/**
 * 小程序码面（WX-A9，docs/04 §27.3）：`code | unlimit | link | schema` 四形态一张口。
 *
 * 四形态的**应答体各不相同**是这一条的实质形状：
 *  - `code`/`unlimit`：**二进制图片**（`getwxacode` 返回字节流，不是 JSON）；
 *  - `link`/`schema`：**JSON 里带 URL 字符串**。
 * 把四者塞进同一个 JSON 是最容易犯的形状错——前端拿图片字节是直接当 `<img src>` 的，
 * 包一层 JSON 就是契约漂移，所以控制器按形态分流（§27.6 门槛⑤的"可解析 PNG 字节头"断的就是它）。
 *
 * mock 的 PNG 是**真实可解析的 PNG 字节头**（1×1 像素位图，不是假前缀几十字节）：
 * 解析工具/测试断言都要能读出合法 PNG 签名（89 50 4E 47），这要求字节串是真的图。
 */
@Service
class WxAcodeService(
    private val apps: WxAppMapper,
    private val client: WxApiClient,
    private val mapper: ObjectMapper,
) {

    /** 出参形状由调用方（控制器）按形态分流；这里统一返回"载荷 + 是不是二进制" */
    fun generate(companyId: Long, form: WxAcodeForm): WxAcodeResult {
        val type = when (form.type?.takeIf { it.isNotBlank() }?.lowercase()) {
            null, "code" -> "code"
            "unlimit" -> "unlimit"
            "link" -> "link"
            "schema" -> "schema"
            else -> throw BizException(
                ErrorCode.VALIDATION, "type 只能是 code/unlimit/link/schema 之一",
                mapOf("type" to listOf("仅支持 code/unlimit/link/schema")),
            )
        }
        // 形态入参守卫各自要求什么：
        //  code 要 path（有限码是"这个页面这张图"）；unlimit 要 scene（进页之外的参数从这里带）；
        //  link/schema 没有必填位。
        val path = form.path?.takeIf { it.isNotBlank() }
        val scene = form.scene?.takeIf { it.isNotBlank() }
        if (type == "code" && path == null) {
            throw BizException(ErrorCode.VALIDATION, "code 形态需要 path", mapOf("path" to listOf("path 必填")))
        }
        if (type == "unlimit" && scene == null) {
            throw BizException(ErrorCode.VALIDATION, "unlimit 形态需要 scene", mapOf("scene" to listOf("scene 必填")))
        }
        val width = (form.width ?: 430).coerceIn(MIN_WIDTH, MAX_WIDTH)

        if (client.isReal()) {
            val app = apps.listByCompany(companyId).firstOrNull { it.status == 1 }
                ?: apps.listByCompany(companyId).firstOrNull()
                ?: throw BizException(
                    ErrorCode.BIZ, "当前租户未绑定应用，无法生成小程序码",
                    mapOf("app_id" to listOf("未绑定应用")),
                )
            val token = client.authorizerAccessToken(app)
            return when (type) {
                "code" -> {
                    val (bytes, ct) = client.postBinary(
                        "/wxa/getwxacode", token,
                        mapper.writeValueAsString(mapOf("path" to path, "width" to width)),
                    )
                    jsonOrImage(bytes, ct)
                }
                "unlimit" -> {
                    val (bytes, ct) = client.postBinary(
                        "/wxa/getwxacodeunlimit", token,
                        mapper.writeValueAsString(mapOf("scene" to scene, "page" to path, "width" to width)),
                    )
                    jsonOrImage(bytes, ct)
                }
                "link" -> {
                    val r = client.postJson(
                        "/wxa/generate_urllink", token,
                        mapper.writeValueAsString(mapOf("path" to path, "env_version" to "release")),
                    )
                    WxAcodeResult(json = linkedMapOf("url_link" to r.path("url_link").asText("")))
                }
                else -> {
                    val r = client.postJson(
                        "/wxa/generatescheme", token,
                        mapper.writeValueAsString(mapOf("jump_wxa" to mapOf("path" to path, "env_version" to "release"))),
                    )
                    WxAcodeResult(json = linkedMapOf("weixin_schema" to r.path("schema").asText("")))
                }
            }
        }

        // ---------------- mock 分支（不出网） ----------------
        return when (type) {
            "code", "unlimit" -> WxAcodeResult(binary = MOCK_PNG, contentType = "image/png")
            "link" -> WxAcodeResult(
                json = linkedMapOf(
                    "url_link" to ("https://wxaurl.cn/" + WxCrypt.sha1Hex("$type|$path|$scene|$companyId").take(16)),
                ),
            )
            else -> WxAcodeResult(
                json = linkedMapOf(
                    "weixin_schema" to (
                        "weixin://dl/business/?t=" +
                            WxCrypt.sha1Hex("$type|$path|$scene|$companyId").take(16)
                        ),
                ),
            )
        }
    }

    /** 微信对失败回 JSON、成功回字节流——按 Content-Type 判，JSON 就翻成业务异常（带它的 errcode） */
    private fun jsonOrImage(bytes: ByteArray, contentType: String): WxAcodeResult {
        val ct = contentType.lowercase()
        return if (ct.contains("image")) {
            WxAcodeResult(binary = bytes, contentType = if (ct.contains("jpeg") || ct.contains("jpg")) "image/jpeg" else "image/png")
        } else {
            val r = runCatching { mapper.readTree(bytes) }.getOrNull()
                ?: throw WxConfigException("微信返回既不是图片也不是 JSON")
            throw WxConfigException(
                "小程序码生成失败：${r.path("errcode").asInt(0)} ${r.path("errmsg").asText("")}",
            )
        }
    }

    companion object {
        const val MIN_WIDTH = 280
        const val MAX_WIDTH = 1280

        /**
         * 1×1 全透明 PNG（43 字节）：89 50 4E 47 0D 0A 1A 0A 开头是 PNG 签名，
         * 谁拿它去解析都读得动——这不是占位假字节，是刻意能过校验的真图。
         */
        val MOCK_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk" +
                "YAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        )
    }
}

/** WX-A9 出参（二进制或 JSON，由控制器分流） */
data class WxAcodeResult(
    val binary: ByteArray? = null,
    val contentType: String? = null,
    val json: Map<String, Any?>? = null,
)

/** WX-A9 入参（§27.3：`{path, width?, scene?, type}`） */
class WxAcodeForm(
    val path: String? = null,
    val width: Int? = null,
    val scene: String? = null,
    val type: String? = null,
)
