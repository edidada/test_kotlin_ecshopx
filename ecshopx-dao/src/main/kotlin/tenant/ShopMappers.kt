package com.ecshopx.kotlin.dao.tenant

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/**
 * 店铺档案读取（distribution_distributor）。
 * M0 只取购物车/结算分组要展示的那几列，分销业务本身（佣金、商品池）仍属 M1。
 */
class ShopProfileDO {
    var distributorId: Long = 0
    var name: String = ""
    var mobile: String? = null
    var address: String? = null
    var lat: String? = null
    var lng: String? = null
    var isZiti: Boolean = false
    var isDelivery: Boolean = false
}

@Mapper
interface DistributorShopMapper {

    @Select("<script>SELECT distributor_id, name, mobile, address, lat, lng, is_ziti, is_delivery " +
        "FROM distribution_distributor WHERE company_id = #{companyId} AND distributor_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun listByIds(@Param("companyId") companyId: Long, @Param("ids") ids: List<Long>): List<ShopProfileDO>
}
