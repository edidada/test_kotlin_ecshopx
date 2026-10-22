package com.ecshopx.kotlin.dao.wx

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 微信开放平台族两表（M2-2 §27.5）。
 *
 *  - [WxAppDO]：**授权应用**（一个租户可以同时挂公众号与小程序，`uk(company_id, app_id)` 允许两种并存）。
 *    直连与第三方授权两条链都落这一张表，靠 `authorizer_refresh_token` 有无来分链——
 *    直连是租户自己抄的密钥，授权链是平台侧替它拿的票据；两条链的 access_token 来源不同（§27 盘点 6），
 *    混用会在"授权换了、消息还发得出去"这种场景才暴露。
 *  - [WxMessageLogDO]：**出站消息留痕**。mock 分支不豁免落库（§27.6 门槛⑤——
 *    "没配置"与"发失败"必须能用同一张表分辨出来，所以 `errcode` 落的是微信原话或 mock 的模拟码）。
 *
 * `app_secret` 是沾 C175 纪律的一列：口令只进不出，出参对象里不产出它（不是置 null）。
 */
class WxAppDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var appId: String = ""
    var appSecret: String? = null
    var appType: String = "wxa"
    var authorizerRefreshToken: String? = null
    var nickName: String? = null
    var verifyType: Int = 0
    var principalName: String? = null
    var status: Int = 1
    var expiresAt: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

/** 出站消息留痕行。`mock=1` 的行是 mock 分支发的，e2e 与冒烟用它区分"走了模拟"与"真出了网" */
class WxMessageLogDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var appId: String = ""
    var touser: String = ""
    var templateId: String = ""
    var page: String? = null
    var data: String? = null
    var status: String = "pending"
    var errcode: Int = 0
    var errmsg: String? = null
    var mock: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

private const val APP_COLS =
    "id, company_id, app_id, app_secret, app_type, authorizer_refresh_token, " +
        "nick_name, verify_type, principal_name, status, expires_at, created, updated"

private const val MSG_COLS =
    "id, company_id, app_id, touser, template_id, page, data, status, errcode, errmsg, mock, created, updated"

private const val MSG_LOG_FILTER_SQL = """
    AND l.company_id = #{f.companyId}
    <if test="f.appId != null"> AND l.app_id = #{f.appId}</if>
    <if test="f.touser != null"> AND l.touser = #{f.touser}</if>
    <if test="f.status != null"> AND l.status = #{f.status}</if>
    <if test="f.createdStart != null"> AND l.created &gt;= #{f.createdStart}</if>
    <if test="f.createdEnd != null"> AND l.created &lt;= #{f.createdEnd}</if>
"""

@Mapper
interface WxAppMapper {

    @Select("SELECT $APP_COLS FROM wx_app WHERE company_id = #{companyId} AND app_id = #{appId}")
    fun find(@Param("companyId") companyId: Long, @Param("appId") appId: String): WxAppDO?

    /** 最新授权的那条在前（updated 降序）：A4 "当前租户的授权信息" 取第一条 */
    @Select("SELECT $APP_COLS FROM wx_app WHERE company_id = #{companyId} ORDER BY updated DESC, id DESC")
    fun listByCompany(@Param("companyId") companyId: Long): List<WxAppDO>

    /** app_id 不带租户号（回调路由只有 appid 一段），跨租户按 app_id 全局定位 */
    @Select("SELECT $APP_COLS FROM wx_app WHERE app_id = #{appId} ORDER BY updated DESC, id DESC")
    fun listByAppId(@Param("appId") appId: String): List<WxAppDO>

    @Insert(
        "INSERT INTO wx_app(company_id, app_id, app_secret, app_type, authorizer_refresh_token, " +
            "nick_name, verify_type, principal_name, status, expires_at, created, updated) " +
            "VALUES(#{companyId}, #{appId}, #{appSecret}, #{appType}, #{authorizerRefreshToken}, " +
            "#{nickName}, #{verifyType}, #{principalName}, #{status}, #{expiresAt}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(a: WxAppDO): Int

    /**
     * 授权刷新：refresh token、到期时间与展示信息**传了才替换**（null = 不动这一列），
     * 与 M1-4 那条"读-改-只覆盖传入字段"的纪律一致——换授权码只换票据，不该把昵称洗掉。
     */
    @Update(
        "<script>UPDATE wx_app SET status = #{status}, updated = #{updated}" +
            "<if test='appSecret != null'>, app_secret = #{appSecret}</if>" +
            "<if test='appType != null'>, app_type = #{appType}</if>" +
            "<if test='authorizerRefreshToken != null'>, authorizer_refresh_token = #{authorizerRefreshToken}</if>" +
            "<if test='nickName != null'>, nick_name = #{nickName}</if>" +
            "<if test='verifyType != null'>, verify_type = #{verifyType}</if>" +
            "<if test='principalName != null'>, principal_name = #{principalName}</if>" +
            "<if test='expiresAt != null'>, expires_at = #{expiresAt}</if>" +
            " WHERE company_id = #{companyId} AND app_id = #{appId}</script>",
    )
    fun updateByCompanyAndApp(
        @Param("companyId") companyId: Long,
        @Param("appId") appId: String,
        @Param("appSecret") appSecret: String?,
        @Param("appType") appType: String?,
        @Param("authorizerRefreshToken") authorizerRefreshToken: String?,
        @Param("nickName") nickName: String?,
        @Param("verifyType") verifyType: Int?,
        @Param("principalName") principalName: String?,
        @Param("status") status: Int,
        @Param("expiresAt") expiresAt: Long?,
        @Param("updated") updated: Long,
    ): Int

    /** 第三方平台 `unauthorized` 事件按 app_id 全局翻位：一个 appid 同租户内只会有得多行中的一个 */
    @Update(
        "UPDATE wx_app SET status = 0, updated = #{updated} WHERE app_id = #{appId} AND status = 1",
    )
    fun markUnauthorized(@Param("appId") appId: String, @Param("updated") updated: Long): Int
}

@Mapper
interface WxMessageLogMapper {

    @Insert(
        "INSERT INTO wx_message_log(company_id, app_id, touser, template_id, page, data, " +
            "status, errcode, errmsg, mock, created, updated) " +
            "VALUES(#{companyId}, #{appId}, #{touser}, #{templateId}, #{page}, #{data}, " +
            "#{status}, #{errcode}, #{errmsg}, #{mock}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(l: WxMessageLogDO): Int

    @Select("SELECT COUNT(*) FROM wx_message_log l WHERE 1 = 1 $MSG_LOG_FILTER_SQL")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $MSG_COLS FROM wx_message_log l WHERE 1 = 1 $MSG_LOG_FILTER_SQL " +
            "ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<WxMessageLogDO>
}

