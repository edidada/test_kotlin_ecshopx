package com.ecshopx.kotlin.dao.webhook

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * Webhook 出站两表（M2-1 §25.5）。
 *
 * `webhook_subscription` 是**配置**（一个开发者 × 一个 topic × 一个 url 一行，可以直接删）；
 * `webhook_delivery_log` 是**事件的存放处**（outbox）—— 它与业务变更同事务提交，
 * 投递动作在事务外。这两件事分开是本族 DAO 的全部理由：
 * 源系统只有 `systemlink_oms_queuelog` 那种"调用留痕"表，留痕表不能当事件源用
 * （写留痕的那一步在 HTTP 之后，进程先死就什么都没留下）。
 */
class WebhookSubscriptionDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var developerId: Long = 0
    var topic: String = ""
    var url: String = ""
    var status: Int = 1
    override var created: Long = 0
    override var updated: Long = 0
}

/**
 * 订阅列表行：多带开发者侧的 `app_key`/`name`（后台看"这条订阅是谁家的"靠这两列，
 * 只出 `developer_id` 等于让运营拿着一个数字没法查）。同样**不出密钥**。
 */
class WebhookSubscriptionRow {
    var id: Long = 0
    var companyId: Long = 0
    var developerId: Long = 0
    var topic: String? = null
    var url: String? = null
    var status: Int = 1
    var created: Long = 0
    var updated: Long = 0
    var appKey: String? = null
    var developerName: String? = null
    var developerStatus: Int = 1
}

/**
 * 投递目标（一个"事件 × 订阅"一行 outbox 之前先要读到它）。
 * 出站密钥三列在 SQL 里一次带下来：装配点再单独查一次开发者是 N+1，
 * 而这三列同时为空 = 这个开发者没有出站能力，服务层据此在建订阅时就 422（§25.3 WH-A2）。
 */
class WebhookTargetRow {
    var subscriptionId: Long = 0
    var companyId: Long = 0
    var developerId: Long = 0
    var topic: String? = null
    var url: String? = null
    var externalBaseUri: String? = null
    var externalAppKey: String? = null
    var externalAppSecret: String? = null
}

class WebhookDeliveryDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var developerId: Long = 0
    var subscriptionId: Long = 0
    var msgId: String = ""
    var topic: String = ""
    var url: String = ""
    var payload: String? = null

    /** pending / running / success / fail（§25.4 状态机；`fail` 是终态，只能手动重推） */
    var status: String = "pending"
    var attempts: Int = 0
    var nextTryAt: Long = 0
    var lastHttpStatus: Int = 0
    var lastError: String? = null
    var responseBody: String? = null

    /** 当次实际发出的 `X-Ecx-Signature` 与 `X-Ecx-Timestamp`（WH-A6 出这两位，理由见 [WebhookAttempt]） */
    var lastSignature: String? = null
    var lastTs: Long = 0
    var runtime: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

/** 投递记录列表行（与 DO 同表同列，独立一个类是为了让"列表出参"和"投递内部态"能各自演进） */
class WebhookDeliveryRow {
    var id: Long = 0
    var companyId: Long = 0
    var developerId: Long = 0
    var subscriptionId: Long = 0
    var msgId: String? = null
    var topic: String? = null
    var url: String? = null
    var status: String? = null
    var attempts: Int = 0
    var nextTryAt: Long = 0
    var lastHttpStatus: Int = 0
    var lastError: String? = null
    var runtime: Int = 0
    var created: Long = 0
    var updated: Long = 0
}

/** 概况聚合（OP-A6）。返回 DO 而不是 Map：H2 `DATABASE_TO_LOWER` 会把结果集标签折成小写（本仓惯例） */
class WebhookStatusCountDO {
    var status: String? = null
    var cnt: Long = 0
}

/** 积压分桶行（WH-B0 批G）：`bucket` 是服务侧写的固定四档名，`cnt` 是桶内行数 */
class WebhookBucketRow {
    var bucket: String? = null
    var cnt: Long = 0
}

/**
 * 一次投递尝试的结果 —— `markSuccess` / `markRetry` / `markFail` 三条状态迁移共用的入参形状。
 *
 * 做成一个对象而不是给三条方法各加两个位置参数：这三条写法的列清单**必须逐字相同**
 * （哪一次尝试漏写了 `last_signature`，那一行的"当次签名"就停留在上一轮的旧值上，
 * 而 WH-A6 出的是这个值 —— 静默错位比报错难查得多）。共用一个载体，漂移就只能发生在 SQL 文本里。
 *
 * `signature` 与 `ts` 是**当次实际发出的那两个头值**，不是事后重算值：
 * HMAC 覆盖 `ts + "\n" + body`，时间戳一动、密钥一换，重算值就对不上对端收到的那个，
 * 只剩"留下发出去的这一份"能复现现场（§25.3 WH-A6）。
 */
class WebhookAttempt {
    var httpStatus: Int = 0
    var error: String? = null
    var responseBody: String? = null
    var signature: String? = null
    var ts: Long = 0
    var runtime: Int = 0
    /** 仅 `markRetry` 用：下一次的时刻（指数退避在服务层算，SQL 只管写） */
    var nextTryAt: Long = 0
}

private const val S_COLS =
    "id, company_id, developer_id, topic, url, status, created, updated"

private const val S_FILTER_SQL = """
    AND s.company_id = #{f.companyId}
    <if test="f.developerId != null"> AND s.developer_id = #{f.developerId}</if>
    <if test="f.topic != null"> AND s.topic = #{f.topic}</if>
    <if test="f.status != null"> AND s.status = #{f.status}</if>
"""

private const val L_COLS =
    "id, company_id, developer_id, subscription_id, msg_id, topic, url, status, attempts, next_try_at, " +
        "last_http_status, last_error, runtime, created, updated"

/**
 * 两个 CLOB 加两位签名头值。**只进 `find`（WH-A5 的详情），不进 `page`**：
 * 列表页没人逐行读正文，而 `payload` 是 CLOB —— 把它放进列表列清单，
 * `WH-A5` 翻一页就等于把几十条事件正文全量搬进内存（源系统 `queuelog` 的列表就是这么拖死的）。
 * [WebhookDeliveryMapper.listDue] 是例外中的例外：它只要 `payload`（发不出去就没有正文可留），
 * 不要 `response_body` —— 一轮 20 条、每条两个 CLOB，作业自己会变成慢查询。
 */
private const val L_HEAVY_COLS =
    "payload, response_body, last_signature, last_ts"

/**
 * 投递记录的筛选字典。`status` 这里是**字符串三态**（不传 = 全出），
 * 与上面开发者那张的三态同一条纪律：`count` 与 `page` 必须共用（C194）。
 */
private const val L_FILTER_SQL = """
    AND l.company_id = #{f.companyId}
    <if test="f.developerId != null"> AND l.developer_id = #{f.developerId}</if>
    <if test="f.subscriptionId != null"> AND l.subscription_id = #{f.subscriptionId}</if>
    <if test="f.topic != null"> AND l.topic = #{f.topic}</if>
    <if test="f.status != null"> AND l.status = #{f.status}</if>
    <if test="f.msgId != null"> AND l.msg_id = #{f.msgId}</if>
    <if test="f.url != null"> AND l.url LIKE CONCAT('%', #{f.url}, '%')</if>
    <if test="f.createdStart != null"> AND l.created &gt;= #{f.createdStart}</if>
    <if test="f.createdEnd != null"> AND l.created &lt;= #{f.createdEnd}</if>
"""

@Mapper
interface WebhookSubscriptionMapper {

    @Insert(
        "INSERT INTO webhook_subscription(company_id, developer_id, topic, url, status, created, updated) " +
            "VALUES(#{companyId}, #{developerId}, #{topic}, #{url}, #{status}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(s: WebhookSubscriptionDO): Int

    @Select("SELECT $S_COLS FROM webhook_subscription WHERE id = #{id} AND company_id = #{companyId}")
    fun find(@Param("id") id: Long, @Param("companyId") companyId: Long): WebhookSubscriptionDO?

    /** 重复订阅判据（WH-A2 的 422）：同开发者同 topic 同 url 才算重复，换 url 是合法的第二家接收端 */
    @Select("SELECT COUNT(*) FROM webhook_subscription WHERE developer_id = #{developerId} AND topic = #{topic} AND url = #{url}")
    fun countSame(
        @Param("developerId") developerId: Long,
        @Param("topic") topic: String,
        @Param("url") url: String,
    ): Int

    @Update("UPDATE webhook_subscription SET topic = #{topic}, url = #{url}, status = #{status}, updated = #{updated} " +
        "WHERE id = #{id} AND company_id = #{companyId}")
    fun update(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("topic") topic: String,
        @Param("url") url: String,
        @Param("status") status: Int,
        @Param("updated") updated: Long,
    ): Int

    /** 订阅是配置、可以直接删；`webhook_delivery_log` 的行留着（它们是对接方"我收到了"的证据） */
    @Delete("DELETE FROM webhook_subscription WHERE id = #{id} AND company_id = #{companyId}")
    fun delete(@Param("id") id: Long, @Param("companyId") companyId: Long): Int

    @Select("<script>SELECT COUNT(*) FROM webhook_subscription s WHERE 1=1$S_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT s.id, s.company_id, s.developer_id, s.topic, s.url, s.status, s.created, s.updated, " +
            "d.app_key AS app_key, d.name AS developer_name, d.status AS developer_status " +
            "FROM webhook_subscription s LEFT JOIN openapi_developer d ON d.developer_id = s.developer_id " +
            "WHERE 1=1$S_FILTER_SQL ORDER BY s.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<WebhookSubscriptionRow>

    /**
     * 事件发布时的取目标：只给"订阅在用 **且** 开发者也在用"的行（C178 那条纪律在钱面之外的同款——
     * 停用即刻断推送，不需要等在途的那条跑完）。
     * 出站密钥三列一次带下来，避免装配点 N+1。
     */
    @Select(
        "SELECT s.id AS subscription_id, s.company_id, s.developer_id, s.topic, s.url, " +
            "d.external_base_uri, d.external_app_key, d.external_app_secret " +
            "FROM webhook_subscription s JOIN openapi_developer d ON d.developer_id = s.developer_id " +
            "WHERE s.company_id = #{companyId} AND s.topic = #{topic} AND s.status = 1 AND d.status = 1",
    )
    fun listTargetsByTopic(
        @Param("companyId") companyId: Long,
        @Param("topic") topic: String,
    ): List<WebhookTargetRow>
}

@Mapper
interface WebhookDeliveryMapper {

    /**
     * outbox 落行：`payload` 是**待发信封的 JSON 串**（含 `msg_id`/`topic`/`data`，不含 `sign`）——
     * 发送时按订阅密钥现算 `sign` 与 HMAC 头，所以留痕里的 payload 与对端收到的 body 只差那个签名字段。
     * 每次重推都复用同一行（`msg_id` 不变，§25.4），不插新行。
     */
    @Insert(
        "INSERT INTO webhook_delivery_log(company_id, developer_id, subscription_id, msg_id, topic, url, " +
            "payload, status, attempts, next_try_at, last_http_status, last_error, response_body, runtime, created, updated) " +
            "VALUES(#{companyId}, #{developerId}, #{subscriptionId}, #{msgId}, #{topic}, #{url}, " +
            "#{payload}, #{status}, #{attempts}, #{nextTryAt}, #{lastHttpStatus}, #{lastError}, #{responseBody}, " +
            "#{runtime}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(d: WebhookDeliveryDO): Int

    /**
     * 单行读（投递与详情都用它）。
     * 列清单比列表多 `payload`/`response_body` 与两位签名头值：`WebhookDeliveryDO` 本来就有这些字段，
     * 不查它们会让"读出来直接发"这条路拿到一个 `payload = null` 的行，
     * 症状是"手动重推一律失败成正文不合法"—— 比编译错误更难查。
     */
    @Select("SELECT $L_COLS, $L_HEAVY_COLS FROM webhook_delivery_log WHERE id = #{id} AND company_id = #{companyId}")
    fun find(@Param("id") id: Long, @Param("companyId") companyId: Long): WebhookDeliveryDO?

    @Select("SELECT payload FROM webhook_delivery_log WHERE id = #{id} AND company_id = #{companyId}")
    fun findPayload(@Param("id") id: Long, @Param("companyId") companyId: Long): String?

    /**
     * 到期待投的行。`status = 'pending'` 是硬条件：`running` 的那条正有人在投，
     * 手动重推走 [resetForRetry] 把它放回 `pending` 之后才重新进这一批。
     *
     * 返回**整行**而不是 id：投递要的就是 `payload`/`url`/`attempts` 这几列，
     * 只回 id 会逼调用方对每一行再查一次（一批 20 条就是 20 次往返），
     * 而"取待投"与"读正文"分两步做，还会让中途被改成 `fail` 的行照投不误。
     */
    @Select("SELECT $L_COLS, payload FROM webhook_delivery_log " +
        "WHERE status = 'pending' AND next_try_at <= #{now} ORDER BY next_try_at LIMIT #{limit}")
    fun listDue(@Param("now") now: Long, @Param("limit") limit: Int): List<WebhookDeliveryDO>

    /**
     * 抢占（CAS）：`WHERE status = 'pending'` 命中 0 行 = 已被别的线程/别的实例拿走，本轮跳过。
     * 多实例部署时这一条就是"同一事件不被投两次"的唯一防线（M2-4 复核时不必再回来补）。
     */
    @Update("UPDATE webhook_delivery_log SET status = 'running', attempts = attempts + 1, updated = #{now} " +
        "WHERE id = #{id} AND status = 'pending'")
    fun claim(@Param("id") id: Long, @Param("now") now: Long): Int

    @Update("UPDATE webhook_delivery_log SET status = 'success', last_http_status = #{a.httpStatus}, " +
        "response_body = #{a.responseBody}, last_error = NULL, last_signature = #{a.signature}, last_ts = #{a.ts}, " +
        "runtime = #{a.runtime}, updated = #{now} WHERE id = #{id}")
    fun markSuccess(@Param("id") id: Long, @Param("a") a: WebhookAttempt, @Param("now") now: Long): Int

    /** 可重试失败：回到 `pending` 并排下一次时刻（指数退避在服务层算，SQL 只管写） */
    @Update("UPDATE webhook_delivery_log SET status = 'pending', next_try_at = #{a.nextTryAt}, " +
        "last_http_status = #{a.httpStatus}, last_error = #{a.error}, last_signature = #{a.signature}, last_ts = #{a.ts}, " +
        "runtime = #{a.runtime}, updated = #{now} WHERE id = #{id}")
    fun markRetry(@Param("id") id: Long, @Param("a") a: WebhookAttempt, @Param("now") now: Long): Int

    /** 终态 `fail`：不再自动排（`next_try_at` 写 0，扫描条件自然永不命中），只能 WH-A7 手动重推 */
    @Update("UPDATE webhook_delivery_log SET status = 'fail', next_try_at = 0, last_http_status = #{a.httpStatus}, " +
        "last_error = #{a.error}, response_body = #{a.responseBody}, last_signature = #{a.signature}, last_ts = #{a.ts}, " +
        "runtime = #{a.runtime}, updated = #{now} WHERE id = #{id}")
    fun markFail(@Param("id") id: Long, @Param("a") a: WebhookAttempt, @Param("now") now: Long): Int

    /**
     * 手动重推（WH-A7）：**`attempts` 不清零**（清了就没有人知道这一条到底试过几次），
     * `msg_id` 也不换（换号会让对端把同一件事收到两次且无从判重，§25.4）。
     * 条件带 `status IN ('fail','running')`：正在投的那条不给重复点，`success` 的更不需要重推。
     */
    @Update("UPDATE webhook_delivery_log SET status = 'pending', next_try_at = #{now}, updated = #{now} " +
        "WHERE id = #{id} AND company_id = #{companyId} AND status IN ('fail', 'running')")
    fun resetForRetry(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("now") now: Long,
    ): Int

    @Select("<script>SELECT COUNT(*) FROM webhook_delivery_log l WHERE 1=1$L_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $L_COLS FROM webhook_delivery_log l WHERE 1=1$L_FILTER_SQL " +
            "ORDER BY l.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<WebhookDeliveryRow>

    /** 待投数（`pending` + `running` 两态合一给运营看"还有多少在路上"） */
    @Select("SELECT COUNT(*) FROM webhook_delivery_log WHERE company_id = #{companyId} AND status IN ('pending', 'running')")
    fun countInFlight(@Param("companyId") companyId: Long): Long

    @Select("SELECT status, COUNT(*) AS cnt FROM webhook_delivery_log " +
        "WHERE company_id = #{companyId} AND developer_id = #{developerId} GROUP BY status")
    fun statsByDeveloper(
        @Param("companyId") companyId: Long,
        @Param("developerId") developerId: Long,
    ): List<WebhookStatusCountDO>

    /**
     * 待投行按**出生时刻**分桶（WH-B0 批G，告警读面的账）。
     * 按龄不看 `next_try_at`：那一位在退避里推来推去（成功重试也会推远），
     * "卡了多久"只能看事件出生多久了 `created`；`pending` 与 `running` 算同一拨"在路上"。
     * 分桶界从服务层传值，让"算多久"与"查多久"是同一份常量。
     */
    @Select(
        "SELECT CASE WHEN created >= #{h6} THEN '0_6h' WHEN created >= #{h24} THEN '6_24h' " +
            "WHEN created >= #{h72} THEN '24_72h' ELSE 'over_72h' END AS bucket, COUNT(*) AS cnt " +
            "FROM webhook_delivery_log " +
            "WHERE company_id = #{companyId} AND status IN ('pending', 'running') " +
            "GROUP BY CASE WHEN created >= #{h6} THEN '0_6h' WHEN created >= #{h24} THEN '6_24h' " +
            "WHEN created >= #{h72} THEN '24_72h' ELSE 'over_72h' END",
    )
    fun pendingByAge(
        @Param("companyId") companyId: Long,
        @Param("h6") h6: Long,
        @Param("h24") h24: Long,
        @Param("h72") h72: Long,
    ): List<WebhookBucketRow>

    /** 待投行里最老的一条出生时刻（null = 没有积压） */
    @Select(
        "SELECT MIN(created) FROM webhook_delivery_log " +
            "WHERE company_id = #{companyId} AND status IN ('pending', 'running')",
    )
    fun pendingOldest(@Param("companyId") companyId: Long): Long?

    /** 终态 fail 数（自动重试已经救不回来、等人工 WH-A7 的一拨） */
    @Select("SELECT COUNT(*) FROM webhook_delivery_log WHERE company_id = #{companyId} AND status = 'fail'")
    fun countFail(@Param("companyId") companyId: Long): Long

    /**
     * WH-B1 批量重推（批H）：目标行 id 清单——**只扫终态 `fail`**，最老最先，
     * `limit` 闸住单次体量（`running` 正在投不动、`pending` 本来就在路上，都不进来）。
     */
    @Select(
        "<script>SELECT l.id FROM webhook_delivery_log l WHERE 1=1$L_FILTER_SQL AND l.status = 'fail' " +
            "ORDER BY l.created, l.id LIMIT #{limit}</script>",
    )
    fun replayTargets(@Param("f") f: Map<String, Any?>, @Param("limit") limit: Int): List<Long>

    /** 目标行置回 pending（id 清单由服务层先查后传；MySQL 拒 UPDATE 同表子查询 [1093]，两步走两方言同语义） */
    @Update(
        "<script>UPDATE webhook_delivery_log SET status = 'pending', next_try_at = #{now}, updated = #{now} " +
            "WHERE id IN <foreach collection='ids' item='v' open='(' separator=',' close=')'>#{v}</foreach></script>",
    )
    fun replayMarkPending(@Param("ids") ids: List<Long>, @Param("now") now: Long): Int
}
