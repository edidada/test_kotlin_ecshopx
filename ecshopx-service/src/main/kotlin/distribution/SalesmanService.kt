package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MembersMapper
import com.ecshopx.kotlin.dao.distribution.DistributorMapper
import com.ecshopx.kotlin.dao.distribution.DistributorValid
import com.ecshopx.kotlin.dao.distribution.SalesmanDO
import com.ecshopx.kotlin.dao.distribution.SalesmanMapper
import com.ecshopx.kotlin.dao.distribution.SalesmanRoleDO
import com.ecshopx.kotlin.dao.distribution.SalesmanRoleMapper
import com.ecshopx.kotlin.dao.distribution.SalesmanRoleRow
import com.ecshopx.kotlin.dao.distribution.SalesmanRow
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.service.auth.JwtService
import com.ecshopx.kotlin.service.auth.Kind
import com.ecshopx.kotlin.service.auth.SalesmanPrincipal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.order.OrderCreateForm
import com.ecshopx.kotlin.service.order.OrderCreateService
import com.ecshopx.kotlin.service.order.OrderQueryService
import com.ecshopx.kotlin.service.order.WriteOffService
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.security.crypto.bcrypt.BCrypt
import org.springframework.stereotype.Service

/**
 * M1-4 第三批后半（3B）导购域（docs/04 §18.2 SM-A1..A6 / §18.3 ST-E2..E4 / §18.1 SM-F1..F2）。
 *
 * 三件事分别由三个面负责，本案只管"人"这一层：
 *  - **后台（SM-A）**：平台或店铺运营管人在册行，权限码 `salesman.lists` / `salesman.update` 两条（§18.7），
 *    数据权限照 §18.8 门槛②那一套：列表按 `distributor_ids` 收窄、资源引用越界 404；
 *  - **会员面（SM-F）**：扫码进店看导购是谁（匿名）、把当前归属绑到自己身上（登录）；
 *  - **员工面（ST-E）**：本人登录后看自己的店与自己的账 —— 这里**不吃运营权限码**，
 *    判据只有一个：会话里那个 `salesman_id`（§18.7 最后一行）。
 *
 * 两条纪律贯穿全类：
 *  1. **口令只进不出**。`password` 列只在登录比对时被读进 [SalesmanDO]，任何出参视图都不含它，
 *     连 SM-A1 的行类 [SalesmanRow] 都压根没这个字段（结构上给不了，比"记得别输出"可靠）；
 *  2. **停用即刻生效**（D44）。员工面每个请求都回查 `is_valid`，所以这里停用之后，
 *     那枚还没过期的 JWT 立刻打不进任何一条 ST-E/SO-E —— 源系统"停用后会话仍活"的缺陷不继承。
 */
@Service
class SalesmanService(
    private val salesmen: SalesmanMapper,
    private val roles: SalesmanRoleMapper,
    private val distributors: DistributorMapper,
    private val members: MembersMapper,
    private val commissions: CommissionService,
    private val orders: OrderCreateService,
    private val queries: OrderQueryService,
    private val writeOffs: WriteOffService,
    private val jwt: JwtService,
) {

    // ===================== 后台 SM-A1..A6 =====================

    /** SM-A1 导购列表（`distributor_id`/`keyword`/`role_id`/`is_valid` + 分页） */
    fun list(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            // 显式要的店与"能看的店"取交集；交集为空时 narrow 给 [-1]，SQL 自然 0 行
            "ids" to scope.narrow(listOfNotNull(raw["distributor_id"]?.toLongOrNull()?.takeIf { it > 0 })),
            "distributorId" to null,
            "roleId" to raw["role_id"]?.toLongOrNull()?.takeIf { it > 0 },
            "isValid" to raw["is_valid"]?.takeIf { it == "true" || it == "false" },
            "userId" to raw["user_id"]?.toLongOrNull()?.takeIf { it > 0 },
            "keyword" to raw["keyword"]?.takeIf { it.isNotBlank() },
        )
        return PageHelper.paginate(
            pq,
            count = { salesmen.count(f) },
            query = { offset, limit -> salesmen.page(f, offset, limit) },
            map = { rows ->
                val ids = rows.map { it.salesmanId }.distinct()
                val orders = if (ids.isEmpty()) emptyMap() else toCountMap(salesmen.orderCounts(ids))
                val fee = if (ids.isEmpty()) emptyMap() else toCountMap(salesmen.openCommission(ids))
                rows.map { rowView(it, orders[it.salesmanId] ?: 0L, fee[it.salesmanId] ?: 0L) }
            },
        )
    }

    /** SM-A2 新增导购：`(店, 手机号)` 店內唯一；口令给了就落 bcrypt 哈希，不给=建了但登不进员工端 */
    fun create(
        companyId: Long,
        form: SalesmanForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val e = linkedMapOf<String, List<String>>()
        val distributorId = form.distributorId ?: 0
        if (distributorId <= 0) e["distributor_id"] = listOf("必填")
        if (form.salesmanName.isNullOrBlank()) e["salesman_name"] = listOf("不能为空")
        if (form.mobile.isNullOrBlank()) e["mobile"] = listOf("不能为空")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "导购信息不合法", e)
        val d = distributors.findById(distributorId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
        if (d.isValid == DistributorValid.CLOSED) throw BizException(ErrorCode.BIZ, "该店铺已关店，不能新增导购")
        // 归属判在写之前：店铺账号只能往自己绑定的店里添人（越界=这店对你不存在）
        scope.require(distributorId)
        requireMobileFree(companyId, distributorId, form.mobile!!.trim(), null)
        val roleId = form.roleId ?: 0
        if (roleId > 0) requireUsableRole(companyId, distributorId, roleId)
        val s = SalesmanDO().apply {
            this.companyId = companyId
            this.distributorId = distributorId
            shopId = form.shopId ?: d.shopId
            userId = form.userId ?: 0
            mobile = form.mobile!!.trim()
            salesmanName = form.salesmanName!!.trim()
            this.roleId = roleId
            isValid = validState(form.isValid) ?: "true"
            avatar = form.avatar
            password = hashOf(form.password)
        }
        salesmen.insert(s)
        return detailOf(companyId, s.salesmanId, scope)
    }

    /** SM-A3 更新（读-改-写：null 字段保持原值；`password` 传了才换哈希，传空串=清空口令） */
    fun update(
        companyId: Long,
        salesmanId: Long,
        form: SalesmanForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val s = load(companyId, salesmanId, scope)
        val distributorId = form.distributorId ?: s.distributorId
        if (distributorId != s.distributorId) scope.require(distributorId)
        val mobile = form.mobile?.trim()?.takeIf { it.isNotEmpty() } ?: s.mobile ?: ""
        if (mobile != s.mobile) requireMobileFree(companyId, distributorId, mobile, salesmanId)
        val roleId = form.roleId ?: s.roleId
        if (roleId != s.roleId) requireUsableRole(companyId, distributorId, roleId)
        s.distributorId = distributorId
        s.shopId = form.shopId ?: s.shopId
        s.userId = form.userId ?: s.userId
        s.mobile = mobile
        s.salesmanName = form.salesmanName?.trim()?.takeIf { it.isNotEmpty() } ?: s.salesmanName
        s.roleId = roleId
        s.avatar = form.avatar ?: s.avatar
        validState(form.isValid)?.let { s.isValid = it }
        // 口令这次要不要动，只看入参给没给：没给 = 原哈希留着，给空串 = 清空（员工端随即登不进来），给了 = 换哈希。
        val hashed = when {
            form.password == null -> null
            form.password!!.isEmpty() -> null
            else -> hashOf(form.password)
        }
        s.password = if (form.password == null) s.password else hashed
        salesmen.updateFull(s)
        // `updateFull` 的写回列表里**没有** `password` 那一列（整行更新不碰凭据），所以换/清口令要单独走 [updatePassword]。
        // 少了这一步，SM-A3 会"报成功、回显也对，但库里那枚哈希一个字都没变"——
        // 清空口令后那人还能拿旧口令登录，这是凭据面上唯一一种"悄悄没生效"的错法。
        if (form.password != null) salesmen.updatePassword(salesmanId, companyId, hashed, TimeSec.now())
        return detailOf(companyId, salesmanId, scope)
    }

    /** SM-A4 调岗：角色必须是"本店能选"的角色（本店的 或 平台通用岗），且是启用态 */
    fun changeRole(
        companyId: Long,
        salesmanId: Long,
        form: SalesmanRoleBindForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val s = load(companyId, salesmanId, scope)
        val roleId = form.roleId ?: 0
        if (roleId <= 0) {
            throw BizException(ErrorCode.VALIDATION, "role_id 必填", mapOf("role_id" to listOf("必须指定岗位")))
        }
        requireUsableRole(companyId, s.distributorId, roleId)
        salesmen.updateRole(salesmanId, companyId, roleId, TimeSec.now())
        return detailOf(companyId, salesmanId, scope)
    }

    /** SM-A5 启停（停用即刻关掉员工端登录入口，D44） */
    fun setStatus(
        companyId: Long,
        salesmanId: Long,
        form: SalesmanStatusForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val v = form.isValid
        if (v != "true" && v != "false") {
            throw BizException(ErrorCode.VALIDATION, "is_valid 非法", mapOf("is_valid" to listOf("只支持 true/false")))
        }
        load(companyId, salesmanId, scope)
        salesmen.setStatus(salesmanId, companyId, v, TimeSec.now())
        return linkedMapOf("salesman_id" to salesmanId, "is_valid" to v)
    }

    /** SM-A6 角色列表（`include_platform=true` 时把 `distributor_id=0` 的平台通用岗一起带出来） */
    fun roleList(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
        val distributorId = raw["distributor_id"]?.toLongOrNull()?.takeIf { it > 0 }
        if (distributorId != null) scope.require(distributorId)
        val withPlatform = raw["include_platform"] == "true" || raw["include_platform"] == "1"
        val dids = when {
            distributorId != null -> if (withPlatform) listOf(distributorId, 0L) else listOf(distributorId)
            scope.unrestricted -> null
            else -> scope.narrow(null)
        }
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "dids" to dids,
            "status" to raw["status"]?.toIntOrNull(),
            "keyword" to raw["keyword"]?.takeIf { it.isNotBlank() },
        )
        return PageHelper.paginate(
            pq,
            count = { roles.count(f) },
            query = { offset, limit -> roles.page(f, offset, limit) },
            map = { rows -> rows.map { roleView(it) } },
        )
    }

    /** SM-A6 新建角色 */
    fun roleCreate(
        companyId: Long,
        form: SalesmanRoleForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val name = form.roleName?.trim()
        if (name.isNullOrEmpty()) throw BizException(ErrorCode.VALIDATION, "role_name 必填", mapOf("role_name" to listOf("不能为空")))
        val distributorId = form.distributorId ?: 0
        // 平台通用岗（distributor_id=0）只有 admin 型账号建得了 —— 与自提点的通用点同一条口径（C161）
        scope.require(distributorId)
        if (roles.countSameName(companyId, distributorId, name, null) > 0) {
            throw BizException(ErrorCode.BIZ, "该店铺下已有同名岗位", mapOf("role_name" to listOf("已存在")))
        }
        val r = SalesmanRoleDO().apply {
            this.companyId = companyId
            this.distributorId = distributorId
            roleName = name
            remark = form.remark
            status = if (form.status == 0) 0 else 1
        }
        roles.insert(r)
        return roleOf(companyId, r.roleId)
    }

    /** SM-A6 改角色（读-改-写） */
    fun roleUpdate(
        companyId: Long,
        roleId: Long,
        form: SalesmanRoleForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val r = roles.findById(roleId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "岗位不存在")
        scope.require(r.distributorId)
        val name = form.roleName?.trim()?.takeIf { it.isNotEmpty() } ?: r.roleName ?: ""
        if (name != r.roleName && roles.countSameName(companyId, r.distributorId, name, roleId) > 0) {
            throw BizException(ErrorCode.BIZ, "该店铺下已有同名岗位", mapOf("role_name" to listOf("已存在")))
        }
        r.roleName = name
        r.remark = form.remark ?: r.remark
        form.status?.let { r.status = if (it == 0) 0 else 1 }
        roles.updateFull(r)
        return roleOf(companyId, roleId)
    }

    /** SM-A6 删除角色：还有导购挂着就不许删（422），删的是行本身（见 DAO 那条注释） */
    fun roleDelete(
        companyId: Long,
        roleId: Long,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val r = roles.findById(roleId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "岗位不存在")
        scope.require(r.distributorId)
        val used = salesmen.countByRole(roleId)
        if (used > 0) {
            throw BizException(ErrorCode.BIZ, "该岗位下还有 $used 名导购，请先调岗再删", mapOf("role_id" to listOf("仍被引用")))
        }
        roles.delete(roleId, companyId)
        return linkedMapOf("role_id" to roleId, "deleted" to 1)
    }

    // ===================== 会员面 SM-F1/F2 =====================

    /**
     * SM-F1 导购信息（扫码进入，匿名）。
     *
     * `salesman_id` 与 `mobile` 两种查法都只认**在册 + 营业中店铺**的人：
     * 停业或还没过审的店，它的员工在前台就是"查无此人"（404），
     * 而不是"这人存在但店不能下单" —— 前台没有解释后者的义务，那会顺手把店铺状态暴露出去。
     */
    fun frontInfo(
        companyId: Long,
        salesmanId: Long?,
        mobile: String?,
    ): Map<String, Any?> {
        val row = when {
            salesmanId != null && salesmanId > 0 -> salesmen.findActiveById(companyId, salesmanId)
            !mobile.isNullOrBlank() -> salesmen.findActiveByMobile(companyId, mobile.trim())
            else -> throw BizException(ErrorCode.VALIDATION, "salesman_id 或 mobile 至少要给一个",
                mapOf("salesman_id" to listOf("与 mobile 二选一")))
        } ?: throw BizException(ErrorCode.NOT_FOUND, "导购不存在")
        if (row.roleId <= 0L) throw BizException(ErrorCode.NOT_FOUND, "导购不存在")
        val d = distributors.findById(row.distributorId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "导购不存在")
        if (d.isValid != DistributorValid.TRUE || !d.reviewStatus) throw BizException(ErrorCode.NOT_FOUND, "导购不存在")
        return linkedMapOf(
            "salesman_id" to row.salesmanId,
            "salesman_name" to (row.salesmanName ?: ""),
            "avatar" to (row.avatar ?: ""),
            "distributor_id" to row.distributorId,
            "shop_name" to (row.shopName ?: d.name),
            // 扫码页要落一件"扫了谁"的事：这里给的是拼好的分享文案，前端只负责画码
            "qrcode_text" to "salesman=${row.salesmanId}",
            "is_ziti" to d.isZiti,
            "role_name" to (row.roleName ?: ""),
        )
    }

    /**
     * SM-F2 绑定我的导购（会员面）。
     *
     * 写的是"当前归属"这一列，可以换绑（D62：源系统那张 24h 有效绑定的关系表本项目不还原）。
     * 绑上之后，这个会员自己下单时 `salesman_id` 会跟着进单（C177），佣金才认得到人。
     */
    fun bind(
        companyId: Long,
        userId: Long,
        form: SalesmanBindForm,
    ): Map<String, Any?> {
        val salesmanId = form.salesmanId ?: 0
        if (salesmanId <= 0) throw BizException(ErrorCode.VALIDATION, "salesman_id 必填", mapOf("salesman_id" to listOf("不能为空")))
        val row = salesmen.findActiveById(companyId, salesmanId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "导购不存在")
        val now = TimeSec.now()
        if (members.bindSalesman(userId, companyId, salesmanId, now) == 0) {
            throw BizException(ErrorCode.NOT_FOUND, "会员不存在")
        }
        return linkedMapOf(
            "user_id" to userId,
            "salesman_id" to salesmanId,
            "salesman_name" to (row.salesmanName ?: ""),
            "distributor_id" to row.distributorId,
            "bind_time" to now,
        )
    }

    /** 下单回落用的"这个会员现在归谁"（`OrderCreateService` 调，0=没绑） */
    fun boundSalesmanId(
        companyId: Long,
        userId: Long,
    ): Long = members.salesmanIdOf(userId, companyId) ?: 0

    // ===================== 员工面 ST-E1..E4 =====================

    /**
     * ST-E1 员工登录：`{mobile, password}`（可选 `distributor_id`）。
     *
     * 三种失败都是 401 且不区分文案（停用 / 没设口令 / 口令不符）——
     * 员工端的账号枚举面不该回答"这个手机号在不在册"。
     */
    fun login(form: StaffLoginForm, companyId: Long): Map<String, Any?> {
        val mobile = form.mobile?.trim()
        val password = form.password
        if (mobile.isNullOrEmpty() || password.isNullOrEmpty()) {
            throw BizException(ErrorCode.VALIDATION, "mobile 与 password 必填",
                mapOf("mobile" to listOf("必填"), "password" to listOf("必填")))
        }
        val distributorId = form.distributorId?.takeIf { it > 0 }
        val s = salesmen.findLogin(companyId, mobile, distributorId) ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
        val hash = s.password
        if (hash.isNullOrEmpty() || !matches(password, hash)) throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
        val role = if (s.roleId > 0) roles.findById(s.roleId, companyId) else null
        return linkedMapOf(
            "token" to jwt.issue(Kind.SALESMAN, s.salesmanId, companyId),
            "salesman_id" to s.salesmanId,
            "salesman_name" to (s.salesmanName ?: ""),
            "distributor_id" to s.distributorId,
            "shop_id" to s.shopId,
            "role_id" to s.roleId,
            "role_name" to (role?.roleName ?: ""),
        )
    }

    /** 会话回查：令牌只带 id，**在册与否每个请求现查**（D44 停用即刻 401 的落点） */
    fun principalOf(salesmanId: Long): SalesmanPrincipal? {
        val s = salesmen.findById(salesmanId, TenantContext.companyId)?.takeIf { it.isValid == "true" } ?: return null
        return SalesmanPrincipal(
            salesmanId = s.salesmanId,
            companyId = s.companyId,
            distributorId = s.distributorId,
            shopId = s.shopId,
            userId = s.userId,
            roleId = s.roleId,
            salesmanName = s.salesmanName ?: "",
            mobile = s.mobile ?: "",
        )
    }

    /** ST-E2 当前员工信息（含所属店铺名与岗位名；自己的手机号不掩码） */
    fun info(p: SalesmanPrincipal): Map<String, Any?> {
        val s = salesmen.findById(p.salesmanId, p.companyId)
            ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
        val d = distributors.findById(p.distributorId, p.companyId)
        val role = if (s.roleId > 0) roles.findById(s.roleId, p.companyId) else null
        return linkedMapOf(
            "salesman_id" to p.salesmanId,
            "salesman_name" to (s.salesmanName ?: p.salesmanName),
            "mobile" to (s.mobile ?: p.mobile),
            "avatar" to (s.avatar ?: ""),
            "user_id" to s.userId,
            "distributor_id" to p.distributorId,
            "shop_name" to (d?.name ?: ""),
            "shop_mobile" to (d?.mobile ?: ""),
            "role_id" to s.roleId,
            "role_name" to (role?.roleName ?: ""),
            "is_valid" to s.isValid,
        )
    }

    /** ST-E3 本店简易档（员工端首页那几栏） */
    fun shopOf(p: SalesmanPrincipal): Map<String, Any?> {
        val d = distributors.findById(p.distributorId, p.companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
        return linkedMapOf(
            "distributor_id" to d.distributorId,
            "name" to d.name,
            "mobile" to (d.mobile ?: ""),
            "address" to (d.address ?: ""),
            "logo" to (d.logo ?: ""),
            "is_ziti" to d.isZiti,
            "is_delivery" to d.isDelivery,
            // 营业时段源系统在店铺配置里，本项目未开该域（§18 开头的 P2 清单），给空串保住字段形状
            "hour" to "",
        )
    }

    /**
     * ST-E4 我的统计。
     *
     * 三个单数走订单表、两个佣金数走流水表，余额与店铺那侧同一条公式（§18.5），
     * 只是聚合键换成 `salesman_id` —— 岗的账与店的账分列展示，但**钱是同一口池**：
     * `balance` 取 `min(本岗已结算−本岗占用, 店铺池余额)`（[CommissionService.salesmanWithdrawable]，
     * §22 C176 记的是这个准确口径，不是"两份独立的钱"）。
     */
    fun myCount(p: SalesmanPrincipal): Map<String, Any?> {
        val c = commissions.salesmanCommission(p.companyId, p.salesmanId)
        val w = commissions.salesmanWithdraw(p.companyId, p.salesmanId)
        val today = startOfToday()
        return linkedMapOf(
            "order_count" to salesmen.countOrders(p.salesmanId),
            "today_count" to salesmen.countOrdersSince(p.salesmanId, today),
            "pending_count" to salesmen.countPending(p.salesmanId),
            "commission_total" to c.commissionTotal,
            "commission_open" to c.commissionOpen,
            "commission_closed" to c.commissionClosed,
            "withdraw_occupying" to w,
            "balance" to commissions.salesmanWithdrawable(p.companyId, p.salesmanId, p.distributorId),
        )
    }

    /** ST-E5 我的佣金明细：口径同 DL-A1，只是 `salesman_id` 由会话强制（§18.3"强制 salesman_id=自己"） */
    fun myFeeList(
        p: SalesmanPrincipal,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val f = linkedMapOf<String, String>()
        raw.forEach { (k, v) -> if (k != "salesman_id" && k != "distributor_id") f[k] = v }
        f["salesman_id"] = p.salesmanId.toString()
        return commissions.list(p.companyId, f, DistributorScope(listOf(p.distributorId)))
    }

    // ===================== 员工面 SO-E1..E4（订单与核销） =====================

    /**
     * SO-E1 代客下单。
     *
     * 三条强制，一条都不交给前端：
     *  - `salesman_id` **取会话**，入参给了也忽略（§18.3）—— 否则任何人传一个 id 就能把佣金记到别人头上；
     *  - `shop_id`/`distributor_id` 一律折到本人所在店：给了别的店是 **403**（不是 404 ——
     *    这里 distributor_id 是**写入**的目标而不是资源引用，明说"你只能开本店的单"比"这店不存在"更有用，
     *    也与他确实能从前台看到那家店不矛盾）；
     *  - 被代下单的会员必须真实存在且属于本租户，否则 404：写一个不存在的 `user_id` 进订单头，
     *    这单永远没人能查、能付、能退。
     *
     * 散客（`user_id` 不传且本人没绑会员账号）落 `user_id=0`：此时只有 `cart_type=fastbuy/offline`
     * 这种"带着行来"的模式能成单，购物车模式在算价侧自然 0 行 → 422"购物车为空"，
     * 这是**期望行为**而不是缺陷 —— 散客没有车。
     */
    fun staffCreateOrder(
        p: SalesmanPrincipal,
        form: OrderCreateForm,
    ): Map<String, Any?> {
        val requested = listOfNotNull(form.distributorId, form.shopId).firstOrNull { it > 0 }
        if (requested != null && requested != p.distributorId) {
            throw BizException(ErrorCode.NO_PERM, "只能为本人所属店铺开单", mapOf("distributor_id" to listOf("非本店铺")))
        }
        val buyer = (form.userId ?: p.userId).coerceAtLeast(0L)
        if (buyer > 0) {
            // 租户也要对上：`members` 的 id 全局唯一，但"别的租户的会员"对这单来说同样是不存在
            val m = members.findById(buyer)
            if (m == null || m.companyId != p.companyId) throw BizException(ErrorCode.NOT_FOUND, "被代下单的会员不存在")
        }
        return orders.create(
            buyer,
            p.companyId,
            form.copy(salesmanId = p.salesmanId, distributorId = p.distributorId, shopId = p.distributorId),
        )
    }

    /**
     * SO-E2 我开的单（分页 + `order_status`/`keyword`）。
     *
     * `salesman_id` 与 `distributor_id` 都由会话填：前者把行收窄到"他开的那些单"，
     * 后者把可见面收窄到本店（同一个 `list` 方法，只是 [DistributorScope] 换了来源，§18.3）。
     * 入参里带过来的同名键**一律丢弃**，不做"取交集"那种看起来更安全、实际要多一层解释的合并。
     */
    fun staffOrderList(
        p: SalesmanPrincipal,
        raw: Map<String, String>,
    ): Map<String, Any?> {
        val f = raw.toMutableMap()
        f.remove("user_id")
        f.remove("distributor_id")
        f["salesman_id"] = p.salesmanId.toString()
        return queries.list(f, p.companyId, null, staffScope(p))
    }

    /**
     * SO-E3 我开的单的详情。
     *
     * 先用**同一条列表判据**探一次（`order_id` + `salesman_id` + 租户 + 店），0 行就是"这单不是你的"，404；
     * 通过后才把详情交给 ORDER-5 那个共用视图。多那一次 count 换的是：
     * 员工面不用另写一套详情装配（那套装配有 200 多行、六个 JOIN，复制一份迟早漂），
     * 也不会出现"列表里看不到、拼个 id 直接进详情"的越权面。
     */
    fun staffOrderDetail(
        p: SalesmanPrincipal,
        orderId: Long,
    ): Map<String, Any?> {
        val probe = queries.list(
            linkedMapOf("order_id" to orderId.toString(), "salesman_id" to p.salesmanId.toString()),
            p.companyId,
            null,
            staffScope(p),
        )
        val hit = (probe["total_count"] as? Number)?.toLong() ?: 0L
        if (hit == 0L) throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        val v = queries.detail(orderId, null, staffScope(p)).toMutableMap()
        v["salesman"] = linkedMapOf(
            "salesman_id" to p.salesmanId,
            "salesman_name" to p.salesmanName,
            "mobile" to p.mobile,
        )
        return v
    }

    /**
     * SO-E4 员工端核销。
     *
     * 打的是与 WO-A2 **同一个 service 方法**（§18.6）：核销是"承认买家已经把货拿走了"，
     * 后台点一下和员工扫一下必须是同一条状态迁移、同一份留痕形状，只是数据来源与操作人署名不同。
     */
    fun staffWriteOff(
        p: SalesmanPrincipal,
        orderId: Long,
        code: String,
    ): Map<String, Any?> = writeOffs.writeOff(
        companyId = p.companyId,
        orderId = orderId,
        code = code,
        scope = staffScope(p),
        operatorId = p.salesmanId,
        operator = "salesman:${p.salesmanId}",
        operatorRole = "salesman",
    )

    /** 员工面的数据权限：只有"他自己那一家店"，没有全场这个概念 */
    private fun staffScope(p: SalesmanPrincipal) = DistributorScope(listOf(p.distributorId))

    // ===================== 内部 =====================

    private fun load(
        companyId: Long,
        salesmanId: Long,
        scope: DistributorScope,
    ): SalesmanDO {
        val s = salesmen.findById(salesmanId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "导购不存在")
        // 资源引用面越界一律 404，不给"这个人存在"的信息（与 C145 同一条）
        scope.require(s.distributorId)
        return s
    }

    private fun detailOf(
        companyId: Long,
        salesmanId: Long,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val s = load(companyId, salesmanId, scope)
        val r = if (s.roleId > 0) roles.findById(s.roleId, companyId) else null
        return rowView(
            SalesmanRow().apply {
                this.salesmanId = s.salesmanId; this.companyId = s.companyId; distributorId = s.distributorId; shopId = s.shopId
                userId = s.userId; mobile = s.mobile; salesmanName = s.salesmanName; roleId = s.roleId
                isValid = s.isValid; avatar = s.avatar; created = s.created; updated = s.updated
                shopName = distributors.findById(s.distributorId, companyId)?.name
                roleName = r?.roleName
            },
            salesmen.countOrders(s.salesmanId),
            commissions.salesmanCommission(companyId, s.salesmanId).commissionOpen,
        )
    }

    private fun roleOf(
        companyId: Long,
        roleId: Long,
    ): Map<String, Any?> {
        val r = roles.findById(roleId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "岗位不存在")
        return roleView(
            SalesmanRoleRow().apply {
                this.roleId = r.roleId; this.companyId = r.companyId; distributorId = r.distributorId
                roleName = r.roleName; remark = r.remark; status = r.status; created = r.created; updated = r.updated
                shopName = if (r.distributorId > 0) distributors.findById(r.distributorId, companyId)?.name else ""
            },
        )
    }

    private fun requireMobileFree(
        companyId: Long,
        distributorId: Long,
        mobile: String,
        excludeId: Long?,
    ) {
        if (salesmen.countSameMobile(companyId, distributorId, mobile, excludeId) > 0) {
            throw BizException(ErrorCode.BIZ, "该店铺下已有同手机号的导购", mapOf("mobile" to listOf("店内重复")))
        }
    }

    private fun requireUsableRole(
        companyId: Long,
        distributorId: Long,
        roleId: Long,
    ) {
        if (roles.countUsable(companyId, distributorId, roleId) == 0L) {
            throw BizException(ErrorCode.BIZ, "该岗位不存在或已停用", mapOf("role_id" to listOf("本店不可选")))
        }
    }

    /** 口令列只存 bcrypt 哈希；空/空白视为"没设口令"，员工端登录据此直接拒 */
    private fun hashOf(raw: String?): String? =
        raw?.takeIf { it.isNotBlank() }?.let { BCrypt.hashpw(it, BCrypt.gensalt(BCRYPT_COST)) }

    /** 坏哈希（历史脏数据）不能把登录变成 500：比对异常一律按"口令不对"处理 */
    private fun matches(
        raw: String,
        hash: String,
    ): Boolean = try {
        BCrypt.checkpw(raw, hash)
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun toCountMap(rows: List<Map<String, Any?>>): Map<Long, Long> =
        rows.mapNotNull { r ->
            val id = (r["salesman_id"] as? Number)?.toLong() ?: return@mapNotNull null
            val v = (r["c"] as? Number ?: r["s"] as? Number)?.toLong() ?: 0L
            id to v
        }.toMap()

    private fun rowView(
        r: SalesmanRow,
        orderCount: Long,
        commissionFee: Long,
    ): Map<String, Any?> = linkedMapOf(
        "salesman_id" to r.salesmanId,
        "company_id" to r.companyId,
        "distributor_id" to r.distributorId,
        "shop_id" to r.shopId,
        "user_id" to r.userId,
        "salesman_name" to (r.salesmanName ?: ""),
        // 列表面掩码（§18.2 SM-A1）：运营看的是"哪个岗有几个人"，不是抄手机号
        "mobile" to mask(r.mobile),
        "role_id" to r.roleId,
        "role_name" to (r.roleName ?: ""),
        "shop_name" to (r.shopName ?: ""),
        "is_valid" to r.isValid,
        "avatar" to (r.avatar ?: ""),
        "order_count" to orderCount,
        "commission_fee" to commissionFee,
        "created" to r.created,
        "updated" to r.updated,
    )

    private fun roleView(r: SalesmanRoleRow): Map<String, Any?> = linkedMapOf(
        "role_id" to r.roleId,
        "company_id" to r.companyId,
        "distributor_id" to r.distributorId,
        "role_name" to (r.roleName ?: ""),
        "remark" to (r.remark ?: ""),
        "status" to r.status,
        "status_text" to if (r.status == 1) "启用" else "停用",
        "scope_text" to if (r.distributorId == 0L) "全平台通用" else (r.shopName ?: ""),
        "created" to r.created,
        "updated" to r.updated,
    )

    private fun mask(v: String?): String? = v?.takeIf { it.length >= 7 }?.let { "${it.take(3)}****${it.takeLast(4)}" } ?: v

    /**
     * `is_valid` 是源系统留在 VARCHAR 里的两位枚举（`'true'` / `'false'`），不是布尔列。
     *
     * 判据只有三种：`null`/空串 = 这次没传（SM-A3 读-改-写保持原值），`true`/`false` = 要设成这个态，
     * **其余值一律 400**。不校验的话 `"yes"` 会被静默折成某个默认态写进库 ——
     * 员工面每条请求都回查 `is_valid == 'true'`（D44），一个写歪的值等于把这个人悄悄停用，
     * 而报错的却是"你没登录"。与 `DistributorService.updateStatus` 的 `VALID_STATES` 同一条纪律。
     */
    private fun validState(v: String?): String? {
        if (v.isNullOrEmpty()) return null
        if (v != "true" && v != "false") {
            throw BizException(ErrorCode.VALIDATION, "is_valid 只能取 true 或 false", mapOf("is_valid" to listOf("只能是 true 或 false")))
        }
        return v
    }

    /**
     * 当日零点（按 `TimeSec.TZ`，与订单时间列同一时区）。
     * 不在 SQL 里取 `CURDATE()`：H2 与 MySQL 的当日函数不同名，
     * 而且把"今天"算成参数，测试才能把它挪到任意一天去验 `today_count`。
     */
    private fun startOfToday(): Long =
        java.time.LocalDate.now(TimeSec.TZ).atStartOfDay(TimeSec.TZ).toEpochSecond()

    companion object {
        private const val BCRYPT_COST = 10
    }
}

/** SM-A2/A3 导购表单（`password` 只在建/改时接收，任何出参都不回显） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SalesmanForm(
    val distributorId: Long? = null,
    val salesmanName: String? = null,
    val mobile: String? = null,
    val roleId: Long? = null,
    val userId: Long? = null,
    val shopId: Long? = null,
    val avatar: String? = null,
    val password: String? = null,
    val isValid: String? = null,
)

/** SM-A4 调岗 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SalesmanRoleBindForm(
    val roleId: Long? = null,
)

/** SM-A5 启停 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SalesmanStatusForm(
    val isValid: String? = null,
)

/** SM-A6 角色表单 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SalesmanRoleForm(
    val distributorId: Long? = null,
    val roleName: String? = null,
    val remark: String? = null,
    val status: Int? = null,
)

/** SM-F2 会员绑定导购 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SalesmanBindForm(
    val salesmanId: Long? = null,
)

/** ST-E1 员工登录 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class StaffLoginForm(
    val mobile: String? = null,
    val password: String? = null,
    val distributorId: Long? = null,
)
