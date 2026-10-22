package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.BargainService
import com.ecshopx.kotlin.service.wx.WxAcodeForm
import com.ecshopx.kotlin.service.wx.WxAcodeService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * BG-F 砍价会员面（docs/04 §29.2，前缀 `/api/h5app/wxapp/promotion`，鉴权按附录原文）：
 *  - `bargains` 列表 / `userbargain` 参与：JWT（发起要人）；
 *  - `userbargain` 详情 / `bargainlog` 帮砍：**frontnoauth**（游客围观帮砍页是源站语义，照抄位）；
 *  - `bargainfriendwxappcode`：JWT，直接委托 [WxAcodeService]（A9 复用，scene=`ub:{id}`），
 *    出参是**二进制 PNG**（与 M2-2 A9 同一条形状，不是 JSON 包络）。
 *
 * 鉴权分家照抄不解释（与 C221 的 write-on-GET 照抄位同族）：帮砍页本来就是分享出去给朋友点开的，
 * 好友端可能登录也可能没登录，源系统对这两条挂 frontnoauth，本项目对同等语义保持同一宽严。
 */
@RestController
class BargainFrontController(
    private val bargains: BargainService,
    private val codes: WxAcodeService,
) {

    /** BG-F1 砍价活动列表（JWT，未停用行） */
    @GetMapping("/api/h5app/wxapp/promotion/bargains")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> =
        ApiResponse.ok(bargains.frontPage(cid(p), pq(raw)))

    /** BG-F2 参与（POST/jwt） */
    @PostMapping("/api/h5app/wxapp/promotion/userbargain")
    fun join(
        @RequestParam raw: Map<String, String>,
        @org.springframework.web.bind.annotation.RequestBody(required = false) body: Map<String, Any?>?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(bargains.join(cid(p), uid(p), bid(raw, body)))

    /** BG-F3 详情（GET/frontnoauth：游客可看，登录了也一样） */
    @GetMapping("/api/h5app/wxapp/promotion/userbargain")
    fun detail(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(bargains.userDetail(cid(p), bid(raw, null)))

    /** BG-F4 帮砍一刀（POST/frontnoauth；落到无 JWT 时 helper_id=0） */
    @PostMapping("/api/h5app/wxapp/promotion/bargainlog")
    fun bargainLog(
        @RequestParam raw: Map<String, String>,
        @org.springframework.web.bind.annotation.RequestBody(required = false) body: Map<String, Any?>?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(bargains.cut(cid(p), p?.id ?: 0L, bid(raw, body)))

    /** BG-F5 分享码（JWT）：委托 WxAcodeService（A9），scene 带 ub 编号） */
    @GetMapping("/api/h5app/wxapp/promotion/bargainfriendwxappcode")
    fun friendCode(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ResponseEntity<Any> {
        val ubgId = bid(raw, null)
        val res = codes.generate(
            cid(p),
            WxAcodeForm(type = "unlimit", scene = "ub=$ubgId", width = 430),
        )
        return if (res.binary != null) {
            ResponseEntity.ok().contentType(MediaType.parseMediaType(res.contentType ?: "image/png")).body(res.binary)
        } else {
            ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(res.json)
        }
    }

    /** id 解析：query 优先、JSON body 兜底；两处都没有就是 422 */
    private fun bid(raw: Map<String, String>, body: Map<String, Any?>?): Long {
        val fromQuery = raw["user_bargain_id"]?.toLongOrNull()
        val fromBody = (body?.get("user_bargain_id") as? Number)?.toLong()
            ?: body?.get("user_bargain_id")?.toString()?.toLongOrNull()
        val fromQuery2 = raw["bargain_id"]?.toLongOrNull()
        val fromBody2 = (body?.get("bargain_id") as? Number)?.toLong()
            ?: body?.get("bargain_id")?.toString()?.toLongOrNull()
        // join 用 bargain_id，cut/detail 用 user_bargain_id；两者解一即用
        return (fromQuery ?: fromBody ?: fromQuery2 ?: fromBody2)
            ?: throw BizException(ErrorCode.VALIDATION, "id 必填", mapOf("id" to listOf("user_bargain_id 或 bargain_id 必填")))
    }

    private fun pq(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    /** frontnoauth 面：JWT 缺席走 TenantContext（与传输顺序无关的租户兜底） */
    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
