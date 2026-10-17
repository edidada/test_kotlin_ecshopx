package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.pay.OfflineAuditForm
import com.ecshopx.kotlin.service.pay.OfflineAuditService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * SUP-6 线下转账审核（docs/05 §M1-2，docs/04 §8 C55 里"审核结清属 M1"的那一半）。
 *
 * 前台买家在 M0-7 就能提交凭证（PAY-2 `pay_type=offline_pay`，只落 `offline_payment`、订单留待付款），
 * 本轮补上卖家侧的两个动作：`GET /api/offline-payments` 看待审列表，`POST /api/offline-payments/check` 给结论。
 *
 * 两条权限码分立（同 §11.2 的审核岗/财务岗口径）：
 *  - `offline_payment.lists`：只读，能看到凭证与转账信息；
 *  - `offline_payment.check`：**唯一把钱收进来的入口**，通过即结清订单，所以写路径一律 `@Audited`。
 *
 * 不单开"凭证详情"端点：列表行已含 `voucher_pic/pay_sn/transfer_remark` 全部字段，
 * 且 `?order_id=` 可钻取单笔订单的凭证，详情只是同一份数据的第二次读。
 */
@RestController
class OfflinePaymentAdminController(private val offline: OfflineAuditService) {

    /** SUP-6a 凭证列表：`check_status`（0 待审 / 1 已审 / 2 已拒）与 `order_id` 均可筛 */
    @RequiresResource("offline_payment.lists")
    @GetMapping("/api/offline-payments")
    fun list(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(offline.list(cid(p), raw))

    /** SUP-6b 审核：`check_status=1` 通过并结清，`=2` 驳回（驳回不产生任何资金动作） */
    @Audited(route = "offline_payment.check")
    @RequiresResource("offline_payment.check")
    @PostMapping("/api/offline-payments/check")
    fun check(
        @RequestBody form: OfflineAuditForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(offline.audit(cid(p), p?.id?.let { "admin:$it" }, form))

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
