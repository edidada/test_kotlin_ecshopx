package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.PickupLocationService
import com.ecshopx.kotlin.service.order.WriteOffService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * PL-F1 + ZT-F1 会员面自提（docs/04 §18.1，前缀 `/api/h5app/wxapp`）。
 *
 * 两条的鉴权正好相反，这不是随手定的：
 *  - **PL-F1 匿名**（标 *）：选点发生在下单之前，小程序这一步还没有令牌；出参电话一律掩码，
 *    匿名面不能变成同行抄联系方式的口子（§18.1）。
 *  - **ZT-F1 必须登录**：取货码就是提货凭证，"谁都能查任意单的码"等于把码公开，
 *    核销闸门③（码逐字相等）会当场失去意义 —— 所以归属按令牌里的 `user_id` 认，不是本人的单一律 404。
 */
@RestController
class PickupFrontController(
    private val pickups: PickupLocationService,
    private val writeOffs: WriteOffService,
) {

    /** PL-F1 某店可用自提点（可选 `lng`/`lat` 带出 `distance`） */
    @GetMapping("/api/h5app/wxapp/distributor/pickuplocation")
    fun pickupLocations(
        @RequestParam(required = false, name = "distributor_id") distributorId: String?,
        @RequestParam(required = false) lng: String?,
        @RequestParam(required = false) lat: String?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(
            pickups.frontList(
                companyId = cid(p),
                distributorId = distributorId?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
                lng = lng?.toDoubleOrNull(),
                lat = lat?.toDoubleOrNull(),
            ),
        )

    /** ZT-F1 我的取货码（只有本人 `receipt_type=ziti` 的单查得到） */
    @GetMapping("/api/h5app/wxapp/order/{order_id}/ziticode")
    fun zitiCode(
        @PathVariable("order_id") orderId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(writeOffs.myCode(cid(p), userId(p), oid(orderId)))

    private fun userId(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun oid(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 }
            ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
