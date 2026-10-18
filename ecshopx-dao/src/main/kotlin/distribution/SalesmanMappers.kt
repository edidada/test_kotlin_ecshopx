package com.ecshopx.kotlin.dao.distribution

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-4 第三批后半（3B）导购 DAO（`distribution_distributor_salesman` + 角色表，docs/04 §18.2 SM-A1..A6 / §18.3）。
 *
 * 四个形状上的决定：
 *  1. **`is_valid` 是 `'true'/'false'` 字符串列**（照抄源系统与 `distribution_distributor.is_valid` 的列型），
 *     不是布尔。这张表的启停要和店铺档案那侧用同一套出参形状，前端一个控件两边复用；
 *  2. **口令列在这里读出去**（[SalesmanDO.password]）—— 员工端登录的 bcrypt 比对必须在服务层做，
 *     DAO 只负责把那一列带回来。出参视图**永远不含这一列**（§22 的 C175），
 *     所以 SM-A1 列表行用的是 [SalesmanRow]，它压根没有这个字段；
 *  3. **`(distributor_id, mobile)` 店內唯一**（schema 里的 `uk_salesman_mobile`）。
 *     跨店重号是允许的：同一个人在两家加盟店各带一个岗位，是两个身份、两份佣金账；
 *  4. **角色两列语义**（`distributor_id=0` = 平台通用岗位）沿用的是自提点那一套（C160 同族）：
 *     判"这个岗位能不能选"看的是 `distributor_id IN (0, 本店)`，不是归属列，
 *     否则平台建的角色在任何一家店里都选不到。
 *
 * 统计数（`order_count` / `commission_fee`）不落在导购行上，全部现算：
 *  列表按**当页的 id 集合**一次 GROUP BY 拿回来，不在行循环里逐条查库（N+1），
 *  也不冗余成列 —— 冗余列要跟着订单状态机、退款、结算三个流程各自回写，漏一处就是永久错数。
 */

/** 列表与计数的同一条筛选（键缺省即不启用）：两条语句共用，防止"计数与取数判据不同源" */
private const val SM_FILTER_SQL =
    "<if test='f.companyId != null'>AND s.company_id = #{f.companyId}</if>" +
    "<if test='f.distributorId != null'>AND s.distributor_id = #{f.distributorId}</if>" +
    "<if test='f.ids != null'>AND s.distributor_id IN <foreach item='g' collection='f.ids' open='(' separator=',' close=')'>#{g}</foreach></if>" +
    "<if test='f.roleId != null'>AND s.role_id = #{f.roleId}</if>" +
    "<if test='f.isValid != null'>AND s.is_valid = #{f.isValid}</if>" +
    "<if test='f.userId != null'>AND s.user_id = #{f.userId}</if>" +
    "<if test='f.keyword != null'>AND (s.salesman_name LIKE CONCAT('%', #{f.keyword}, '%') OR s.mobile LIKE CONCAT('%', #{f.keyword}, '%'))</if>"

private const val SR_FILTER_SQL =
    "<if test='f.companyId != null'>AND r.company_id = #{f.companyId}</if>" +
    // 归属店 +（要不要带上平台通用岗）由服务层算成一个 id 集合传进来：
    // 在 SQL 里拼 `AND 店 OR 0` 会把前面的条件一起 OR 掉，条件组一旦要嵌套就该在服务层收敛。
    "<if test='f.dids != null'>AND r.distributor_id IN <foreach item='g' collection='f.dids' open='(' separator=',' close=')'>#{g}</foreach></if>" +
    "<if test='f.status != null'>AND r.status = #{f.status}</if>" +
    "<if test='f.keyword != null'>AND r.role_name LIKE CONCAT('%', #{f.keyword}, '%')</if>"

/** `distribution_distributor_salesman` 一行；`password` 只给登录比对用，不出参 */
class SalesmanDO : Stampable {
    var salesmanId: Long = 0
    var companyId: Long = 0
    var distributorId: Long = 0
    var shopId: Long = 0

    /** 绑定的会员（老板/导购本人的小程序账号）；0 = 只有工号没有会员身份 */
    var userId: Long = 0
    var mobile: String? = null
    var salesmanName: String? = null
    var roleId: Long = 0

    /** 'true' / 'false'（源系统列型，不是布尔） */
    var isValid: String = "true"

    /** bcrypt 口令；NULL = 从没设过，员工端登录一律拒 */
    var password: String? = null
    var avatar: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

/** SM-A1 的行：在册行 + 店名 + 岗位名（两个 JOIN 列，不进 DO 免得被当成可写列） */
class SalesmanRow {
    var salesmanId: Long = 0
    var companyId: Long = 0
    var distributorId: Long = 0
    var shopId: Long = 0
    var userId: Long = 0
    var mobile: String? = null
    var salesmanName: String? = null
    var roleId: Long = 0
    var isValid: String = "true"
    var avatar: String? = null
    var created: Long = 0
    var updated: Long = 0
    var shopName: String? = null
    var roleName: String? = null
}

/** `distribution_distributor_salesman_role` 一行（SM-A6） */
class SalesmanRoleDO : Stampable {
    var roleId: Long = 0
    var companyId: Long = 0

    /** 归属店；`0` = 平台通用岗位，每家店都选得到 */
    var distributorId: Long = 0
    var roleName: String? = null
    var remark: String? = null

    /** 1 启用 / 0 停用（与自提点同一形状：停用不删行，历史导购还要指认岗位名） */
    var status: Int = 1
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface SalesmanMapper {

    @Insert(
        "INSERT INTO distribution_distributor_salesman(company_id, distributor_id, shop_id, user_id, mobile, " +
            "salesman_name, role_id, is_valid, password, avatar, created, updated) " +
            "VALUES(#{companyId}, #{distributorId}, #{shopId}, #{userId}, #{mobile}, #{salesmanName}, #{roleId}, " +
            "#{isValid}, #{password}, #{avatar}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "salesmanId", keyColumn = "salesman_id")
    fun insert(s: SalesmanDO): Int

    @Select(
        "SELECT salesman_id, company_id, distributor_id, shop_id, user_id, mobile, salesman_name, role_id, " +
            "is_valid, password, avatar, created, updated FROM distribution_distributor_salesman " +
            "WHERE salesman_id = #{id} AND company_id = #{companyId}",
    )
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): SalesmanDO?

    /**
     * 员工登录按手机号认人（ST-E1）。
     *
     * `distributor_id` 传了就只在店内认；没传时同一手机号在多家店在册会命中多行，
     * 取 `salesman_id` 最小的那条（§22 C172 如实记这个回落，不做"猜一个"的隐式行为）。
     * 只认 `is_valid='true'`：停用的人当场登不进来。
     */
    @Select(
        "<script>SELECT salesman_id, company_id, distributor_id, shop_id, user_id, mobile, salesman_name, role_id, " +
            "is_valid, password, avatar, created, updated FROM distribution_distributor_salesman " +
            "WHERE company_id = #{companyId} AND mobile = #{mobile} AND is_valid = 'true' " +
            "<if test='distributorId != null'>AND distributor_id = #{distributorId}</if>" +
            " ORDER BY salesman_id LIMIT 1</script>",
    )
    fun findLogin(
        @Param("companyId") companyId: Long,
        @Param("mobile") mobile: String,
        @Param("distributorId") distributorId: Long?,
    ): SalesmanDO?

    /** SM-A2 的"店內手机号唯一"判据（`excludeId` 给 SM-A3 改手机号时用） */
    @Select(
        "<script>SELECT COUNT(*) FROM distribution_distributor_salesman WHERE company_id = #{companyId} " +
            "AND distributor_id = #{distributorId} AND mobile = #{mobile} " +
            "<if test='excludeId != null'>AND salesman_id &lt;&gt; #{excludeId}</if></script>",
    )
    fun countSameMobile(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long,
        @Param("mobile") mobile: String,
        @Param("excludeId") excludeId: Long?,
    ): Long

    /** SM-A3 整行更新（读-改-写后的全量回写；`password` 不在其中，改口令走 [updatePassword]） */
    @Update(
        "UPDATE distribution_distributor_salesman SET distributor_id = #{distributorId}, shop_id = #{shopId}, " +
            "user_id = #{userId}, mobile = #{mobile}, salesman_name = #{salesmanName}, role_id = #{roleId}, " +
            "is_valid = #{isValid}, avatar = #{avatar}, updated = #{updated} " +
            "WHERE salesman_id = #{salesmanId} AND company_id = #{companyId}",
    )
    fun updateFull(s: SalesmanDO): Int

    /** SM-A4 调岗：只动 `role_id` 这一列 */
    @Update(
        "UPDATE distribution_distributor_salesman SET role_id = #{roleId}, updated = #{updated} " +
            "WHERE salesman_id = #{id} AND company_id = #{companyId}",
    )
    fun updateRole(@Param("id") id: Long, @Param("companyId") companyId: Long, @Param("roleId") roleId: Long, @Param("updated") updated: Long): Int

    /** SM-A5 启停 */
    @Update(
        "UPDATE distribution_distributor_salesman SET is_valid = #{isValid}, updated = #{updated} " +
            "WHERE salesman_id = #{id} AND company_id = #{companyId}",
    )
    fun setStatus(@Param("id") id: Long, @Param("companyId") companyId: Long, @Param("isValid") isValid: String, @Param("updated") updated: Long): Int

    /** 建/改时给口令（NULL 与空串都在这里落成"设过这枚哈希"；清空口令由服务层传 null 表达） */
    @Update(
        "UPDATE distribution_distributor_salesman SET password = #{password}, updated = #{updated} " +
            "WHERE salesman_id = #{id} AND company_id = #{companyId}",
    )
    fun updatePassword(@Param("id") id: Long, @Param("companyId") companyId: Long, @Param("password") password: String?, @Param("updated") updated: Long): Int

    /** SM-F1：按 id 认导购（前台扫码进店，只能看到营业中店铺的人） */
    @Select(
        "SELECT s.salesman_id, s.company_id, s.distributor_id, s.shop_id, s.user_id, s.mobile, s.salesman_name, " +
            "s.role_id, s.is_valid, s.avatar, s.created, s.updated, d.name AS shop_name, r.role_name AS role_name " +
            "FROM distribution_distributor_salesman s " +
            "JOIN distribution_distributor d ON d.distributor_id = s.distributor_id " +
            "LEFT JOIN distribution_distributor_salesman_role r ON r.role_id = s.role_id " +
            "WHERE s.company_id = #{companyId} AND s.salesman_id = #{id} AND s.is_valid = 'true'",
    )
    fun findActiveById(@Param("companyId") companyId: Long, @Param("id") id: Long): SalesmanRow?

    /** SM-F1：按手机号认导购（§18.1 给了两种查法） */
    @Select(
        "SELECT s.salesman_id, s.company_id, s.distributor_id, s.shop_id, s.user_id, s.mobile, s.salesman_name, " +
            "s.role_id, s.is_valid, s.avatar, s.created, s.updated, d.name AS shop_name, r.role_name AS role_name " +
            "FROM distribution_distributor_salesman s " +
            "JOIN distribution_distributor d ON d.distributor_id = s.distributor_id " +
            "LEFT JOIN distribution_distributor_salesman_role r ON r.role_id = s.role_id " +
            "WHERE s.company_id = #{companyId} AND s.mobile = #{mobile} AND s.is_valid = 'true' " +
            "ORDER BY s.salesman_id LIMIT 1",
    )
    fun findActiveByMobile(@Param("companyId") companyId: Long, @Param("mobile") mobile: String): SalesmanRow?

    /**
     * 会员名下有哪些在册导购（CW-F3「导购提现」与 SM-F2 绑定的判据）。
     *
     * 返回**列表**而不是第一条：一个会员理论上可以在多家店各有一个身份，
     * 提现时"提到哪家店"必须由入参说清，服务层不能替他挑。
     */
    @Select(
        "SELECT salesman_id, company_id, distributor_id, shop_id, user_id, mobile, salesman_name, role_id, " +
            "is_valid, password, avatar, created, updated FROM distribution_distributor_salesman " +
            "WHERE company_id = #{companyId} AND user_id = #{userId} AND is_valid = 'true' ORDER BY salesman_id",
    )
    fun listActiveByUser(@Param("companyId") companyId: Long, @Param("userId") userId: Long): List<SalesmanDO>

    @Select(
        "<script>SELECT s.salesman_id, s.company_id, s.distributor_id, s.shop_id, s.user_id, s.mobile, s.salesman_name, " +
            "s.role_id, s.is_valid, s.avatar, s.created, s.updated, d.name AS shop_name, r.role_name AS role_name " +
            "FROM distribution_distributor_salesman s " +
            "LEFT JOIN distribution_distributor d ON d.distributor_id = s.distributor_id " +
            "LEFT JOIN distribution_distributor_salesman_role r ON r.role_id = s.role_id " +
            "WHERE 1=1$SM_FILTER_SQL ORDER BY s.salesman_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<SalesmanRow>

    @Select("<script>SELECT COUNT(*) FROM distribution_distributor_salesman s WHERE 1=1$SM_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    /**
     * SM-A1 的成交单数（当页一次 GROUP BY）。
     *
     * 排除 `CANCEL`：一张取消的单从没成交过，把它算进"这个导购开了多少单"，
     * 运营照这个数发提成就会多发。
     */
    @Select(
        "<script>SELECT salesman_id AS salesman_id, COUNT(*) AS c FROM orders_normal_orders " +
            "WHERE order_status &lt;&gt; 'CANCEL' AND salesman_id IN " +
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach> " +
            "GROUP BY salesman_id</script>",
    )
    fun orderCounts(@Param("ids") ids: List<Long>): List<Map<String, Any?>>

    /**
     * ST-E4 的三个单数走同一条表、三条判据（`[today]` 靠传入的当日零点秒数，不在 SQL 里取 `NOW()`：
     * 本仓时间是服务层给的，测试要把"今天"挪到哪天都行）。
     */
    @Select("SELECT COUNT(*) FROM orders_normal_orders WHERE salesman_id = #{id} AND order_status <> 'CANCEL'")
    fun countOrders(@Param("id") id: Long): Long

    @Select(
        "SELECT COUNT(*) FROM orders_normal_orders WHERE salesman_id = #{id} AND order_status <> 'CANCEL' AND created >= #{from}",
    )
    fun countOrdersSince(@Param("id") id: Long, @Param("from") from: Long): Long

    /** 待收口：既没付款也没完成、还没取消 —— 导购要跟的就是这一批单 */
    @Select(
        "SELECT COUNT(*) FROM orders_normal_orders WHERE salesman_id = #{id} " +
            // 非 `<script>` 的注解 SQL 是逐字送进数据库的，这里不能写 `&lt;&gt;`：实体只在内层 XML 里解码
            "AND pay_status <> 'PAYED' AND order_status NOT IN ('CANCEL', 'DONE')",
    )
    fun countPending(@Param("id") id: Long): Long

    /** SM-A1 的"未结算佣金"（按当页 id 一次 GROUP BY；负向冲销行的符号自己会把账抵掉） */
    @Select(
        "<script>SELECT salesman_id AS salesman_id, COALESCE(SUM(rebate), 0) AS s FROM distribution_distribute_logs " +
            "WHERE is_close = FALSE AND salesman_id IN " +
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach> " +
            "GROUP BY salesman_id</script>",
    )
    fun openCommission(@Param("ids") ids: List<Long>): List<Map<String, Any?>>

    /** SM-A6 的删除守卫：这个角色下面还有没有导购 */
    @Select("SELECT COUNT(*) FROM distribution_distributor_salesman WHERE role_id = #{roleId}")
    fun countByRole(@Param("roleId") roleId: Long): Long
}

@Mapper
interface SalesmanRoleMapper {

    @Insert(
        "INSERT INTO distribution_distributor_salesman_role(company_id, distributor_id, role_name, remark, status, created, updated) " +
            "VALUES(#{companyId}, #{distributorId}, #{roleName}, #{remark}, #{status}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "roleId", keyColumn = "role_id")
    fun insert(r: SalesmanRoleDO): Int

    @Select(
        "SELECT role_id, company_id, distributor_id, role_name, remark, status, created, updated " +
            "FROM distribution_distributor_salesman_role WHERE role_id = #{id} AND company_id = #{companyId}",
    )
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): SalesmanRoleDO?

    @Update(
        "UPDATE distribution_distributor_salesman_role SET distributor_id = #{distributorId}, role_name = #{roleName}, " +
            "remark = #{remark}, status = #{status}, updated = #{updated} " +
            "WHERE role_id = #{roleId} AND company_id = #{companyId}",
    )
    fun updateFull(r: SalesmanRoleDO): Int

    /**
     * SM-A6 删除：**真删**（与自提点的逻辑停用不同）。
     *
     * 岗位没有"历史单要指认当时哪个岗"的诉求 —— 导购行上的 `role_id` 才是那条事实，
     * 岗名没了就没了，所以删除前先查"有没有人还挂着"（[SalesmanMapper.countByRole]）挡住，
     * 挡不住的是引用完整性，不是留痕。
     */
    @Delete("DELETE FROM distribution_distributor_salesman_role WHERE role_id = #{id} AND company_id = #{companyId}")
    fun delete(@Param("id") id: Long, @Param("companyId") companyId: Long): Int

    /**
     * SM-A4 的"这个岗本店能不能选"：`status=1` 且（本店的 或 平台通用的）。
     * 一条 COUNT 代替"先查角色再判两列"，服务层只问结果。
     */
    @Select(
        "SELECT COUNT(*) FROM distribution_distributor_salesman_role " +
            "WHERE role_id = #{roleId} AND company_id = #{companyId} AND status = 1 " +
            "AND (distributor_id = 0 OR distributor_id = #{distributorId})",
    )
    fun countUsable(@Param("companyId") companyId: Long, @Param("distributorId") distributorId: Long, @Param("roleId") roleId: Long): Long

    @Select(
        "<script>SELECT r.*, d.name AS shop_name FROM distribution_distributor_salesman_role r " +
            "LEFT JOIN distribution_distributor d ON d.distributor_id = r.distributor_id " +
            "WHERE 1=1$SR_FILTER_SQL ORDER BY r.role_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<SalesmanRoleRow>

    @Select(
        "<script>SELECT COUNT(*) FROM distribution_distributor_salesman_role r WHERE 1=1$SR_FILTER_SQL</script>",
    )
    fun count(@Param("f") f: Map<String, Any?>): Long

    /** 同店内重名岗位先挡在服务层（建/改都查），唯一索引不加：历史行里可能已有同名 */
    @Select(
        "<script>SELECT COUNT(*) FROM distribution_distributor_salesman_role WHERE company_id = #{companyId} " +
            "AND distributor_id = #{distributorId} AND role_name = #{roleName} " +
            "<if test='excludeId != null'>AND role_id &lt;&gt; #{excludeId}</if></script>",
    )
    fun countSameName(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long,
        @Param("roleName") roleName: String,
        @Param("excludeId") excludeId: Long?,
    ): Long
}

/** SM-A6 的行：岗位 + 归属店名（`distributor_id=0` 时店名为空，出参给"全平台"文案） */
class SalesmanRoleRow {
    var roleId: Long = 0
    var companyId: Long = 0
    var distributorId: Long = 0
    var roleName: String? = null
    var remark: String? = null
    var status: Int = 1
    var created: Long = 0
    var updated: Long = 0
    var shopName: String? = null
}
