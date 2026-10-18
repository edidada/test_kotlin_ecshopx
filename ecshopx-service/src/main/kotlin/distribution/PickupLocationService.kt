package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.distribution.DistributorMapper
import com.ecshopx.kotlin.dao.distribution.DistributorValid
import com.ecshopx.kotlin.dao.distribution.PickupLocationDO
import com.ecshopx.kotlin.dao.distribution.PickupLocationMapper
import com.ecshopx.kotlin.dao.distribution.PickupRow
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.stereotype.Service

/**
 * M1-4 自提点（docs/04 §18.6 / PL-A1..A5 / PL-F1）。
 *
 * 两条判据是这个类存在的全部理由：
 *  1. **"谁能改这个点"看 `distributor_id`（归属店），"谁的单能用这个点"看 `rel_distributor_id`（使用方）**。
 *     列表按前者收窄（§18.3 数据权限），前台按店取点时 `rel_distributor_id=0` 的通用点也要出得来；
 *  2. **停用之前要看有没有未完结的自提单指着它**（PL-A4）。自提单没有发货环节，取货地址就是这一行，
 *     单还没核销就把点停掉，买家拿着码到店却无处取货 —— 所以这是一条 422 而不是"随便删"。
 *
 * 距离（`lng`/`lat`）在服务层算：表里两列是 VARCHAR（照源系统列型），
 * 而球面公式在 H2 与 MySQL 里函数名不同，写进 SQL 就要为两种方言各维护一遍。
 */
@Service
class PickupLocationService(
    private val locations: PickupLocationMapper,
    private val distributors: DistributorMapper,
) {

    /** PL-A1 后台列表（按数据权限收窄；电话给明文，运营要打就是打这个号） */
    fun list(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())
        val status = raw["status"]?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val f = linkedMapOf<String, Any?>(
            "companyId" to companyId,
            "distributorId" to raw["distributor_id"]?.toLongOrNull()?.takeIf { it >= 0 },
            "relDistributorId" to raw["rel_distributor_id"]?.toLongOrNull()?.takeIf { it >= 0 },
            "status" to status,
            "keyword" to raw["keyword"]?.takeIf { it.isNotBlank() },
            "ids" to scope.narrow(null),
        )
        return PageHelper.paginate(
            pq,
            count = { locations.count(f) },
            query = { offset, limit -> locations.page(f, offset, limit) },
            map = { rows -> rows.map { rowView(it, mask = false) } },
        )
    }

    /**
     * PL-A2 新建自提点。
     *
     * `distributor_id` 可以是 0 = **平台自建的全平台通用点**（§18.6：`rel_distributor_id=0` 那条通路
     * 给所有店铺的前台取点页用）。它走的是同一个 [DistributorScope.require]：店铺账号既看不到也不建得了
     * `distributor_id=0` 的行 —— 通用点是平台的权力，不是任何一家店的权利。
     */
    fun create(
        companyId: Long,
        form: PickupForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val distributorId = form.distributorId ?: 0
        if (distributorId < 0) throw BizException(ErrorCode.VALIDATION, "distributor_id 不合法", mapOf("distributor_id" to listOf("不能为负")))
        scope.require(distributorId)
        if (distributorId > 0) {
            val d = distributors.findById(distributorId, companyId)
                ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
            if (d.isValid != DistributorValid.TRUE) throw BizException(ErrorCode.BIZ, "店铺未启用，不能建自提点")
        }
        val p = PickupLocationDO().apply {
            this.companyId = companyId
            this.distributorId = distributorId
            relDistributorId = form.relDistributorId ?: 0
            name = form.name
            province = form.province
            city = form.city
            area = form.area
            address = form.address
            lng = form.lng
            lat = form.lat
            contractPhone = form.contractPhone
            // 不"顺手归一"成 1：把 7 当"启用"存进去，PL-A1 的 status 过滤器就再也筛不到这类脏值
            status = form.status ?: 1
        }
        require(p)
        if (p.relDistributorId > 0) requireRel(companyId, p.relDistributorId, scope)
        locations.insert(p)
        return viewOf(p)
    }

    /** PL-A3 更新：读-改-写，只覆盖传进来的字段（与 SI-A3 同一条纪律，改个电话不动 status） */
    fun update(
        companyId: Long,
        id: Long,
        form: PickupForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val cur = one(companyId, id, scope)
        form.distributorId?.let { nd ->
            // 只许"店↔店"改归属，不许把店的点收归平台（nd=0）—— 那等于把别人的取货地址变成公共资产
            if (nd > 0 && nd != cur.distributorId) {
                scope.require(nd)
                requireRel(companyId, nd, scope)
                cur.distributorId = nd
            }
        }
        form.relDistributorId?.let { cur.relDistributorId = it }
        form.name?.let { cur.name = it }
        form.province?.let { cur.province = it }
        form.city?.let { cur.city = it }
        form.area?.let { cur.area = it }
        form.address?.let { cur.address = it }
        form.lng?.let { cur.lng = it }
        form.lat?.let { cur.lat = it }
        form.contractPhone?.let { cur.contractPhone = it }
        form.status?.let { cur.status = it }
        require(cur)
        if (cur.relDistributorId > 0) requireRel(companyId, cur.relDistributorId, scope)
        locations.updateFull(cur)
        return viewOf(cur)
    }

    /**
     * PL-A4 停用（逻辑删）。
     *
     * 行不真删：历史自提单要能回看"我在哪儿取的钱"，删了行那条单就查无此处。
     * 有未完结的单指着它才 422 —— 已经核销完的单不影响停用。
     */
    fun disable(
        companyId: Long,
        id: Long,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val cur = one(companyId, id, scope)
        val open = locations.countOpenZitiOrders(id)
        if (open > 0L) throw BizException(ErrorCode.BIZ, "该自提点还有 $open 张未核销的自提单，请先核销或改点")
        locations.disable(id, companyId, TimeSec.now())
        return mapOf("id" to id, "status" to 0)
    }

    /** PL-A5 挂店/摘店：`bind=true` 把点交给某店用，`false` 摘回"全平台通用"（0） */
    fun bind(
        companyId: Long,
        form: PickupBindForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val id = form.id ?: 0
        val cur = one(companyId, id, scope)
        val rel = if (form.bind == true) form.distributorId ?: 0 else 0
        if (rel > 0) {
            scope.require(rel)
            requireRel(companyId, rel, scope)
        }
        locations.relBind(id, companyId, rel, TimeSec.now())
        return linkedMapOf("id" to id, "rel_distributor_id" to rel, "bind" to (form.bind == true))
    }

    /**
     * PL-F1 前台取点列表：只出启用的，且归属该店或全平台通用；可选 `lng/lat` 带出距离。
     *
     * 电话一律掩码（§18.1）：前台这页是给买家看"去哪儿取"，不是给同行抄客户联系方式的。
     */
    fun frontList(
        companyId: Long,
        distributorId: Long,
        lng: Double?,
        lat: Double?,
    ): Map<String, Any?> {
        if (distributorId <= 0) throw BizException(ErrorCode.VALIDATION, "distributor_id 必填", mapOf("distributor_id" to listOf("必填")))
        val rows = locations.listUsable(companyId, distributorId)
        return mapOf("list" to rows.map { rowView(it, mask = true, lng = lng, lat = lat) })
    }

    /** DS-F2 店铺主页的 `pickup_locations[]`：与 PL-F1 同一份取数、同一套装配（不各写一遍） */
    fun pickupLocationsFor(
        companyId: Long,
        distributorId: Long,
    ): List<Map<String, Any?>> = locations.listUsable(companyId, distributorId).map { rowView(it, mask = true) }

    /** 建单侧用：该店当前可用的第一个点（买家没显式选点时的回落），没有就 null */
    fun defaultFor(
        companyId: Long,
        distributorId: Long,
    ): PickupRow? = locations.listUsable(companyId, distributorId).firstOrNull()

    /** 建单侧用：显式选了点，就要验它当下可用、且确实能被这家店用（通用点也算能用） */
    fun requireUsable(
        companyId: Long,
        distributorId: Long,
        id: Long,
    ): PickupRow = locations.listUsable(companyId, distributorId).firstOrNull { it.id == id }
        ?: throw BizException(
            ErrorCode.BIZ,
            "自提点不可用",
            mapOf("pickup_location_id" to listOf("不属于该店铺或已停用")),
        )

    /** 出参装配用：按 id 回读那一行（已停用的也要读得到，历史单的取货地址不能被"停用"抹掉） */
    fun locationOf(
        companyId: Long,
        id: Long,
    ): PickupLocationDO? = if (id <= 0) null else locations.findById(id, companyId)

    private fun one(
        companyId: Long,
        id: Long,
        scope: DistributorScope,
    ): PickupLocationDO {
        val cur = locations.findById(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "自提点不存在")
        // 列表面是"过滤到 0 行"，资源引用面是 404（C145 的两面在点上也一样）
        scope.require(cur.distributorId)
        return cur
    }

    private fun requireRel(
        companyId: Long,
        relDistributorId: Long,
        scope: DistributorScope,
    ) {
        val d = distributors.findById(relDistributorId, companyId)
            ?: throw BizException(ErrorCode.BIZ, "使用方店铺不存在", mapOf("rel_distributor_id" to listOf("不存在")))
        if (d.isValid != DistributorValid.TRUE) {
            throw BizException(ErrorCode.BIZ, "使用方店铺未启用", mapOf("rel_distributor_id" to listOf("未启用")))
        }
        scope.require(relDistributorId)
    }

    private fun require(p: PickupLocationDO) {
        val e = linkedMapOf<String, List<String>>()
        if (p.name.isNullOrBlank()) e["name"] = listOf("不能为空")
        if (p.name.orEmpty().length > 128) e["name"] = listOf("最长 128 字")
        if (p.address.isNullOrBlank()) e["address"] = listOf("不能为空")
        if (p.contractPhone.isNullOrBlank()) e["contract_phone"] = listOf("不能为空")
        if (!p.contractPhone.isNullOrBlank() && p.contractPhone!!.length > 20) e["contract_phone"] = listOf("最长 20 字")
        listOf(
            p.lng to "lng", p.lat to "lat",
        ).forEach { (v, k) ->
            if (!v.isNullOrBlank() && v.toDoubleOrNull() == null) e[k] = listOf("必须是数字")
        }
        if (p.status != 0 && p.status != 1) e["status"] = listOf("只支持 1/0")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "自提点信息不完整", e)
    }

    /**
     * 单行回显（PL-A2/A3 的出参）：DO 里没有 `shop_name`（那是 JOIN 出来的展示列），
     * 但出参形状必须与列表一致 —— 前端建完点直接把这行插进列表，两个形状不一致就得各写一套渲染。
     */
    private fun viewOf(p: PickupLocationDO): Map<String, Any?> = rowView(
        PickupRow().apply {
            id = p.id; companyId = p.companyId; distributorId = p.distributorId; relDistributorId = p.relDistributorId
            name = p.name; province = p.province; city = p.city; area = p.area; address = p.address
            lng = p.lng; lat = p.lat; contractPhone = p.contractPhone; status = p.status
            created = p.created; updated = p.updated
            // 平台通用点（distributor_id=0）没有归属店，shop_name 出空串而不是 null（同上，渲染位）
            shopName = if (p.distributorId > 0) distributors.findById(p.distributorId, p.companyId)?.name else null
        },
        mask = false,
    )

    private fun rowView(
        r: PickupRow,
        mask: Boolean,
        lng: Double? = null,
        lat: Double? = null,
    ): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        "company_id" to r.companyId,
        "distributor_id" to r.distributorId,
        "shop_name" to (r.shopName ?: ""),
        "rel_distributor_id" to r.relDistributorId,
        "name" to r.name,
        "province" to r.province,
        "city" to r.city,
        "area" to r.area,
        "address" to r.address,
        "full_address" to listOf(r.province, r.city, r.area, r.address).filter { !it.isNullOrBlank() }.joinToString(""),
        "lng" to r.lng,
        "lat" to r.lat,
        "contract_phone" to (if (mask) Mask.mobile(r.contractPhone) else r.contractPhone),
        "status" to r.status,
        // 没传坐标就不给 distance 这个键，前端据此决定要不要显示"距您 x km"
        "distance" to (if (lng != null && lat != null) distanceKm(lng, lat, r.lng?.toDoubleOrNull(), r.lat?.toDoubleOrNull()) else null),
        "created" to r.created,
        "updated" to r.updated,
    )


    /** 球面距离（km，一位小数）。坐标缺一侧就 null，让"没配经纬度"在出参里看得出来 */
    private fun distanceKm(
        lng1: Double,
        lat1: Double,
        lng2: Double?,
        lat2: Double?,
    ): Double? {
        if (lng2 == null || lat2 == null) return null
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return Math.round(2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)) * 10) / 10.0
    }
}

/** PL-A2/A3 表单：更新时 null 字段=不改（读-改-写） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PickupForm(
    val distributorId: Long? = null,
    val relDistributorId: Long? = null,
    val name: String? = null,
    val province: String? = null,
    val city: String? = null,
    val area: String? = null,
    val address: String? = null,
    val lng: String? = null,
    val lat: String? = null,
    val contractPhone: String? = null,
    val status: Int? = null,
)

/** PL-A5 挂店/摘店 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PickupBindForm(
    val id: Long? = null,
    val distributorId: Long? = null,
    val bind: Boolean? = null,
)
