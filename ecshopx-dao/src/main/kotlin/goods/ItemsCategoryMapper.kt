package com.ecshopx.kotlin.dao.goods

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/** 商品分类（树在 Service 层组装并 Caffeine 缓存） */
@Mapper
interface ItemsCategoryMapper {

    @Select(
        "SELECT category_id, company_id, shop_id, parent_id, category_name, category_level, path, " +
            "is_main_category, is_show_front, commission_ratio, distributor_id, sort, created, updated " +
            "FROM items_category WHERE company_id = #{companyId} ORDER BY sort, category_id",
    )
    fun listByCompany(@Param("companyId") companyId: Long): List<ItemCategoryDO>

    @Select("SELECT category_id, company_id, parent_id, category_name, category_level, path, is_main_category, sort " +
        "FROM items_category WHERE category_id = #{id}")
    fun findById(@Param("id") id: Long): ItemCategoryDO?

    @Insert(
        "INSERT INTO items_category(company_id, shop_id, parent_id, category_name, category_level, path, " +
            "is_main_category, is_show_front, commission_ratio, distributor_id, sort, created, updated) " +
            "VALUES(#{companyId}, #{shopId}, #{parentId}, #{categoryName}, #{categoryLevel}, #{path}, " +
            "#{isMainCategory}, #{isShowFront}, #{commissionRatio}, #{distributorId}, #{sort}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "categoryId", keyColumn = "category_id")
    fun insert(cat: ItemCategoryDO): Int

    @Update("UPDATE items_category SET category_name=#{categoryName}, parent_id=#{parentId}, " +
        "category_level=#{categoryLevel}, path=#{path}, is_main_category=#{isMainCategory}, " +
        "is_show_front=#{isShowFront}, commission_ratio=#{commissionRatio}, sort=#{sort}, updated=#{updated} " +
        "WHERE category_id=#{categoryId}")
    fun updateFull(cat: ItemCategoryDO): Int

    @Delete("DELETE FROM items_category WHERE category_id = #{id}")
    fun deleteById(@Param("id") id: Long): Int
}
