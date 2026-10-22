package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.promotion.CheckInService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * IC-S 会员面签到（docs/04 §29.1，前缀 `/api/h5app/wxapp`，鉴权=会员 JWT）。
 *
 * 两条路径族照抄附录（源 `frontapi/promotions.php`）：
 *  - `/sign` + `/sign/weekly/list`：签到主口；
 *  - `/promotions/checkin/create` + `/promotion/checkin/getlist`：CheckInController 那族的别名口。
 * 四条是**同一个实现、同一个出参契约**——源系统两族处理器各写一遍，本项目按"别名口"收敛，
 * 别名不存在的理由（§29.1 差异表 D-IC2）：四条路一对两天线，行为分叉只会制造对不上账的两份数据。
 */
@RestController
class SignFrontController(private val checkin: CheckInService) {

    /** IC-S1 签到（幂等：同天第二次回业务错"今日已签到"） */
    @PostMapping("/api/h5app/wxapp/sign")
    fun sign(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(checkin.sign(cid(p), uid(p)))

    /** IC-S2 周面板（近 7 天逐日子项 + 汇总） */
    @GetMapping("/api/h5app/wxapp/sign/weekly/list")
    fun weekly(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(checkin.weeklyList(cid(p), uid(p)))

    /** IC-S5 别名口：与 sign 同一契约、同一幂等判据 */
    @PostMapping("/api/h5app/wxapp/promotion/checkin/create")
    fun create(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(checkin.sign(cid(p), uid(p)))

    /** IC-S5 别名口：与 weekly/list 同一契约 */
    @GetMapping("/api/h5app/wxapp/promotion/checkin/getlist")
    fun checkinList(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(checkin.weeklyListAlias(cid(p), uid(p)))

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
