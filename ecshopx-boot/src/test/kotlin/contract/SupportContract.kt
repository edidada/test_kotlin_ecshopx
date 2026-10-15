package com.ecshopx.kotlin.boot.contract

/**
 * M0-8 会员域出参字段集（抄自 docs/04 §2.6 + §9 实现期补充）。
 * 判定沿用 ContractDiff：缺失即失败，多出仅回显。
 */
object MemberContract {

    /** MEM-1 我的信息 */
    val PROFILE = setOf(
        "user_id", "grade_id", "mobile", "nickname", "avatar", "sex", "birthday",
        "gradeInfo", "nextGradeInfo", "totalConsumption", "point", "deposit", "is_new",
    )

    /** gradeInfo / nextGradeInfo（next 档额外给 need_growth 差额） */
    val GRADE_INFO = setOf("grade_id", "grade_name", "level", "growth_value", "discount", "need_growth")

    /** MEM-4 地址行 */
    val ADDRESS_ROW = setOf(
        "address_id", "user_id", "company_id", "username", "telephone", "province", "city", "county",
        "adrdetail", "receiver_address", "postal_code", "lng", "lat", "is_def", "created", "updated",
    )

    /** MEM-5 收藏行：item_price 是实时价，fav_price 是收藏时快照价 */
    val FAV_ROW = setOf(
        "fav_id", "item_id", "item_name", "item_image", "item_price", "fav_price",
        "item_bn", "store", "is_onsale", "created",
    )

    /** MEM-7 资产 + 角标 */
    val STATISTICAL = setOf("point", "deposit", "orderscount")
    val ORDERSCOUNT = setOf("notpay", "pending_delivery", "pending_receive", "done", "evaluation", "cancel", "total")

    /** MEM-8 验证码回执（mock_code 仅演示通道返回） */
    val VCODE = setOf("mobile", "type", "status", "expire_seconds")

    /** 浏览足迹行 */
    val BROWSE_ROW = setOf("item_id", "created", "item")
}

/**
 * M0-8 支撑域出参字段集（docs/04 §2.7）。
 */
object SupportContract {

    /** SUP-1 配置行（company_id=0 表示全局默认，1 表示本租户覆盖） */
    val CONFIG_ROW = setOf("id", "company_id", "config_key", "config_value", "updated")

    /** SUP-2 上传结果 */
    val UPLOAD = setOf("id", "url", "path", "file_name", "file_type", "file_size")

    /** SUP-4 审计日志行 */
    val OPLOG_ROW = setOf(
        "id", "company_id", "operator_id", "operator_name", "route_name", "method", "params", "ip", "created",
    )
}
