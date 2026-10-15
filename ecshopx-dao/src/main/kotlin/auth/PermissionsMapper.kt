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
}
