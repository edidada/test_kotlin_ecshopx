package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.wx.WxAcodeForm
import com.ecshopx.kotlin.service.wx.WxAcodeService
import com.ecshopx.kotlin.service.wx.WxAppService
import com.ecshopx.kotlin.service.wx.WxBindForm
import com.ecshopx.kotlin.service.wx.WxDirectBindForm
import com.ecshopx.kotlin.service.wx.WxMessageService
import com.ecshopx.kotlin.service.wx.WxSendMessageForm
import com.ecshopx.kotlin.service.wx.WxTemplateForm
import com.ecshopx.kotlin.service.wx.WxTemplateService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 微信能力后台面（WX-A1..A9，docs/04 §27.3，前缀 `/api/wechat`）。
 *
 * 三条权限码（§27.5）：`wechat.lists`（A4/A5 看）、`wechat.update`（A1..A3/A6/A7 凭据与模板）、
 * `wechat.push`（A8/A9 **主动连微信发包**）。push 单列一码的出处与 `webhook.push` 相同：
 * 前者是"能改配置"，后者是"能让服务器现在就往外发一次请求"——出事时看的是动作那一位。
 *
 * POST 族收裸 map 而不是 typed DTO：`direct_bind` 的出参是"入参原样 + 服务补的绑定事实"，
 * typed 表单会先把没声明的键丢掉，出参就不再能照原样回显（与 WH-A9 同一条裁定）。
 */
@RestController
@RequestMapping("/api/wechat")
class WechatAdminController(
    private val apps: WxAppService,
    private val templates: WxTemplateService,
    private val messages: WxMessageService,
    private val codes: WxAcodeService,
) {

    /** WX-A1 预授权 URL：`redirect_uri` 是跳回来的地址，拼在 bindcomponent 的 query 里 */
    @Audited(route = "wechat.openplatform.pre_auth_url")
    @RequiresResource("wechat.update")
    @GetMapping("/openplatform/pre_auth_url")
    fun preAuthUrl(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(apps.preAuthUrl(cid(p), raw["redirect_uri"]))

    /** WX-A2 授权码回调绑定（第三方授权链） */
    @Audited(route = "wechat.openplatform.bind")
    @RequiresResource("wechat.update")
    @PostMapping("/openplatform/bind")
    fun bind(
        @RequestBody(required = false) raw: Map<String, Any?>?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(apps.bind(cid(p), WxBindForm(bindStr(raw, "authorization_code"), bindStr(raw, "app_id"))))

    /** WX-A3 直连绑定（app_id + app_secret，词表守卫在 service） */
    @Audited(route = "wechat.openplatform.direct_bind")
    @RequiresResource("wechat.update")
    @PostMapping("/openplatform/direct_bind")
    fun directBind(
        @RequestBody(required = false) raw: Map<String, Any?>?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(
        apps.directBind(
            cid(p),
            WxDirectBindForm(bindStr(raw, "app_id"), bindStr(raw, "app_secret"), bindStr(raw, "app_type")),
        ),
    )

    /** WX-A4 当前租户的授权信息（出参不含 `app_secret`，服务层列全在装配处） */
    @RequiresResource("wechat.lists")
    @GetMapping("/openplatform/authorizer")
    fun authorizer(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(apps.authorizer(cid(p)))

    /** WX-A5 模板列表（`type` = oa 公众号模板消息 / wxa 订阅消息，两套词表两条 API） */
    @RequiresResource("wechat.lists")
    @GetMapping("/templates")
    fun templates(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(
        templates.list(cid(p), raw["type"], raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull()),
    )

    /** WX-A6 新增模板（`short_id` 短码 / `template_id` 全名，至少给一个；词表在服务） */
    @Audited(route = "wechat.template_create")
    @RequiresResource("wechat.update")
    @PostMapping("/template")
    fun add(
        @RequestBody(required = false) raw: Map<String, Any?>?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(
        templates.add(
            cid(p),
            WxTemplateForm(bindStr(raw, "type"), bindStr(raw, "short_id"), bindStr(raw, "template_id"), bindStr(raw, "title")),
        ),
    )

    /** WX-A7 删模板。真链删除靠 `template_id`（A7 传出的就是它），mock 的注册表按自增 id 记 */
    @Audited(route = "wechat.template_delete")
    @RequiresResource("wechat.update")
    @DeleteMapping("/template/{id}")
    fun delete(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(templates.delete(cid(p), id))

    /** WX-A8 发模板消息/订阅消息（`wechat.push`——这一位是"能让服务器现在就开始动作"） */
    @Audited(route = "wechat.template_send")
    @RequiresResource("wechat.push")
    @PostMapping("/template/send")
    fun send(
        @RequestBody(required = false) raw: Map<String, Any?>?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(
        messages.send(
            cid(p),
            WxSendMessageForm(
                touser = bindStr(raw, "touser"),
                templateId = bindStr(raw, "template_id"),
                page = bindStr(raw, "page"),
                data = raw?.get("data") as? Map<String, Any?>,
            ),
        ),
    )

    /** WX-A9 小程序码四形态：二进制形态（code/unlimit）直接回图片字节，JSON 形态（link/schema）走包络 */
    @RequiresResource("wechat.push")
    @PostMapping("/wxa_code")
    fun wxaCode(
        @RequestBody(required = false) raw: Map<String, Any?>?,
        @AuthenticationPrincipal p: Principal?,
    ): ResponseEntity<Any> {
        val res = codes.generate(
            cid(p),
            WxAcodeForm(
                path = bindStr(raw, "path"),
                width = bindStr(raw, "width")?.toIntOrNull(),
                scene = bindStr(raw, "scene"),
                type = bindStr(raw, "type"),
            ),
        )
        return if (res.binary != null) {
            ResponseEntity.ok().contentType(MediaType.parseMediaType(res.contentType ?: "image/png")).body(res.binary)
        } else {
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.ok(res.json))
        }
    }

    private fun bindStr(raw: Map<String, Any?>?, key: String): String? =
        raw?.get(key)?.let { (it as? Number)?.toString() ?: (it as? String)?.takeIf { s -> s.isNotBlank() } }

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
