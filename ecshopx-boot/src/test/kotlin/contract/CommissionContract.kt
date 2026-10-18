package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §18.5 抄录的佣金与提现字段清单（M1-4 第二批：DL 族与 CW 族）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object CommissionContract {

    /** DL-A1 佣金流水行（负向冲销行同构，靠 `rebate` 的符号区分） */
    val LOG_ROW = setOf(
        "id", "order_id", "order_no", "distributor_id", "item_id", "item_name", "user_id", "shop_id",
        "num", "nickname", "rebate", "total_rebate", "is_close", "plan_close_time", "created", "updated",
    )

    /** DL-A2 分销统计：六个账数 + 占用额 + 余额（余额是纯派生数，C149） */
    val STATS = setOf(
        "commission_total", "commission_open", "commission_closed",
        "withdraw_applied", "withdraw_paid", "withdraw_refused",
        "withdraw_occupying", "balance",
    )

    /** DL-A3 显式结算出参 */
    val CLOSE_RESULT = setOf("closed_count", "closed_fee", "to_time", "distributor_id")

    /** CW-A1/CW-F1/CW-F2 的提现行（同一份列，前后台共用一个装配函数） */
    val WITHDRAW_ROW = setOf(
        "id", "distributor_id", "shop_name", "user_id", "shop_id", "money",
        "status", "status_text", "remarks", "created", "updated", "paid_time",
    )

    /** CW-A3 打款信息（mock 回执，D47） */
    val PAYINFO = setOf("id", "distributor_id", "money", "status", "pay_type", "paid_time", "account")

    /** CW-A3 的账户栏 */
    val PAYINFO_ACCOUNT = setOf("name", "mobile", "bank_name", "bank_account", "tip")
}
