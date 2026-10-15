package com.ecshopx.kotlin.dao.auth

import com.ecshopx.kotlin.dao.stamp.Stampable

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * members_address（MEM-4 地址 CRUD；下单链路读 address_id 展开收件人）。
 * 列名沿用源系统拼写 `adrdetail`，出参时再映射成 receiver_address。
 */
@Mapper
interface MembersAddressMapper {

    @Select("SELECT * FROM members_address WHERE address_id = #{addressId} AND user_id = #{userId} LIMIT 1")
    fun findById(@Param("addressId") addressId: Long, @Param("userId") userId: Long): MemberAddressDO?

    @Select("SELECT * FROM members_address WHERE company_id = #{companyId} AND user_id = #{userId} ORDER BY is_def DESC, address_id DESC")
    fun listByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): List<MemberAddressDO>

    /** 默认地址互斥：先全量取消再置新默认（两条语句同事务，见 AddressService） */
    @Update("UPDATE members_address SET is_def = FALSE, updated = #{updated} WHERE user_id = #{userId} AND is_def = TRUE")
    fun clearDefault(@Param("userId") userId: Long, @Param("updated") updated: Long): Int

    @Update("UPDATE members_address SET is_def = TRUE, updated = #{updated} WHERE address_id = #{addressId} AND user_id = #{userId}")
    fun markDefault(@Param("addressId") addressId: Long, @Param("userId") userId: Long, @Param("updated") updated: Long): Int

    @Insert("INSERT INTO members_address(user_id, company_id, username, telephone, province, city, county, " +
        "adrdetail, postal_code, lng, lat, is_def, created, updated) " +
        "VALUES(#{userId}, #{companyId}, #{username}, #{telephone}, #{province}, #{city}, #{county}, " +
        "#{adrdetail}, #{postalCode}, #{lng}, #{lat}, #{isDef}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "addressId", keyColumn = "address_id")
    fun insert(a: MemberAddressDO): Int

    /** 编辑：整行覆盖（前台表单本就回传全字段），按 address_id + user_id 双条件命中，天然挡越权 */
    @Update("UPDATE members_address SET username = #{username}, telephone = #{telephone}, province = #{province}, " +
        "city = #{city}, county = #{county}, adrdetail = #{adrdetail}, postal_code = #{postalCode}, " +
        "lng = #{lng}, lat = #{lat}, updated = #{updated} " +
        "WHERE address_id = #{addressId} AND user_id = #{userId}")
    fun updateFull(a: MemberAddressDO): Int

    @Delete("DELETE FROM members_address WHERE address_id = #{addressId} AND user_id = #{userId}")
    fun delete(@Param("addressId") addressId: Long, @Param("userId") userId: Long): Int

    @Select("SELECT COUNT(*) FROM members_address WHERE company_id = #{companyId} AND user_id = #{userId}")
    fun countByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): Long
}

class MemberAddressDO : Stampable {
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
    override var created: Long = 0
    override var updated: Long = 0
}
