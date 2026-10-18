package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.asset.PointAdjustForm
import com.ecshopx.kotlin.service.asset.PointRuleForm
import com.ecshopx.kotlin.service.asset.PointService
import com.ecshopx.kotlin.service.auth.Datapass
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.DistributorScope
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PT-A1..A4 后台积分（docs/04 §23.2，前缀 `/api`）。
 *
 * 两条权限码（§23.4）：`point.lists` 看，`point.update` 调账与改规则。
 * 店铺运营型账号（seed 的 role 2）**只配 54**：调账等于凭空造钱、改规则影响全站算价，
 * 都是平台权力（D57/C167 那条划分），所以这里 role 2 进来只有两个读面。
 *
 * `PT-A2` 是全仓唯一"无订单来源"的动账口，`remark` 必填、负数钳到 0：
 * 对账脚本（`tools/m15_reconcile_smoke.py`）靠这条流水的备注解释差额，
 * 没有备注的调账会让"账实相符"变成"账实相符但没人知道为什么"。
 *
 * `full` 是 datapass 判定：只影响出参里手机号印全值还是 `138****0001`，
 * 不参与"看得见哪些行"（那仍由 [DistributorScope] 判，MASK-R1 第 1 条）。
 */
@RestController
class PointAdminController(
    private val points: PointService,
    private val pass: Datapass,
) {

    /** PT-A1 会员积分列表（卡头四个数与明细同一份筛选字典，不会出现"总数比明细大"） */
    @RequiresResource("point.lists")
    @GetMapping("/api/point/members")
    fun members(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(points.adminMembers(cid(p), raw, scope(p), pass.full(p)))

    /** PT-A2 后台调账（`change_point` 正负皆可、不可为 0；`remark` 必填） */
    @Audited(route = "point.update")
    @RequiresResource("point.update")
    @PostMapping("/api/point/adjustment")
    fun adjust(
        @RequestBody form: PointAdjustForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(points.adjust(cid(p), form))

    /** PT-A3 全量流水（对账的 HTTP 面：`Σ change_point == point_member.point` 靠这两条读） */
    @RequiresResource("point.lists")
    @GetMapping("/api/point/logs")
    fun logs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(points.adminLogs(cid(p), raw, pass.full(p)))

    /** PT-A4 规则读（当前值 + 默认值 + 生效值三张对照） */
    @RequiresResource("point.lists")
    @GetMapping("/api/member/point/rule")
    fun rule(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(points.configView(cid(p)))

    /** PT-A4 规则写（整组写回租户层；`point.deduct.max_ratio` 越界 422） */
    @Audited(route = "point.update")
    @RequiresResource("point.update")
    @PutMapping("/api/member/point/rule")
    fun writeRule(
        @RequestBody form: PointRuleForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(points.configWrite(cid(p), form))

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
