package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.BargainService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * BG-A 砍价后台（docs/04 §29.2，前缀 `/api/promotions`，运营 JWT + 权限码 81/82）。
 *
 * 路由按附录照抄（源 `Action\BargainPromotions`）：CRUD 五条 + 终止一条。
 * 写路径全挂 `@Audited`（改的是台面上"还剩几刀"的事实价格）。
 */
@RestController
class BargainAdminController(private val bargains: BargainService) {

    /** BG-A1 创建（item_id 必填；item_price 缺省取商品现价） */
    @Audited(route = ActivityCodes.BARGAIN_UPDATE)
    @RequiresResource(ActivityCodes.BARGAIN_UPDATE)
    @PostMapping("/api/promotions/bargains")
    fun create(
        @RequestBody body: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            bargains.save(
                cid(p),
                long(body, "item_id") ?: throw BizException(ErrorCode.VALIDATION, "item_id 必填",
                    mapOf("item_id" to listOf("不能为空"))),
                long(body, "item_price"),
                long(body, "floor_price") ?: throw BizException(ErrorCode.VALIDATION, "floor_price 必填",
                    mapOf("floor_price" to listOf("不能为空"))),
                (int(body, "steps") ?: 10),
            ),
        )

    /** BG-A2 列表翻页 */
    @RequiresResource(ActivityCodes.BARGAIN_LISTS)
    @GetMapping("/api/promotions/bargains")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(bargains.adminPage(cid(p), pq(raw)))

    /** BG-A2 详情 */
    @RequiresResource(ActivityCodes.BARGAIN_LISTS)
    @GetMapping("/api/promotions/bargains/{bargain_id}")
    fun detail(
        @PathVariable("bargain_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(bargains.adminDetail(cid(p), id(id)))

    /** BG-A3 编辑（覆盖式；`disabled` 也走这条） */
    @Audited(route = ActivityCodes.BARGAIN_UPDATE)
    @RequiresResource(ActivityCodes.BARGAIN_UPDATE)
    @PutMapping("/api/promotions/bargains/{bargain_id}")
    fun update(
        @PathVariable("bargain_id") id: String,
        @RequestBody body: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            bargains.update(
                cid(p),
                id(id),
                long(body, "item_id"),
                long(body, "item_price"),
                long(body, "floor_price"),
                int(body, "steps"),
                body["disabled"] as? Boolean ?: body["disabled"]?.toString()?.let { it == "1" || it == "true" },
            ),
        )

    /** BG-A4 终止（停止发刀，行与刀史保留） */
    @Audited(route = ActivityCodes.BARGAIN_UPDATE)
    @RequiresResource(ActivityCodes.BARGAIN_UPDATE)
    @PutMapping("/api/promotions/bargains/termination/{bargain_id}")
    fun terminate(
        @PathVariable("bargain_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(bargains.terminate(cid(p), id(id)))

    /** BG-A5 删除（有会员参与则回业务错；保护位见服务层注释） */
    @Audited(route = ActivityCodes.BARGAIN_UPDATE)
    @RequiresResource(ActivityCodes.BARGAIN_UPDATE)
    @DeleteMapping("/api/promotions/bargains/{bargain_id}")
    fun delete(
        @PathVariable("bargain_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(bargains.delete(cid(p), id(id)))

    // ===================== 调用件 =====================

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "砍价活动不存在")

    private fun long(body: Map<String, Any?>, key: String): Long? =
        (body[key] as? Number)?.toLong() ?: (body[key] as? String)?.toLongOrNull()

    private fun int(body: Map<String, Any?>, key: String): Int? =
        (body[key] as? Number)?.toInt() ?: (body[key] as? String)?.toIntOrNull()

    private fun pq(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
