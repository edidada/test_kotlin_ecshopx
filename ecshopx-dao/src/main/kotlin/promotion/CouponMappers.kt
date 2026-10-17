package com.ecshopx.kotlin.dao.promotion

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update
import com.ecshopx.kotlin.dao.stamp.Stampable

/**
 * M1-3 优惠券 DAO（`promotions_coupon` 模板 / `promotions_coupon_user` 实例）。
 *
 * 三条口径（docs/04 §14.6、D22/D23）：
 *  1. **抢量与领取都是 CAS**：`send_num<total_num`、`WHERE status='UNUSED'`，
 *     影响 0 行就是没抢到，调用方必须放弃 —— 源系统靠 Redis 锁，本仓库无 Redis，
 *     条件更新是同一语义的更便宜实现（超发在数据库层不可能发生）；
 *  2. **实例带快照**：面额/门槛/名称在领取时冻结，后台改模板不回溯已发出的券；
 *  3. **状态机四态**：`UNUSED → LOCKED → USED`，`LOCKED → UNUSED`（取消回退），`UNUSED → EXPIRED`（回收）。
 *     源系统 `freezeUserCard()` 被调用但全仓无实现，锁定态其实不存在 —— 这里是行为修复，不是照抄。
 *
 * 注意两张表的 `status` 同名不同型：模板是 BOOLEAN（是否停发），实例是 VARCHAR（状态机）。
 */

/** `promotions_coupon_user.status` */
object CouponStatus {
    const val UNUSED = "UNUSED"
    const val LOCKED = "LOCKED"
    const val USED = "USED"
    const val EXPIRED = "EXPIRED"

    /** 算价链只认这一种可用态（LOCKED 已被别的单占住，EXPIRED 由回收作业与 expire_time 双重把关） */
    const val AVAILABLE = UNUSED
}

class CouponDO : Stampable {
    var couponId: Long = 0
    var companyId: Long = 0

    /** cash=现金券 / discount=seed 里那张"满100减20"的遗留取值（同义）/ exchange=兑换券(P2) */
    var couponType: String = "cash"
    var couponName: String = ""

    /** 面额（分） */
    var discountFee: Long = 0

    /** 使用门槛（分），0=无门槛 */
    var minOrderFee: Long = 0
    var totalNum: Long = 0
    var sendNum: Long = 0

    /** range=固定起止 / days=领后 N 天 */
    var validType: String = "range"
    var beginTime: Long = 0
    var endTime: Long = 0
    var validDays: Int = 0

    /** 模板是否可发（false=停发，已发出的券不受影响） */
    var status: Boolean = true
    var getLimit: Int = 0
    var instruction: String? = null
    override var created: Long = 0
    override var updated: Long = 0
}

class CouponUserDO {
    var id: Long = 0
    var couponId: Long = 0
    var userId: Long = 0
    var companyId: Long = 0
    var couponCode: String = ""
    var status: String = CouponStatus.UNUSED
    var orderId: Long = 0
    var receiveTime: Long = 0
    var useTime: Long = 0
    var expireTime: Long = 0
    var lockTime: Long = 0

    /** 领取时冻结的模板快照 */
    var couponName: String = ""
    var couponType: String = "cash"
    var discountFee: Long = 0
    var minOrderFee: Long = 0

    /** receive=自领 / send=后台发券 */
    var source: String = "receive"
}

/** 券模板统计（CP-A3 详情 / CP-A2 列表的发放/锁定/已用/过期四计数） */
class CouponCountRow {
    var couponId: Long = 0
    var received: Long = 0
    var locked: Long = 0
    var used: Long = 0
    var expired: Long = 0
}

/**
 * CP-F1 批量行：`owned` 是累计领取数（限领判定用，与 [CouponUserMapper.countOwned] 同口径），
 * `usable` 是**当前能拿出来用**的张数（UNUSED 且未过期，不含被别的单锁住的）。
 */
class CouponOwnedRow {
    var couponId: Long = 0
    var owned: Long = 0
    var usable: Long = 0
}

private const val C_COLS =
    "coupon_id, company_id, coupon_type, coupon_name, discount_fee, min_order_fee, total_num, send_num, " +
        "valid_type, begin_time, end_time, valid_days, status, get_limit, instruction, created, updated"

private const val CU_COLS =
    "id, coupon_id, user_id, company_id, coupon_code, status, order_id, receive_time, use_time, expire_time, " +
        "lock_time, coupon_name, coupon_type, discount_fee, min_order_fee, source"

private const val C_FILTER_SQL = """
    <if test="f.companyId != null"> AND company_id = #{f.companyId}</if>
    <if test="f.name != null"> AND coupon_name LIKE CONCAT('%', #{f.name}, '%')</if>
    <if test="f.type != null"> AND coupon_type = #{f.type}</if>
    <if test="f.status != null"> AND status = #{f.status}</if>
"""

/** CP-F3 的会员视角分桶（见 [CouponUserMapper.pageByUser] 注释）；两处引用必须同判据，否则 count 与 list 会打架 */
private const val USER_STATUS_SQL = """
    <choose>
        <when test="status == 'UNUSED'"> AND status = 'UNUSED' AND (expire_time = 0 OR expire_time &gt; #{now})</when>
        <when test="status == 'EXPIRED'">
            AND (status = 'EXPIRED' OR (status = 'UNUSED' AND expire_time &gt; 0 AND expire_time &lt;= #{now}))
        </when>
        <when test="status != null"> AND status = #{status}</when>
        <otherwise> AND status &lt;&gt; 'LOCKED'</otherwise>
    </choose>
"""

@Mapper
interface CouponMapper {

    @Insert(
        "INSERT INTO promotions_coupon(company_id, coupon_type, coupon_name, discount_fee, min_order_fee, total_num, " +
            "send_num, valid_type, begin_time, end_time, valid_days, status, get_limit, instruction, created, updated) " +
            "VALUES(#{companyId}, #{couponType}, #{couponName}, #{discountFee}, #{minOrderFee}, #{totalNum}, 0, " +
            "#{validType}, #{beginTime}, #{endTime}, #{validDays}, #{status}, #{getLimit}, #{instruction}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "couponId", keyColumn = "coupon_id")
    fun insert(c: CouponDO): Int

    @Select("SELECT $C_COLS FROM promotions_coupon WHERE coupon_id = #{couponId} AND company_id = #{companyId}")
    fun findById(@Param("couponId") couponId: Long, @Param("companyId") companyId: Long): CouponDO?

    /** 前台可领券列表：只给在架 + 在发期内的模板（CP-F1） */
    @Select(
        "<script>SELECT $C_COLS FROM promotions_coupon WHERE company_id = #{companyId} AND status = TRUE " +
            "<if test=\"openOnly\"> AND (total_num &lt;= 0 OR send_num &lt; total_num) " +
            "AND (valid_type &lt;&gt; 'range' OR (begin_time &lt;= #{now} AND (end_time = 0 OR end_time &gt;= #{now})))</if>" +
            " ORDER BY coupon_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun pageOpen(
        @Param("companyId") companyId: Long,
        @Param("openOnly") openOnly: Boolean,
        @Param("now") now: Long,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<CouponDO>

    @Select("<script>SELECT COUNT(*) FROM promotions_coupon WHERE 1=1$C_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    @Select(
        "<script>SELECT $C_COLS FROM promotions_coupon WHERE 1=1$C_FILTER_SQL" +
            " ORDER BY coupon_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<CouponDO>

    /** 模板编辑：面额/门槛改动不回溯已发出的券（快照在实例行上） */
    @Update(
        "UPDATE promotions_coupon SET coupon_type = #{couponType}, coupon_name = #{couponName}, discount_fee = #{discountFee}, " +
            "min_order_fee = #{minOrderFee}, total_num = #{totalNum}, valid_type = #{validType}, begin_time = #{beginTime}, " +
            "end_time = #{endTime}, valid_days = #{validDays}, get_limit = #{getLimit}, instruction = #{instruction}, " +
            "updated = #{updated} WHERE coupon_id = #{couponId} AND company_id = #{companyId}",
    )
    fun update(c: CouponDO): Int

    @Update("UPDATE promotions_coupon SET status = #{status}, updated = #{updated} WHERE coupon_id = #{couponId} AND company_id = #{companyId}")
    fun updateStatus(
        @Param("couponId") couponId: Long,
        @Param("companyId") companyId: Long,
        @Param("status") status: Boolean,
        @Param("updated") updated: Long,
    ): Int

    /** CP-A6 删除：只允许删没发出去过的模板，否则历史券实例会指向空洞 */
    @Delete("DELETE FROM promotions_coupon WHERE coupon_id = #{couponId} AND company_id = #{companyId} AND send_num = 0")
    fun deleteIfUnsent(@Param("couponId") couponId: Long, @Param("companyId") companyId: Long): Int

    /**
     * 领取抢量（CAS）：`total_num<=0` 视为不限量。
     * 停发的模板在这里一并挡掉（`status = TRUE`），服务层不用再判一次。
     *
     * `valid_type='range'` 的券同时校验**使用窗**（docs/04 §14.6）：窗口已过的券不能领进来当死券。
     */
    @Update(
        "UPDATE promotions_coupon SET send_num = send_num + 1, updated = #{updated} " +
            "WHERE coupon_id = #{couponId} AND company_id = #{companyId} AND status = TRUE " +
            "AND (total_num <= 0 OR send_num < total_num) " +
            "AND (valid_type <> 'range' OR (begin_time <= #{now} AND (end_time = 0 OR end_time >= #{now})))",
    )
    fun takeQuota(
        @Param("couponId") couponId: Long,
        @Param("companyId") companyId: Long,
        @Param("now") now: Long,
        @Param("updated") updated: Long,
    ): Int

    /** 后台发券失败/回滚时把量还回去（不会低于 0） */
    @Update("UPDATE promotions_coupon SET send_num = GREATEST(send_num - #{num}, 0), updated = #{updated} WHERE coupon_id = #{couponId}")
    fun backQuota(@Param("couponId") couponId: Long, @Param("num") num: Long, @Param("updated") updated: Long): Int
}

@Mapper
interface CouponUserMapper {

    @Insert(
        "INSERT INTO promotions_coupon_user(coupon_id, user_id, company_id, coupon_code, status, order_id, receive_time, " +
            "use_time, expire_time, lock_time, coupon_name, coupon_type, discount_fee, min_order_fee, source) " +
            "VALUES(#{couponId}, #{userId}, #{companyId}, #{couponCode}, #{status}, 0, #{receiveTime}, 0, #{expireTime}, 0, " +
            "#{couponName}, #{couponType}, #{discountFee}, #{minOrderFee}, #{source})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(c: CouponUserDO): Int

    @Select("SELECT $CU_COLS FROM promotions_coupon_user WHERE id = #{id} AND company_id = #{companyId} AND user_id = #{userId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long, @Param("userId") userId: Long): CouponUserDO?

    /** 结算侧按券码定位（券码是入参，id 不是） */
    @Select("SELECT $CU_COLS FROM promotions_coupon_user WHERE coupon_code = #{code} AND company_id = #{companyId}")
    fun findByCode(@Param("code") code: String, @Param("companyId") companyId: Long): CouponUserDO?

    /**
     * 我的券（CP-F3）。`status` 语义按**会员视角**分桶而不是裸列值：
     *  - `UNUSED` 只看还没过期的（过期没用的归 EXPIRED，否则"我的可用券"里混着死券）；
     *  - `EXPIRED` 含"已回收"与"未用但已到期"两种，后者靠本 SQL 现算，不等 Job 扫到才算过期；
     *  - 缺省（null）给全部但不含 `LOCKED` —— 被待付款单占住的券在"我的券"里不露面，
     *    取消/关单回退后自己会回来（D23：锁定态是本项目的行为修复，前台只按四态展示）。
     */
    @Select(
        "<script>SELECT $CU_COLS FROM promotions_coupon_user " +
            "WHERE company_id = #{companyId} AND user_id = #{userId}" +
            USER_STATUS_SQL + " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun pageByUser(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("status") status: String?,
        @Param("now") now: Long,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<CouponUserDO>

    @Select(
        "<script>SELECT COUNT(*) FROM promotions_coupon_user " +
            "WHERE company_id = #{companyId} AND user_id = #{userId}" + USER_STATUS_SQL + "</script>",
    )
    fun countByUser(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("status") status: String?,
        @Param("now") now: Long,
    ): Long

    /**
     * 本人的券候选集（CP-F4 与算价预取共用，上限 100 张）。
     *
     * 这里**故意不按 `expire_time` 过滤**：`UNUSED` 但已到期的行要进候选集，
     * 服务层的 [CouponUserMapper] 判据才能给出"该券已过期"这条原因
     * （只给"能用的一堆"，用户在结算页看不到"我明明有券为什么不能用"）。
     * 排序把在期的放前面、面额大的再放前面，所以 100 张上限只会截掉过期/小额的尾巴。
     */
    @Select(
        "SELECT $CU_COLS FROM promotions_coupon_user WHERE company_id = #{companyId} AND user_id = #{userId} " +
            "AND status = 'UNUSED' " +
            "ORDER BY CASE WHEN expire_time > 0 AND expire_time <= #{now} THEN 1 ELSE 0 END, discount_fee DESC LIMIT 100",
    )
    fun listAvailable(@Param("companyId") companyId: Long, @Param("userId") userId: Long, @Param("now") now: Long): List<CouponUserDO>

    /** 限领判定：按**累计领取数**算（已用/已过期也占额度，否则"领—用—再领"能无限领） */
    @Select("SELECT COUNT(*) FROM promotions_coupon_user WHERE company_id = #{companyId} AND user_id = #{userId} AND coupon_id = #{couponId}")
    fun countOwned(@Param("companyId") companyId: Long, @Param("userId") userId: Long, @Param("couponId") couponId: Long): Long

    /** CP-F1 批量：本人在这些模板下已领了几张 / 现在还能用几张 */
    @Select(
        "<script>SELECT coupon_id AS coupon_id, COUNT(*) AS owned, " +
            "SUM(CASE WHEN status = 'UNUSED' AND (expire_time = 0 OR expire_time &gt; #{now}) THEN 1 ELSE 0 END) AS usable " +
            "FROM promotions_coupon_user " +
            "WHERE company_id = #{companyId} AND user_id = #{userId} AND coupon_id IN" +
            "<foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach>" +
            " GROUP BY coupon_id</script>",
    )
    fun countOwnedBatch(
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("ids") ids: List<Long>,
        @Param("now") now: Long,
    ): List<CouponOwnedRow>

    /**
     * 下单锁券（CAS，D23）。守卫三件：归属本人、态是 UNUSED、没过期。
     * 0 行 = 这张券已被别的单占住，服务层必须报"优惠券已使用"而不是继续算价。
     */
    @Update(
        "UPDATE promotions_coupon_user SET status = 'LOCKED', order_id = #{orderId}, lock_time = #{now} " +
            "WHERE coupon_code = #{code} AND company_id = #{companyId} AND user_id = #{userId} AND status = 'UNUSED' " +
            "AND (expire_time = 0 OR expire_time > #{now})",
    )
    fun lock(
        @Param("code") code: String,
        @Param("companyId") companyId: Long,
        @Param("userId") userId: Long,
        @Param("orderId") orderId: Long,
        @Param("now") now: Long,
    ): Int

    /** 核销：支付成功后由 PayService 结清链调用（只从 LOCKED 出发，重复通知不会二次改） */
    @Update("UPDATE promotions_coupon_user SET status = 'USED', use_time = #{now} WHERE order_id = #{orderId} AND status = 'LOCKED'")
    fun consume(@Param("orderId") orderId: Long, @Param("now") now: Long): Int

    /** 取消/关单回退：LOCKED → UNUSED（order_id 归零，券重新可用） */
    @Update(
        "UPDATE promotions_coupon_user SET status = 'UNUSED', order_id = 0, lock_time = 0 " +
            "WHERE order_id = #{orderId} AND status = 'LOCKED'",
    )
    fun release(@Param("orderId") orderId: Long): Int

    /**
     * 本单锁住的券（下单落快照与订单详情回读用）。
     * 与 [release]/[consume] 同守卫条件，三者对"哪些行属于这笔单"的口径必须一致。
     */
    @Select("SELECT $CU_COLS FROM promotions_coupon_user WHERE order_id = #{orderId} AND status = 'LOCKED' ORDER BY id")
    fun listUnlocked(@Param("orderId") orderId: Long): List<CouponUserDO>

    /** 过期回收（CouponExpireJob）：只收未使用的；LOCKED 的单还在途，等它回退或核销后再扫 */
    @Update(
        "UPDATE promotions_coupon_user SET status = 'EXPIRED' " +
            "WHERE status = 'UNUSED' AND expire_time > 0 AND expire_time <= #{now}",
    )
    fun expireBatch(@Param("now") now: Long): Int

    /** 回收前的候选计数（测试与后台统计用，与 expireBatch 同判据） */
    @Select("SELECT COUNT(*) FROM promotions_coupon_user WHERE status = 'UNUSED' AND expire_time > 0 AND expire_time <= #{now}")
    fun countExpirable(@Param("now") now: Long): Long

    /** CP-A7 领取/使用记录 */
    @Select(
        "<script>SELECT $CU_COLS FROM promotions_coupon_user WHERE company_id = #{companyId} AND coupon_id = #{couponId}" +
            "<if test=\"userId != null\"> AND user_id = #{userId}</if>" +
            "<if test=\"status != null\"> AND status = #{status}</if>" +
            " ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun pageRecords(
        @Param("companyId") companyId: Long,
        @Param("couponId") couponId: Long,
        @Param("userId") userId: Long?,
        @Param("status") status: String?,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<CouponUserDO>

    @Select(
        "<script>SELECT COUNT(*) FROM promotions_coupon_user WHERE company_id = #{companyId} AND coupon_id = #{couponId}" +
            "<if test=\"userId != null\"> AND user_id = #{userId}</if>" +
            "<if test=\"status != null\"> AND status = #{status}</if></script>",
    )
    fun countRecords(
        @Param("companyId") companyId: Long,
        @Param("couponId") couponId: Long,
        @Param("userId") userId: Long?,
        @Param("status") status: String?,
    ): Long

    @Select(
        "<script>SELECT coupon_id AS coupon_id, COUNT(*) AS received, " +
            "SUM(CASE WHEN status = 'LOCKED' THEN 1 ELSE 0 END) AS locked, " +
            "SUM(CASE WHEN status = 'USED' THEN 1 ELSE 0 END) AS used, " +
            "SUM(CASE WHEN status = 'EXPIRED' THEN 1 ELSE 0 END) AS expired " +
            "FROM promotions_coupon_user WHERE company_id = #{companyId} AND coupon_id = #{couponId} GROUP BY coupon_id</script>",
    )
    fun stats(@Param("companyId") companyId: Long, @Param("couponId") couponId: Long): CouponCountRow?

    /** 列表页批量统计（CP-A2 一行一次 GROUP BY，避免每行一次 stats） */
    @Select(
        "<script>SELECT coupon_id AS coupon_id, COUNT(*) AS received, " +
            "SUM(CASE WHEN status = 'LOCKED' THEN 1 ELSE 0 END) AS locked, " +
            "SUM(CASE WHEN status = 'USED' THEN 1 ELSE 0 END) AS used, " +
            "SUM(CASE WHEN status = 'EXPIRED' THEN 1 ELSE 0 END) AS expired " +
            "FROM promotions_coupon_user WHERE company_id = #{companyId} AND coupon_id IN" +
            "<foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach>" +
            " GROUP BY coupon_id</script>",
    )
    fun listStats(@Param("companyId") companyId: Long, @Param("ids") ids: List<Long>): List<CouponCountRow>

    /** 按券码批量取实例（CP-A8 发券后的回读、订单详情的 coupon_info） */
    @Select(
        "<script>SELECT $CU_COLS FROM promotions_coupon_user WHERE company_id = #{companyId} AND coupon_code IN" +
            "<foreach collection=\"codes\" item=\"c\" open=\"(\" separator=\",\" close=\")\">#{c}</foreach></script>",
    )
    fun listByCodes(@Param("companyId") companyId: Long, @Param("codes") codes: List<String>): List<CouponUserDO>
}
