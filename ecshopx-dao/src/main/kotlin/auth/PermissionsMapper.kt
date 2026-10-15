package com.ecshopx.kotlin.dao.auth

import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

interface PermissionsMapper {

    /** operator → role → resource_code 闭包（对应 PHP CheckActivedMiddleWare::checkPermission 的数据源） */
    @Select(
        "SELECT r.resource_code FROM resources r " +
            "JOIN role_rel_resources rr ON rr.resource_id = r.resource_id " +
            "JOIN operator_rel_roles orr ON orr.role_id = rr.role_id " +
            "WHERE orr.operator_id = #{operatorId} AND r.resource_type = 'actived'",
    )
    fun codesByOperator(@Param("operatorId") operatorId: Long): List<String>
}

interface OperatorLogsMapper {

    @Insert(
        "INSERT INTO operator_logs(company_id, operator_id, operator_name, route_name, method, params, ip, created) " +
            "VALUES(#{companyId}, #{operatorId}, #{operatorName}, #{routeName}, #{method}, #{params}, #{ip}, #{created})",
    )
    fun log(
        @Param("companyId") companyId: Long,
        @Param("operatorId") operatorId: Long?,
        @Param("operatorName") operatorName: String?,
        @Param("routeName") routeName: String?,
        @Param("method") method: String?,
        @Param("params") params: String?,
        @Param("ip") ip: String?,
        @Param("created") created: Long,
    ): Int

    /**
     * SUP-4 审计日志分页。筛选三选可：操作人、动作名（模糊）、时间区间。
     * `<if>` 串在同一段 WHERE，count 与 data 共用，避免两套条件漂移。
     */
    @Select(
        "<script>SELECT COUNT(*) FROM operator_logs WHERE company_id = #{companyId}" +
            "<if test='f.operatorId != null'> AND operator_id = #{f.operatorId}</if>" +
            "<if test='f.keyword != null'> AND route_name LIKE CONCAT('%', #{f.keyword}, '%')</if>" +
            "<if test='f.from != null'> AND created &gt;= #{f.from}</if>" +
            "<if test='f.to != null'> AND created &lt;= #{f.to}</if></script>",
    )
    fun count(@Param("companyId") companyId: Long, @Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT id, company_id, operator_id, operator_name, route_name, method, params, ip, created " +
            "FROM operator_logs WHERE company_id = #{companyId}" +
            "<if test='f.operatorId != null'> AND operator_id = #{f.operatorId}</if>" +
            "<if test='f.keyword != null'> AND route_name LIKE CONCAT('%', #{f.keyword}, '%')</if>" +
            "<if test='f.from != null'> AND created &gt;= #{f.from}</if>" +
            "<if test='f.to != null'> AND created &lt;= #{f.to}</if>" +
            " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(
        @Param("companyId") companyId: Long,
        @Param("f") f: Map<String, Any?>,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<OperatorLogDO>
}

/** SUP-4 出参行；params 是 @Audited 序列化的请求体原文（可能为空） */
class OperatorLogDO {
    var id: Long = 0
    var companyId: Long = 0
    var operatorId: Long? = null
    var operatorName: String? = null
    var routeName: String? = null
    var method: String? = null
    var params: String? = null
    var ip: String? = null
    var created: Long = 0
}
