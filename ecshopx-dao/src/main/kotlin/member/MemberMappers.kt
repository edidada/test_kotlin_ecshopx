package com.ecshopx.kotlin.dao.member

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.Update

/**
 * M0-8 会员域只读/写视图（MEM-1..8）。
 *
 * 表沿用源系统拼写：资料在 `members_info`（主键即 user_id，一会员一行），
 * 收藏快照在 `members_items_fav`，浏览轨迹在 `members_browse_history`。
 */

class MemberInfoDO {
    var userId: Long = 0
    var username: String? = null
    var name: String? = null
    var avatar: String? = null
    var sex: Int = 0
    var birthday: String? = null
    var address: String? = null
    var email: String? = null
    var industry: String? = null
    var income: String? = null
    var eduBackground: String? = null
    /** JSON 数组字符串：[{name,ischecked}] */
    var habbit: String? = null
    var haveConsume: Boolean = false
    var customData: String? = null
}

@Mapper
interface MembersInfoMapper {

    @Select("SELECT * FROM members_info WHERE user_id = #{userId}")
    fun findByUserId(@Param("userId") userId: Long): MemberInfoDO?

    /** 批量昵称/头像（券领取记录 CP-A7、团成员 GP-F3 用，一次回捞防 N+1） */
    @Select(
        "<script>SELECT user_id, username, name, avatar FROM members_info WHERE user_id IN" +
            "<foreach collection=\"ids\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach></script>",
    )
    fun listByUserIds(@Param("ids") ids: List<Long>): List<MemberInfoDO>

    /**
     * upsert：资料表可能没有行（wxapp 静默注册的会员），有则改。
     * H2 `MODE=MySQL` 与 MySQL 都支持 ON DUPLICATE KEY UPDATE，主键 user_id 即冲突键。
     */
    @Update(
        "INSERT INTO members_info(user_id, username, name, avatar, sex, birthday, address, email, industry, " +
            "income, edu_background, habbit, have_consume) " +
            "VALUES(#{userId}, #{username}, #{name}, #{avatar}, #{sex}, #{birthday}, #{address}, #{email}, " +
            "#{industry}, #{income}, #{eduBackground}, #{habbit}, #{haveConsume}) " +
            "ON DUPLICATE KEY UPDATE username = #{username}, name = #{name}, avatar = #{avatar}, sex = #{sex}, " +
            "birthday = #{birthday}, address = #{address}, email = #{email}, industry = #{industry}, " +
            "income = #{income}, edu_background = #{eduBackground}, habbit = #{habbit}",
    )
    fun upsert(info: MemberInfoDO): Int
}

class FavDO {
    var favId: Long = 0
    var userId: Long = 0
    var companyId: Long = 0
    var itemId: Long = 0
    var itemName: String? = null
    var itemImage: String? = null
    var itemPrice: Long = 0
    var created: Long = 0
}

@Mapper
interface MembersFavMapper {

    @Select(
        "<script>SELECT COUNT(*) FROM members_items_fav WHERE user_id = #{userId} AND company_id = #{companyId}" +
            "<if test='keyword != null'> AND item_name LIKE CONCAT('%', #{keyword}, '%')</if></script>",
    )
    fun count(
        @Param("userId") userId: Long,
        @Param("companyId") companyId: Long,
        @Param("keyword") keyword: String?,
    ): Long

    @Select(
        "<script>SELECT fav_id, user_id, company_id, item_id, item_name, item_image, item_price, created " +
            "FROM members_items_fav WHERE user_id = #{userId} AND company_id = #{companyId}" +
            "<if test='keyword != null'> AND item_name LIKE CONCAT('%', #{keyword}, '%')</if>" +
            " ORDER BY fav_id DESC LIMIT #{limit} OFFSET #{offset}</script>",
    )
    fun page(
        @Param("userId") userId: Long,
        @Param("companyId") companyId: Long,
        @Param("keyword") keyword: String?,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<FavDO>

    @Select("SELECT fav_id FROM members_items_fav WHERE user_id = #{userId} AND item_id = #{itemId} LIMIT 1")
    fun findId(@Param("userId") userId: Long, @Param("itemId") itemId: Long): Long?

    @Select("SELECT fav_id, user_id, company_id, item_id, item_name, item_image, item_price, created " +
        "FROM members_items_fav WHERE fav_id = #{favId}")
    fun findById(@Param("favId") favId: Long): FavDO?

    @Insert(
        "INSERT INTO members_items_fav(user_id, company_id, item_id, item_name, item_image, item_price, created) " +
            "VALUES(#{userId}, #{companyId}, #{itemId}, #{itemName}, #{itemImage}, #{itemPrice}, #{created})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "favId", keyColumn = "fav_id")
    fun insert(f: FavDO): Int

    @Delete("DELETE FROM members_items_fav WHERE user_id = #{userId} AND item_id = #{itemId}")
    fun delete(@Param("userId") userId: Long, @Param("itemId") itemId: Long): Int

    @Select("SELECT COUNT(*) FROM members_items_fav WHERE user_id = #{userId} AND company_id = #{companyId}")
    fun countByUser(@Param("userId") userId: Long, @Param("companyId") companyId: Long): Long
}

/**
 * 浏览轨迹：同一 (user,item) 只留最新一次（先删后插），列表按时间倒序。
 * 源系统另有 30 天清理任务，属运维脚本范畴，M0 不做。
 */
@Mapper
interface MembersBrowseMapper {

    @Select(
        "SELECT item_id, MAX(created) AS created FROM members_browse_history " +
            "WHERE user_id = #{userId} AND company_id = #{companyId} " +
            "GROUP BY item_id ORDER BY created DESC LIMIT #{limit} OFFSET #{offset}",
    )
    fun pageItemIds(
        @Param("userId") userId: Long,
        @Param("companyId") companyId: Long,
        @Param("offset") offset: Long,
        @Param("limit") limit: Int,
    ): List<BrowseRow>

    @Select("SELECT COUNT(DISTINCT item_id) FROM members_browse_history WHERE user_id = #{userId} AND company_id = #{companyId}")
    fun count(@Param("userId") userId: Long, @Param("companyId") companyId: Long): Long

    @Delete("DELETE FROM members_browse_history WHERE user_id = #{userId} AND item_id = #{itemId}")
    fun deleteItem(@Param("userId") userId: Long, @Param("itemId") itemId: Long): Int

    @Delete("DELETE FROM members_browse_history WHERE user_id = #{userId} AND company_id = #{companyId}")
    fun clear(@Param("userId") userId: Long, @Param("companyId") companyId: Long): Int

    @Insert(
        "INSERT INTO members_browse_history(user_id, item_id, company_id, created) " +
            "VALUES(#{userId}, #{itemId}, #{companyId}, #{created})",
    )
    fun insert(
        @Param("userId") userId: Long,
        @Param("itemId") itemId: Long,
        @Param("companyId") companyId: Long,
        @Param("created") created: Long,
    ): Int
}

class BrowseRow {
    var itemId: Long = 0
    var created: Long = 0
}
