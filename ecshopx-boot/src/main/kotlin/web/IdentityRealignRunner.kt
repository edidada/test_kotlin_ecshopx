package com.ecshopx.kotlin.boot.web

import java.sql.Connection
import java.sql.Statement
import javax.sql.DataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 自增序列启动校正件（M2-4 批G，修 M2-1 踩坑⑤的"RESTART WITH 100 回拨"）。
 *
 * 踩坑⑤的现场：seed 给一批演示行显式主键（如 `items_barcode` 的演示行）后用
 * `ALTER TABLE ... RESTART WITH 100` 把自增序列抬到 100，不然后续默认主键的新增会撞演示行。
 * H2 官方语法 `RESTART [ WITH long ]` **只收字面量**——"按 MAX(id)+1 复位"写不进 SQL
 * （`RESTART WITH (SELECT MAX(id)+1 ...)` 不是合法语法）；而 sql.init `mode=always` 的幂等纪律
 * 又决定了这段不能只在冷启动跑。于是每一次重启都把序列拨回 100：
 * 常驻演示库里历史运行已经写过 id ≥ 100 的行（冒烟/e2e 新增的行），下一次新增就撞主键。
 *
 * MySQL 侧反而安全：InnoDB 对 `AUTO_INCREMENT = n` 的低值重置**自动忽略**（计数器取
 * max(当前值, max(id)+1)），所以 MySQL 上这句无害；坑只在 H2 上成立。
 *
 * 修法落在这里：启动脚本跑完之后，用 JDBC 把每张配置表的序列重新校正——
 * 能算数的只有应用层，SQL 里算不了。MySQL 走 `ALTER TABLE t AUTO_INCREMENT = n`，
 * H2 走 `ALTER TABLE t ALTER COLUMN c RESTART WITH n`（同一句在另一方言听不懂，故按方言分支）。
 * e2e 可直接调 [realign] 模拟"下一次重启"（run 由框架在启动时调一次，测试里自己再调）。
 *
 * seed 里的 `RESTART WITH 100` 保留：它是**冷启动下限**（空表/首装时保证默认主键越过演示行），
 * 本件在它之上做校正——所以校正值取 **max(MAX(列)+1, 下限)** 两个不变量同时成立：
 * **下一发默认主键大于表内最大 id**（本件）**且不小于 seed 的 RESTART 常量**（e2e 判据依赖，
 * 演示行占 1..4、GOODS 面断"新建的 itemId ≥ 100"，拨到 5 会破判据）。缺省下限 100
 * （seed 现行 RESTART 常量全族同值）；条目可写第三段显式指定：`表:列:下限`。
 *
 * 每张表独立 try、失败只 warn 不阻断启动（序列没校正
 * 的代价是"下一次新增可能撞号"，不是启动不了）。
 *
 * 配置：`ecx.schema.identity-realign-tables`，格式 `表:列[:下限]` 逗号分隔；表清单与 seed 里
 * RESTART 那一批一一对应，**注意**：seed 加新演示行/新表时这张清单要跟着加（改 seed
 * 的人看不到 boot 包，application.yml 里的注释就是留给他的路标）。
 */
@Component
class IdentityRealignRunner(
    private val dataSource: DataSource,
    @Value("\${ecx.schema.identity-realign-tables:items:item_id,items_attributes:attribute_id,items_attribute_values:attribute_value_id,items_rel_attributes:id,items_barcode:id,items_tags:tag_id,items_rel_tags:id,servicelabels:label_id,items_rel_type:id,deposit_recharge_rule:id,members_items_fav:fav_id,members_browse_history:history_id}")
    private val spec: String,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        realign()
    }

    /** 按配置清单把每张表的自增序列校正到 max(MAX(列)+1, 下限)；空表只有下限兜住 */
    fun realign() {
        val entries = spec.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { entry ->
                val parts = entry.split(':')
                require(parts.size in 2..3 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                    "identity-realign-tables 条目应为 `表:列[:下限]`：$entry"
                }
                val floor = if (parts.size == 3) parts[2].toLongOrNull() else DEFAULT_FLOOR
                require(floor != null && floor > 0) { "identity-realign-tables 下限应为正数：$entry" }
                Triple(parts[0], parts[1], floor)
            }
        if (entries.isEmpty()) return
        dataSource.connection.use { conn ->
            val isH2 = conn.metaData.databaseProductName.contains("h2", ignoreCase = true)
            for ((table, column, floor) in entries) {
                try {
                    realignOne(conn, table, column, floor, isH2)
                } catch (e: Exception) {
                    log.warn("自增序列校正失败（不阻断启动，下一次新增可能撞号）：{} {}", table, e.message)
                }
            }
        }
    }

    private fun realignOne(conn: Connection, table: String, column: String, floor: Long, isH2: Boolean) {
        val next = conn.createStatement().use { st ->
            st.executeQuery("SELECT MAX($column) FROM $table").use { rs ->
                if (!rs.next() || rs.getObject(1) == null) floor else maxOf(rs.getLong(1) + 1, floor)
            }
        }
        val sql = if (isH2) {
            "ALTER TABLE $table ALTER COLUMN $column RESTART WITH $next"
        } else {
            "ALTER TABLE $table AUTO_INCREMENT = $next"
        }
        conn.createStatement().use { st: Statement -> st.execute(sql) }
        log.info("自增序列校正：{} -> 下一值 {}", table, next)
    }

    private companion object {
        /** seed 现行 RESTART 常量全族同值 100（e2e 判据"新建 id ≥100"依赖它）；条目第三段可覆写 */
        const val DEFAULT_FLOOR = 100L
    }
}
