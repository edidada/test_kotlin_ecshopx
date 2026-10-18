package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §18.1/§18.6 抄录的自提点与核销字段清单（M1-4 第三批：PL 族 + WO 族 + ZT-F1）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 */
object PickupContract {

    /** PL-A1/A2/A3 的自提点行：档案 + 归属/使用两列 + 拼好的整段地址（前端直接显示） */
    val PICKUP_ROW = setOf(
        "id", "company_id", "distributor_id", "shop_name", "rel_distributor_id",
        "name", "province", "city", "area", "address", "full_address",
        "lng", "lat", "contract_phone", "status", "distance", "created", "updated",
    )

    /** PL-A1 列表外层（PageResult 只有这两个键） */
    val LIST_ENVELOPE = setOf("total_count", "list")

    /** PL-F1 前台取点：外层只有 `list`，行形状同 PL-A1 但电话掩码 */
    val FRONT_LIST = setOf("list")

    /** PL-A4 停用出参 */
    val DISABLED = setOf("id", "status")

    /** PL-A5 挂店/摘店出参：回显这次绑到哪家店、是不是绑上了 */
    val BIND_RESULT = setOf("id", "rel_distributor_id", "bind")

    /** WO-A1/A2 的核销单：单头 + 取货块 + 明细（明细只给"到店点货要对得上"那几列） */
    val WRITE_OFF_ORDER = setOf(
        "order_id", "order_no", "ziti_code", "ziti_status", "ziti_status_text",
        "order_status", "pay_status", "receipt_type", "pickup_location_id",
        "distributor_id", "user_id", "receiver_name", "receiver_mobile",
        "total_fee", "pay_fee", "created", "updated", "pickup", "items",
    )

    /** WO-* 的明细行 */
    val WRITE_OFF_ITEM = setOf("item_id", "item_name", "num", "price", "total_fee")

    /** 单上取的点地址块（读点的现值，不是建单快照 —— §18.6） */
    val PICKUP_BLOCK = setOf(
        "id", "name", "province", "city", "area", "address", "full_address", "contract_phone", "status",
    )

    /** ZT-F1 会员取货码 */
    val ZITI_CODE = setOf("order_id", "ziti_code", "ziti_status", "pickup", "valid_text")
}
