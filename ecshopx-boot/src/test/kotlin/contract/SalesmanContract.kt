package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §18.1/§18.2/§18.3 抄录的导购与员工端字段清单（M1-4 第三批后半 3B：SM 族 + ST/SO 族）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object SalesmanContract {

    /** SM-A1/A2/A3/A4 的导购行：在册行 + 两个 JOIN 名 + 两个现算统计数（§18.2） */
    val SALESMAN_ROW = setOf(
        "salesman_id", "company_id", "distributor_id", "shop_id", "user_id",
        "salesman_name", "mobile", "role_id", "role_name", "shop_name",
        "is_valid", "avatar", "order_count", "commission_fee", "created", "updated",
    )

    /** SM-A5 启停出参（只有这两栏：启停不是改档案，回显"置成了什么"就够） */
    val STATUS = setOf("salesman_id", "is_valid")

    /** SM-A6 岗位行 */
    val ROLE_ROW = setOf(
        "role_id", "company_id", "distributor_id", "role_name", "remark",
        "status", "status_text", "scope_text", "created", "updated",
    )

    /** SM-A6 删除出参 */
    val ROLE_DELETED = setOf("role_id", "deleted")

    /** SM-F1 扫码进店的导购卡（匿名面：不含手机号明文，§18.1） */
    val FRONT_SALESMAN = setOf(
        "salesman_id", "salesman_name", "avatar", "distributor_id", "shop_name",
        "qrcode_text", "is_ziti", "role_name",
    )

    /** SM-F2 绑定出参：会员 + 岗 + 绑到哪家店 + 绑的时刻（D62：只留当前归属） */
    val BIND = setOf("user_id", "salesman_id", "salesman_name", "distributor_id", "bind_time")

    /** ST-E1 员工登录（令牌 + 会话身份，前端据此决定进哪个工作台） */
    val STAFF_LOGIN = setOf(
        "token", "salesman_id", "salesman_name", "distributor_id", "shop_id", "role_id", "role_name",
    )

    /** ST-E2 当前员工信息（自己的手机号不掩码） */
    val STAFF_INFO = setOf(
        "salesman_id", "salesman_name", "mobile", "avatar", "user_id",
        "distributor_id", "shop_name", "shop_mobile", "role_id", "role_name", "is_valid",
    )

    /** ST-E3 本店简易档 */
    val STAFF_SHOP = setOf(
        "distributor_id", "name", "mobile", "address", "logo", "is_ziti", "is_delivery", "hour",
    )

    /** ST-E4 我的统计：三个单数走订单表，五个账数走流水与提现表（C176） */
    val STAFF_COUNT = setOf(
        "order_count", "today_count", "pending_count",
        "commission_total", "commission_open", "commission_closed",
        "withdraw_occupying", "balance",
    )

    /** SO-E3 详情比 ORDER-5 多的那一个块（他开的单要能指认"是谁开的"） */
    val STAFF_SALESMAN_BLOCK = setOf("salesman_id", "salesman_name", "mobile")

    /** 列表外层（PageResult 只有这两个键） */
    val LIST_ENVELOPE = setOf("total_count", "list")
}
