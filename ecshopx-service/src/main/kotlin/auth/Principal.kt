package com.ecshopx.kotlin.service.auth

/** 认证主体类别（Dingo provider 语义：jwt=OPERATOR, wxapp=MEMBER, adminwxapp=OPERATOR(wx), super=SUPER） */
enum class Kind { OPERATOR, MEMBER, SALESMAN, SUPER }

/**
 * JWT claims 承载的最小主体（sub=账号 id, kind, company_id），
 * 明细（角色/店铺绑定）服务端回查 + Caffeine 缓存，对应 PHP getUserData 每请求全量回查的收敛版。
 */
data class Principal(
    val kind: Kind,
    val id: Long,
    val companyId: Long,
    val operatorType: String? = null,
    val distributorIds: List<Long> = emptyList(),
    val openId: String? = null,
)

/** 独立部署（system_companys_id 固定）时 TenantFilter 兜底 1 */
object TenantContext {
    private val tl = ThreadLocal<Long?>()
    var companyId: Long
        get() = tl.get() ?: 1L
        set(v) { tl.set(v) }

    fun clear() = tl.remove()
}
