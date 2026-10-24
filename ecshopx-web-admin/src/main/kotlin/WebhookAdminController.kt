package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.webhook.WebhookService
import com.ecshopx.kotlin.service.webhook.WebhookSubscriptionForm
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * WH-A1..A9 Webhook 订阅与投递（docs/04 §25.3，前缀 `/api/webhook`）。
 *
 * 三条权限码（§25.5）：`webhook.lists` 读、`webhook.update` 改订阅、`webhook.push` 往外打流量。
 * 重推与测试事件单独一个码不是洁癖：它们都会**主动连一个外部地址**，
 * 拿到 `webhook.update` 的人能改订阅目标，但只有拿到 `webhook.push` 的人能让服务器现在就去敲那扇门 ——
 * 前者是配置，后者是动作，出事的时候看的是动作那一位给了谁。
 *
 * 这一组走 `adminChain`（`/api` 前缀那一条，认运营令牌），与网关那条 `/api/openapi` 的匿名链无关。
 *
 * （又踩一次本仓那条 Kotlin 块注释坑：注释里不能写"`/api` 双星"那种 ant 路径字面量 ——
 *  斜杠紧跟一个星号就会被当成嵌套注释的开头，编译器只报 "Unclosed comment"、不报是哪一行开的头。
 *  `SecurityConfig` 与 `OpenapiEnvelope` 各栽过一次，这是第三次。）
 */
@RestController
@RequestMapping("/api/webhook")
class WebhookAdminController(private val webhook: WebhookService) {

    /** WH-A1 订阅列表（`developer_id?` / `topic?` / `status?`，分页） */
    @RequiresResource("webhook.lists")
    @GetMapping("/subscriptions")
    fun subscriptions(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(webhook.list(cid(p), raw))

    /** WH-A2 新建订阅：无出站密钥、非 https 且非本机回环 → 422（两道判据在服务层） */
    @Audited(route = "webhook.subscription_create")
    @RequiresResource("webhook.update")
    @PostMapping("/subscription")
    fun create(
        @RequestBody form: WebhookSubscriptionForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(webhook.create(cid(p), form))

    /** WH-A3 改 `url`/`topic`/`status`；改址不追改历史记录 */
    @Audited(route = "webhook.subscription_update")
    @RequiresResource("webhook.update")
    @PutMapping("/subscription/{id}")
    fun update(
        @PathVariable("id") id: Long,
        @RequestBody form: WebhookSubscriptionForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(webhook.update(cid(p), id, form))

    /** WH-A4 删订阅：`webhook_delivery_log` 的行留着（那是对接方"我收到了"的证据） */
    @Audited(route = "webhook.subscription_delete")
    @RequiresResource("webhook.update")
    @DeleteMapping("/subscription/{id}")
    fun delete(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(webhook.delete(cid(p), id))

    /** WH-A5 投递记录（`topic?`/`status?`/`msg_id?`/`developer_id?`/时间段，分页；列表不含正文） */
    @RequiresResource("webhook.lists")
    @GetMapping("/deliveries")
    fun deliveries(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(webhook.deliveries(cid(p), raw))

    /** WH-A6 详情：正文 + 最后响应 + **当次实际发出的签名头**（不是重算值） */
    @RequiresResource("webhook.lists")
    @GetMapping("/delivery/{id}")
    fun delivery(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(webhook.delivery(cid(p), id))

    /** WH-A7 手动重推：同 `msg_id`、`attempts` 不清零 */
    @Audited(route = "webhook.delivery_retry")
    @RequiresResource("webhook.push")
    @PostMapping("/delivery/{id}/retry")
    fun retry(
        @PathVariable("id") id: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(webhook.retry(cid(p), id))

    /** WH-A8 事件目录 + 载荷字段说明（读注册表，与 OP-A5 同一个纪律） */
    @RequiresResource("webhook.lists")
    @GetMapping("/topics")
    fun topics(): ApiResponse<List<Map<String, Any?>>> = ApiResponse.ok(webhook.topics())

    /**
     * WH-B0 积压告警读面（批G）：待投按出生时刻分桶 + 终态 fail 数。
     * 复用读码 `webhook.lists`——它本来就是投递记录的一种聚合读，不另开权限位；
     * 出参形状见 `WebhookContract.BACKLOG`（e2e 钉空库形状）。
     */
    @RequiresResource("webhook.lists")
    @GetMapping("/backlog")
    fun backlog(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(webhook.backlog(cid(p)))

    /**
     * WH-A9 测试事件：签名与正式推送同一套，且**落一条 `delivery_log`**。
     *
     * 入参形状不做表单类：`echo` 要出的是"入参原样"， typed DTO 会先把没声明的键丢掉，
     * 那就不叫原样了 —— 直收 map，`url`/`developer_id`/`topic` 之外的键整份进 `data.echo`。
     */
    @Audited(route = "webhook.test_push")
    @RequiresResource("webhook.push")
    @PostMapping("/test")
    fun test(
        @RequestBody raw: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(webhook.testEvent(cid(p), raw))

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
