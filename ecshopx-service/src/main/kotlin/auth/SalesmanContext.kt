package com.ecshopx.kotlin.service.auth

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode

/**
 * 员工端（第三条链）的会话主体（docs/04 §18.3）。
 *
 * 为什么不是一个 `Principal` 就够：运营面带的是"这个账号能看哪几家店"（`distributor_ids`，一个集合），
 * 员工面带的是"这个人就是哪家店的那个岗"（一个确定的 `distributor_id` + `salesman_id`）。
 * 前者用来**收窄查询**，后者用来**强制归属**（SO-E1 的 `salesman_id` 由会话注入、入参给了也忽略），
 * 两种语义塞进同一个 data class 会变成一堆"这个字段在这条链上没意义"。
 */
data class SalesmanPrincipal(
    val salesmanId: Long,
    val companyId: Long,
    val distributorId: Long,
    val shopId: Long,
    val userId: Long,
    val roleId: Long,
    val salesmanName: String,
    val mobile: String,
)

/**
 * 请求级的当前员工（与 [TenantContext] 同一条 ThreadLocal 纪律：过滤器进、 finally 清）。
 *
 * 不用 `SecurityContext` 的 principal 承载是因为两条链的 principal 类型不同，
 * 控制器上同时挂两种 `@AuthenticationPrincipal` 会在切面鉴权时踩到类型转换；
 * ThreadLocal 那条路本项目已经走通（租户上下文），复用同一条最稳。
 */
object SalesmanContext {
    private val tl = ThreadLocal<SalesmanPrincipal?>()

    var current: SalesmanPrincipal?
        get() = tl.get()
        set(v) {
            tl.set(v)
        }

    /** 员工面每个入口都要人：拿不到就是没认证，401 的文案与全局一致 */
    fun require(): SalesmanPrincipal = current ?: throw BizException(ErrorCode.UNAUTH, "Unauthenticated.")

    fun clear() = tl.remove()
}
