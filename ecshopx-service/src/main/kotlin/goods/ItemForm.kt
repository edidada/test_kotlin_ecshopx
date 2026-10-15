package com.ecshopx.kotlin.service.goods

import com.ecshopx.kotlin.dao.goods.ItemDO
import com.ecshopx.kotlin.dao.goods.ItemRelTypeDO
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * GOODS-3/4 入参模型（docs/04 §2.2）。
 * 仅用 @JsonNaming 局部转 snake_case，不动全局 Jackson 配置（避免影响既有 M0-3 出参契约）。
 * 可空字段语义：create 必填项由 ItemService.validate 校验；update 传 null 表示"不改该字段"。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ItemForm(
    val itemName: String? = null,
    val itemBn: String? = null,
    val brief: String? = null,
    val price: Long? = null,
    val costPrice: Long? = null,
    val marketPrice: Long? = null,
    val store: Long? = null,
    val warningStore: Long? = null,
    val itemUnit: String? = null,
    val itemType: String? = null,
    val itemCategory: String? = null,
    val consumeType: String? = null,
    val nospec: Boolean? = null,
    val approveStatus: String? = null,
    val auditStatus: String? = null,
    val templatesId: Long? = null,
    val distributorId: Long? = null,
    val supplierId: Long? = null,
    val itemSource: String? = null,
    val pics: List<String>? = null,
    val videos: List<String>? = null,
    val intro: String? = null,
    val weight: Double? = null,
    val volume: Double? = null,
    val brandId: Long? = null,
    val type: Int? = null,
    val taxRate: Int? = null,
    val sort: Int? = null,
    val isPoint: Boolean? = null,
    val point: Long? = null,
    val rebate: Long? = null,
    val isGift: Boolean? = null,
    val isMedicine: Int? = null,
    val isPrescription: Int? = null,
    val category: List<Long>? = null,
    val barcodes: List<String>? = null,
    val tagIds: List<Long>? = null,
    val attrs: List<AttrForm>? = null,
    val typeLabels: List<LabelForm>? = null,
    val specItems: List<SpecForm>? = null,
) {

    /** null → 默认值落库（items 列全 NOT NULL，除 tax_rate/weight/volume/brief 等） */
    fun toNew(companyId: Long, isDefault: Boolean, defaultItemId: Long, goodsId: Long, nospec: Boolean): ItemDO {
        val d = ItemDO()
        d.companyId = companyId
        d.isDefault = isDefault
        d.defaultItemId = defaultItemId
        d.goodsId = goodsId
        d.itemName = itemName ?: ""
        d.itemType = itemType ?: "normal"
        d.itemSource = itemSource ?: "mall"
        d.approveStatus = approveStatus ?: "instock"
        d.auditStatus = auditStatus ?: "submitting"
        d.nospec = this.nospec ?: nospec
        applyTo(d)
        return d
    }

    /** 读-改-写：只覆盖请求里出现的字段（null 保持 DB 现值） */
    fun applyTo(target: ItemDO) {
        itemName?.let { target.itemName = it }
        itemBn?.let { target.itemBn = it }
        brief?.let { target.brief = it }
        price?.let { target.price = it }
        costPrice?.let { target.costPrice = it }
        marketPrice?.let { target.marketPrice = it }
        store?.let { target.store = it }
        warningStore?.let { target.warningStore = it }
        itemUnit?.let { target.itemUnit = it }
        itemType?.let { target.itemType = it }
        itemCategory?.let { target.itemCategory = it }
        consumeType?.let { target.consumeType = it }
        nospec?.let { target.nospec = it }
        approveStatus?.let { target.approveStatus = it }
        auditStatus?.let { target.auditStatus = it }
        templatesId?.let { target.templatesId = it }
        distributorId?.let { target.distributorId = it }
        supplierId?.let { target.supplierId = it }
        itemSource?.let { target.itemSource = it }
        pics?.let { target.pics = if (it.isEmpty()) null else writeJson(it) }
        videos?.let { target.videos = if (it.isEmpty()) null else writeJson(it) }
        intro?.let { target.intro = it }
        weight?.let { target.weight = it }
        volume?.let { target.volume = it }
        brandId?.let { target.brandId = it }
        type?.let { target.type = it }
        taxRate?.let { target.taxRate = it }
        sort?.let { target.sort = it }
        isPoint?.let { target.isPoint = it }
        point?.let { target.point = it }
        rebate?.let { target.rebate = it }
        isGift?.let { target.isGift = it }
        isMedicine?.let { target.isMedicine = it }
        isPrescription?.let { target.isPrescription = it }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    data class AttrForm(
        val attributeId: Long? = null,
        val attributeType: String? = null,
        val attributeValueId: Long? = null,
        val customAttributeValue: String? = null,
    )

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    data class LabelForm(
        val labelId: Long? = null,
        val labelName: String? = null,
        val labelPrice: Long? = null,
        val numType: String? = null,
        val num: Long? = null,
        val isNotLimitNum: Int? = null,
        val limitTime: Long? = null,
    ) {
        fun toRel(itemId: Long): ItemRelTypeDO = ItemRelTypeDO().apply {
            this.itemId = itemId
            labelId = this@LabelForm.labelId ?: 0L
            labelName = this@LabelForm.labelName
            labelPrice = this@LabelForm.labelPrice ?: 0L
            numType = this@LabelForm.numType
            num = this@LabelForm.num ?: 0L
            isNotLimitNum = this@LabelForm.isNotLimitNum ?: 1
            limitTime = this@LabelForm.limitTime ?: 0L
        }
    }

    /** 多规格子行：item_id 缺省=新增，给出=更新（GOODS-4） */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
    data class SpecForm(
        val itemId: Long? = null,
        val itemName: String? = null,
        val itemBn: String? = null,
        val price: Long? = null,
        val store: Long? = null,
        val marketPrice: Long? = null,
        val costPrice: Long? = null,
        val pics: List<String>? = null,
        val barcode: String? = null,
        val itemSpec: List<AttrForm>? = null,
    ) {
        fun toNewChild(parent: ItemDO): ItemDO = ItemForm(
            itemName = itemName, itemBn = itemBn, price = price, store = store,
            marketPrice = marketPrice, costPrice = costPrice, pics = pics,
            itemType = parent.itemType,
        ).toNew(
            companyId = parent.companyId, isDefault = false,
            defaultItemId = parent.itemId, goodsId = parent.goodsId,
            nospec = false,     // 子行本身就是规格行的组成部分
        ).also { if (it.itemName.isEmpty()) it.itemName = parent.itemName }

        fun applyToChild(child: ItemDO) = asForm().applyTo(child)

        /** 子行字段是主表单字段的子集，转成 ItemForm 复用读-改-写逻辑 */
        fun asForm(): ItemForm = ItemForm(
            itemName = itemName, itemBn = itemBn, price = price, store = store,
            marketPrice = marketPrice, costPrice = costPrice, pics = pics,
        )
    }

    companion object {
        /** DB 里 pics/videos 存 JSON 数组字符串（与 PHP json_encode 一致） */
        fun writeJson(list: List<String>): String =
            list.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
    }
}
