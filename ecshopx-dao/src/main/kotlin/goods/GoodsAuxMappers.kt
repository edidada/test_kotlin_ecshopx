package com.ecshopx.kotlin.dao.goods

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/** 商品 ↔ 分类 */
@Mapper
interface GoodsRelCatsMapper {

    @Select("SELECT id, item_id, category_id, company_id, created, updated FROM goods_items_rel_cats WHERE item_id = #{itemId}")
    fun listByItem(@Param("itemId") itemId: Long): List<GoodsRelCatDO>

    @Select("<script>SELECT id, item_id, category_id, company_id, created, updated FROM goods_items_rel_cats WHERE item_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun listByItems(@Param("ids") ids: List<Long>): List<GoodsRelCatDO>

    @Delete("DELETE FROM goods_items_rel_cats WHERE item_id = #{itemId}")
    fun deleteByItem(@Param("itemId") itemId: Long): Int

    @Insert("INSERT INTO goods_items_rel_cats(item_id, category_id, company_id, created, updated) " +
        "VALUES(#{itemId}, #{categoryId}, #{companyId}, #{created}, #{updated})")
    fun insert(rel: GoodsRelCatDO): Int
}

/** 属性定义/取值（spec/brand/item_param/unit…） */
@Mapper
interface AttributesMapper {

    @Select("SELECT attribute_id, company_id, shop_id, attribute_type, attribute_name, attribute_show, distributor_id, sort, created, updated " +
        "FROM items_attributes WHERE company_id = #{companyId} ORDER BY attribute_type, sort, attribute_id")
    fun listByCompany(@Param("companyId") companyId: Long): List<AttributeDO>

    @Insert("INSERT INTO items_attributes(company_id, shop_id, attribute_type, attribute_name, attribute_show, distributor_id, sort, created, updated) " +
        "VALUES(#{companyId}, #{shopId}, #{attributeType}, #{attributeName}, #{attributeShow}, #{distributorId}, #{sort}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "attributeId", keyColumn = "attribute_id")
    fun insert(attr: AttributeDO): Int

    @Select("SELECT attribute_value_id, attribute_id, company_id, shop_id, attribute_value, sort " +
        "FROM items_attribute_values WHERE attribute_id = #{attributeId} ORDER BY sort, attribute_value_id")
    fun valuesByAttribute(@Param("attributeId") attributeId: Long): List<AttributeValueDO>

    @Insert("INSERT INTO items_attribute_values(attribute_id, company_id, shop_id, attribute_value, sort) " +
        "VALUES(#{attributeId}, #{companyId}, #{shopId}, #{attributeValue}, #{sort})")
    @Options(useGeneratedKeys = true, keyProperty = "attributeValueId", keyColumn = "attribute_value_id")
    fun insertValue(v: AttributeValueDO): Int
}

/** 商品 ↔ 属性（规格/参数载体） */
@Mapper
interface RelAttributesMapper {

    @Select("<script>SELECT ra.item_id, ra.attribute_id, ra.attribute_type, ra.attribute_value_id, " +
        "ra.custom_attribute_value, a.attribute_name, v.attribute_value " +
        "FROM items_rel_attributes ra " +
        "LEFT JOIN items_attributes a ON a.attribute_id = ra.attribute_id " +
        "LEFT JOIN items_attribute_values v ON v.attribute_value_id = ra.attribute_value_id " +
        "WHERE ra.item_id IN <foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach> " +
        "ORDER BY ra.id</script>")
    fun listByItems(@Param("ids") ids: List<Long>): List<ItemAttrRelView>

    @Delete("DELETE FROM items_rel_attributes WHERE item_id = #{itemId}")
    fun deleteByItem(@Param("itemId") itemId: Long): Int

    @Insert("INSERT INTO items_rel_attributes(item_id, attribute_id, attribute_type, attribute_value_id, custom_attribute_value, company_id) " +
        "VALUES(#{itemId}, #{attributeId}, #{attributeType}, #{attributeValueId}, #{customAttributeValue}, #{companyId})")
    fun insert(rel: ItemRelAttrDO): Int
}

/** 条码 */
@Mapper
interface BarcodeMapper {

    @Select("<script>SELECT id, item_id, barcode, default_item_id, company_id FROM items_barcode WHERE item_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun listByItems(@Param("ids") ids: List<Long>): List<ItemBarcodeDO>

    @Delete("DELETE FROM items_barcode WHERE item_id = #{itemId}")
    fun deleteByItem(@Param("itemId") itemId: Long): Int

    @Insert("INSERT INTO items_barcode(item_id, barcode, default_item_id, company_id) " +
        "VALUES(#{itemId}, #{barcode}, #{defaultItemId}, #{companyId})")
    fun insert(b: ItemBarcodeDO): Int
}

/** 标签 + 商品↔标签 */
@Mapper
interface TagsMapper {

    @Select("SELECT tag_id, company_id, tag_name, tag_color, font_color, distributor_id, front_show, created FROM items_tags " +
        "WHERE company_id = #{companyId} ORDER BY tag_id")
    fun listByCompany(@Param("companyId") companyId: Long): List<ItemTagDO>

    @Insert("INSERT INTO items_tags(company_id, tag_name, tag_color, font_color, distributor_id, front_show, created) " +
        "VALUES(#{companyId}, #{tagName}, #{tagColor}, #{fontColor}, #{distributorId}, #{frontShow}, #{created})")
    @Options(useGeneratedKeys = true, keyProperty = "tagId", keyColumn = "tag_id")
    fun insert(tag: ItemTagDO): Int

    @Delete("DELETE FROM items_tags WHERE tag_id = #{id}")
    fun deleteById(@Param("id") id: Long): Int

    @Select("<script>SELECT id, tag_id, item_id, company_id FROM items_rel_tags WHERE item_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun relByItems(@Param("ids") ids: List<Long>): List<ItemRelTagDO>

    @Delete("DELETE FROM items_rel_tags WHERE item_id = #{itemId}")
    fun deleteRelByItem(@Param("itemId") itemId: Long): Int

    @Insert("INSERT INTO items_rel_tags(tag_id, item_id, company_id) VALUES(#{tagId}, #{itemId}, #{companyId})")
    fun insertRel(rel: ItemRelTagDO): Int
}

/** 服务标签（type_labels 来源） */
@Mapper
interface RelTypeMapper {

    @Select("<script>SELECT id, item_id, label_id, label_name, label_price, num_type, num, is_not_limit_num, limit_time " +
        "FROM items_rel_type WHERE item_id IN <foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun listByItems(@Param("ids") ids: List<Long>): List<ItemRelTypeDO>

    @Delete("DELETE FROM items_rel_type WHERE item_id = #{itemId}")
    fun deleteByItem(@Param("itemId") itemId: Long): Int

    @Insert("INSERT INTO items_rel_type(item_id, label_id, label_name, label_price, num_type, num, is_not_limit_num, limit_time) " +
        "VALUES(#{itemId}, #{labelId}, #{labelName}, #{labelPrice}, #{numType}, #{num}, #{isNotLimitNum}, #{limitTime})")
    fun insert(rel: ItemRelTypeDO): Int

    @Select("SELECT label_id, company_id, label_name, label_price, service_type, created, updated FROM servicelabels " +
        "WHERE company_id = #{companyId} ORDER BY label_id")
    fun labelsByCompany(@Param("companyId") companyId: Long): List<ServiceLabelDO>
}

/** 运费模板 */
@Mapper
interface ShippingTemplatesMapper {

    @Select("SELECT template_id, company_id, name, is_free, valuation, status, minprice, fee_conf, distributor_id, supplier_id, created, updated " +
        "FROM shipping_templates WHERE company_id = #{companyId} AND status = TRUE ORDER BY template_id")
    fun listByCompany(@Param("companyId") companyId: Long): List<ShippingTemplateDO>

    @Insert("INSERT INTO shipping_templates(company_id, name, is_free, valuation, status, fee_conf, distributor_id, supplier_id, created, updated) " +
        "VALUES(#{companyId}, #{name}, #{isFree}, #{valuation}, #{status}, #{feeConf}, #{distributorId}, #{supplierId}, #{created}, #{updated})")
    @Options(useGeneratedKeys = true, keyProperty = "templateId", keyColumn = "template_id")
    fun insert(t: ShippingTemplateDO): Int

    @Update("UPDATE shipping_templates SET name=#{name}, is_free=#{isFree}, valuation=#{valuation}, status=#{status}, " +
        "fee_conf=#{feeConf}, updated=#{updated} WHERE template_id=#{templateId}")
    fun updateFull(t: ShippingTemplateDO): Int
}

/** 全局/租户配置（warning_store 阈值等） */
@Mapper
interface SysConfigMapper {

    @Select("SELECT config_value FROM sys_config WHERE company_id = #{companyId} AND config_key = #{key}")
    fun getValue(@Param("companyId") companyId: Long, @Param("key") key: String): String?
}
