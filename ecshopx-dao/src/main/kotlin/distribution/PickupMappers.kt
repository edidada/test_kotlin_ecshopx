package com.ecshopx.kotlin.dao.distribution

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M1-4 自提点 DAO（`distribution_pickup_location`，docs/04 §18.6 / PL-A1..A5 / PL-F1）。
 *
 * 三个形状上的决定：
 *  1. **`distributor_id` 是归属店，`rel_distributor_id` 是"这条行挂给谁用"**。源系统两列并存看着冗余，
 *     其实是两件事：点由哪家店建（数据权限按它收窄）、点给哪家店的单用（`0` = 全平台通用点）。
 *     列表与前台取点各自判各自的那一列，混用会让"通用点"在任何一家店的主页都消失；
 *  2. **删除是 `status=0` 的逻辑停用**（PL-A4），但停用前必须查"还有没有未完结的自提单指向它" ——
 *     自提单没发货环节，点是它唯一的取货地址；单还没核销就把点删了，买家拿着码到店却找不到地方；
 *  3. **`lng/lat` 是 VARCHAR**（照源系统列型）。距离在**服务层**算（§18.6：入参可选 `lng/lat`），
 *     不在 SQL 里写球面公式——H2 与 MySQL 的函数名不一样，而本项目单机演示的点数是"一页十几条"的量级。
 */

/** PL-A1 列表与计数的同一条筛选（键缺省即不启用）：两条语句共用，防止"计数与取数判据不同源" */
private const val PL_FILTER_SQL =
    "<if test='f.companyId != null'>AND p.company_id = #{f.companyId}</if>" +
    "<if test='f.distributorId != null'>AND p.distributor_id = #{f.distributorId}</if>" +
    "<if test='f.relDistributorId != null'>AND p.rel_distributor_id = #{f.relDistributorId}</if>" +
    "<if test='f.status != null'>AND p.status = #{f.status}</if>" +
    "<if test='f.ids != null'>AND p.distributor_id IN <foreach item='g' collection='f.ids' open='(' separator=',' close=')'>#{g}</foreach></if>" +
    "<if test='f.keyword != null'>AND (p.name LIKE CONCAT('%', #{f.keyword}, '%') OR p.address LIKE CONCAT('%', #{f.keyword}, '%') OR p.contract_phone LIKE CONCAT('%', #{f.keyword}, '%'))</if>"

/** `distribution_pickup_location` 一行 */
class PickupLocationDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0

    /** 归属店铺（数据权限按这一列） */
    var distributorId: Long = 0

    /** 使用方店铺；`0` = 全平台通用点 */
    var relDistributorId: Long = 0
    var name: String? = null
    var province: String? = null
    var city: String? = null
    var area: String? = null
    var address: String? = null
    var lng: String? = null
    var lat: String? = null
    var contractPhone: String? = null

    /** 1=启用 0=停用（PL-A4 的"删除"就是置 0） */
    var status: Int = 1
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface PickupLocationMapper {

    @Insert(
        "INSERT INTO distribution_pickup_location(company_id, distributor_id, rel_distributor_id, name, " +
            "province, city, area, address, lng, lat, contract_phone, status, created, updated) " +
            "VALUES(#{companyId}, #{distributorId}, #{relDistributorId}, #{name}, #{province}, #{city}, #{area}, " +
            "#{address}, #{lng}, #{lat}, #{contractPhone}, #{status}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id")
    fun insert(p: PickupLocationDO): Int

    @Select("SELECT * FROM distribution_pickup_location WHERE id = #{id} AND company_id = #{companyId}")
    fun findById(@Param("id") id: Long, @Param("companyId") companyId: Long): PickupLocationDO?

    /** PL-A3 整行更新：`name/address/contract_phone/...` 都给，服务层先读后写（与 SI-A3 同一条读-改-写） */
    @Update(
        "UPDATE distribution_pickup_location SET distributor_id = #{distributorId}, rel_distributor_id = #{relDistributorId}, " +
            "name = #{name}, province = #{province}, city = #{city}, area = #{area}, address = #{address}, " +
            "lng = #{lng}, lat = #{lat}, contract_phone = #{contractPhone}, status = #{status}, updated = #{updated} " +
            "WHERE id = #{id} AND company_id = #{companyId}",
    )
    fun updateFull(p: PickupLocationDO): Int

    /** PL-A4 逻辑停用（只动 status，行留着给历史单指认取货地址） */
    @Update("UPDATE distribution_pickup_location SET status = 0, updated = #{updated} WHERE id = #{id} AND company_id = #{companyId}")
    fun disable(@Param("id") id: Long, @Param("companyId") companyId: Long, @Param("updated") updated: Long): Int

    /** PL-A5 挂店/摘店：`rel_distributor_id` 是唯一被改的列（0=摘回全平台通用） */
    @Update("UPDATE distribution_pickup_location SET rel_distributor_id = #{relDistributorId}, updated = #{updated} " +
        "WHERE id = #{id} AND company_id = #{companyId}")
    fun relBind(@Param("id") id: Long, @Param("companyId") companyId: Long, @Param("relDistributorId") relDistributorId: Long, @Param("updated") updated: Long): Int

    /**
     * 这个点还指着几张**没走完**的自提单。
     *
     * 判据是"未核销"而不是"未完成"：已核销（`ziti_status='true'`）的单货已经到手，
     * 点停用与否都不影响它；待付款的单也算，因为它一旦付款就立刻要拿这个地址取货。
     *
     * `CANCEL` 也在排除之列（冒烟照出来的）：取消单永远不会有货被取走，
     * 只排 `DONE` 的话，一张取消的自提单就把这个点**永久锁死**——门店再也想停也停不掉。
     */
    @Select(
        "SELECT COUNT(*) FROM orders_normal_orders WHERE receipt_type = 'ziti' AND pickup_location_id = #{id} " +
            "AND ziti_status = 'false' AND order_status NOT IN ('DONE', 'CANCEL')",
    )
    fun countOpenZitiOrders(@Param("id") id: Long): Long

    @Select(
        "<script>SELECT p.*, d.name AS shop_name FROM distribution_pickup_location p " +
            "LEFT JOIN distribution_distributor d ON d.distributor_id = p.distributor_id " +
            "WHERE 1=1$PL_FILTER_SQL ORDER BY p.id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(@Param("f") f: Map<String, Any?>, @Param("offset") offset: Long, @Param("limit") limit: Int): List<PickupRow>

    @Select("<script>SELECT COUNT(*) FROM distribution_pickup_location p WHERE 1=1$PL_FILTER_SQL</script>")
    fun count(@Param("f") f: Map<String, Any?>): Long

    /**
     * 前台取点（PL-F1 / DS-F2 的 `pickup_locations[]`）：启用的、归属该店或 `rel_distributor_id=0` 的通用点。
     * 一条 SQL 把"本店自己的点"和"平台通用点"一起捞出来，`rel_distributor_id` 出参原样给前端看形状。
     */
    @Select(
        "SELECT p.*, d.name AS shop_name FROM distribution_pickup_location p " +
            "LEFT JOIN distribution_distributor d ON d.distributor_id = p.distributor_id " +
            "WHERE p.company_id = #{companyId} AND p.status = 1 " +
            "AND (p.distributor_id = #{distributorId} OR p.rel_distributor_id = 0 OR p.rel_distributor_id = #{distributorId}) " +
            "ORDER BY p.id",
    )
    fun listUsable(
        @Param("companyId") companyId: Long,
        @Param("distributorId") distributorId: Long,
    ): List<PickupRow>
}

/**
 * 列表/前台取点的读出形状：行本身 + 归属店名。
 *
 * 不继承 DO 是故意的：`shop_name` 是 JOIN 出来的展示列，混进 DO 会让 `updateFull` 以为它是可写列。
 */
class PickupRow {
    var id: Long = 0
    var companyId: Long = 0
    var distributorId: Long = 0
    var relDistributorId: Long = 0
    var name: String? = null
    var province: String? = null
    var city: String? = null
    var area: String? = null
    var address: String? = null
    var lng: String? = null
    var lat: String? = null
    var contractPhone: String? = null
    var status: Int = 1
    var created: Long = 0
    var updated: Long = 0
    var shopName: String? = null
}
