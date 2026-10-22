package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.SalesmanContext
import com.ecshopx.kotlin.service.promotion.RightsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * RT-E 员工面次卡核销（docs/04 §29.4，前缀 `/api/admin/wxapp/right`，链=`staffChain`，header `x-wxapp-session`）。
 *
 * 与 [StaffController] 同一条链同一窟口径：**没有 `@RequiresResource`**（§18.7）——
 * 判据只有会话（`JwtSalesmanFilter` 每请求回查）+ 公司归属（服务层 `companyId` 收口）。
 * 波及面控制：核销是一个"用掉一次"的动作，`service/consume` 里 CAS + 日志双写恒等，
 * 重复扫码（网络重试第二次提交）要么第二行进"次数已用完"要么 CAS 赢得一次动账，不会两行穿透。
 */
@RestController
class StaffRightsController(private val rights: RightsService) {

    /** RT-E1 扫码先看清这张卡（详情 + 动账史；不归属本公司一律 404） */
    @GetMapping("/api/admin/wxapp/right")
    fun detail(
        @RequestParam("id") id: String,
    ): ApiResponse<Map<String, Any?>> {
        val me = SalesmanContext.require()
        return ApiResponse.ok(rights.staffDetail(me.companyId, resolved(id.toLongOrNull())))
    }

    /** RT-E2 核销一次：`operator_id` 记的就是扫码员工的人（DL/客服按它回查） */
    @PostMapping("/api/admin/wxapp/right/consume")
    fun consume(
        @RequestBody form: ConsumeForm,
    ): ApiResponse<Map<String, Any?>> {
        val me = SalesmanContext.require()
        return ApiResponse.ok(rights.consume(me.companyId, resolved(form.id ?: form.rightsIdAlias), me.salesmanId))
    }

    private fun resolved(v: Long?): Long =
        v?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "权益不存在")
}

/** RT-E 核销入参：`id` 为主、兼容 `rights_id` 两种字段名 */
data class ConsumeForm(val id: Long? = null, @com.fasterxml.jackson.annotation.JsonAlias("rights_id") val rightsIdAlias: Long? = null)
