package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §25.3 / §25.4 抄录的开放能力字段清单（M2-1 OP-A1..A6 网关后台面）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 *
 * 这一族的契约测试比别的域多一件事：**它要钉的是"哪些键不许出现"**。
 * 商品行少一个字段是显示问题，开发者列表多一个 `app_secret` 是密钥泄露 ——
 * 后者用"缺失即失败"永远测不出来，所以这里另给一张 [FORBIDDEN] 表，
 * 由 e2e 逐行断"这些键一个都不许有"（§25.3 的三条判据：入向密钥只进不出、出站密钥永不出、
 * 改档案不回显密钥）。
 */
object OpenapiContract {

    /** 网关应答信封（§25.1 末段）：只有这四键，HTTP 恒 200、错误语义全在 `code` 位 */
    val GATEWAY_ENVELOPE = setOf("status", "code", "message", "data")

    /** OP-A1 开发者列表行（`app_secret` 连 DAO 的列清单都没有，见 `OpenapiMappers`） */
    val DEVELOPER_ROW = setOf(
        "developer_id", "company_id", "app_key", "name", "remark", "status",
        "external_base_uri", "external_app_key", "created", "updated",
    )

    /** OP-A2 新建响应：**唯一一次**带出 `app_secret`；`created/updated` 这一份没有（行还没经过一次读口） */
    val DEVELOPER_CREATE = setOf(
        "developer_id", "company_id", "app_key", "app_secret", "name", "remark", "status",
        "external_base_uri", "external_app_key",
    )

    /** OP-A3 改档案：与列表行同形（出站密钥不回显，改了也不给看） */
    val DEVELOPER_UPDATE = DEVELOPER_ROW

    /** OP-A4 重置入向密钥：新值当场出一次，就这两个键 */
    val RESET_SECRET = setOf("developer_id", "app_secret")

    /** OP-A5 method 目录：来自注册表不来自库 */
    val METHODS = setOf("versions", "list")
    val METHOD_ROW = setOf("id", "version", "method", "verb", "summary", "required", "required_any")

    /** OP-A6 概况：网关三位（进程内）与出站四位（表里）不同源，因此分成两个键组 */
    val STATS = setOf("developer_id", "app_key", "gateway", "delivery", "note")
    val STATS_GATEWAY = setOf("total", "success", "fail")
    val STATS_DELIVERY = setOf("pending", "running", "success", "fail")

    /** 任何对外读面都不许出现的密钥列（§25.3 判据 1/2 的机械化断言） */
    val FORBIDDEN = setOf("app_secret", "external_app_secret", "secret")
}

/**
 * docs/04 §25.3 WH-A1..A9 与 §25.4 的订阅/投递字段清单（M2-1）。
 *
 * 两处形状值得单看：
 *  - **列表行没有正文**：`payload` 是 CLOB，只在详情出（`L_HEAVY_COLS` 那条纪律），
 *    所以 `DELIVERY_ROW` 与 `DELIVERY_DETAIL` 的差集就是"重字段"那四位；
 *  - `signature` / `signature_ts` 出的是**当次实际发出**的头值，不是拿当前密钥重算的值 ——
 *    对端说"验不过"时，只有这一份能和它的日志逐字对上（§25.3 WH-A6）。
 */
object WebhookContract {

    /** WH-A1 订阅行 = 订阅表列 + 开发者侧三列（运营看"这条是谁家的"靠它们） */
    val SUB_ROW = setOf(
        "id", "company_id", "developer_id", "topic", "url", "status", "created", "updated",
        "app_key", "developer_name", "developer_status",
    )

    /** WH-A2 新建响应（刚插入的那一行，不含 join 出来的开发者列） */
    val SUB_CREATE = setOf("id", "company_id", "developer_id", "topic", "url", "status")

    /** WH-A5 投递记录行（列表：不含正文） */
    val DELIVERY_ROW = setOf(
        "id", "company_id", "developer_id", "subscription_id", "msg_id", "topic", "url",
        "status", "attempts", "next_try_at", "last_http_status", "last_error", "runtime", "created", "updated",
    )

    /** WH-A6 详情 = 列表行 + 正文 + 最后响应 + 当次实发的两位签名头值 */
    val DELIVERY_DETAIL = DELIVERY_ROW + setOf("payload", "response_body", "signature", "signature_ts")

    /** WH-A7 手动重推的返回（`dispatched` 表示这次有没有真的发出去，不是"提交成功了"） */
    val RETRY = setOf("id", "msg_id", "dispatched", "status", "attempts", "last_http_status", "last_error")

    /** WH-A9 测试事件的返回 */
    val TEST_PUSH = setOf("id", "msg_id", "topic", "url", "dispatched", "status", "last_http_status", "last_error")

    /** WH-A8 事件目录行 */
    val TOPIC_ROW = setOf("topic", "summary", "hook", "data_fields")

    /** 出站信封（§25.4）：线格式上多一个 `sign`，库里那份不含它 */
    val ENVELOPE = setOf("msg_id", "topic", "company_id", "developer_id", "occurred_at", "data")
    val ENVELOPE_ON_WIRE = ENVELOPE + setOf("sign")

    /** 分页信封（`PageResult` 只有这两键，与其余各域同一形状） */
    val LIST_ENVELOPE = setOf("total_count", "list")
}
