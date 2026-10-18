package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.SalesmanContext
import com.ecshopx.kotlin.service.auth.SalesmanPrincipal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.distribution.SalesmanService
import com.ecshopx.kotlin.service.distribution.StaffLoginForm
import com.ecshopx.kotlin.service.order.OrderCreateForm
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * M1-4 第三批后半（3B）员工端面（docs/04 §18.3，前缀 `/api/admin/wxapp`，链=`staffChain`，header `x-wxapp-session`）。
 *
 * 这一条链上**没有 `@RequiresResource`**，一条都没有（§18.7 最后一行）。
 * 运营面的权限码回答的是"这个账号在后台能做哪类事"，而员工面问的是"你是不是这个店的这个岗"——
 * 给导购配 `order.writeoff` 既没意义也危险：那份权限会让他同时拿到后台那条 WO-A2。
 * 所以这里的判据只有两样：会话（`JwtSalesmanFilter` 每请求回查 `is_valid`，D44）+ 会话里那个 `distributor_id`。
 *
 * ST-E1 是唯一的匿名入口（登录本身），其余每一条都以 [SalesmanContext.require] 开头 ——
 * 拿会员令牌或运营令牌打到这里，`JwtSalesmanFilter` 认不出 `Kind.SALESMAN`，直接 401 `Unauthenticated.`，
 * 与既有的"跨面拒绝"同口径。
 */
@RestController
class StaffController(private val salesmen: SalesmanService) {

    /** ST-E1 员工登录（`{mobile, password, distributor_id?}`；三种失败同一句 401，不回答"这手机号在不在册"） */
    @PostMapping("/api/admin/wxapp/login")
    fun login(
        @RequestBody form: StaffLoginForm,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.login(form, TenantContext.companyId))

    /** ST-E2 当前员工信息 */
    @GetMapping("/api/admin/wxapp/getinfo")
    fun info(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.info(principal()))

    /** ST-E3 本店简易档 */
    @GetMapping("/api/admin/wxapp/shoplist")
    fun shop(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.shopOf(principal()))

    /** ST-E4 我的统计（三个单数 + 佣金三数 + 可提现余额） */
    @GetMapping("/api/admin/wxapp/salespersonCount")
    fun count(): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.myCount(principal()))

    /** ST-E5 我的佣金明细（口径同 DL-A1，`salesman_id` 由会话强制） */
    @GetMapping("/api/admin/wxapp/salespersonFee")
    fun fee(
        @RequestParam raw: Map<String, String>,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(salesmen.myFeeList(principal(), raw))

    /** SO-E1 代客下单：ORDER-2 全集 + `user_id?`；`salesman_id` 由会话强制，越店的 `distributor_id` → 403 */
    @PostMapping("/api/admin/wxapp/order/create")
    fun createOrder(
        @RequestBody form: OrderCreateForm,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.staffCreateOrder(principal(), form))

    /** SO-E2 我开的单 */
    @GetMapping("/api/admin/wxapp/order/getlist")
    fun orderList(
        @RequestParam raw: Map<String, String>,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.staffOrderList(principal(), raw))

    /** SO-E3 单详情（同 ORDER-5 字段集，外加 `salesman{}`；不是他开的单 → 404） */
    @GetMapping("/api/admin/wxapp/order/getinfo")
    fun orderInfo(
        @RequestParam(name = "order_id", required = false) orderId: String?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(salesmen.staffOrderDetail(principal(), oid(orderId)))

    /** SO-E4 员工端核销：与后台 WO-A2 同一个 service 方法，只是署名与数据权限来源不同（§18.6） */
    @PostMapping("/api/admin/wxapp/order/ziti")
    fun writeOff(
        @RequestBody form: StaffWriteOffForm,
    ): ApiResponse<Map<String, Any?>> {
        val p = principal()
        val orderId = form.orderId ?: throw BizException(ErrorCode.VALIDATION, "order_id 必填", mapOf("order_id" to listOf("不能为空")))
        val code = form.zitiCode
            ?: throw BizException(ErrorCode.VALIDATION, "ziti_code 必填", mapOf("ziti_code" to listOf("不能为空")))
        return ApiResponse.ok(salesmen.staffWriteOff(p, orderId, code))
    }

    /** 每个入口的第一句话：没有会话就是 401，不给"参数缺失"这种看着像 400 的错 */
    private fun principal(): SalesmanPrincipal = SalesmanContext.require()

    private fun oid(raw: String?): Long =
        raw?.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
}

/** SO-E4 员工端核销入参（`{order_id(Y), ziti_code(Y)}`，空串不当"没传"处理，直接进逐字比对失败） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class StaffWriteOffForm(
    val orderId: Long? = null,
    val zitiCode: String? = null,
)
