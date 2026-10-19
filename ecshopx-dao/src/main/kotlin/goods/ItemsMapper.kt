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

    /**
     * OA-M8 `ecx.item.entity.get` 用：按 `item_bn` **精确**取 SPU（M2-1 §25.2）。
     *
     * 现成的那一条是 `f.itemBn` 的 `LIKE '%…%'`（后台搜索框语义），
     * 拿它当"按编号取一个"会让对接方查一个拿到十个、取到哪个看数据库心情。
     * `LIMIT 1` 是兜底：`item_bn` 在本仓不是唯一索引，真出现同编号多行时
     * 取 `item_id` 最小的那条（最早建的那件），比抛异常更贴近对接方的预期。
     */
    @Select("<script>SELECT $COLS FROM items WHERE company_id = #{companyId} AND item_bn = #{itemBn} " +
        "ORDER BY item_id LIMIT 1</script>")
    fun findByBn(@Param("companyId") companyId: Long, @Param("itemBn") itemBn: String): ItemDO?

    @Select("<script>SELECT " + COLS + " FROM items WHERE is_default = FALSE AND default_item_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach> ORDER BY item_id</script>")
    fun listChildren(@Param("ids") ids: List<Long>): List<ItemDO>

    /** 订单快照/明细回查用：按 id 批量取，不判上下架（历史单要能读到已下架商品） */
    @Select("<script>SELECT " + COLS + " FROM items WHERE item_id IN " +
        "<foreach item=\"i\" collection=\"ids\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>")
    fun listByIds(@Param("ids") ids: List<Long>): List<ItemDO>

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

    /**
     * 下单扣库存（CAS，docs/05 M0-6 门槛「并发 50 单同库存不超卖」的唯一防线）：
     * `store >= num` 作为更新条件写进 WHERE，返回 0 即库存不足或并发抢占，Service 不做"读—判—写"。
     * 只碰 store/sales 两列，绝不走 updateFull，避免与改价并发互相覆盖。
     */
    @Update("UPDATE items SET store = store - #{num}, sales = sales + #{num}, updated = #{updated} " +
        "WHERE item_id = #{itemId} AND store >= #{num}")
    fun deductStock(@Param("itemId") itemId: Long, @Param("num") num: Long, @Param("updated") updated: Long): Int

    /**
     * 取消/超时关单回补库存：sales 回退同样用 `sales >= num` 兜底，防止重复回补把销量打成负数。
     * store 上限不设（商品可能被改高库存），因此重复调用有超补风险——调用方必须先做订单状态 CAS。
     */
    @Update("UPDATE items SET store = store + #{num}, sales = CASE WHEN sales >= #{num} THEN sales - #{num} ELSE 0 END, " +
        "updated = #{updated} WHERE item_id = #{itemId}")
    fun restoreStock(@Param("itemId") itemId: Long, @Param("num") num: Long, @Param("updated") updated: Long): Int
}
