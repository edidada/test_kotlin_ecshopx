package com.ecshopx.kotlin.dao.auth

/** MyBatis 自动映射用可变 POJO（data class 无默认构造不参与 automapping） */
class OperatorDO {
    var operatorId: Long = 0
    var companyId: Long = 0
    var username: String = ""
    var password: String = ""
    var mobile: String? = null
    var operatorType: String = "staff"
    var distributorIds: String? = null
    var disabled: Boolean = false
    var lastLoginTime: Long? = null
    var created: Long = 0
    var updated: Long = 0
}

class MemberDO {
    var userId: Long = 0
    var companyId: Long = 0
    var gradeId: Long = 1
    var mobile: String? = null
    var password: String? = null
    var disabled: Boolean = false
    var inviterId: Long = 0
    var sourceFrom: String? = null
    var created: Long = 0
    var updated: Long = 0
}

class AssocDO {
    var id: Long = 0
    var userId: Long = 0
    var companyId: Long = 0
    var openId: String? = null
    var unionid: String? = null
    var userType: String = "wechat"
}

/**
 * members_grade：M0-5 算价只用 discount（百分比，100=无折扣）；
 * 成长值/等级升降属 M0-8，此处不建写路径。
 */
class MemberGradeDO {
    var gradeId: Long = 0
    var companyId: Long = 0
    var gradeName: String = ""
    var level: Int = 1
    var discount: Int = 100
}
