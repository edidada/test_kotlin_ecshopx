package com.ecshopx.kotlin.service.wx

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.wx.WxAppDO
import com.ecshopx.kotlin.dao.wx.WxAppMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 授权面（WX-A1..A4，docs/04 §27.3）。
 *
 * 两条绑定链落同一张 `wx_app` 表，差异只在来源凭据与 token 形态：
 *  - **授权码链**（A2）：票据来自微信侧，`authorizer_refresh_token` 有值、`app_secret` 为空；
 *    access_token 每次从 component token 换。
 *  - **直连链**（A3）：租户自己抄 app_id + app_secret，`authorizer_refresh_token` 为空；
 *    access_token 用 `/cgi-bin/token` 直换。
 *
 * mock profile 的含义只有一条：**不出网**。真链的差别全在 [WxApiClient] 的路径换法里，
 * 业务落库与出参形状两条链是同一个——换成 real profile 不该连带出参字段集的变化，
 * "换环境就换契约"是最难追的漂移。
 */
@Service
class WxAppService(
    private val apps: WxAppMapper,
    private val client: WxApiClient,
    @Value("\${ecx.wx.profile:mock}") private val profile: String,
    @Value("\${ecx.wx.component-app-id:}") private val componentAppId: String,
) {

    fun isMock(): Boolean = !profile.equals("real", ignoreCase = true)

    // ===================== WX-A1 预授权 URL =====================

    /**
     * 真链的号需要 component_appid + pre_auth_code + redirect_uri 三段；mock 侧 pre_auth_code
     * 用 `mock_ + 当秒 + 租户尾号` —— 这个 URL 只有运营在浏览器里点开才产生效果，
     * 服务端不需要预演那一步，断言只钉它成分齐全与参数进得去。
     */
    fun preAuthUrl(companyId: Long, redirectUri: String?): Map<String, Any?> {
        val comp = componentAppId.ifBlank { MOCK_COMPONENT_APPID }
        val pre = if (isMock()) {
            "mock_" + TimeSec.now() + "_" + (companyId % 10_000)
        } else {
            client.createPreAuthCode()
        }
        val red = redirectUri?.takeIf { it.isNotBlank() } ?: ""
        val url = "https://mp.weixin.qq.com/safe/bindcomponent" +
            "?component_appid=" + WxApiClient.enc(comp) +
            "&pre_auth_code=" + WxApiClient.enc(pre) +
            "&redirect_uri=" + WxApiClient.enc(red) +
            "&bind=1"
        return linkedMapOf("pre_auth_url" to url, "pre_auth_code" to pre)
    }

    // ===================== WX-A2 授权码回调绑定 =====================

    fun bind(companyId: Long, form: WxBindForm): Map<String, Any?> {
        val code = form.authorizationCode?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "授权码必填", mapOf("authorization_code" to listOf("authorization_code 必填")))
        val appId: String
        val model: WxAppDO
        if (isMock()) {
            // mock：不向微信要票据，授权码的"身份"就是 app_id（给了就用、没给从 SHA1 推一个）。
            // 推导是**确定性**的——同一次码重放得到同一个 app_id，e2e 与冒烟才能对得上。
            appId = form.appId?.takeIf { it.isNotBlank() }
                ?: "wx" + WxCrypt.sha1Hex("$code|$companyId").take(12)
            model = WxAppDO().apply {
                appType = "wxa"
                status = 1
                expiresAt = TimeSec.now() + MOCK_AUTH_EXPIRES_IN
            }
        } else {
            val info = client.queryAuth(code)
            appId = info.path("authorizer_appid").asText("").ifBlank {
                throw BizException(ErrorCode.BIZ, "授权码无效：微信未返回 authorizer_appid")
            }
            model = WxAppDO().apply {
                appSecret = null
                appType = "wxa" // 授权事件里没有可靠的应用形态判据（func_info 是权限集不是形态）；oa 一般走直连
                authorizerRefreshToken = info.path("authorizer_refresh_token").asText(null)
                nickName = info.path("nick_name").asText(null)
                verifyType = info.path("verify_type_info").path("id").asInt(0)
                principalName = info.path("principal_name").asText(null)
                status = 1
                expiresAt = TimeSec.now() + info.path("expires_in").asLong(MOCK_AUTH_EXPIRES_IN)
            }
        }
        saveOrUpdate(companyId, appId, model)
        return authorizerView(apps.find(companyId, appId))
    }

    // ===================== WX-A3 直连绑定 =====================

    fun directBind(companyId: Long, form: WxDirectBindForm): Map<String, Any?> {
        val appId = form.appId?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "app_id 必填", mapOf("app_id" to listOf("app_id 必填")))
        val secret = form.appSecret?.takeIf { it.isNotBlank() }
            ?: throw BizException(ErrorCode.VALIDATION, "app_secret 必填", mapOf("app_secret" to listOf("app_secret 必填")))
        val type = formType(form.appType)
        saveOrUpdate(
            companyId, appId,
            WxAppDO().apply {
                appSecret = secret
                appType = type
                status = 1
                // 直连没有"过期"概念：token 随时能再拿，只有租户解绑或微信侧清理才有终局
                expiresAt = PERMANENT_EXPIRES_AT
            },
        )
        return authorizerView(apps.find(companyId, appId))
    }

    // ===================== WX-A4 当前授权信息 =====================

    /** 最近一条（含已停用的——"它授权过"不该被停用抹掉，`status` 才是活不活） */
    fun authorizer(companyId: Long): Map<String, Any?> {
        val row = apps.listByCompany(companyId).firstOrNull()
        return authorizerView(row)
    }

    // ===================== 内部 =====================

    /** 新行插入 / 旧行"传了才替换"。[model] 只是那组新鲜值，不是完整 DO（id/时间戳在这里赋） */
    private fun saveOrUpdate(companyId: Long, appId: String, model: WxAppDO) {
        val now = TimeSec.now()
        val exist = apps.find(companyId, appId)
        if (exist == null) {
            val d = model
            d.companyId = companyId
            d.appId = appId
            d.created = now
            d.updated = now
            apps.insert(d)
        } else {
            apps.updateByCompanyAndApp(
                companyId = companyId,
                appId = appId,
                appSecret = model.appSecret,
                appType = model.appType,
                authorizerRefreshToken = model.authorizerRefreshToken,
                nickName = model.nickName,
                verifyType = model.verifyType,
                principalName = model.principalName,
                status = model.status,
                expiresAt = model.expiresAt.takeIf { it > 0 },
                updated = now,
            )
        }
    }

    /** `app_type` 的词表守卫：不是 oa/wxa 一律打回，不猜（这是有限词表） */
    private fun formType(raw: String?): String = when (raw?.takeIf { it.isNotBlank() }?.lowercase()) {
        null, "wxa" -> "wxa"
        "oa" -> "oa"
        else -> throw BizException(
            ErrorCode.VALIDATION, "app_type 只能是 oa 或 wxa",
            mapOf("app_type" to listOf("仅支持 oa/wxa")),
        )
    }

    /**
     * 出参装配。`app_secret` **不产出**（C175 同一纪律）：对象里没有这一字段，不是置 null——
     * 把 null 出出来等于告诉对端"这一位存在"，契约按字段全集 diff 时形状就已漂移。
     */
    private fun authorizerView(row: WxAppDO?): Map<String, Any?> = if (row == null) {
        linkedMapOf(
            "app_id" to null,
            "app_type" to null,
            "nick_name" to null,
            "verify_type" to null,
            "principal_name" to null,
            "status" to null,
            "expires_at" to null,
            "has_refresh_token" to false,
            "linked_at" to null,
        )
    } else {
        linkedMapOf(
            "app_id" to row.appId,
            "app_type" to row.appType,
            "nick_name" to row.nickName,
            "verify_type" to row.verifyType,
            "principal_name" to row.principalName,
            "status" to row.status,
            "expires_at" to row.expiresAt,
            "has_refresh_token" to row.authorizerRefreshToken.isNullOrBlank().not(),
            "linked_at" to row.created,
        )
    }

    companion object {
        const val MOCK_COMPONENT_APPID = "wx_mock_component_appid"
        const val MOCK_AUTH_EXPIRES_IN = 7200L
        const val PERMANENT_EXPIRES_AT = 4102444800L // 2100-01-01（秒），company_license 同一语义
    }
}

/** WX-A2 入参（real 链只认授权码；mock 链在 e2e 里可多给一个 app_id 做确定性断言） */
class WxBindForm(
    val authorizationCode: String? = null,
    val appId: String? = null,
)

/** WX-A3 入参 */
class WxDirectBindForm(
    val appId: String? = null,
    val appSecret: String? = null,
    val appType: String? = null,
)
