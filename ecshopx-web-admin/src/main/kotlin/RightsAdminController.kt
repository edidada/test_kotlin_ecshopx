package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.RightsGrantForm
import com.ecshopx.kotlin.service.promotion.RightsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * RT-A 次卡后台（docs/04 §29.4，运营 JWT + 权限码 85/86）。
 *
 * 两条注记：
 *  1. **手工发放是补偿口**：次卡的主发放位在结清挂点（markPaid 自动发）；这里补的是
 *     客诉/漏发的手工补录（例如线下办卡），所以挂 `@Audited` —— 发出去的是"能核销的次数"，与改钱同级；
 *  2. 源 `POST /api/rights` 挂的是 jwt（会员面"新增权益"），语义上就是权限口放错位置：
 *     能发卡的入口必须归位到运营链（§29.4 差异 C231），会员侧只读不写。
 */
@RestController
class RightsAdminController(private val rights: RightsService) {

    /** RT-A1 手工发放（补录/客诉；user_id/item_id 必填，total_count=0 意为不限次） */
    @Audited(route = ActivityCodes.RIGHTS_UPDATE)
    @RequiresResource(ActivityCodes.RIGHTS_UPDATE)
    @PostMapping("/api/rights/admin")
    fun grant(
        @RequestBody form: RightsGrantForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(rights.adminGrant(cid(p), form, opId(p)))

    /** RT-A2 权益台账翻页（全量口径：含已停用/已过期行） */
    @RequiresResource(ActivityCodes.RIGHTS_LISTS)
    @GetMapping("/api/rights/admin/list")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(
        rights.adminPage(cid(p), pq(raw)),
    )

    /** RT-A3 动账流水翻页（发放/核销/转赠/延期，只读） */
    @RequiresResource(ActivityCodes.RIGHTS_LISTS)
    @GetMapping("/api/rights/admin/logList")
    fun logs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(
        rights.adminLogs(cid(p), pq(raw)),
    )

    private fun pq(raw: Map<String, String>): PageQuery = PageQuery.of(
        raw["page"]?.toIntOrNull(), raw["page_size"]?.toIntOrNull() ?: raw["pageSize"]?.toIntOrNull(),
    )

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun opId(p: Principal?): Long = p?.id ?: 0L
}
