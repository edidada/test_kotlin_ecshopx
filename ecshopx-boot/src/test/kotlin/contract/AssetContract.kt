package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §23.1/§23.2 抄录的积分与储值字段清单（M1-5 PT 族 + CZ 族）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 *
 * 三处形状值得单看，都是实现期定下的、契约测试要钉住的地方：
 *  - 会员面的流水行**没有** `nickname/mobile` 两个键（不是"给了空值"，是整键不出，见 [POINT_LOG_ROW]）；
 *  - 后台行固定多一列 `mobile_masked`，与按 datapass 决定的 `mobile` 并存，
 *    前端因此能预判"这一列要不要去申请全值权限"（MASK-R1）；
 *  - PT-A1 的响应不是标准分页信封：`total_count/list` 之外还挂着四个合计数，
 *    且合计数与列表共用同一份筛选字典（[POINT_MEMBERS]）。
 */
object AssetContract {

    /** 分页信封（PageResult 只有这两键） */
    val LIST_ENVELOPE = setOf("total_count", "list")

    /** PT-F1 我的积分总览 */
    val POINT_OVERVIEW = setOf(
        "point", "total_point", "user_point", "ratio", "max_deduct_fee", "enable",
    )

    /** PT-F3 积分规则展示（库里不存文案，`text` 按当前配置拼） */
    val POINT_RULE = setOf("enable", "deduct_max_ratio", "point_per_fen", "get_by_item", "text")

    /** PT-F2 会员面积分流水行 */
    val POINT_LOG_ROW = setOf(
        "id", "user_id", "order_id", "change_point", "before_point", "after_point", "remark", "created",
    )

    /** PT-A3 后台面积分流水行 = 会员行 + 身份三列 */
    val POINT_LOG_ADMIN_ROW = POINT_LOG_ROW + setOf("nickname", "mobile", "mobile_masked")

    /** PT-A1 会员积分行 */
    val POINT_MEMBER_ROW = setOf(
        "user_id", "nickname", "mobile", "mobile_masked", "point", "total_point", "updated",
    )

    /** PT-A1 整页：分页信封 + 卡头四个数 */
    val POINT_MEMBERS = LIST_ENVELOPE + setOf("total_point_sum", "total_granted", "total_used", "member_count")

    /** PT-A2 调账出参（`change_point` 回的是钳位后真正落账的数，不是入参那个） */
    val POINT_ADJUST = setOf("user_id", "before_point", "after_point", "change_point")

    /** PT-A4 规则读写：当前值 / 默认值 / 生效值三张对照 */
    val POINT_CONFIG = setOf("config", "defaults", "effective")

    /** CZ-F1 我的余额（`balance` 是本金 + 赠金） */
    val DEPOSIT_OVERVIEW = setOf("deposit_amount", "gift_amount", "balance", "total_recharge", "enable")

    /** CZ-F2 会员面储值动账行（`amount` 是总动账、`gift_amount` 是其中赠金部分，CZ-R1） */
    val DEPOSIT_TRADE_ROW = setOf(
        "trade_id", "user_id", "trade_type", "rel_id", "amount", "gift_amount",
        "before_balance", "after_balance", "status", "remark", "created",
    )

    /** CZ-A1 后台面动账行 = 会员行 + 身份三列 */
    val DEPOSIT_TRADE_ADMIN_ROW = DEPOSIT_TRADE_ROW + setOf("nickname", "mobile", "mobile_masked")

    /** CZ-F3/A2 档位行 */
    val RECHARGE_RULE_ROW = setOf("id", "rule_name", "recharge_amount", "gift_amount", "status")

    /** CZ-F3 前台档位页：列表 + 充值送积分倍数 */
    val DEPOSIT_RULES = setOf("list", "recharge_point_rate")

    /** CZ-A2 后台档位列表 */
    val DEPOSIT_RULE_ADMIN_LIST = LIST_ENVELOPE

    /** CZ-A4 储值总览（现值与历史各说一件事） */
    val DEPOSIT_COUNT = setOf(
        "principal_sum", "gift_sum", "notpay_count", "recharge_sum_7d",
        "recharge_days", "member_count", "balance_sum",
    )

    /** CZ-A3 代客充值出参（入账后的三个数摊平在这一层，不再嵌一块） */
    val OPERATOR_RECHARGE = setOf(
        "trade_id", "user_id", "amount", "gift_amount", "remark", "trade_state",
        "deposit_amount", "gift_balance", "balance",
    )
}
