package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.distribution.PickupBindForm
import com.ecshopx.kotlin.service.distribution.PickupForm
import com.ecshopx.kotlin.service.distribution.PickupLocationService
import com.ecshopx.kotlin.service.order.WriteOffForm
import com.ecshopx.kotlin.service.order.WriteOffService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PL-A1..A5 自提点（docs/04 §18.6，前缀 `/api`）。
 *
 * 两条权限码（§18.7）：`pickup.lists` 看，`pickup.update` 建/改/停/挂店。
 * 拆开的理由和店铺档案一样 —— 能看点的运营不该能把一个还有未核销单的点停掉。
 *
 * 每条都带 [DistributorScope]：归属列（`distributor_id`）在 DAO 层收窄，
 * 店铺账号传别人的点 id 是 404、列表是 0 行；`distributor_id=0` 的平台通用点只有 admin 型账号碰得到。
 */
@RestController
class PickupAdminController(private val pickups: PickupLocationService) {

    /** PL-A1 自提点列表（`keyword`/`status`/`rel_distributor_id`/`distributor_id` + 分页） */
    @RequiresResource("pickup.lists")
    @GetMapping("/api/pickuplocation")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(pickups.list(cid(p), raw, scope(p)))

    /** PL-A2 新建（`distributor_id` 不传=平台通用点） */
    @Audited(route = "pickup.update")
    @RequiresResource("pickup.update")
    @PostMapping("/api/pickuplocation")
    fun create(
        @RequestBody form: PickupForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(pickups.create(cid(p), form, scope(p)))

    /** PL-A3 更新（读-改-写：null 字段保持原值） */
    @Audited(route = "pickup.update")
    @RequiresResource("pickup.update")
    @PutMapping("/api/pickuplocation/{id}")
    fun update(
        @PathVariable("id") id: String,
        @RequestBody form: PickupForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(pickups.update(cid(p), pid(id), form, scope(p)))

    /** PL-A4 停用（逻辑删；还有未核销自提单指着它时 422） */
    @Audited(route = "pickup.update")
    @RequiresResource("pickup.update")
    @DeleteMapping("/api/pickuplocation/{id}")
    fun disable(
        @PathVariable("id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(pickups.disable(cid(p), pid(id), scope(p)))

    /** PL-A5 挂店/摘店（`bind=false` = 摘回 `rel_distributor_id=0` 全平台通用） */
    @Audited(route = "pickup.update")
    @RequiresResource("pickup.update")
    @PutMapping("/api/pickuplocation/reldistributor")
    fun bind(
        @RequestBody form: PickupBindForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(pickups.bind(cid(p), form, scope(p)))

    /** id 非法与不存在同一个出口：都是"这个点你看不见" */
    private fun pid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.NOT_FOUND, "自提点不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}

/**
 * WO-A1/A2 自提核销（docs/04 §18.6）。
 *
 * 一条 `order.writeoff`：核销是"承认买家已经把货拿走了"，这一下之后单子不能再退、不能再改，
 * 所以它既不归"看订单"（`order.lists`）也不归"改订单"（`order.update`），单独一条码（§18.7）。
 *
 * 与员工端 SO-E4 打的是**同一个 service 方法**，差别只在数据权限来源：
 * 这里用运营令牌的 `distributor_ids`，员工端用会话里强制的 `salesman.distributor_id`（#64 第三批后半）。
 */
@RestController
class WriteOffAdminController(private val writeOffs: WriteOffService) {

    /** WO-A1 核销查询：`?ziti_code=` 给了就顺比一次（输错码先把单子形状显示出来） */
    @RequiresResource("order.writeoff")
    @GetMapping("/api/writeoff/{order_id}")
    fun query(
        @PathVariable("order_id") orderId: String,
        @RequestParam(required = false, name = "ziti_code") code: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(writeOffs.query(cid(p), oid(orderId), code, scope(p)))

    /** WO-A2 核销执行：`{ziti_code(Y)}` → 单推 DONE、`ziti_status=true`，留痕给操作人 */
    @Audited(route = "order.writeoff")
    @RequiresResource("order.writeoff")
    @PostMapping("/api/writeoff/{order_id}")
    fun execute(
        @PathVariable("order_id") orderId: String,
        @RequestBody form: WriteOffForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val code = form.zitiCode
            ?: throw BizException(ErrorCode.VALIDATION, "ziti_code 必填", mapOf("ziti_code" to listOf("不能为空")))
        return ApiResponse.ok(
            writeOffs.writeOff(
                companyId = cid(p),
                orderId = oid(orderId),
                code = code,
                scope = scope(p),
                operatorId = p?.id ?: 0,
                operator = p?.let { "operator:${it.id}" } ?: "operator:0",
                operatorRole = "seller",
            ),
        )
    }

    /** 核销端的"订单不存在"与"你看不见这单"必须是同一个 404（C145） */
    private fun oid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
