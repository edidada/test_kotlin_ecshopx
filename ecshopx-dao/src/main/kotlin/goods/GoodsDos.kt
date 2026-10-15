package com.ecshopx.kotlin.dao.goods

import com.ecshopx.kotlin.dao.stamp.Stampable

/** items 行（SPU 主行 is_default=true；多规格 SKU 子行 is_default=false, default_item_id=主行）。列名见 sql/schema.sql §3 */
class ItemDO : Stampable {
    var itemId: Long = 0
    var companyId: Long = 0
    var goodsId: Long = 0
    var defaultItemId: Long = 0
    var isDefault: Boolean = true
    var itemType: String = "normal"
    var itemCategory: String? = null
    var consumeType: String? = null
    var itemName: String = ""
    var itemBn: String? = null
    var brief: String? = null
    var price: Long = 0
    var costPrice: Long = 0
    var marketPrice: Long = 0
    var store: Long = 0
    var warningStore: Long = 0
    var sales: Long = 0
    var itemUnit: String? = null
    var pics: String? = null          // JSON 数组字符串
    var videos: String? = null
    var intro: String? = null
    var nospec: Boolean = true
    var approveStatus: String = "instock"
    var auditStatus: String = "submitting"
    var distributorId: Long = 0
    var supplierId: Long = 0
    var itemSource: String = "mall"
    var templatesId: Long = 0
    // Kotlin 会给 isPoint 生成 isPoint()/setPoint(Boolean)，与下面 point: Long 的
    // getPoint()/setPoint(Long) 在 JavaBeans 层面同名，MyBatis Reflector 直接报
    // "ambiguous type for property 'point'"。显式改 JVM 访问器名，属性名仍是 isPoint。
    @get:JvmName("getIsPoint") @set:JvmName("setIsPoint")
    var isPoint: Boolean = false
    var point: Long = 0
    var rebate: Long = 0
    var isGift: Boolean = false
    var isMedicine: Int = 0
    var isPrescription: Int = 0
    var type: Int = 0
    var taxRate: Int? = null
    var weight: Double? = null
    var volume: Double? = null
    var brandId: Long = 0
    var sort: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

class ItemCategoryDO : Stampable {
    var categoryId: Long = 0
    var companyId: Long = 0
    var shopId: Long = 0
    var parentId: Long = 0
    var categoryName: String = ""
    var categoryLevel: Int = 1
    var path: String? = null
    var isMainCategory: Boolean = false
    var isShowFront: Int = 1
    var commissionRatio: Int = 0
    var distributorId: Long = 0
    var sort: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

class GoodsRelCatDO : Stampable {
    var id: Long = 0
    var itemId: Long = 0
    var categoryId: Long = 0
    var companyId: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

class AttributeDO : Stampable {
    var attributeId: Long = 0
    var companyId: Long = 0
    var shopId: Long = 0
    var attributeType: String = ""
    var attributeName: String = ""
    var attributeShow: String? = "select"
    var distributorId: Long = 0
    var sort: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

class AttributeValueDO {
    var attributeValueId: Long = 0
    var attributeId: Long = 0
    var companyId: Long = 0
    var shopId: Long = 0
    var attributeValue: String? = null
    var sort: Int = 0
}

/** items_rel_attributes ⋈ items_attribute_values 的读视图 */
class ItemAttrRelView {
    var itemId: Long = 0
    var attributeId: Long = 0
    var attributeType: String? = null
    var attributeValueId: Long? = null
    var customAttributeValue: String? = null
    var attributeName: String? = null
    var attributeValue: String? = null
}

class ItemRelAttrDO {
    var id: Long = 0
    var itemId: Long = 0
    var attributeId: Long = 0
    var attributeType: String? = null
    var attributeValueId: Long? = null
    var customAttributeValue: String? = null
    var companyId: Long = 0
}

class ItemBarcodeDO {
    var id: Long = 0
    var itemId: Long = 0
    var barcode: String = ""
    var defaultItemId: Long = 0
    var companyId: Long = 0
}

/** items_tags 表只有 created 列（见 schema.sql），故不参与 Stampable 自动打点 */
class ItemTagDO {
    var tagId: Long = 0
    var companyId: Long = 0
    var tagName: String = ""
    var tagColor: String? = null
    var fontColor: String? = null
    var distributorId: Long = 0
    var frontShow: Int = 0
    var created: Long = 0
}

class ItemRelTagDO {
    var id: Long = 0
    var tagId: Long = 0
    var itemId: Long = 0
    var companyId: Long = 0
}

/** items_rel_type 无时间戳列（见 schema.sql） */
class ItemRelTypeDO {
    var id: Long = 0
    var itemId: Long = 0
    var labelId: Long = 0
    var labelName: String? = null
    var labelPrice: Long = 0
    var numType: String? = null
    var num: Long = 0
    var isNotLimitNum: Int = 1
    var limitTime: Long = 0
}

class ServiceLabelDO : Stampable {
    var labelId: Long = 0
    var companyId: Long = 0
    var labelName: String = ""
    var labelPrice: Long = 0
    var serviceType: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

class ShippingTemplateDO : Stampable {
    var templateId: Long = 0
    var companyId: Long = 0
    var name: String = ""
    var isFree: String = "n"
    var valuation: String = "1"
    var status: Boolean = true
    var feeConf: String? = null
    var distributorId: Long = 0
    var supplierId: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}
