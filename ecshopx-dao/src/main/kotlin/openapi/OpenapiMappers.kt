package com.ecshopx.kotlin.dao.openapi

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 开放平台开发者（M2-1 §25.5，源系统 `OpenapiBundle/Entities/OpenapiDeveloper.php` 同形 + 一列 `status`）。
 *
 * 三件事决定这一族 DAO 的形状：
 * 1. **`app_key` 是全局唯一的身份，不是租户内的编号**：网关拿到 `app_key` 时还根本不知道租户是谁
 *    （租户恰恰是从这一行读出来的），所以按 key 的那条读口**不带 `company_id` 条件**，
 *    而后台的读改口一律带 —— 两套读口分开写，就是为了不让人"顺手"把它们合并成一条。
 * 2. **`app_secret` 只进不出**：DAO 层就把这一点钉住（`page`/`count` 的列清单里没有它），
 *   只有 OP-A2 新建与 OP-A4 重置那两处走 [findSecret] 单独取一次、由服务层立刻交给响应体。
 *    列表面连 DO 都不返回 secret 字段，防止某个装配函数忘了掩码就把密钥端出去。
 * 3. **`status` 是本项目补的**（源系统没有吊销手段，只能删行）：停用即刻 `E4003`，
 *    而行本身留着 —— 删行会把它名下 `webhook_delivery_log` 的归属一起抹掉
 *    （§25.3 OP-A3 不给 DELETE 是同一条理由）。
 */
class OpenapiDeveloperDO : Stampable {
    var developerId: Long = 0
    var companyId: Long = 0
    var appKey: String = ""
    var appSecret: String = ""
    var name: String? = null
    var remark: String? = null

    /** 1 启用 / 0 停用（TINYINT 用 Int 承载，与库的判据 `status = 1` 逐字对得上） */
    var status: Int = 1
    var externalBaseUri: String? = null
    var externalAppKey: String? = null
    var externalAppSecret: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/**
 * 后台列表行：与 DO 同表，但**天生没有 `app_secret` 这一列**（见上第 2 条）。
 */
class OpenapiDeveloperRow {
    var developerId: Long = 0
    var companyId: Long = 0
    var appKey: String? = null
    var name: String? = null
    var remark: String? = null
    var status: Int = 1
    var externalBaseUri: String? = null
    var externalAppKey: String? = null
    var created: Long = 0
    var updated: Long = 0
}

private const val D_PUBLIC_COLS =
    "developer_id, company_id, app_key, name, remark, status, external_base_uri, external_app_key, created, updated"

/**
 * 筛选字典（服务层归一化之后传进来）。
 *
 * `status` 走**三态**（`null` = 不过滤 / `1` / `0`），不在这里做布尔归一：
 * M1-5 的 C194 就是在同一个坑里栽的两次 —— 把"没传"压成 `false` 之后
 * `<if test="f.x == null">` 永远不成立，那道筛静默失效。
 * 而且 `count` 与 `page` 共用本字典（可见性判据只挂取数那一段会让末页成为空页）。
 */
private const val D_FILTER_SQL = """
    AND d.company_id = #{f.companyId}
    <if test="f.status != null"> AND d.status = #{f.status}</if>
    <if test="f.keyword != null"> AND (d.name LIKE CONCAT('%', #{f.keyword}, '%') OR d.app_key LIKE CONCAT('%', #{f.keyword}, '%'))</if>
"""

@Mapper
interface OpenapiDeveloperMapper {

    @Insert(
        "INSERT INTO openapi_developer(company_id, app_key, app_secret, name, remark, status, " +
            "external_base_uri, external_app_key, external_app_secret, created, updated) " +
            "VALUES(#{companyId}, #{appKey}, #{appSecret}, #{name}, #{remark}, #{status}, " +
            "#{externalBaseUri}, #{externalAppKey}, #{externalAppSecret}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "developerId", keyColumn = "developer_id")
    fun insert(d: OpenapiDeveloperDO): Int

    /**
     * 网关验签用的唯一一条"无租户条件"读口：此刻租户还未知，租户正是从这一行读出来的。
     * `status = 1` 焊在 SQL 里 —— 停用与不存在**同回 0 行**，服务层据此一律 `E4003`（§25.1 第 5 道闸门），
     * 不给"这个 key 存在但被停了"这种能用来探测密钥的回答。
     */
    @Select("SELECT developer_id, company_id, app_key, app_secret, name, remark, status, " +
        "external_base_uri, external_app_key, external_app_secret, created, updated " +
        "FROM openapi_developer WHERE app_key = #{appKey} AND status = 1")
    fun findActiveByAppKey(@Param("appKey") appKey: String): OpenapiDeveloperDO?

    /** 后台档案读（带 `company_id`：跨租户的开发者在后台面上根本不存在） */
    @Select("SELECT developer_id, company_id, app_key, app_secret, name, remark, status, " +
        "external_base_uri, external_app_key, external_app_secret, created, updated " +
        "FROM openapi_developer WHERE developer_id = #{developerId} AND company_id = #{companyId}")
    fun find(
        @Param("developerId") developerId: Long,
        @Param("companyId") companyId: Long,
    ): OpenapiDeveloperDO?

    /**
     * 密钥明文单独一条读口，只服务 OP-A2 建后的那一次响应与 OP-A4 重置（§25.3 C1）。
     * 写成独立方法而不是往 DO 上放一个字段：这样"谁在读明文"在全仓是一次方法名搜索就能穷举的。
     */
    @Select("SELECT app_secret FROM openapi_developer WHERE developer_id = #{developerId} AND company_id = #{companyId}")
    fun findSecret(
        @Param("developerId") developerId: Long,
        @Param("companyId") companyId: Long,
    ): String?

    /**
     * 出站三列的读取（订阅投递时用）：`app_key` 与 `app_secret` 是入向身份，
     * 出站一律用 `external_*`（§25.4 两套密钥不共用的那条裁定）。
     */
    @Select("SELECT external_base_uri, external_app_key, external_app_secret " +
        "FROM openapi_developer WHERE developer_id = #{developerId} AND company_id = #{companyId} AND status = 1")
    fun findOutboundConfig(
        @Param("developerId") developerId: Long,
        @Param("companyId") companyId: Long,
    ): OpenapiDeveloperDO?

    @Update(
        "<script>UPDATE openapi_developer SET " +
            "name = #{name}, remark = #{remark}, status = #{status}, " +
            "external_base_uri = #{externalBaseUri}, external_app_key = #{externalAppKey}, " +
            "external_app_secret = #{externalAppSecret}, updated = #{updated} " +
            "WHERE developer_id = #{developerId} AND company_id = #{companyId}</script>",
    )
    fun updateProfile(d: OpenapiDeveloperDO): Int

    /** 重置入向密钥（OP-A4）：新值当场生效，旧值下一个请求即 `E4004`（没有宽限期） */
    @Update("UPDATE openapi_developer SET app_secret = #{appSecret}, updated = #{updated} " +
        "WHERE developer_id = #{developerId} AND company_id = #{companyId}")
    fun resetSecret(
        @Param("developerId") developerId: Long,
        @Param("companyId") companyId: Long,
        @Param("appSecret") appSecret: String,
        @Param("updated") updated: Long,
    ): Int

    @Select("<script>SELECT COUNT(*) FROM openapi_developer d WHERE 1=1$D_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $D_PUBLIC_COLS FROM openapi_developer d WHERE 1=1$D_FILTER_SQL " +
            " ORDER BY d.developer_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<OpenapiDeveloperRow>
}

/** 网关调用量日聚合（批F §29.6，D76 收口的"账"） */
class OpenapiCallStatsDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var day: Int = 0
    var calls: Long = 0
    var success: Long = 0
    var fails: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface OpenapiCallStatsMapper {

    @Select(
        "SELECT id, company_id, day, calls, success, fails, created, updated " +
            "FROM openapi_call_stats WHERE company_id = #{companyId} AND day = #{day}",
    )
    fun find(
        @Param("companyId") companyId: Long,
        @Param("day") day: Int,
    ): OpenapiCallStatsDO?

    @Select(
        "SELECT id, company_id, day, calls, success, fails, created, updated " +
            "FROM openapi_call_stats WHERE company_id = #{companyId} ORDER BY day DESC LIMIT #{limit} OFFSET #{offset}",
    )
    fun pageByCompany(
        @Param("companyId") companyId: Long,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<OpenapiCallStatsDO>

    @Insert(
        "INSERT INTO openapi_call_stats(company_id, day, calls, success, fails, created, updated) " +
            "VALUES(#{companyId}, #{day}, #{calls}, #{success}, #{fails}, #{created}, #{updated})",
    )
    fun insert(s: OpenapiCallStatsDO): Int

    /** 累加并入（"账"的语义：只加不覆，多实例并发各并各的，同一行两次 UPDATE 都成立） */
    @Update(
        "UPDATE openapi_call_stats SET calls = calls + #{calls}, success = success + #{success}, " +
            "fails = fails + #{fails}, updated = #{updated} WHERE company_id = #{companyId} AND day = #{day}",
    )
    fun accumulate(
        @Param("companyId") companyId: Long,
        @Param("day") day: Int,
        @Param("calls") calls: Long,
        @Param("success") success: Long,
        @Param("fails") fails: Long,
        @Param("updated") updated: Long,
    ): Int
}
