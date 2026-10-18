package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.GroupsService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * GP-F1..F4 会员面拼团（docs/04 §14.1，前缀 `/api/h5app/wxapp`，鉴权=会员 JWT）。
 *
 * 三条口径：
 *  1. **成员昵称一律脱敏**（`138****0001`）：团成员列表是给全租户看的，不能把手机号带出去；
 *  2. **停用的活动对前台就是"不存在"**：与 MK-F2 同一判据，不给"存在但买不了"这种要解释的状态；
 *  3. **`team_id` 是路径参数不是数字**（D29：`GT` + 号段），所以这里用 String 接、不做 `toLong`。
 */
@RestController
class GroupsFrontController(private val groups: GroupsService) {

    /** GP-F1 活动列表（带 `team_count`：正在进行中的团有几个） */
    @GetMapping("/api/h5app/wxapp/groups/activities")
    fun activities(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(groups.frontActivities(cid(p), raw))

    /** GP-F2 活动详情 + 可参加的团 */
    @GetMapping("/api/h5app/wxapp/groups/activities/{groups_activity_id}")
    fun detail(
        @PathVariable("groups_activity_id") id: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(groups.frontDetail(cid(p), long(id)))

    /** GP-F3 团成员（昵称脱敏、机器人标记） */
    @GetMapping("/api/h5app/wxapp/groups/teams/{team_id}")
    fun team(
        @PathVariable("team_id") teamId: String,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(groups.teamDetail(cid(p), teamId))

    /** GP-F4 我参与的团（含我发起的） */
    @GetMapping("/api/h5app/wxapp/groups/my/teams")
    fun myTeams(
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val list = groups.myTeams(cid(p), uid(p))
        return ApiResponse.ok(linkedMapOf("teams" to list))
    }

    private fun long(raw: String): Long =
        raw.toLongOrNull()?.takeIf { it > 0 } ?: throw BizException(ErrorCode.NOT_FOUND, "活动不存在")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
