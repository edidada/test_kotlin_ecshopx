package com.ecshopx.kotlin.service.wx

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.wx.WxAppDO
import com.ecshopx.kotlin.dao.wx.WxAppMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 回调面（WX-G1 / WX-G2，docs/04 §27.3）。
 *
 * 两类回调**形状不同但都在这同一个服务里处理**——源系统 `OpenPlatform::notify` 与
 * 公众号/小程序的事件入口各自一段，本仓把它们收成一族，理由与 SystemLink 的 OME 一致：
 * "同一个微信在叫你"，分开写迟早有一半的通知把另一半的守卫漏了。但守卫按各自认领。
 *
 * **Verify 次序是这一族的位次**：
 *  1. `signature` 验签（sha1 三连）——不通过连报文都不解；
 *  2. 加密体（真实 profile 的 `Encrypt`）尝试解密——解不开 → 400 且不落任何库；
 *  3. 事件分发。§27.6 门槛⑤的"解密失败 → 400"钉在第 2 位。
 */
@Service
class WxNotifyService(
    private val apps: WxAppMapper,
    private val state: WxComponentState,
    private val mapper: ObjectMapper,
    @Value("\${ecx.wx.component-token:}") private val componentToken: String,
    @Value("\${ecx.wx.component-aes-key:}") private val componentAesKey: String,
    @Value("\${ecx.wx.component-app-id:}") private val componentAppId: String,
) {

    // ===================== WX-G2 第三方平台事件 =====================

    /** GET 握手：签名带的 echostr 原样吐回即是握手成功 */
    fun componentEcho(appid: String?, params: Map<String, String>): String {
        if (!WxCrypt.verifySignature(componentToken, params["timestamp"], params["nonce"], params["signature"])) {
            throw BizException(ErrorCode.VALIDATION, "签名校验失败", emptyMap())
        }
        return params["echostr"] ?: ""
    }

    /**
     * POST：事件体。加密形态（真链，`Encrypt` 字段）与明文 JSON（`InfoType` 直给）都收。
     * 分发对象：`component_verify_ticket` 是**平台级**凭据，只进 [WxComponentState] 的进程内状态，
     * 不落任何租户表；`authorized`/`updateauthorized`/`unauthorized` 写租户授权行
     * （落 `wx_app`，§27.6 门槛⑤"落 wx_app"的真身——ticket 只进状态，事件才落表）。
     */
    fun componentEvent(appid: String?, query: Map<String, String>, body: String?): String {
        if (!WxCrypt.verifySignature(componentToken, query["timestamp"], query["nonce"], query["signature"])) {
            throw BizException(ErrorCode.VALIDATION, "签名校验失败", emptyMap())
        }
        val doc: JsonNode = if (body.isNullOrBlank()) {
            throw BizException(ErrorCode.VALIDATION, "事件体为空", emptyMap())
        } else if (body.trimStart().startsWith("{")) {
            mapper.readTree(body)
        } else {
            val encrypted = XML_FIELD.findAll(body).firstOrNull { it.groupValues[1] == "Encrypt" }?.groupValues[2]
            if (encrypted != null) {
                val plain = WxCrypt.decrypt(
                    encrypted, componentAesKey,
                    if (componentAppId.isBlank()) null else componentAppId,
                ) ?: throw BizException(ErrorCode.VALIDATION, "事件解密失败", emptyMap())
                mapper.readTree(plain)
            } else {
                // XML 明文（只在未配置加密的开发/测试环境出现）：把 XML 提成 map 再转 JSON 节点
                val fields = XML_FIELD.findAll(body)
                    .associate { it.groupValues[1] to it.groupValues[2] as Any? }
                mapper.valueToTree(fields)
            }
        }

        val infoType = doc.path("InfoType").asText("")
        val authorizerAppId = doc.path("AuthorizerAppId").asText("")
        val componentAppIdField = doc.path("AppId").asText(appid ?: "")
        when (infoType) {
            "component_verify_ticket" -> {
                val ticket = doc.path("ComponentVerifyTicket").asText("")
                if (ticket.isNotBlank()) state.offer(componentAppIdField, ticket, TimeSec.now())
            }
            "authorized", "updateauthorized" -> {
                if (authorizerAppId.isNotBlank()) {
                    upsertAuthorizedEvent(authorizerAppId, doc)
                }
            }
            "unauthorized" -> {
                if (authorizerAppId.isNotBlank()) {
                    apps.markUnauthorized(authorizerAppId, TimeSec.now())
                }
            }
            else -> {
                // 微信家会往同一条 URL 上发别的（如 `component_verify_ticket` 之外的杂项事件）。
                // 未知的属于"收到、不动作"——回 success 让它别重试。
            }
        }
        return "success"
    }

    // ===================== WX-G1 应用侧事件 =====================

    fun appEvent(appid: String, query: Map<String, String>, body: String?): String {
        if (!WxCrypt.verifySignature(componentToken, query["timestamp"], query["nonce"], query["signature"])) {
            throw BizException(ErrorCode.VALIDATION, "签名校验失败", emptyMap())
        }
        if (body.isNullOrBlank()) return "success"
        // 应用侧回调的真实内容在 real profile 是 XML + 加密；mock 只需要 ack（留痕在发侧已落 wx_message_log）。
        // 关注/取关、模板送达这些事件在本系统的对应动作是"会员绑定关系/AccessToken 刷新"，
        // 那两件事由各自的登录/买货流程推动——这里只负责让微信别重试。
        return "success"
    }

    // ===================== 内部 =====================

    private fun upsertAuthorizedEvent(appId: String, doc: JsonNode) {
        val now = TimeSec.now()
        val rows = apps.listByAppId(appId)
        if (rows.isEmpty()) {
            // 事件先于绑定（或安装从来没来过本仓）：不知道是哪家租户——不猜，直接丢
            return
        }
        rows.forEach { row: WxAppDO ->
            apps.updateByCompanyAndApp(
                companyId = row.companyId,
                appId = appId,
                appSecret = null,
                appType = null,
                authorizerRefreshToken = doc.path("AuthorizationCodeRefreshToken").asText(null)
                    ?: doc.path("authorizer_refresh_token").asText(null),
                nickName = doc.path("NickName").asText(null),
                verifyType = null,
                principalName = doc.path("PrincipalName").asText(null),
                status = 1,
                expiresAt = doc.path("AuthorizationCodeExpiredTime")
                    .takeIf { it.canConvertToLong() && it.asLong() > 0 }?.asLong(),
                updated = now,
            )
        }
    }

    companion object {
        const val InfoType = "InfoType"

        /** XML 的字段抽取（公众号/小程序事件的真实媒体格式；NotifyController 同族写法） */
        private val XML_FIELD = Regex("<([a-zA-Z_][a-zA-Z0-9_]*)>(?:<!\\[CDATA\\[)?([^<]*?)(?:\\]\\]>)?</\\1>")
    }
}
