package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.TurntableService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * IC-T 会员面大转盘（docs/04 §29.1，前缀 `/api/h5app/wxapp/promotion`，鉴权=会员 JWT）。
 *
 * 五条按附录照抄：`turntableconfig`（配置）、`getLuckyDrawData`（状态）、`turntable`（抽取）、
 * `getLuckyDrawLog`（我的流水）、`loginaddtimes`（登录送次数）。`GET /promotion/turntable` 的
 * "GET 参与抽奖"是源系统原样（写动作挂 GET 与 REST 相悖，但 §29.1 照抄位那条纪律：接住它，
 * 不发明 PUT 版本，避免"前端只认识 GET"的存量调用端直接不兼容）。
 */
@RestController
class TurntableFrontController(private val turntable: TurntableService) {

    /** IC-T3 配置页（奖品池 + 次数规则） */
    @GetMapping("/api/h5app/wxapp/promotion/turntableconfig")
    fun config(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.frontConfig(cid(p)))

    /** IC-T3 状态页（配置 + 我当日剩余/已抽/领取态） */
    @GetMapping("/api/h5app/wxapp/promotion/getLuckyDrawData")
    fun data(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.frontData(cid(p), uid(p)))

    /** IC-T4 抽取（GET 照抄源；次数不足/未开启 422 业务错） */
    @GetMapping("/api/h5app/wxapp/promotion/turntable")
    fun draw(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.draw(cid(p), uid(p)))

    /** IC-T6 我的抽奖流水 */
    @GetMapping("/api/h5app/wxapp/promotion/getLuckyDrawLog")
    fun log(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> =
        ApiResponse.ok(turntable.myLog(cid(p), uid(p), pq(raw)))

    /** IC-T7 登录送次数（每日一次，重复领不加、不报错） */
    @GetMapping("/api/h5app/wxapp/promotion/loginaddtimes")
    fun loginAddTimes(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.loginAddTimes(cid(p), uid(p)))

    private fun pq(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
