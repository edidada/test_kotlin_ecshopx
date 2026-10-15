package com.ecshopx.kotlin.dao.tenant

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/** 租户主体（shop_id=0 的平台自营店名即取此处，M1 分销店铺另有 distribution_distributor） */
class CompanyDO {
    var companyId: Long = 0
    var companyName: String = ""
    var status: String = "normal"
}

@Mapper
interface CompanyMapper {

    @Select("SELECT company_id, company_name, status FROM company WHERE company_id = #{companyId}")
    fun findById(@Param("companyId") companyId: Long): CompanyDO?
}
