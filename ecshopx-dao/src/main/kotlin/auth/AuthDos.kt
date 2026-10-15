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
