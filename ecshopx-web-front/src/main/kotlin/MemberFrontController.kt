package com.ecshopx.kotlin.web.front

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.member.AddressService
import com.ecshopx.kotlin.service.member.CollectService
import com.ecshopx.kotlin.service.member.MemberService
import com.ecshopx.kotlin.service.member.VcodeService
import com.ecshopx.kotlin.service.order.OrderQueryService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * MEM-1..8 个人中心（docs/04 §2.6）。
 *
 * user_id / company_id 只认会员令牌，任何入参里的同名字段都会被服务层拒掉；
 * MEM-8 验证码是唯一的匿名面（登录前就要发码），已在 SecurityConfig 放行。
 */
@RestController
class MemberFrontController(
    private val member: MemberService,
    private val addresses: AddressService,
    private val collect: CollectService,
    private val vcode: VcodeService,
    private val orders: OrderQueryService,
) {

    data class MobileForm(val mobile: String? = null, val vcode: String? = null, val check_type: String? = "mobile")
    data class VcodeForm(val mobile: String? = null, val type: String? = "login")
    data class DeleteForm(val address_id: Long? = null)

    /** MEM-1 我的信息（手机号脱敏） */
    @GetMapping("/api/h5app/wxapp/member")
    fun profile(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(member.profile(cid(p), uid(p)))

    /** MEM-2 修改资料：整包透传，白名单与禁改字段判定都在服务层 */
    @PutMapping("/api/h5app/wxapp/member")
    fun update(
        @RequestBody raw: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(member.updateProfile(cid(p), uid(p), raw))

    /** MEM-3 换绑手机（验证码走 mock 恒码） */
    @PutMapping("/api/h5app/wxapp/member/mobile")
    fun rebind(
        @RequestBody form: MobileForm,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(member.rebindMobile(cid(p), uid(p), form.mobile, form.vcode))

    /** MEM-7 订单角标 + 资产 */
    @GetMapping("/api/h5app/wxapp/member/statistical")
    fun statistical(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(member.statistical(cid(p), uid(p), orders.counts(cid(p), uid(p))))

    /** MEM-4 地址簿 */
    @GetMapping("/api/h5app/wxapp/addresslist")
    fun addressList(@AuthenticationPrincipal p: Principal?): ApiResponse<PageResult<Map<String, Any?>>> =
        ApiResponse.ok(addresses.list(cid(p), uid(p)))

    @PostMapping("/api/h5app/wxapp/addresslist")
    fun addressCreate(
        @RequestBody raw: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(addresses.create(cid(p), uid(p), raw))

    @PutMapping("/api/h5app/wxapp/addresslist/{address_id}")
    fun addressUpdate(
        @PathVariable("address_id") addressId: Long,
        @RequestBody raw: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(addresses.update(cid(p), uid(p), addressId, raw))

    /** 删除：RESTful 路径与"集合 + body"两种写法都收（源系统前端两种都在用） */
    @DeleteMapping("/api/h5app/wxapp/addresslist/{address_id}")
    fun addressDelete(
        @PathVariable("address_id") addressId: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(addresses.delete(cid(p), uid(p), addressId))

    @DeleteMapping("/api/h5app/wxapp/addresslist")
    fun addressDeleteByBody(
        @RequestBody(required = false) form: DeleteForm?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val id = form?.address_id ?: throw BizException(ErrorCode.VALIDATION, "address_id 必填", mapOf("address_id" to listOf("不能为空")))
        return ApiResponse.ok(addresses.delete(cid(p), uid(p), id))
    }

    /** MEM-5 收藏列表 */
    @GetMapping("/api/h5app/wxapp/member/collect/items")
    fun collectList(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(collect.list(cid(p), uid(p), raw))

    /** MEM-6 收藏 / 取消收藏 */
    @PostMapping("/api/h5app/wxapp/member/collect/items/{item_id}")
    fun collectAdd(
        @PathVariable("item_id") itemId: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(collect.add(cid(p), uid(p), itemId))

    @DeleteMapping("/api/h5app/wxapp/member/collect/items/{item_id}")
    fun collectCancel(
        @PathVariable("item_id") itemId: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(collect.cancel(cid(p), uid(p), itemId))

    /** 浏览足迹：列表 / 删一条 / 清空（源系统个人中心"我的浏览"） */
    @GetMapping("/api/h5app/wxapp/member/browse/items")
    fun browseList(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(collect.browseList(cid(p), uid(p), raw))

    @DeleteMapping("/api/h5app/wxapp/member/browse/items/{item_id}")
    fun browseDelete(
        @PathVariable("item_id") itemId: Long,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> = ApiResponse.ok(collect.deleteBrowse(uid(p), itemId))

    @DeleteMapping("/api/h5app/wxapp/member/browse/items")
    fun browseClear(@AuthenticationPrincipal p: Principal?): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(collect.clearBrowse(cid(p), uid(p)))

    /** MEM-8 发送验证码（匿名可用：登录前就要发码） */
    @PostMapping("/api/h5app/vcode")
    fun sendVcode(@RequestBody form: VcodeForm): ApiResponse<Map<String, Any?>> =
        ApiResponse.ok(vcode.send(form.mobile, form.type))

    private fun uid(p: Principal?): Long = p?.id ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    private fun cid(p: Principal?): Long = p?.companyId ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")
}
