package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.pay.PayChannelConfigService
import com.ecshopx.kotlin.service.pay.PayChannelForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 支付渠道配置后台面（docs/04 §29.6，批F；兑现 §27.5 留下的"渠道开户/开关 Admin 面"）。
 *
 * 三条职责边界：
 *  1. **台账读 90**：密钥只出掩码形态（`Mask.secret`），三家行一并回显；
 *  2. **开户/改配置 91**：`api_key` 空串=不改（改开关不抹密钥），非空=覆盖并 AES-GCM 加密落库；
 *  3. **开关 91**：关掉的渠道在支付面上是"渠道未配置"（422 同口径），不静默换渠道。
 *
 * 源系统没有这一族 HTTP 面（配置行在库里直改）；本项目开这个面是生产化的必要动作——
 * 密钥不能只靠运维进库手改，改错了是钱的事。
 */
@RestController
class PayChannelAdminController(private val payConfigs: PayChannelConfigService) {

    /** PC-C1 渠道配置台账（本租户全部渠道行，密钥掩码） */
    @RequiresResource(ActivityCodes.PAYCONFIG_LISTS)
    @GetMapping("/api/pay/channel/config/list")
    fun list(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Any?> = ApiResponse.ok(payConfigs.listForAdmin(cid(p)))

    /** PC-C2 开户/改配置（upsert by channel；`api_key` 空=不改） */
    @RequiresResource(ActivityCodes.PAYCONFIG_UPDATE)
    @Audited(route = "pay.channel.config")
    @PostMapping("/api/pay/channel/config")
    fun upsert(
        @RequestBody form: PayChannelForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(payConfigs.upsert(cid(p), form))

    /** PC-C3 渠道开关（1=开/0=关） */
    @RequiresResource(ActivityCodes.PAYCONFIG_UPDATE)
    @Audited(route = "pay.channel.toggle")
    @PostMapping("/api/pay/channel/config/{channel}/toggle")
    fun toggle(
        @PathVariable channel: String,
        @RequestParam("status", required = false, defaultValue = "") statusRaw: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(
        payConfigs.toggle(cid(p), channel, statusRaw.toIntOrNull()
            ?: (if (statusRaw == "true") 1 else 0)),
    )

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
