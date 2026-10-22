package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.RightsService
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * RT-F 会员面次卡（docs/04 §29.4，源 `Action\Rights`/`Action\TransferRights`，jwt 会员 JWT）。
 *
 * 附录里这一族在 `/api/rights/*` 与 `/api/transfer/rights*`（落在运营链上，见 §29.4 差异 C231）；
 * 本项目的会员面**统一归位 `/api/h5app/wxapp`** 前缀进 frontChain（与砍价/加价购同一条流域）。
 * 三个只读位 + 两个动作位，全是"自己名下"的范围过滤（服务层按 user_id 收口）。
 */
@RestController
class RightsFrontController(private val rights: RightsService) {

    private fun uid(p: Principal?): Long =
        p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long =
        p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun pq(raw: Map<String, String>): PageQuery = PageQuery.of(
        raw["page"]?.toIntOrNull(), raw["page_size"]?.toIntOrNull() ?: raw["pageSize"]?.toIntOrNull(),
    )

    /** RT-F1 我的可用卡汇总（`{cards 可用张数, times 剩余总次数|null}`） */
    @GetMapping("/api/h5app/wxapp/rights/getdata")
    fun getData(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(rights.getData(cid(p), uid(p)))

    /** RT-F2 我的次卡翻页（`total_count=0` 出参 remain=null=不限次） */
    @GetMapping("/api/h5app/wxapp/rights")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(rights.belongPage(cid(p), uid(p), pq(raw)))

    /** RT-F3 卡详情 + 该卡动账史（归属本人才可见，他人的 rights_id 一律 404 —— 不存在也不 probing） */
    @GetMapping("/api/h5app/wxapp/rights/info")
    fun info(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(rights.detail(cid(p), uid(p), id(raw)))

    /** RT-F4 我的动账流水（转出的 from、转入的 to 两腿都在） */
    @GetMapping("/api/h5app/wxapp/rights/log")
    fun logs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(rights.myLogs(cid(p), uid(p), pq(raw)))

    /** RT-F5 转赠：卡换个人持有（受赠人必须本公司在册会员） */
    @PutMapping("/api/h5app/wxapp/transfer/rights")
    fun transfer(
        @RequestBody form: TransferForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val to = form.toUserId
            ?: throw BizException(ErrorCode.VALIDATION, "参数不合法", mapOf("to_user_id" to listOf("必填")))
        return ApiResponse.ok(rights.transfer(cid(p), uid(p), rid(form.rightsId), to))
    }

    /** RT-F6 我的转赠记录（behavior=transfer 的流水腿） */
    @GetMapping("/api/h5app/wxapp/transfer/rights/list")
    fun transfers(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(rights.myTransfers(cid(p), uid(p), pq(raw)))

    /** RT-F7 延期：有期限的卡 `end_time += days`；永久卡 422（没有到期日可延） */
    @PostMapping("/api/h5app/wxapp/rights/delay")
    fun delay(
        @RequestBody form: TransferForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        // 源系统 delay 入参带 `days`；复用同一入形体少一个类
        val days = form.days ?: throw BizException(ErrorCode.VALIDATION, "参数不合法", mapOf("days" to listOf("必填")))
        return ApiResponse.ok(rights.delay(cid(p), uid(p), rid(form.rightsId), days))
    }

    private fun id(raw: Map<String, String>): Long =
        raw["id"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: raw["rights_id"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.NOT_FOUND, "权益不存在")

    private fun rid(v: Long?): Long =
        v?.takeIf { it > 0 } ?: throw BizException(ErrorCode.VALIDATION, "参数不合法", mapOf("rights_id" to listOf("必填")))
}

/**
 * RT-F 转赠/延期入参（源系统体名不统一：`rights_id`/`to_user_id`/`days`；
 * `to_user_id` 的别名兜 `user_id` 的旧客户端写法）
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TransferForm(
    val rightsId: Long? = null,
    @com.fasterxml.jackson.annotation.JsonAlias("user_id")
    val toUserId: Long? = null,
    val days: Long? = null,
)
