package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §14.1 / §14.2 / §14.6 抄录的拼团字段清单（M1-3 #57 GP-*）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object GroupsContract {

    /** GP-A2 活动行（`team_status` 是团上的状态，活动只有时间窗派生的 `date_status`） */
    val ACT_ROW = setOf(
        "groups_activity_id", "company_id", "act_name", "goods_id", "goods_name", "act_price",
        "origin_price", "person_num", "store", "limit_buy_num", "limit_time", "begin_time",
        "end_time", "robot", "instruction", "disabled", "date_status", "created", "updated",
    )

    /** GP-A3 详情追加团统计与团列表 */
    val ADMIN_DETAIL = ACT_ROW + setOf("team_total", "team_doing", "team_success", "team_fail", "teams")

    /** GP-A5 停用/强制结束出参（`failed_teams` 只在这次真砸了团时才大于 0） */
    val STATUS_RESULT = ADMIN_DETAIL + setOf("failed_teams")

    /** GP-F1 活动行：后台行的子集去掉 `disabled`，再给算价链要的活动坐标 */
    val FRONT_ROW = setOf(
        "groups_activity_id", "company_id", "act_name", "goods_id", "goods_name", "act_price",
        "origin_price", "person_num", "store", "limit_buy_num", "limit_time", "begin_time",
        "end_time", "robot", "instruction", "date_status", "created", "updated",
        "activity_type", "activity_id", "team_count",
    )

    /** GP-F2 详情 */
    val FRONT_DETAIL = FRONT_ROW - setOf("team_count") + setOf("teams")

    /** 团行（GP-A6 / GP-F2 `teams[]` 的公共部分） */
    val TEAM_ROW = setOf(
        "team_id", "act_id", "head_user_id", "team_status", "team_status_text",
        "join_person_num", "person_num", "lack_num", "robot_num", "begin_time", "end_time",
    )

    /** GP-F2 的可参加团：团行 + 团长掩码昵称 */
    val FRONT_TEAM = TEAM_ROW + setOf("head_nickname")

    /** GP-F3 团成员（昵称一律掩码，机器人 `user_id<0` 且 `is_robot`） */
    val MEMBER_ROW = setOf(
        "user_id", "nickname", "avatar", "join_time", "paid", "is_head", "is_robot", "order_id",
    )

    /** GP-F3 团详情 */
    val TEAM_DETAIL = setOf(
        "team_id", "act_id", "act_name", "goods_id", "act_price", "team_status", "team_status_text",
        "person_num", "join_person_num", "lack_num", "robot_num", "begin_time", "end_time", "members",
    )

    /** GP-F4 我的团 */
    val MY_TEAM = setOf(
        "team_id", "act_id", "team_status", "team_status_text", "join_person_num", "person_num",
        "lack_num", "end_time", "order_id", "paid", "join_time", "is_head",
    )

    /** ORDER-2 拼团出参（`team_id` 只有拼团单非空，普通单给 null 但键位必须在） */
    val ORDER_CREATE = setOf("order_id", "pay_type", "order_type", "team_id", "total_fee")
}
