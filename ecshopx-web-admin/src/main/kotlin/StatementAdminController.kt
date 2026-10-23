package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.StatementService
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 结算对账单后台面（docs/04 §29.6，批F；源 `/api/statement/*` 走运营链，本项目归后台）。
 *
 * 源系统这一族是"店铺自己看自己的账"（jwt = 店铺身份）；本项目把它归到运营链——
 * 对账单是**平台与店铺之间的账**，谁有权看哪一张是权限问题，不是身份问题。
 * 读 88 / 改 89。
 *
 * 两条口径写在代码里而不是文档里：
 *  1. **账期单由任务生成，不由接口生成**：这里没有"手动出账"的口子——
 *     账单一出就带金额，手工触发的出账等于让一个 HTTP 请求改账；补跑靠运维触发任务腿。
 *  2. **确认只推一格**：`ready→confirmed`；`confirmed→paid`（打款完成）本批不做——
 *     打款要接财务回单，没有回单就置"已付"等于凭空宣布付过钱（C242）。
 */
@RestController
class StatementAdminController(private val statements: StatementService) {

    /** ST-A0 账期设置读（两种结算对象各一行） */
    @RequiresResource(ActivityCodes.STATEMENT_LISTS)
    @GetMapping("/api/statement/period/setting")
    fun periodSetting(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(statements.settingsOf(cid(p)))

    /** ST-A0 账期设置写（按 merchant_type upsert） */
    @RequiresResource(ActivityCodes.STATEMENT_UPDATE)
    @Audited(route = "statement.period.setting")
    @PostMapping("/api/statement/period/setting")
    fun savePeriodSetting(
        @RequestBody form: PeriodSettingForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(
        statements.saveSetting(cid(p), form.merchantType ?: "distributor", form.periodType ?: "daily"),
    )

    /** ST-A1 账期单翻页（merchant_type/distributor_id/statement_status 可选筛） */
    @RequiresResource(ActivityCodes.STATEMENT_LISTS)
    @GetMapping("/api/statement/summarized")
    fun summarized(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(statements.summarized(cid(p), raw))

    /** ST-A2 账期单详情（头 + 明细行） */
    @RequiresResource(ActivityCodes.STATEMENT_LISTS)
    @GetMapping("/api/statement/detail/{id}")
    fun detail(
        @PathVariable id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(statements.detail(cid(p), id.toLongOrNull()
        ?: throw BizException(ErrorCode.VALIDATION, "参数不合法", mapOf("id" to listOf("必须是数字")))))

    /** ST-A3 确认结算（ready→confirmed） */
    @RequiresResource(ActivityCodes.STATEMENT_UPDATE)
    @Audited(route = "statement.confirm")
    @PostMapping("/api/statement/confirm/{id}")
    fun confirm(
        @PathVariable id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(
        statements.confirm(
            cid(p),
            id.toLongOrNull() ?: throw BizException(ErrorCode.VALIDATION, "参数不合法", mapOf("id" to listOf("必须是数字"))),
            p?.id,
            p?.let { "operator:${it.id}" } ?: "operator:system",
        ),
    )

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}

/** 账期设置入参（`merchant_type` 缺省=distributor，`period_type` 缺省=daily） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PeriodSettingForm(
    val merchantType: String? = null,
    val periodType: String? = null,
)
