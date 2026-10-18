package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.promotion.SeckillForm
import com.ecshopx.kotlin.service.promotion.SeckillGoodsForm
import com.ecshopx.kotlin.service.promotion.SeckillService
import com.ecshopx.kotlin.service.promotion.SeckillStatusForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * SK-A1..A7 后台秒杀（docs/04 §14.2，前缀 `/api`，运营 JWT + `@RequiresResource`，写路径 `@Audited`）。
 *
 * 与满减域的两处不同：
 *  1. **没有审核档**：场次靠时间窗 + `disabled` 生效，`pending` 的场次到点自动进算价链，
 *     不需要"改完悄悄继续生效"这道闸门（源系统同样没有）；
 *  2. **商品行是独立一条端点**（SK-A6 upsert）：价格、活动库存、限购都长在行上，
 *     场次本身只管时间窗与限额，所以编辑活动与配商品是两个动作。
 */
@RestController
class SeckillAdminController(private val seckill: SeckillService) {

    /** SK-A1 建场次 */
    @Audited(route = "promotions.seckill.update")
    @RequiresResource("promotions.seckill.update")
    @PostMapping("/api/seckill/activities")
    fun create(
        @RequestBody form: SeckillForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(seckill.save(cid(p), form.copy(seckillId = null)))

    /** SK-A2 场次列表（`date_status` 过滤） */
    @RequiresResource("promotions.seckill.lists")
    @GetMapping("/api/seckill/activities")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(seckill.list(cid(p), raw))

    /** SK-A3 详情 + 商品行（含 `hold_store`） */
    @RequiresResource("promotions.seckill.lists")
    @GetMapping("/api/seckill/activities/{seckill_id}")
    fun detail(
        @PathVariable("seckill_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(seckill.detail(cid(p), id(id)))

    /** SK-A4 编辑场次（时间窗/限额/支付时限/名称） */
    @Audited(route = "promotions.seckill.update")
    @RequiresResource("promotions.seckill.update")
    @PutMapping("/api/seckill/activities/{seckill_id}")
    fun update(
        @PathVariable("seckill_id") id: String,
        @RequestBody form: SeckillForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(seckill.save(cid(p), form.copy(seckillId = id(id))))

    /** SK-A5 停用 / 恢复 */
    @Audited(route = "promotions.seckill.update")
    @RequiresResource("promotions.seckill.update")
    @PutMapping("/api/seckill/activities/{seckill_id}/status")
    fun status(
        @PathVariable("seckill_id") id: String,
        @RequestBody form: SeckillStatusForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val v = form.disabled
            ?: throw BizException(ErrorCode.VALIDATION, "disabled 必填", mapOf("disabled" to listOf("不能为空")))
        return ApiResponse.ok(seckill.status(cid(p), id(id), v))
    }

    /** SK-A6 商品行 upsert（已产生销量/占用的行只允许加库存） */
    @Audited(route = "promotions.seckill.update")
    @RequiresResource("promotions.seckill.update")
    @PutMapping("/api/seckill/activities/{seckill_id}/goods")
    fun goods(
        @PathVariable("seckill_id") id: String,
        @RequestBody form: SeckillGoodsForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(seckill.saveGoods(cid(p), id(id), form))

    /** SK-A7 场次销量报表 */
    @RequiresResource("promotions.seckill.lists")
    @GetMapping("/api/seckill/activities/{seckill_id}/report")
    fun report(
        @PathVariable("seckill_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(seckill.report(cid(p), id(id)))

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "秒杀场次不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
