package com.ecshopx.kotlin.service.wx

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.wx.WxAppMapper
import com.ecshopx.kotlin.dao.wx.WxMessageLogDO
import com.ecshopx.kotlin.dao.wx.WxMessageLogMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * 发送面（WX-A8，docs/04 §27.3）。
 *
 * **两条硬判据来自 §27.6 门槛⑤**：
 *  1. 未绑定租户发消息 → 422 且 `wx_message_log` **不落行**——"没配置"与"发失败"不是一件事；
 *  2. mock profile 落库行 `mock=1` 且 `status=success`，**全程不出网**（无外网环境也必须绿）。
 *
 * 落库的位置是关键：mock 与真链都在**拿到业务判据之后**写留痕（模板是否存在、应用是否绑定），
 * 入参校验失败不落——那是调用方的错，不是发送的事件；落了它，留痕表就成了校验日志。
 */
@Service
class WxMessageService(
    private val apps: WxAppMapper,
    private val logs: WxMessageLogMapper,
    private val templates: WxTemplateService,
    private val client: WxApiClient,
    private val mapper: ObjectMapper,
) {

    fun send(companyId: Long, form: WxSendMessageForm): Map<String, Any?> {
        val touser = form.touser?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "接收人 openid 必填", mapOf("touser" to listOf("touser 必填")))
        val templateId = form.templateId?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "模板 id 必填", mapOf("template_id" to listOf("template_id 必填")))
        val data = form.data ?: emptyMap()
        if (data.isEmpty()) {
            throw BizException(ErrorCode.VALIDATION, "模板数据必填", mapOf("data" to listOf("data 必填")))
        }

        // 挑应用：oa 模板消息找 oa 应用、订阅消息找 wxa 应用；列表里哪一类都没有 → 拿任意活的行兜底
        val app = apps.listByCompany(companyId).firstOrNull { it.status == 1 && it.appType == "oa" }
            ?: apps.listByCompany(companyId).firstOrNull { it.status == 1 }
            ?: throw BizException(
                ErrorCode.BIZ, "当前租户未绑定公众号或小程序，无法发送模板消息",
                mapOf("app_id" to listOf("未绑定应用")),
            )

        val payload = mapper.writeValueAsString(data)
        val res: SendOutcome = when {
            client.isReal() -> sendReal(app, touser, templateId, form.page, data)
            templates.hasTemplate(companyId, templateId, "wxa") || templates.hasTemplate(companyId, templateId, "oa") ->
                SendOutcome(success = true, errcode = 0, errmsg = "mock:ok", mock = true)
            else -> SendOutcome(success = false, errcode = WX_ECODE_TEMPLATE_NOT_FOUND, errmsg = "模板不存在", mock = true)
        }
        logs.insert(
            WxMessageLogDO().apply {
                this.companyId = companyId
                this.appId = app.appId
                this.touser = touser
                this.templateId = templateId
                this.page = form.page
                this.data = payload
                this.status = if (res.success) "success" else "fail"
                this.errcode = res.errcode
                this.errmsg = res.errmsg
                this.mock = if (res.mock) 1 else 0
                this.created = TimeSec.now()
                this.updated = TimeSec.now()
            },
        )
        if (res.success) {
            return linkedMapOf(
                "status" to "success", "errcode" to 0, "errmsg" to "ok",
                "app_id" to app.appId, "mock" to res.mock,
            )
        }
        return linkedMapOf(
            "status" to "fail", "errcode" to res.errcode, "errmsg" to res.errmsg,
            "app_id" to app.appId, "mock" to res.mock,
        )
    }

    /** 真链：oa 走 template/send、wxa 走 subscribe/send，词表与判据按微信各自家约定 */
    private fun sendReal(
        app: com.ecshopx.kotlin.dao.wx.WxAppDO,
        touser: String,
        templateId: String,
        page: String?,
        data: Map<String, Any?>,
    ): SendOutcome {
        val path: String
        val body: Map<String, Any?> = when (app.appType) {
            "oa" -> {
                path = "/cgi-bin/message/template/send"
                buildMap {
                    put("touser", touser)
                    put("template_id", templateId)
                    page?.let { put("url", it) }
                    put("data", data.entries.associate { (k, v) -> k to mapOf("value" to v) })
                }
            }
            else -> {
                path = "/cgi-bin/message/subscribe/send"
                buildMap {
                    put("touser", touser)
                    put("template_id", templateId)
                    page?.let { put("page", it) }
                    put("data", data.entries.associate { (k, v) -> k to mapOf("value" to v) })
                }
            }
        }
        val r = client.postJson(path, client.authorizerAccessToken(app), mapper.writeValueAsString(body))
        val errcode = r.path("errcode").asInt(0)
        return SendOutcome(
            success = errcode == 0,
            errcode = errcode,
            errmsg = r.path("errmsg").asText(""),
            mock = false,
        )
    }

    /** 发送的三态归一：success / fail，外加"这是不是 mock 发的"这一位（留痕区分的是它不是状态） */
    private data class SendOutcome(val success: Boolean, val errcode: Int, val errmsg: String, val mock: Boolean)

    companion object {
        /** mock 分支的模拟错码：模板不存在（微信原话 40037，这里原样借用以保持词表一致） */
        const val WX_ECODE_TEMPLATE_NOT_FOUND = 40037
    }
}

/** WX-A8 入参（§27.3：touser/template_id/data 必填，page 可选） */
class WxSendMessageForm(
    val touser: String? = null,
    val templateId: String? = null,
    val page: String? = null,
    val data: Map<String, Any?>? = null,
)
