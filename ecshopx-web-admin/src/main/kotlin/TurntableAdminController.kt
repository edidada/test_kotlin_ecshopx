package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.TurntableService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * IC-T8 后台大转盘（docs/04 §29.1，前缀 `/api/promotions`）。
 *
 * 路由按附录照抄（源 `Action\Turntable`）：
 *  - POST/GET/PUT `/api/promotions/turntableconfig`：读写配置在**同一路由**上（源系统即如此，照抄）；
 *  - GET `getturntableList` / `getturntable` / `getturntable_log/byid` / `getturntable_count/byid`：
 *    列表 / 详情 / 流水 / 汇总，四条都是读（79）；
 *  - GET `down_lucky_draw/byid`：下架（80），GET 写动作同前台 `turntable` 那条照抄位。
 *
 * 配置写（POST/PUT）与下架都挂 `@Audited`：改的是全店送分口径，与 point.update 同级留痕。
 */
@RestController
class TurntableAdminController(private val turntable: TurntableService) {

    /** IC-T1 配置读/写同一路由：GET = IC-T8 列表首行 + 配置，POST/PUT = 覆盖式写（缺字段按缺省） */
    @RequiresResource(ActivityCodes.TURNTABLE_UPDATE)
    @GetMapping("/api/promotions/turntableconfig")
    fun getConfig(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.activityView(cid(p)))

    @Audited(route = "promotions.turntable.update")
    @RequiresResource(ActivityCodes.TURNTABLE_UPDATE)
    @PostMapping("/api/promotions/turntableconfig")
    fun postConfig(@RequestBody body: Map<String, Any?>, @AuthenticationPrincipal p:Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.adminSaveConfig(cid(p), body))

    @Audited(route = "promotions.turntable.update")
    @RequiresResource(ActivityCodes.TURNTABLE_UPDATE)
    @PutMapping("/api/promotions/turntableconfig")
    fun putConfig(@RequestBody body: Map<String, Any?>, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.adminSaveConfig(cid(p), body))

    /** IC-T8 列表（每租户一个转盘：恒一行快照，分配语义见 §29.1 差异表 D-IC1） */
    @RequiresResource(ActivityCodes.TURNTABLE_LISTS)
    @GetMapping("/api/promotions/getturntableList")
    fun list(@RequestParam raw: Map<String, String>, @AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(rows(turntable.activityView(cid(p))))

    /** IC-T8 详情（byid=1） */
    @RequiresResource(ActivityCodes.TURNTABLE_LISTS)
    @GetMapping("/api/promotions/getturntable")
    fun detail(
        @RequestParam("id", required = false) id: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        if ((id?.toLongOrNull() ?: 1L) != TurntableService.ACTIVITY_ID) {
            throw BizException(ErrorCode.NOT_FOUND, "大转盘活动不存在")
        }
        return ApiResponse.ok(turntable.activityView(cid(p)))
    }

    /** IC-T8 流水（user_id 缺省=全量） */
    @RequiresResource(ActivityCodes.TURNTABLE_LISTS)
    @GetMapping("/api/promotions/getturntable_log/byid")
    fun log(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> =
        ApiResponse.ok(turntable.adminLog(cid(p), raw["user_id"]?.toLongOrNull(), pq(raw)))

    /** IC-T8 汇总（笔数 / 送分合计 / 参与人数） */
    @RequiresResource(ActivityCodes.TURNTABLE_LISTS)
    @GetMapping("/api/promotions/getturntable_count/byid")
    fun count(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(turntable.adminCount(cid(p)))

    /** IC-T8 下架（关开关，配置保持原值） */
    @Audited(route = "promotions.turntable.update")
    @RequiresResource(ActivityCodes.TURNTABLE_UPDATE)
    @GetMapping("/api/promotions/down_lucky_draw/byid")
    fun down(
        @RequestParam("id", required = false) id: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(turntable.adminDown(cid(p), id?.toLongOrNull() ?: 1L))

    private fun rows(v: Map<String, Any?>): Map<String, Any?> = linkedMapOf("total_count" to 1L, "list" to listOf(v))

    private fun pq(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
