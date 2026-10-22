package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.CheckInService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 互动域鉴权码（docs/04 §29.1 权限码段 78..80）：
 * `@RequiresResource` 只认 resource_code，与 seed.sql 的 resources.resource_code 一字不差。
 */
object ActivityCodes {
    const val CHECKIN_LISTS = "promotions.checkin.lists"
    const val TURNTABLE_LISTS = "promotions.turntable.lists"
    const val TURNTABLE_UPDATE = "promotions.turntable.update"
}

/**
 * IC-S6 后台签到记录（docs/04 §29.1，前缀 `/api/promotions/checkin`）。
 * 读面一条（78）：签到流水是"送了多少分"的事实清单，改不了也无需改。
 */
@RestController
class CheckinAdminController(private val checkin: CheckInService) {

    /** IC-S6 翻页（`user_id`/`day_start`/`day_end` 均可缺省，0 或缺 = 不设界） */
    @RequiresResource(ActivityCodes.CHECKIN_LISTS)
    @GetMapping("/api/promotions/checkin/getlist")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(
        checkin.adminList(
            cid(p),
            raw["user_id"]?.toLongOrNull(),
            raw["day_start"]?.toLongOrNull(),
            raw["day_end"]?.toLongOrNull(),
            pq(raw),
        ),
    )

    private fun pq(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
