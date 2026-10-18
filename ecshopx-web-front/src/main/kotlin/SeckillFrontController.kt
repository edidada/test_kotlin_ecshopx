package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.SeckillService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * SK-F1/F2 会员面秒杀（docs/04 §14.1，前缀 `/api/h5app/wxapp`，鉴权=会员 JWT）。
 *
 * 前台看得到的价与结算拿得到的价必须是同一个数，所以这里只给
 * **未停用 + 在窗 + 该行可见**的场次与商品（判据与算价链 §14.5 order 15 同源），
 * `bought_by_me` 含未付款在途单 —— 限购 2 件的人下过两笔待付款单后这里就显示"还能买 0 件"。
 */
@RestController
class SeckillFrontController(private val seckill: SeckillService) {

    /** SK-F1 场次列表（`date_status` 由前端 tab 决定看未开始/进行中/已结束） */
    @GetMapping("/api/h5app/wxapp/seckill/activities")
    fun activities(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(seckill.frontActivities(cid(p), raw))

    /** SK-F2 场次商品（含 `available` 与本人限购余量） */
    @GetMapping("/api/h5app/wxapp/seckill/activities/{seckill_id}/items")
    fun items(
        @PathVariable("seckill_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val list = seckill.frontItems(cid(p), id(id), p?.id)
        return ApiResponse.ok(linkedMapOf("seckill_id" to id(id), "items" to list))
    }

    private fun id(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "场次不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
