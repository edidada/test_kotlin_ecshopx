package com.ecshopx.kotlin.dao.distribution

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 结算域 M0 建表（§9）+ M2-4 批F 起服务（StatementService §29.6）。
 *
 * 事实源链路：`trade.is_settled=TRUE` 的成功收款按**结清时刻**落账期窗口（聚合不用订单行，
 * 回调里 CAS 结清那一刻写的 `trade.updated` 才是"钱进来的时刻"），退款取 `trade.refunded_fee`，
 * 佣金/运费回 orders 补（trade 只记实付与实退）。
 */

/** 账期周期设置（每租户每种结算对象一行） */
class StatementPeriodSettingDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var merchantType: String = "distributor"
    var periodType: String = "daily"
    override var created: Long = 0
    override var updated: Long = 0
}

class StatementDO : Stampable {
    var id: Long = 0
    var statementNo: String = ""
    var companyId: Long = 0
    var merchantId: Long = 0
    var supplierId: Long = 0
    var distributorId: Long = 0
    var merchantType: String? = null
    var orderNum: Int = 0
    var totalFee: Long = 0
    var freightFee: Long = 0
    var rebateFee: Long = 0
    var refundFee: Long = 0
    var statementFee: Long = 0
    var commissionFee: Long = 0
    var startTime: Long = 0
    var endTime: Long = 0
    var statementStatus: String = "ready"
    override var created: Long = 0
    override var updated: Long = 0
}

class StatementDetailDO : Stampable {
    var id: Long = 0
    var statementId: Long = 0
    var orderId: Long = 0
    var num: Int = 0
    var totalFee: Long = 0
    var rebateFee: Long = 0
    var refundFee: Long = 0
    var refundNum: Int = 0
    override var created: Long = 0
    override var updated: Long = 0
}

/** 一个店铺在一个账期窗口里的聚合（trade 表 GROUP BY 的承载行） */
class StatementAggRow {
    var distributorId: Long = 0
    var orderNum: Int = 0
    var totalFee: Long = 0
    var refundFee: Long = 0
}

/** trade 明细行（窗口内事实源，details 逐单铺一行用） */
class StatementTradeRow {
    var tradeId: String = ""
    var orderId: String = ""
    var distributorId: Long = 0
    var payFee: Long = 0
    var refundedFee: Long = 0
}

@Mapper
interface StatementPeriodSettingMapper {

    @Select(
        "SELECT id, company_id, merchant_type, period_type, created, updated " +
            "FROM statement_period_setting WHERE company_id = #{companyId}",
    )
    fun listByCompany(@Param("companyId") companyId: Long): List<StatementPeriodSettingDO>

    @Select(
        "SELECT id, company_id, merchant_type, period_type, created, updated " +
            "FROM statement_period_setting " +
            "WHERE company_id = #{companyId} AND merchant_type = #{merchantType}",
    )
    fun find(
        @Param("companyId") companyId: Long,
        @Param("merchantType") merchantType: String,
    ): StatementPeriodSettingDO?

    @Insert(
        "INSERT INTO statement_period_setting(company_id, merchant_type, period_type, created, updated) " +
            "VALUES(#{companyId}, #{merchantType}, #{periodType}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(s: StatementPeriodSettingDO): Int

    @Update(
        "UPDATE statement_period_setting SET period_type = #{periodType}, updated = #{now} " +
            "WHERE id = #{id} AND company_id = #{companyId}",
    )
    fun update(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("periodType") periodType: String,
        @Param("now") now: Long,
    ): Int
}

/** 结算单列清单（SELECT 头共用一份，翻页与详情口径一致） */
private const val ST_COLS =
    "id, statement_no, company_id, merchant_id, supplier_id, distributor_id, merchant_type, " +
        "order_num, total_fee, freight_fee, rebate_fee, refund_fee, statement_fee, commission_fee, " +
        "start_time, end_time, statement_status, created, updated"

@Mapper
interface StatementsMapper {

    @Select(
        "SELECT $ST_COLS FROM statements WHERE id = #{id} AND company_id = #{companyId}",
    )
    fun find(
        @Param("companyId") companyId: Long,
        @Param("id") id: Long,
    ): StatementDO?

    @Select("SELECT id FROM statements WHERE statement_no = #{no}")
    fun idByNo(@Param("no") no: String): Long?

    /** ST-A1 全量可选筛选（f.* 缺省即不启用）；常数列清单在文件顶层（private const val ST_COLS） */
    class Filter {
        var companyId: Long = 0
        var merchantType: String? = null
        var distributorId: Long? = null
        var status: String? = null
    }

    @Select(
        "<script>" +
            "SELECT $ST_COLS FROM statements " +
            "WHERE company_id = #{f.companyId} " +
            "<if test='f.merchantType != null'>AND merchant_type = #{f.merchantType}</if> " +
            "<if test='f.distributorId != null'>AND distributor_id = #{f.distributorId}</if> " +
            "<if test='f.status != null'>AND statement_status = #{f.status}</if> " +
            "ORDER BY id DESC LIMIT #{offset}, #{limit}" +
            "</script>",
    )
    fun page(
        @Param("f") f: Filter,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<StatementDO>

    @Select(
        "<script>" +
            "SELECT COUNT(*) FROM statements " +
            "WHERE company_id = #{f.companyId} " +
            "<if test='f.merchantType != null'>AND merchant_type = #{f.merchantType}</if> " +
            "<if test='f.distributorId != null'>AND distributor_id = #{f.distributorId}</if> " +
            "<if test='f.status != null'>AND statement_status = #{f.status}</if>" +
            "</script>",
    )
    fun count(@Param("f") f: Filter): Long

    @Insert(
        "INSERT INTO statements(statement_no, company_id, merchant_id, supplier_id, distributor_id, merchant_type, " +
            "order_num, total_fee, freight_fee, rebate_fee, refund_fee, statement_fee, commission_fee, " +
            "start_time, end_time, statement_status, created, updated) " +
            "VALUES(#{statementNo}, #{companyId}, #{merchantId}, #{supplierId}, #{distributorId}, #{merchantType}, " +
            "#{orderNum}, #{totalFee}, #{freightFee}, #{rebateFee}, #{refundFee}, #{statementFee}, #{commissionFee}, " +
            "#{startTime}, #{endTime}, #{statementStatus}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(s: StatementDO): Int

    /** 状态机：ready→confirmed→paid；每一级都判前态（CAS，重复确认 0 行不报错） */
    @Update(
        "UPDATE statements SET statement_status = #{to}, updated = #{now} " +
            "WHERE id = #{id} AND company_id = #{companyId} AND statement_status = #{from}",
    )
    fun casStatus(
        @Param("id") id: Long,
        @Param("companyId") companyId: Long,
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("now") now: Long,
    ): Int
}

@Mapper
interface StatementDetailsMapper {

    @Insert(
        "INSERT INTO statement_details(statement_id, order_id, num, total_fee, rebate_fee, refund_fee, refund_num, " +
            "created, updated) VALUES(#{statementId}, #{orderId}, #{num}, #{totalFee}, #{rebateFee}, " +
            "#{refundFee}, #{refundNum}, #{created}, #{updated})",
    )
    fun insert(d: StatementDetailDO): Int

    @Select(
        "SELECT id, statement_id, order_id, num, total_fee, rebate_fee, refund_fee, refund_num, created, updated " +
            "FROM statement_details WHERE statement_id = #{statementId} ORDER BY id",
    )
    fun listByStatement(@Param("statementId") statementId: Long): List<StatementDetailDO>
}

/**
 * 账期事实源（批F §29.6）：
 *  - 结清行：`trade.is_settled=TRUE AND trade_state='SUCCESS'`，窗口按**结清时刻**（`trade.updated`，
 *    CAS 结清那一笔写入）；refunded_fee 读的是生成那一刻的累计；
 *  - 去重判据：一张订单一生只进一张账期单——已铺过单的 order_id 集合先捞出来在服务层过滤
 *    （trade.order_id 是 VARCHAR、statement_details.order_id 是 BIGINT，SQL 里做 CAST 会在
 *    H2/MySQL 两方言之间漂，这一步放 Kotlin，两方言同一份语义）。
 */
@Mapper
interface StatementFactsMapper {

    @Select(
        "SELECT trade_id, order_id, distributor_id, pay_fee, refunded_fee FROM trade " +
            "WHERE company_id = #{companyId} AND is_settled = TRUE AND trade_state = 'SUCCESS' " +
            "AND updated >= #{start} AND updated < #{end} ORDER BY trade_id",
    )
    fun listSettledTrades(
        @Param("companyId") companyId: Long,
        @Param("start") start: Long,
        @Param("end") end: Long,
    ): List<StatementTradeRow>

    @Select(
        "SELECT DISTINCT d.order_id FROM statement_details d " +
            "JOIN statements s ON s.id = d.statement_id WHERE s.company_id = #{companyId}",
    )
    fun listCountedOrderIds(@Param("companyId") companyId: Long): List<Long>
}
