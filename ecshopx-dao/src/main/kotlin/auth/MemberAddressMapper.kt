package com.ecshopx.kotlin.dao.auth

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/**
 * members_address（地址簿 CRUD 属 M0-8，这里先给下单链路需要的只读视图）。
 * 列名沿用源系统拼写 `adrdetail`，出参时再映射成 receiver_address。
 */
@Mapper
interface MembersAddressMapper {

    @Select("SELECT * FROM members_address WHERE address_id = #{addressId} AND user_id = #{userId} LIMIT 1")
    fun findById(@Param("addressId") addressId: Long, @Param("userId") userId: Long): MemberAddressDO?

    @Select("SELECT * FROM members_address WHERE company_id = #{companyId} AND user_id = #{userId} ORDER BY is_def DESC, address_id DESC")
    fun listByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): List<MemberAddressDO>
}

class MemberAddressDO {
    var addressId: Long = 0
    var userId: Long = 0
    var companyId: Long = 0
    /** 收货人 */
    var username: String? = null
    var telephone: String? = null
    var province: String? = null
    var city: String? = null
    var county: String? = null
    var adrdetail: String? = null
    var postalCode: String? = null
    var isDef: Boolean = false
    var lng: String? = null
    var lat: String? = null
    var created: Long = 0
    var updated: Long = 0
}
