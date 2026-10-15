package com.ecshopx.kotlin.service.member

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.ItemDO
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.member.FavDO
import com.ecshopx.kotlin.dao.member.MembersBrowseMapper
import com.ecshopx.kotlin.dao.member.MembersFavMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * MEM-5/6 商品收藏 + 浏览足迹。
 *
 * `members_items_fav` 是**快照表**（下单时的 item_name/item_image/item_price 一样，收藏时也存当时的样子），
 * 但列表页必须显示**现价与实时库存**，所以出参时按 item_id 批量回查 items 一次（无 N+1），
 * 快照价另存 `fav_price` 供前端标"降价"。商品被删/下架不再静默过滤，而是带 `is_onsale = 0` 返回，
 * 与源系统一致——用户要看得到"我收藏的东西下架了"，而不是莫名少一条。
 */
@Component
class CollectService(
    private val favs: MembersFavMapper,
    private val browse: MembersBrowseMapper,
    private val items: ItemsMapper,
    private val mapper: ObjectMapper,
) {

    /** MEM-5 收藏列表（keyword 命中快照名，分页 1 基） */
    fun list(companyId: Long, userId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        val keyword = raw["keyword"]?.takeIf { it.isNotBlank() }
        return PageHelper.paginate(
            pq,
            count = { favs.count(userId, companyId, keyword) },
            query = { offset, limit -> favs.page(userId, companyId, keyword, offset, limit) },
            map = { rows ->
                val live = items.listByIds(rows.map { it.itemId }).associateBy { it.itemId }
                rows.map { favView(it, live[it.itemId]) }
            },
        )
    }

    /** MEM-6 收藏：重复收藏幂等（已存在直接回原行，不报错也不产生第二行） */
    fun add(companyId: Long, userId: Long, itemId: Long): Map<String, Any?> {
        val exists = favs.findId(userId, itemId)
        if (exists != null) return viewOf(exists, companyId, userId)
        val item = items.findById(itemId)?.takeIf { it.companyId == companyId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        favs.insert(
            FavDO().apply {
                this.userId = userId
                this.companyId = companyId
                this.itemId = item.itemId
                itemName = item.itemName
                itemImage = firstPic(item)
                itemPrice = item.price
                created = TimeSec.now()
            },
        )
        val id = favs.findId(userId, itemId) ?: throw BizException(ErrorCode.INTERNAL, "收藏写入失败")
        return viewOf(id, companyId, userId)
    }

    /** MEM-6 取消收藏：没收藏过也回成功（前端乐观 UI 的重复点击不该红框） */
    fun cancel(companyId: Long, userId: Long, itemId: Long): Map<String, Any?> {
        favs.delete(userId, itemId)
        return mapOf("item_id" to itemId, "is_collected" to 0)
    }

    /** 商品详情用：是否已收藏 + 收藏数（一次 COUNT，不读整表） */
    fun statusOf(companyId: Long, userId: Long, itemId: Long): Map<String, Any?> = mapOf(
        "item_id" to itemId,
        "is_collected" to (favs.findId(userId, itemId)?.let { 1 } ?: 0),
        "collect_count" to favs.countByUser(userId, companyId),
    )

    /**
     * 浏览足迹写入（GOODS-8 详情带会员令牌时调用）。
     * 先删后插保证同一 (user,item) 只留最新一条，列表天然按"最后看过"倒序。
     */
    fun recordBrowse(companyId: Long, userId: Long, itemId: Long) {
        browse.deleteItem(userId, itemId)
        browse.insert(userId, itemId, companyId, TimeSec.now())
    }

    fun browseList(companyId: Long, userId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        return PageHelper.paginate(
            pq,
            count = { browse.count(userId, companyId) },
            query = { offset, limit -> browse.pageItemIds(userId, companyId, offset, limit) },
            map = { rows ->
                val live = items.listByIds(rows.map { it.itemId }).associateBy { it.itemId }
                rows.map { r ->
                    linkedMapOf(
                        "item_id" to r.itemId,
                        "created" to r.created,
                        "item" to live[r.itemId]?.let { itemView(it) },
                    )
                }
            },
        )
    }

    fun deleteBrowse(userId: Long, itemId: Long): Map<String, Any?> {
        browse.deleteItem(userId, itemId)
        return mapOf("item_id" to itemId)
    }

    fun clearBrowse(companyId: Long, userId: Long): Map<String, Any?> {
        val n = browse.clear(userId, companyId)
        return mapOf("deleted" to n)
    }

    private fun viewOf(favId: Long, companyId: Long, userId: Long): Map<String, Any?> {
        val row = favs.findById(favId)?.takeIf { it.userId == userId && it.companyId == companyId }
            ?: throw BizException(ErrorCode.NOT_FOUND, "收藏不存在")
        return favView(row, items.findById(row.itemId)).plus("is_collected" to 1)
    }

    private fun favView(f: FavDO, live: ItemDO?): Map<String, Any?> = linkedMapOf(
        "fav_id" to f.favId,
        "item_id" to f.itemId,
        "item_name" to (live?.itemName ?: f.itemName ?: ""),
        "item_image" to (live?.let { firstPic(it) } ?: f.itemImage ?: ""),
        "item_price" to (live?.price ?: f.itemPrice),
        "fav_price" to f.itemPrice,
        "item_bn" to (live?.itemBn ?: ""),
        "store" to (live?.store ?: 0L),
        "is_onsale" to (live?.approveStatus == "onsale").let { if (it) 1 else 0 },
        "created" to f.created,
    )

    /** 实时视图只给列表需要的字段，避免把整个 GOODS-8 装配搬进会员域 */
    private fun itemView(i: ItemDO): Map<String, Any?> = linkedMapOf(
        "item_id" to i.itemId,
        "item_name" to i.itemName,
        "item_image" to firstPic(i),
        "price" to i.price,
        "store" to i.store,
        "approve_status" to i.approveStatus,
    )

    private fun firstPic(i: ItemDO): String = runCatching {
        mapper.readValue(i.pics ?: "[]", List::class.java)
            .filterIsInstance<String>()
            .firstOrNull()
            .orEmpty()
    }.getOrDefault("")
}
