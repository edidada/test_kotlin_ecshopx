package com.ecshopx.kotlin.dao.systemlink

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * SystemLink 对接族三表（M2-2 §27.5）。
 *
 * 这三张表各管一件事，混在一起就说不清"谁是谁的证据"：
 *  - [SystemLinkSettingDO]：**凭据与开关**（一个租户一行，两把 token + IP 白名单）；
 *  - [OmsQueueLogDO]：**调用留痕**（一次调用两行，request 与 response，共用 `msg_id`）；
 *  - [CompanyLicenseDO]：**站点授权**（`eid`/`passport_uid` → `company_id` 的映射与到期时间）。
 *
 * 留痕表不是 outbox：outbox 是"事件与业务同事务、投递在事务外"（M2-1 §25.4），
 * 而这一张是"HTTP 进来时写一行、出去时再写一行"——进程在中间被杀时，留下的正是
 * "请求进来了、应答没写完"这个现场，而这正是要查的那件事。两者的 `status` 词表也因此不同。
 */
class SystemLinkSettingDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var isOpen: Int = 0
    var openapiToken: String? = null
    var omsToken: String? = null
    var ipWhitelist: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/**
 * 调用留痕行。
 *
 * `runtime` 只在 `api_type=response` 那一行有意义（请求行还没跑完，量不出耗时），
 * 所以它在请求行恒 0 —— 这不是缺省值偷懒，是"这一列在这一行上本来就不该有值"。
 */
class OmsQueueLogDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var apiType: String = "request"
    var worker: String = ""
    var params: String? = null
    var result: String? = null
    var status: String = "running"
    var runtime: Int = 0
    var msgId: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/** 留痕列表行：**不含 `params`/`result` 两个 CLOB**（SL-A3 的判据，理由同 `WebhookDeliveryRow`） */
class OmsQueueLogRow {
    var id: Long = 0
    var companyId: Long = 0
    var apiType: String? = null
    var worker: String? = null
    var status: String? = null
    var runtime: Int = 0
    var msgId: String? = null
    var created: Long = 0
    var updated: Long = 0
}

class CompanyLicenseDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var eid: String? = null
    var passportUid: String? = null
    var issueId: String? = null
    var availableDays: Int = 0
    var activeAt: Long = 0
    var expiredAt: Long = 0
    var goodsCode: String? = null
    var productCode: String? = null
    var source: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

private const val SET_COLS =
    "id, company_id, is_open, openapi_token, oms_token, ip_whitelist, created, updated"

private const val LOG_COLS =
    "id, company_id, api_type, worker, status, runtime, msg_id, created, updated"

private const val LOG_FILTER_SQL = """
    AND l.company_id = #{f.companyId}
    <if test="f.apiType != null"> AND l.api_type = #{f.apiType}</if>
    <if test="f.worker != null"> AND l.worker = #{f.worker}</if>
    <if test="f.status != null"> AND l.status = #{f.status}</if>
    <if test="f.msgId != null"> AND l.msg_id = #{f.msgId}</if>
    <if test="f.createdStart != null"> AND l.created &gt;= #{f.createdStart}</if>
    <if test="f.createdEnd != null"> AND l.created &lt;= #{f.createdEnd}</if>
"""

private const val LIC_COLS =
    "id, company_id, eid, passport_uid, issue_id, available_days, active_at, expired_at, " +
        "goods_code, product_code, source, created, updated"

@Mapper
interface SystemLinkSettingMapper {

    @Select("SELECT $SET_COLS FROM systemlink_setting WHERE company_id = #{companyId}")
    fun findByCompany(@Param("companyId") companyId: Long): SystemLinkSettingDO?

    /**
     * 全部启用中的设置。**这是"这个请求属于哪个租户"的唯一判据**：
     * 入站请求里没有 `company_id`（源系统更没有），token 也不随报文发送——
     * 能用来认租户的只有"用哪一把 token 算得出这个签"。
     * 所以验签不是"取一把 token 算一遍"，而是"逐个租户算一遍，第一个对上的就是它"。
     * 租户数是个位数，这个 O(n) 是这一族最便宜的代价；反过来（先按入参 `company_id` 定位租户）
     * 就等于让对端自己声明自己是哪家，签再对也没有意义。
     */
    @Select("SELECT $SET_COLS FROM systemlink_setting WHERE is_open = 1 ORDER BY company_id")
    fun listOpen(): List<SystemLinkSettingDO>

    @Select("SELECT $SET_COLS FROM systemlink_setting WHERE is_open = 1 AND openapi_token = #{token}")
    fun findByOpenapiToken(@Param("token") token: String): SystemLinkSettingDO?

    @Select("SELECT $SET_COLS FROM systemlink_setting WHERE is_open = 1 AND oms_token = #{token}")
    fun findByOmsToken(@Param("token") token: String): SystemLinkSettingDO?

    @Insert(
        "INSERT INTO systemlink_setting(company_id, is_open, openapi_token, oms_token, ip_whitelist, created, updated) " +
            "VALUES(#{companyId}, #{isOpen}, #{openapiToken}, #{omsToken}, #{ipWhitelist}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(s: SystemLinkSettingDO): Int

    /**
     * 保存设置。`null` 表示"这一位不改"——运营看不到 token 全值（SL-A1 掩码出参），
     * 也就没法把它抄回来再存一遍，所以"传空 = 保持原值"是这一条唯一的可用语义。
     */
    @Update(
        "<script>UPDATE systemlink_setting SET is_open = #{isOpen}, " +
            "<if test=\"openapiToken != null\">openapi_token = #{openapiToken}, </if>" +
            "<if test=\"omsToken != null\">oms_token = #{omsToken}, </if>" +
            "ip_whitelist = #{ipWhitelist}, updated = #{updated} WHERE company_id = #{companyId}</script>",
    )
    fun update(
        @Param("companyId") companyId: Long,
        @Param("isOpen") isOpen: Int,
        @Param("openapiToken") openapiToken: String?,
        @Param("omsToken") omsToken: String?,
        @Param("ipWhitelist") ipWhitelist: String?,
        @Param("updated") updated: Long,
    ): Int
}

@Mapper
interface OmsQueueLogMapper {

    @Insert(
        "INSERT INTO systemlink_oms_queuelog(company_id, api_type, worker, params, result, status, runtime, msg_id, created, updated) " +
            "VALUES(#{companyId}, #{apiType}, #{worker}, #{params}, #{result}, #{status}, #{runtime}, #{msgId}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(l: OmsQueueLogDO): Int

    /** 收尾：补 result / status / runtime（请求行与应答行各调一次，`runtime` 只有应答行非 0） */
    @Update(
        "UPDATE systemlink_oms_queuelog SET result = #{result}, status = #{status}, runtime = #{runtime}, " +
            "updated = #{now} WHERE id = #{id}",
    )
    fun finish(
        @Param("id") id: Long,
        @Param("result") result: String?,
        @Param("status") status: String,
        @Param("runtime") runtime: Int,
        @Param("now") now: Long,
    ): Int

    /** 详情（SL-A4）：**带**两个 CLOB —— 排障只有这一条路 */
    @Select("SELECT $LOG_COLS, params, result FROM systemlink_oms_queuelog WHERE id = #{id} AND company_id = #{companyId}")
    fun find(@Param("id") id: Long, @Param("companyId") companyId: Long): OmsQueueLogDO?

    @Select("<script>SELECT COUNT(*) FROM systemlink_oms_queuelog l WHERE 1=1$LOG_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $LOG_COLS FROM systemlink_oms_queuelog l WHERE 1=1$LOG_FILTER_SQL " +
            "ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<OmsQueueLogRow>

    /** 同一 `msg_id` 的两行（e2e 用它断"一次调用两行、号相同"） */
    @Select("SELECT $LOG_COLS FROM systemlink_oms_queuelog WHERE msg_id = #{msgId} ORDER BY id")
    fun listByMsgId(@Param("msgId") msgId: String): List<OmsQueueLogRow>
}

@Mapper
interface CompanyLicenseMapper {

    @Select("SELECT $LIC_COLS FROM company_license WHERE company_id = #{companyId}")
    fun findByCompany(@Param("companyId") companyId: Long): CompanyLicenseDO?

    /** `ecapi.site.*` 的定位口径：`eid` 与 `passport_uid` 任一命中即同一租户（源系统两者都收） */
    @Select("SELECT $LIC_COLS FROM company_license WHERE eid = #{v} OR passport_uid = #{v} LIMIT 1")
    fun findByEidOrPassport(@Param("v") v: String): CompanyLicenseDO?

    @Insert(
        "INSERT INTO company_license(company_id, eid, passport_uid, issue_id, available_days, active_at, expired_at, " +
            "goods_code, product_code, source, created, updated) " +
            "VALUES(#{companyId}, #{eid}, #{passportUid}, #{issueId}, #{availableDays}, #{activeAt}, #{expiredAt}, " +
            "#{goodsCode}, #{productCode}, #{source}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(l: CompanyLicenseDO): Int

    /** 续费：从**原到期时间**起算（已过期的从今天起算），不覆盖 `active_at` */
    @Update("UPDATE company_license SET expired_at = #{expiredAt}, available_days = available_days + #{days}, updated = #{now} " +
        "WHERE company_id = #{companyId}")
    fun renew(
        @Param("companyId") companyId: Long,
        @Param("expiredAt") expiredAt: Long,
        @Param("days") days: Int,
        @Param("now") now: Long,
    ): Int
}

/**
 * 站点开通要动的两张既有表（`company` / `operators`）。
 *
 * 单独一个 mapper 而不是往 `CompanyMapper`/`OperatorsMapper` 里塞：那两个是**读口**
 * （登录、租户校验都读它们），写口跟着 SystemLink 走，混进去以后"谁在写 company"就说不清了。
 */
@Mapper
interface SiteTenantMapper {

    @Select("SELECT company_id, company_name, status FROM company WHERE company_id = #{companyId}")
    fun findCompany(@Param("companyId") companyId: Long): SiteCompanyDO?

    @Insert("INSERT INTO company(company_name, license_code, status, system_is_saas, created, updated) " +
        "VALUES(#{companyName}, #{licenseCode}, #{status}, FALSE, #{now}, #{now})")
    @Options(useGeneratedKeys = true, keyProperty = "companyId", keyColumn = "company_id")
    fun insertCompany(c: SiteCompanyDO): Int

    /** 关站：`status='closed'` 而不是删行（删了历史订单的归属就断了） */
    @Update("UPDATE company SET status = #{status}, updated = #{now} WHERE company_id = #{companyId}")
    fun updateCompanyStatus(
        @Param("companyId") companyId: Long,
        @Param("status") status: String,
        @Param("now") now: Long,
    ): Int

    @Select("SELECT operator_id FROM operators WHERE mobile = #{mobile} AND operator_type = 'admin' LIMIT 1")
    fun findAdminByMobile(@Param("mobile") mobile: String): Long?

    @Insert(
        "INSERT INTO operators(company_id, username, password, mobile, operator_type, disabled, created, updated) " +
            "VALUES(#{companyId}, #{username}, #{password}, #{mobile}, 'admin', FALSE, #{now}, #{now})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "operatorId", keyColumn = "operator_id")
    fun insertAdminOperator(
        @Param("companyId") companyId: Long,
        @Param("username") username: String,
        @Param("password") password: String,
        @Param("mobile") mobile: String,
        @Param("now") now: Long,
    ): Int
}

class SiteCompanyDO {
    var companyId: Long = 0
    var companyName: String = ""
    var licenseCode: String? = null
    var status: String = "normal"
}
