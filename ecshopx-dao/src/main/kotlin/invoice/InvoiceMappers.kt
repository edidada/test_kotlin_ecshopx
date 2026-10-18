package com.ecshopx.kotlin.dao.invoice

import com.ecshopx.kotlin.common.domain.InvoiceApplyStatus
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * 发票族（M1-5 `INV-F1..F6` / `INV-A1..A3`，docs/04 §23）。
 *
 * 两张表、两种生命周期，放一个文件里是因为它们的耦合点只有一条：
 * 申请单可以引用抬头本（`invoices_id`），**引用过就不能删**（`INV-F6`）。
 *
 * 三个形状上的坑都在注释里写清了：
 * 1. `orders_invoice` 的时间列是 `create_time`/`update_time`（不是 `created`/`updated`），
 *    所以它**不吃 `StampInterceptor`**，时间由服务层显式传入 —— 与 `point_member_log` 同一族；
 * 2. `invoice_status` 是小写词表（列默认值就写着 `'pending'`），不是订单那套大写态，
 *    值域见 [InvoiceApplyStatus]；
 * 3. `order_id` 是字符串（源系统允许逗号分隔多订单），本项目收紧成单个订单号并加
 *    `uk_invoice_order`，DAO 因此按字符串匹配，不与 `orders.order_id`（数字）混用。
 */
open class OrdersInvoiceDO {
    var id: Long = 0
    var invoiceApplyBn: String = ""
    var userId: Long = 0
    var companyId: Long = 0
    /** 单个订单号（`INV-R1` 收紧后的口径），列本身仍是 VARCHAR */
    var orderId: String? = null
    /** enterprise/individual —— 注意不是抬头本那套 personal/corporate */
    var invoiceType: String? = null
    /** 01 专票 / 02 普票；空表示租户未限制票种 */
    var invoiceTypeCode: String? = null
    var companyTitle: String? = null
    var taxNumber: String? = null
    var email: String? = null
    var mobile: String? = null
    var invoiceStatus: String = InvoiceApplyStatus.PENDING
    var invoiceAmount: Long = 0
    var invoiceFileUrl: String? = null
    /** 后台处理备注（`rejected` 必填那条判据读它） */
    var remark: String? = null
    /** 0 = 会员手填的抬头，不来自抬头本；非 0 时这一行就是 `INV-F6` 的引用证据 */
    var invoicesId: Long = 0
    var createTime: Long = 0
    var updateTime: Long = 0
}

/** 抬头本（members_invoices）：只有 created，没有 updated —— 时间同样由服务层显式传 */
class MembersInvoiceDO {
    var invoicesId: Long = 0
    var userId: Long = 0
    var companyId: Long = 0
    /** personal/corporate */
    var invoicesType: String = "personal"
    var name: String? = null
    var telephone: String? = null
    var taxNumber: String? = null
    var businessAddress: String? = null
    var bank: String? = null
    var bankAccount: String? = null
    var isDef: Boolean = false
    var created: Long = 0
}

/**
 * 列表行 = 申请单 + 会员昵称。
 *
 * `nickname` 走 `LEFT JOIN members_info`（昵称在 `mi.username`，与售后/拼团/佣金那几处同一取法）；
 * 手机号**不 join** —— 这张表自己存了 `mobile`（申请时填的联系方式），datapass 掩码的就是它。
 */
class InvoiceRow {
    var id: Long = 0
    var invoiceApplyBn: String? = null
    var userId: Long = 0
    var companyId: Long = 0
    var orderId: String? = null
    var invoiceType: String? = null
    var invoiceTypeCode: String? = null
    var companyTitle: String? = null
    var taxNumber: String? = null
    var email: String? = null
    var mobile: String? = null
    var invoiceStatus: String? = null
    var invoiceAmount: Long = 0
    var invoiceFileUrl: String? = null
    var remark: String? = null
    var invoicesId: Long = 0
    var createTime: Long = 0
    var updateTime: Long = 0
    var nickname: String? = null
}

/**
 * 后台与前台共用一套筛选字典（`INV-A1` 传 status/keyword，`INV-F2` 只传 userId）。
 *
 * `keyword` 打五个列：申请单号、抬头、税号、邮箱、手机号 —— 运营手里通常只有其中一个，
 * 拆成五个入参反而让前端要为每个字段各写一个输入框。
 *
 * `distributorIds` 与积分/储值那两处同一条口径：`orders_invoice` 没有店铺归属列，
 * 受限账号按"这个会员在本店下过单"收窄（不是按订单归属，理由见下）。
 * 这里**没有**按 `oo.order_id = i.order_id` 收，因为 `i.order_id` 是 VARCHAR、
 * `oo.order_id` 是 BIGINT，靠隐式转型匹配会让索引失效且遇到历史脏数据直接不比 ——
 * 本项目一单一申请（`INV-R1`），按会员收与按订单收在这一张表上是同一个集合。
 */
private const val INV_WHERE = """
    WHERE 1=1
    <if test="f.companyId != null">AND i.company_id = #{f.companyId}</if>
    <if test="f.userId != null">AND i.user_id = #{f.userId}</if>
    <if test="f.status != null">AND i.invoice_status = #{f.status}</if>
    <if test="f.orderId != null">AND i.order_id = #{f.orderId}</if>
    <if test="f.invoiceType != null">AND i.invoice_type = #{f.invoiceType}</if>
    <if test="f.createdStart != null">AND i.create_time &gt;= #{f.createdStart}</if>
    <if test="f.createdEnd != null">AND i.create_time &lt;= #{f.createdEnd}</if>
    <if test="f.distributorIds != null">AND EXISTS (SELECT 1 FROM orders_normal_orders oo
        WHERE oo.user_id = i.user_id AND oo.distributor_id IN
        <foreach item="g" collection="f.distributorIds" open="(" separator="," close=")">#{g}</foreach>)</if>
    <if test="f.keyword != null">AND (i.invoice_apply_bn LIKE CONCAT('%', #{f.keyword}, '%')
        OR i.company_title LIKE CONCAT('%', #{f.keyword}, '%')
        OR i.tax_number LIKE CONCAT('%', #{f.keyword}, '%')
        OR i.email LIKE CONCAT('%', #{f.keyword}, '%')
        OR i.mobile LIKE CONCAT('%', #{f.keyword}, '%'))</if>
"""

private const val INV_COLS =
    "i.id, i.invoice_apply_bn, i.user_id, i.company_id, i.order_id, i.invoice_type, i.invoice_type_code, " +
        "i.company_title, i.tax_number, i.email, i.mobile, i.invoice_status, i.invoice_amount, " +
        "i.invoice_file_url, i.remark, i.invoices_id, i.create_time, i.update_time"

@Mapper
interface OrdersInvoiceMapper {

    @Insert(
        "INSERT INTO orders_invoice(invoice_apply_bn, user_id, company_id, order_id, invoice_type, invoice_type_code, " +
            "company_title, tax_number, email, mobile, invoice_status, invoice_amount, invoice_file_url, remark, " +
            "invoices_id, create_time, update_time) " +
            "VALUES(#{invoiceApplyBn}, #{userId}, #{companyId}, #{orderId}, #{invoiceType}, #{invoiceTypeCode}, " +
            "#{companyTitle}, #{taxNumber}, #{email}, #{mobile}, #{invoiceStatus}, #{invoiceAmount}, #{invoiceFileUrl}, " +
            "#{remark}, #{invoicesId}, #{createTime}, #{updateTime})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(d: OrdersInvoiceDO): Int

    /** 详情（含昵称）：`INV-F3`/`INV-A3` 同一个取法，掩码与可见性由服务层判 */
    @Select(
        "SELECT $INV_COLS, mi.username AS nickname FROM orders_invoice i " +
            "LEFT JOIN members_info mi ON mi.user_id = i.user_id WHERE i.id = #{id}",
    )
    fun findRow(@Param("id") id: Long): InvoiceRow?

    @Select("SELECT * FROM orders_invoice WHERE id = #{id}")
    fun find(@Param("id") id: Long): OrdersInvoiceDO?

    /**
     * 按订单号找当前申请（`INV-R1` 的 upsert 判据）。
     *
     * 走 `uk_invoice_order(company_id, order_id)` 这个唯一索引，所以至多一行；
     * **不带 company_id 就不调用** —— 缺了它这条查询会跨租户，uk 也拦不住。
     */
    @Select("SELECT * FROM orders_invoice WHERE company_id = #{companyId} AND order_id = #{orderId}")
    fun findByOrder(@Param("companyId") companyId: Long, @Param("orderId") orderId: String): OrdersInvoiceDO?

    @Select("<script>SELECT COUNT(*) FROM orders_invoice i " + INV_WHERE + "</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $INV_COLS, mi.username AS nickname FROM orders_invoice i " +
            "LEFT JOIN members_info mi ON mi.user_id = i.user_id" + INV_WHERE +
            " ORDER BY i.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<InvoiceRow>

    /**
     * 会员改申请（`INV-F1` 的 `pending` 分支）：CAS 在 `invoice_status = 'pending'` 上。
     *
     * 回 0 行 = 已经开票/驳回，是终态（`INV-R1`），服务层据此报 422 而不是"更新成功"。
     * `invoice_apply_bn` 与 `create_time` 不动：单号是会员看到的那个号，改内容不换号。
     */
    @Update(
        "<script>UPDATE orders_invoice SET update_time = #{now}" +
            "<if test='d.invoiceType != null'>, invoice_type = #{d.invoiceType}</if>" +
            "<if test='d.invoiceTypeCode != null'>, invoice_type_code = #{d.invoiceTypeCode}</if>" +
            "<if test='d.companyTitle != null'>, company_title = #{d.companyTitle}</if>" +
            "<if test='d.taxNumber != null'>, tax_number = #{d.taxNumber}</if>" +
            "<if test='d.email != null'>, email = #{d.email}</if>" +
            "<if test='d.mobile != null'>, mobile = #{d.mobile}</if>" +
            "<if test='d.invoiceAmount != null'>, invoice_amount = #{d.invoiceAmount}</if>" +
            "<if test='d.remark != null'>, remark = #{d.remark}</if>" +
            "<if test='d.invoicesId != null'>, invoices_id = #{d.invoicesId}</if>" +
            " WHERE id = #{d.id} AND invoice_status = 'pending'</script>",
    )
    fun updatePending(@Param("d") d: OrdersInvoiceDO, @Param("now") now: Long): Int

    /**
     * 后台处理（`INV-A2`）：`pending → issued/rejected` 的单向迁移。
     *
     * `from` 参与 WHERE，所以并发双审只有一个能成（回 0 行 → 服务层报"已处理"）；
     * 终态之间没有 SQL 路径可回退 —— 想改票只能重开申请单，这是刻意的。
     */
    @Update(
        "<script>UPDATE orders_invoice SET invoice_status = #{to}, update_time = #{now}" +
            "<if test='fileUrl != null'>, invoice_file_url = #{fileUrl}</if>" +
            "<if test='email != null'>, email = #{email}</if>" +
            "<if test='remark != null'>, remark = #{remark}</if>" +
            " WHERE id = #{id} AND invoice_status = #{from}</script>",
    )
    fun transit(
        @Param("id") id: Long,
        @Param("from") from: String,
        @Param("to") to: String,
        @Param("fileUrl") fileUrl: String?,
        @Param("email") email: String?,
        @Param("remark") remark: String?,
        @Param("now") now: Long,
    ): Int

    /**
     * `INV-F6` 的引用证据：这个抬头本被多少张申请单带下来过。
     *
     * 判引用**只认 `invoices_id`**，不按 `company_title + tax_number` 匹配 ——
     * 抬头改名或两个公司重号都会误判（前者让该删的删不掉、后者让不该删的被引用）。
     */
    @Select("SELECT COUNT(*) FROM orders_invoice WHERE company_id = #{companyId} AND invoices_id = #{invoicesId}")
    fun countReferenced(@Param("companyId") companyId: Long, @Param("invoicesId") invoicesId: Long): Int

    /** 卡头统计（`INV-A1` 的 status 分桶，一次扫描出三个数） */
    @Select(
        "<script>SELECT COUNT(*) AS total, " +
            "SUM(CASE WHEN invoice_status = 'pending' THEN 1 ELSE 0 END) AS pending, " +
            "SUM(CASE WHEN invoice_status = 'issued' THEN 1 ELSE 0 END) AS issued, " +
            "SUM(CASE WHEN invoice_status = 'rejected' THEN 1 ELSE 0 END) AS rejected " +
            "FROM orders_invoice WHERE company_id = #{companyId}</script>",
    )
    fun statusCounts(@Param("companyId") companyId: Long): Map<String, Any?>
}

/** 抬头本（`INV-F4..F6`）：所有写方法都带 `user_id`，越权直接回 0 行 → 服务层报 404 */
@Mapper
interface MembersInvoiceMapper {

    @Insert(
        "INSERT INTO members_invoices(user_id, company_id, invoices_type, name, telephone, tax_number, " +
            "business_address, bank, bank_account, is_def, created) " +
            "VALUES(#{userId}, #{companyId}, #{invoicesType}, #{name}, #{telephone}, #{taxNumber}, " +
            "#{businessAddress}, #{bank}, #{bankAccount}, #{isDef}, #{created})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "invoicesId", keyColumn = "invoices_id")
    fun insert(d: MembersInvoiceDO): Int

    @Update(
        "UPDATE members_invoices SET invoices_type = #{invoicesType}, name = #{name}, telephone = #{telephone}, " +
            "tax_number = #{taxNumber}, business_address = #{businessAddress}, bank = #{bank}, " +
            "bank_account = #{bankAccount}, is_def = #{isDef} " +
            "WHERE invoices_id = #{invoicesId} AND user_id = #{userId} AND company_id = #{companyId}",
    )
    fun update(d: MembersInvoiceDO): Int

    @Delete("DELETE FROM members_invoices WHERE invoices_id = #{invoicesId} AND user_id = #{userId} AND company_id = #{companyId}")
    fun delete(@Param("invoicesId") invoicesId: Long, @Param("userId") userId: Long, @Param("companyId") companyId: Long): Int

    @Select("SELECT * FROM members_invoices WHERE invoices_id = #{invoicesId}")
    fun find(@Param("invoicesId") invoicesId: Long): MembersInvoiceDO?

    /** 本人视角的单条（`INV-F1` 带 `invoices_id` 时的取数：查不到即"不是你的抬头"→ 404） */
    @Select("SELECT * FROM members_invoices WHERE invoices_id = #{invoicesId} AND user_id = #{userId} AND company_id = #{companyId}")
    fun findMine(@Param("invoicesId") invoicesId: Long, @Param("userId") userId: Long, @Param("companyId") companyId: Long): MembersInvoiceDO?

    /** 默认抬头排前面，其余按新增时间倒序（前台"选一个"时第一项就是默认） */
    @Select(
        "SELECT * FROM members_invoices WHERE user_id = #{userId} AND company_id = #{companyId} " +
            "ORDER BY is_def DESC, invoices_id DESC",
    )
    fun listMine(@Param("userId") userId: Long, @Param("companyId") companyId: Long): List<MembersInvoiceDO>

    /**
     * `INV-R`/门槛用例第 4 条：置默认前先把其余行清零，保证 `SUM(is_def)=1`。
     * 与 [setDefault] 分成两条而不是 `UPDATE ... CASE`，是因为清零要覆盖"除自己以外"的集合，
     * 服务层已经知道本次的 invoices_id，分开写最好读也最好测。
     */
    @Update(
        "UPDATE members_invoices SET is_def = FALSE WHERE user_id = #{userId} AND company_id = #{companyId} " +
            "AND is_def = TRUE AND invoices_id <> #{excludeId}",
    )
    fun clearDefault(@Param("userId") userId: Long, @Param("companyId") companyId: Long, @Param("excludeId") excludeId: Long): Int

    @Update("UPDATE members_invoices SET is_def = TRUE WHERE invoices_id = #{invoicesId} AND user_id = #{userId} AND company_id = #{companyId}")
    fun setDefault(@Param("invoicesId") invoicesId: Long, @Param("userId") userId: Long, @Param("companyId") companyId: Long): Int

    @Select("SELECT COUNT(*) FROM members_invoices WHERE user_id = #{userId} AND company_id = #{companyId} AND is_def = TRUE")
    fun countDefault(@Param("userId") userId: Long, @Param("companyId") companyId: Long): Int
}
