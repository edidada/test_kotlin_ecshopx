package com.ecshopx.kotlin.service.member

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.MemberAddressDO
import com.ecshopx.kotlin.dao.auth.MembersAddressMapper
import com.ecshopx.kotlin.service.auth.MemberAuthService
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Component

/**
 * MEM-4 收货地址簿。
 *
 * 源系统的两条隐式规矩在这里显式化：
 *  - **默认地址互斥**：任何时刻一个会员至多一行 `is_def = true`，置默认时先清后设（同事务）；
 *    地址簿的第一条无条件成为默认，否则新会员下单页没有预选地址。
 *  - **归属校验在 SQL 里**：改/删都带 `user_id` 条件，影响 0 行即 404，
 *    不给"存在但不是你的地址"留任何可探测的差别。
 */
@Component
class AddressService(
    private val addresses: MembersAddressMapper,
    private val db: Db,
) {

    /** 出参：地址簿整行 + `receiver_address` 别名（前台详情页沿用下单字段名，避免前端两套取值） */
    fun list(companyId: Long, userId: Long): PageResult<Map<String, Any?>> =
        PageResult(
            addresses.countByUser(companyId, userId),
            addresses.listByUser(companyId, userId).map { view(it) },
        )

    fun create(companyId: Long, userId: Long, form: Map<String, Any?>): Map<String, Any?> {
        val row = build(companyId, userId, form, null)
        val first = addresses.countByUser(companyId, userId) == 0L
        val asDefault = row.isDef || first
        db.transactional {
            if (asDefault) addresses.clearDefault(userId, TimeSec.now())
            row.isDef = asDefault
            addresses.insert(row)
        }
        return view(addresses.findById(row.addressId, userId) ?: row)
    }

    fun update(companyId: Long, userId: Long, addressId: Long, form: Map<String, Any?>): Map<String, Any?> {
        val cur = addresses.findById(addressId, userId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "地址不存在")
        val row = build(companyId, userId, form, cur)
        row.addressId = addressId
        val asDefault = row.isDef || (cur.isDef && form["is_def"] == null)
        db.transactional {
            if (addresses.updateFull(row) == 0) throw BizException(ErrorCode.NOT_FOUND, "地址不存在")
            if (asDefault) {
                addresses.clearDefault(userId, TimeSec.now())
                addresses.markDefault(addressId, userId, TimeSec.now())
            }
        }
        return view(addresses.findById(addressId, userId)!!)
    }

    /** 删除默认地址后把剩余最新一条顶为默认，地址簿不留"无默认"状态 */
    fun delete(companyId: Long, userId: Long, addressId: Long): Map<String, Any?> {
        val cur = addresses.findById(addressId, userId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "地址不存在")
        db.transactional {
            if (addresses.delete(addressId, userId) == 0) throw BizException(ErrorCode.NOT_FOUND, "地址不存在")
            if (cur.isDef) {
                val rest = addresses.listByUser(companyId, userId)
                rest.firstOrNull()?.let { addresses.markDefault(it.addressId, userId, TimeSec.now()) }
            }
        }
        return mapOf("address_id" to addressId)
    }

    /** 下单链路（ORDER-2）读地址展开收件人：归属不符一律 404 */
    fun require(companyId: Long, userId: Long, addressId: Long): MemberAddressDO =
        addresses.findById(addressId, userId)?.takeIf { it.companyId == companyId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "地址不存在")

    private fun build(
        companyId: Long,
        userId: Long,
        form: Map<String, Any?>,
        cur: MemberAddressDO?,
    ): MemberAddressDO {
        val errors = linkedMapOf<String, List<String>>()
        val username = text(form["username"]) ?: cur?.username
        val telephone = text(form["telephone"]) ?: cur?.telephone
        val detail = text(form["adrdetail"]) ?: cur?.adrdetail
        if (username.isNullOrBlank()) errors["username"] = listOf("收货人不能为空")
        if (telephone.isNullOrBlank()) errors["telephone"] = listOf("手机号不能为空")
        else if (MemberAuthService.MOBILE.matchEntire(telephone) == null) errors["telephone"] = listOf("手机号格式错误")
        if (detail.isNullOrBlank()) errors["adrdetail"] = listOf("详细地址不能为空")
        if (errors.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "地址参数错误", errors)
        val now = TimeSec.now()
        return MemberAddressDO().apply {
            this.userId = userId
            this.companyId = companyId
            this.username = username
            this.telephone = telephone
            province = text(form["province"]) ?: cur?.province
            city = text(form["city"]) ?: cur?.city
            county = text(form["county"]) ?: cur?.county
            adrdetail = detail
            postalCode = text(form["postal_code"]) ?: text(form["postalCode"]) ?: cur?.postalCode
            lng = text(form["lng"]) ?: cur?.lng
            lat = text(form["lat"]) ?: cur?.lat
            isDef = truth(form["is_def"]) ?: (cur?.isDef ?: false)
            created = cur?.created ?: now
            updated = now
        }
    }

    private fun view(a: MemberAddressDO): Map<String, Any?> = linkedMapOf(
        "address_id" to a.addressId,
        "user_id" to a.userId,
        "company_id" to a.companyId,
        "username" to a.username,
        "telephone" to a.telephone,
        "province" to a.province,
        "city" to a.city,
        "county" to a.county,
        "adrdetail" to a.adrdetail,
        "receiver_address" to fullAddress(a),
        "postal_code" to a.postalCode,
        "lng" to a.lng,
        "lat" to a.lat,
        "is_def" to if (a.isDef) 1 else 0,
        "created" to a.created,
        "updated" to a.updated,
    )

    private fun fullAddress(a: MemberAddressDO): String =
        listOf(a.province, a.city, a.county, a.adrdetail).filterNotNull().filter { it.isNotBlank() }.joinToString("")

    private fun text(v: Any?): String? = v?.toString()?.takeIf { it.isNotBlank() }

    /** 前端传 "1"/true/"true" 都有，统一收口；未传时返回 null 表示"保持原值" */
    private fun truth(v: Any?): Boolean? = when (v) {
        null -> null
        is Boolean -> v
        else -> when (v.toString().lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }
}
