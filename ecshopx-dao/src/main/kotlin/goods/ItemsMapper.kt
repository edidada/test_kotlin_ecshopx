package com.ecshopx.kotlin.dao.goods

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 商品主表 Mapper。动态筛选与 PHP ItemsLists::getItemLists 对齐（docs/04 §GOODS-1），
 * 条件全部经服务端 ItemQuery→Map 归一化后传入（元/分换算、category 展开在 Service 完成）。
 */
@Mapper
interface ItemsMapper {

    companion object {
        const val COLS =
            "item_id, company_id, goods_id, default_item_id, is_default, item_type, item_category, consume_type, " +
            "item_name, item_bn, brief, price, cost_price, market_price, store, warning_store, sales, item_unit, " +
            "pics, videos, intro, nospec, approve_status, audit_status, distributor_id, supplier_id, item_source, " +
            "templates_id, is_point, point, rebate, is_gift, is_medicine, is_prescription, type, tax_rate, weight, " +
            "volume, brand_id, sort, created, updated"

        /** GOODS-1 全量可选筛选（f.* 缺省即不启用） */
        const val WHERE = """
            WHERE company_id = #{f.companyId}
            <if test="f.excludeServices != null">AND item_type != 'services'</if>
            <if test="f.defaultOnly != null">AND is_default = #{f.defaultOnly}</if>
            <if test="f.itemName != null">AND item_name LIKE CONCAT('%', #{f.itemName}, '%')</if>
            <if test="f.keywords != null">AND (item_name LIKE CONCAT('%', #{f.keywords}, '%') OR item_bn LIKE CONCAT('%', #{f.keywords}, '%'))</if>
            <if test="f.itemBn != null">AND item_bn LIKE CONCAT('%', #{f.itemBn}, '%')</if>
            <if test="f.barcode != null">AND item_id IN (SELECT item_id FROM items_barcode WHERE barcode = #{f.barcode})</if>
            <if test="f.itemId != null">AND item_id = #{f.itemId}</if>
            <if test="f.goodsId != null">AND goods_id = #{f.goodsId}</if>
            <if test="f.categoryIds != null">AND item_id IN (SELECT item_id FROM goods_items_rel_cats WHERE category_id IN <foreach item="c" collection="f.categoryIds" open="(" separator="," close=")">#{c}</foreach>)</if>
            <if test="f.approveStatus != null">AND approve_status = #{f.approveStatus}</if>
            <if test="f.auditStatus != null">AND audit_status = #{f.auditStatus}</if>
            <if test="f.distributorIds != null">AND distributor_id IN <foreach item="d" collection="f.distributorIds" open="(" separator="," close=")">#{d}</foreach></if>
            <if test="f.supplierId != null">AND supplier_id = #{f.supplierId}</if>
            <if test="f.itemHolder == 'platform'">AND distributor_id = 0 AND supplier_id = 0</if>
            <if test="f.itemHolder == 'distributor'">AND distributor_id &gt; 0</if>
            <if test="f.itemHolder == 'supplier'">AND supplier_id &gt; 0</if>
            <if test="f.itemSource != null">AND item_source = #{f.itemSource}</if>
            <if test="f.type != null">AND type = #{f.type}</if>
            <if test="f.itemType != null">AND item_type = #{f.itemType}</if>
            <if test="f.nospec != null">AND nospec = #{f.nospec}</if>
            <if test="f.isGift != null">AND is_gift = #{f.isGift}</if>
            <if test="f.isWarning == true">AND store &lt;= warning_store</if>
            <if test="f.isWarning == false">AND store &gt; warning_store</if>
            <if test="f.brandId != null">AND brand_id = #{f.brandId}</if>
            <if test="f.tagId != null">AND item_id IN (SELECT item_id FROM items_rel_tags WHERE tag_id = #{f.tagId})</if>
            <if test="f.rebateZero != null">AND rebate = 0</if>
            <if test="f.rebatePos != null">AND rebate &gt; 0</if>
            <if test="f.templatesId != null">AND templates_id = #{f.templatesId}</if>
            <if test="f.specialType == 'drug'">AND is_medicine = 1</if>
            <if test="f.specialType == 'normal'">AND is_medicine = 0</if>
            <if test="f.createdStart != null">AND created &gt;= #{f.createdStart}</if>
            <if test="f.createdEnd != null">AND created &lt;= #{f.createdEnd}</if>
            <if test="f.priceGt != null">AND price &gt;= #{f.priceGt}</if>
            <if test="f.priceLt != null">AND price &lt;= #{f.priceLt}</if>
            <if test="f.storeGt != null">AND store &gt;= #{f.storeGt}</if>
            <if test="f.storeLt != null">AND store &lt;= #{f.storeLt}</if>
            <if test="f.itemIds != null">AND item_id IN <foreach item="i" collection="f.itemIds" open="(" separator="," close=")">#{i}</foreach></if>
        """
    }

    @Select("<script>SELECT " + COLS + " FROM items " + WHERE + " ORDER BY item_id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    fun listItems(
        @Param("f") f: Map<String, Any?>,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<ItemDO>

    @Select("<script>SELECT COUNT(*) FROM items " + WHERE + "</script>")
    fun countItems(@Param("f") f: Map<String, Any?>): Long

    @Select("SELECT $COLS FROM items WHERE item_id = #{id}")
    fun findById(@Param("id") id: Long): ItemDO?

    /** 前台详情/编辑用：仅上架商品 */
    @Select("SELECT $COLS FROM items WHERE item_id = #{id} AND approve_status = 'onsale'")
    fun findOnsaleById(@Param("id") id: Long): ItemDO?

    @Select("<script>SELECT " + COLS + " FROM items WHERE is_default = FALSE AND default_item_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach> ORDER BY item_id</script>")
    fun listChildren(@Param("ids") ids: List<Long>): List<ItemDO>

    @Insert(
        "INSERT INTO items(company_id, goods_id, default_item_id, is_default, item_type, item_category, consume_type, " +
            "item_name, item_bn, brief, price, cost_price, market_price, store, warning_store, sales, item_unit, " +
            "pics, videos, intro, nospec, approve_status, audit_status, distributor_id, supplier_id, item_source, " +
            "templates_id, is_point, point, rebate, is_gift, is_medicine, is_prescription, type, tax_rate, weight, " +
            "volume, brand_id, sort, created, updated) " +
            "VALUES(#{companyId}, #{goodsId}, #{defaultItemId}, #{isDefault}, #{itemType}, #{itemCategory}, #{consumeType}, " +
            "#{itemName}, #{itemBn}, #{brief}, #{price}, #{costPrice}, #{marketPrice}, #{store}, #{warningStore}, #{sales}, #{itemUnit}, " +
            "#{pics}, #{videos}, #{intro}, #{nospec}, #{approveStatus}, #{auditStatus}, #{distributorId}, #{supplierId}, #{itemSource}, " +
            "#{templatesId}, #{isPoint}, #{point}, #{rebate}, #{isGift}, #{isMedicine}, #{isPrescription}, #{type}, #{taxRate}, #{weight}, " +
            "#{volume}, #{brandId}, #{sort}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "itemId", keyColumn = "item_id")
    fun insert(item: ItemDO): Int

    /** 读-改-写整行更新（可编辑列全量）；created/updated 由 StampInterceptor 刷 */
    @Update(
        "UPDATE items SET item_type=#{itemType}, item_category=#{itemCategory}, consume_type=#{consumeType}, " +
            "item_name=#{itemName}, item_bn=#{itemBn}, brief=#{brief}, price=#{price}, cost_price=#{costPrice}, " +
            "market_price=#{marketPrice}, store=#{store}, warning_store=#{warningStore}, sales=#{sales}, " +
            "item_unit=#{itemUnit}, pics=#{pics}, videos=#{videos}, intro=#{intro}, nospec=#{nospec}, " +
            "approve_status=#{approveStatus}, audit_status=#{auditStatus}, distributor_id=#{distributorId}, " +
            "supplier_id=#{supplierId}, item_source=#{itemSource}, templates_id=#{templatesId}, is_point=#{isPoint}, " +
            "point=#{point}, rebate=#{rebate}, is_gift=#{isGift}, is_medicine=#{isMedicine}, " +
            "is_prescription=#{isPrescription}, type=#{type}, tax_rate=#{taxRate}, weight=#{weight}, volume=#{volume}, " +
            "brand_id=#{brandId}, sort=#{sort}, updated=#{updated} WHERE item_id=#{itemId}",
    )
    fun updateFull(item: ItemDO): Int

    /** 主行自指：goods_id=default_item_id=item_id（自增 id 只有 INSERT 后才可知，故单独一条 UPDATE） */
    @Update("UPDATE items SET goods_id = #{goodsId}, default_item_id = #{defaultItemId} WHERE item_id = #{itemId}")
    fun stampIdentity(@Param("itemId") itemId: Long, @Param("goodsId") goodsId: Long, @Param("defaultItemId") defaultItemId: Long): Int

    /** GOODS-5 逻辑下架（对齐 PHP 删除=下架语义）；updated 由调用方取 TimeSec.now()，与 StampInterceptor 同源 */
    @Update("UPDATE items SET approve_status = 'offline_sale', updated = #{updated} WHERE item_id = #{id}")
    fun softDelete(@Param("id") id: Long, @Param("updated") updated: Long): Int
}
