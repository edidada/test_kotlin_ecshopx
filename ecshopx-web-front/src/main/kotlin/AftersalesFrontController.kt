package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.aftersales.AftersalesApplyForm
import com.ecshopx.kotlin.service.aftersales.AftersalesCloseForm
import com.ecshopx.kotlin.service.aftersales.AftersalesSendbackForm
import com.ecshopx.kotlin.service.aftersales.AftersalesService
import com.ecshopx.kotlin.service.auth.Principal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * AF-F1..7 会员面售后（docs/04 §11.1，前缀 `/api/h5app/wxapp`，鉴权=会员 JWT）。
 *
 * 三条口径：
 *  1. `user_id`/`company_id` 一律取自令牌，绝不接受入参覆盖 —— 越权访问别人的售后单
 *     在 DAO 层就是 0 行（`findByBnAndUser`），出参 404 而不是 403，不泄露单号是否存在；
 *  2. 单号在 URL 与出参里都是**字符串**（16 位号段值），非法格式直接 404 语义，不做静默 0；
 *  3. 换货（`EXCHANGING_GOODS`）由服务层给 501，本层不加判断（口径见 D5/D6）。
 */
@RestController
class AftersalesFrontController(private val aftersales: AftersalesService) {

    /** AF-F1 申请售后（一售后单=一订单行 N 件，D7） */
    @PostMapping("/api/h5app/wxapp/aftersales")
    fun apply(
        @RequestBody form: AftersalesApplyForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(aftersales.apply(userId(p), cid(p), form))

    /** AF-F2 我的售后列表 */
    @GetMapping("/api/h5app/wxapp/aftersales")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(aftersales.list(userId(p), cid(p), raw))

    /** AF-F3 售后详情：主行 + detail[] + refund_info + order_info */
    @GetMapping("/api/h5app/wxapp/aftersales/{aftersales_bn}")
    fun detail(
        @PathVariable("aftersales_bn") bn: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(aftersales.info(bn(bn), cid(p), userId(p)))

    /** AF-F4 填报回寄物流（填完 progress 1→2） */
    @PostMapping("/api/h5app/wxapp/aftersales/sendback")
    fun sendback(
        @RequestBody form: AftersalesSendbackForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aftersales.sendback(bn(form.aftersalesBn ?: bad("aftersales_bn")), cid(p), userId(p), form))

    /** AF-F5 撤销售后申请（进入退款流程后不可撤） */
    @PostMapping("/api/h5app/wxapp/aftersales/close")
    fun close(
        @RequestBody form: AftersalesCloseForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aftersales.close(bn(form.aftersalesBn ?: bad("aftersales_bn")), cid(p), userId(p), form))

    /** AF-F6 单件可退额（= floor(行 total_fee / 行 num)，不含运费） */
    @GetMapping("/api/h5app/wxapp/aftersales/item/price")
    fun itemPrice(
        @RequestParam("order_id") orderId: String,
        @RequestParam("item_id") itemId: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(aftersales.itemPrice(bn(orderId), itemId, userId(p), cid(p)))

    /** AF-F7 申请原因字典（D8：sys_config aftersales.reason，缺省回落四条） */
    @GetMapping("/api/h5app/wxapp/aftersales/reason/list")
    fun reasons(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(mapOf("list" to aftersales.reasons(cid(p))))

    private fun bad(field: String): Nothing =
        throw BizException(ErrorCode.VALIDATION, "$field 必填", mapOf(field to listOf("不能为空")))

    /** 单号只可能是号段生成的数字串：非数字一律 404（与"查不到"同一语义） */
    private fun bn(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "售后单不存在")

    private fun userId(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
