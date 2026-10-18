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
import com.ecshopx.kotlin.service.distribution.SalesmanForm
import com.ecshopx.kotlin.service.distribution.SalesmanRoleBindForm
import com.ecshopx.kotlin.service.distribution.SalesmanRoleForm
import com.ecshopx.kotlin.service.distribution.SalesmanService
import com.ecshopx.kotlin.service.distribution.SalesmanStatusForm
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
 * SM-A1..A6 导购与岗位（docs/04 §18.2，前缀 `/api`）。
 *
 * 两条权限码（§18.7 的 45/46）：`salesman.lists` 看，`salesman.update` 添/改/调岗/启停/岗位 CRUD。
 * 与自提点、店铺档案一个分法：能排班的人不该顺手把一个人的员工端关掉。
 *
 * 每条都带 [DistributorScope]（§18.8 门槛②）：店铺运营账号的列表只看自己绑定的那几家店的人，
 * 传别人的 `salesman_id` 是 404 —— 不给"这个人存在"的信息。
 *
 * 出参永不回显 `password`（§22 C175）：那一列只在服务层做 bcrypt 比对时被读，
 * 连行类 [com.ecshopx.kotlin.dao.distribution.SalesmanRow] 都没有这个字段。
 *
 * 岗位 CRUD 的路径用**复数** `/api/salesman/roles(/{role_id})`：
 * SM-A4 调岗那条是 `/api/salesman/role/{salesman_id}`（照抄源系统 `PUT /distributor/salesman/role/{salesmanId}`），
 * 若岗位也写成单数 `{role_id}`，两条模板在 Spring 里是**同一个 pattern**，容器启动直接 Ambiguous mapping 失败。
 * 源系统不冲突是因为它把岗位放在 `/distributor/salesperson/role`（另一个 controller），这里用复数保住同一区分。
 */
@RestController
class SalesmanAdminController(private val salesmen: SalesmanService) {

    /** SM-A1 导购列表 */
    @RequiresResource("salesman.lists")
    @GetMapping("/api/salesmans")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(salesmen.list(cid(p), raw, scope(p)))

    /** SM-A2 新增导购 */
    @Audited(route = "salesman.update")
    @RequiresResource("salesman.update")
    @PostMapping("/api/salesman")
    fun create(
        @RequestBody form: SalesmanForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.create(cid(p), form, scope(p)))

    /** SM-A3 更新（读-改-写：null 字段保持原值；`password` 传空串=清空口令） */
    @Audited(route = "salesman.update")
    @RequiresResource("salesman.update")
    @PutMapping("/api/salesman/{salesman_id}")
    fun update(
        @PathVariable("salesman_id") salesmanId: String,
        @RequestBody form: SalesmanForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.update(cid(p), sid(salesmanId), form, scope(p)))

    /** SM-A4 调岗 */
    @Audited(route = "salesman.update")
    @RequiresResource("salesman.update")
    @PutMapping("/api/salesman/role/{salesman_id}")
    fun changeRole(
        @PathVariable("salesman_id") salesmanId: String,
        @RequestBody form: SalesmanRoleBindForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.changeRole(cid(p), sid(salesmanId), form, scope(p)))

    /** SM-A5 启停（停用即刻让员工端那枚还没过期的令牌打不进任何一条 ST-E/SO-E，D44） */
    @Audited(route = "salesman.update")
    @RequiresResource("salesman.update")
    @PutMapping("/api/salesman/{salesman_id}/status")
    fun setStatus(
        @PathVariable("salesman_id") salesmanId: String,
        @RequestBody form: SalesmanStatusForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.setStatus(cid(p), sid(salesmanId), form, scope(p)))

    /** SM-A6 岗位列表（`include_platform=true` 连带平台通用岗） */
    @RequiresResource("salesman.lists")
    @GetMapping("/api/salesman/roles")
    fun roles(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(salesmen.roleList(cid(p), raw, scope(p)))

    /** SM-A6 新建岗位 */
    @Audited(route = "salesman.update")
    @RequiresResource("salesman.update")
    @PostMapping("/api/salesman/roles")
    fun roleCreate(
        @RequestBody form: SalesmanRoleForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.roleCreate(cid(p), form, scope(p)))

    /** SM-A6 改岗位 */
    @Audited(route = "salesman.update")
    @RequiresResource("salesman.update")
    @PutMapping("/api/salesman/roles/{role_id}")
    fun roleUpdate(
        @PathVariable("role_id") roleId: String,
        @RequestBody form: SalesmanRoleForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.roleUpdate(cid(p), rid(roleId), form, scope(p)))

    /** SM-A6 删岗位（还有导购挂着 → 422） */
    @Audited(route = "salesman.update")
    @RequiresResource("salesman.update")
    @DeleteMapping("/api/salesman/roles/{role_id}")
    fun roleDelete(
        @PathVariable("role_id") roleId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.roleDelete(cid(p), rid(roleId), scope(p)))

    /** 路径上的 id 非法与"这个人你看不见"同一个出口：404 */
    private fun sid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "导购不存在")

    private fun rid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "岗位不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId

    private fun scope(p: Principal?): DistributorScope = DistributorScope.ofPrincipal(p)
}
