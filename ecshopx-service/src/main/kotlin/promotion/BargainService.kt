package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.ItemDO
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.promotion.BargainPromotionDO
import com.ecshopx.kotlin.dao.promotion.BargainPromotionMapper
import com.ecshopx.kotlin.dao.promotion.UserBargainDO
import com.ecshopx.kotlin.dao.promotion.UserBargainLogDO
import com.ecshopx.kotlin.dao.promotion.UserBargainLogMapper
import com.ecshopx.kotlin.dao.promotion.UserBargainMapper
import com.ecshopx.kotlin.service.tx.Db
import org.springframework.stereotype.Service

/**
 * M2-3 砍价域（docs/04 §29.2：后台 `BG-A1..A5` + 会员面 `BG-F1..F5`）。
 *
 * 四条纪律：
 *  1. **刀是定长的**：每刀减 `ceil((item_price − floor_price) / steps)` 分，末刀钳到地板价。
 *     定长比"随机刀"可解释（页面能预告"每一刀大约砍多少"），随机性留给抽奖域——两个域别混一个味；
 *  2. **剩余刀数是派生值**：`steps_left = steps − user_bargain_log 行数`，不落列
 *     （与互动域"次数是派生值"同一条纪律，DAO/BargainMappers.kt 注记 1）；
 *  3. **幂等长在 uk 上**：`uk(company, bargain, user)` 承诺"同一活动同一会员一份刀"，
 *     并发双击第二次以重复键失败回业务错，不是先查后插；
 *  4. **成单链路不接**（§29.2 C224）：`reached` 只是资格位（页面可以出"按底价下单"），
 *     快照与下单链的接入单独立批 —— 本批不动 cart/order 任何一行。
 */
@Service
class BargainService(
    private val bargains: BargainPromotionMapper,
    private val ubgs: UserBargainMapper,
    private val logs: UserBargainLogMapper,
    private val items: ItemsMapper,
    private val db: Db,
) {

    // ===================== BG-A1..A5 后台 =====================

    /** BG-A1 创建（item_price 缺省取商品现价；floor < price、steps >= 1 是硬校验） */
    fun save(
        companyId: Long,
        itemId: Long,
        itemPriceParam: Long?,
        floorPrice: Long,
        steps: Int,
    ): Map<String, Any?> {
        val item = item(companyId, itemId)
        val start = itemPriceParam ?: item.price
        if (start <= floorPrice) throw BizException(
            ErrorCode.VALIDATION, "底价必须低于原价",
            mapOf("floor_price" to listOf("floor_price < item_price")),
        )
        if (steps <= 0) throw BizException(
            ErrorCode.VALIDATION, "刀数必须为正整数",
            mapOf("steps" to listOf("steps >= 1")),
        )
        val draft = BargainPromotionDO().apply {
            this.companyId = companyId
            this.itemId = itemId
            this.itemPrice = start
            this.floorPrice = floorPrice
            this.steps = steps
            disabled = false
        }
        bargains.insert(draft)
        return view(bargains.find(companyId, draft.id)!!)
    }

    /** BG-A2 列表（含停用行——后台要看得见自己停掉的，前台那份是篮过的） */
    fun adminPage(companyId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { bargains.count(companyId) },
        query = { off, lim -> bargains.page(companyId, off, lim) },
        map = { rows -> rows.map { view(it) } },
    )

    fun adminDetail(companyId: Long, id: Long): Map<String, Any?> = view(act(companyId, id))

    /** BG-A3 编辑（覆盖式：给出的字段生效，缺失 = 不动） */
    fun update(
        companyId: Long,
        id: Long,
        itemId: Long?,
        itemPriceParam: Long?,
        floorPrice: Long?,
        steps: Int?,
        disabled: Boolean?,
    ): Map<String, Any?> {
        val cur = act(companyId, id)
        if (steps != null && steps <= 0) throw BizException(
            ErrorCode.VALIDATION, "刀数必须为正整数", mapOf("steps" to listOf("steps >= 1")),
        )
        val start = itemPriceParam ?: cur.itemPrice
        val floor = floorPrice ?: cur.floorPrice
        if (start <= floor) throw BizException(
            ErrorCode.VALIDATION, "底价必须低于原价", mapOf("floor_price" to listOf("floor_price < item_price")),
        )
        bargains.update(
            BargainPromotionDO().apply {
                this.id = cur.id
                this.companyId = companyId
                this.itemId = itemId ?: cur.itemId
                this.itemPrice = start
                this.floorPrice = floor
                this.steps = steps ?: cur.steps
                this.disabled = disabled ?: cur.disabled
                updated = TimeSec.now()
            },
        )
        return view(act(companyId, id))
    }

    /** BG-A4 终止：停止发刀但保留行与刀史（删除才动行，且有 A5 的守卫） */
    fun terminate(companyId: Long, id: Long): Map<String, Any?> {
        val cur = act(companyId, id)
        bargains.terminate(cur.id, companyId, TimeSec.now())
        return view(act(companyId, id))
    }

    /** BG-A5 删除：有会员（user_bargain）参与则拒绝——行上连着刀史，物理删等于把别人的账删掉 */
    fun delete(companyId: Long, id: Long): Map<String, Any?> {
        val cur = act(companyId, id)
        if (ubgs.countByBargain(companyId, cur.id) > 0) throw BizException(
            ErrorCode.BIZ, "已有用户参与该砍价，不能删除（可终止下架）",
            mapOf("bargain_id" to listOf("已有用户参与")),
        )
        bargains.delete(cur.id, companyId)
        return linkedMapOf("bargain_id" to id, "deleted" to true)
    }

    // ===================== BG-F1..F5 会员面 =====================

    /** BG-F1 列表（只回未停用） */
    fun frontPage(companyId: Long, pq: PageQuery): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pq,
        count = { bargains.countActive(companyId) },
        query = { off, lim -> bargains.pageActive(companyId, off, lim) },
        map = { rows -> rows.map { frontRow(it) } },
    )

    /** BG-F2 参与砍价（每会员每活动一份刀；发起价 = 原价，剩余刀数 = 全部） */
    fun join(companyId: Long, userId: Long, bargainId: Long): Map<String, Any?> {
        val act = bargains.find(companyId, bargainId) ?: throw BizException(ErrorCode.NOT_FOUND, "砍价活动不存在")
        if (act.disabled) throw BizException(ErrorCode.BIZ, "砍价活动已结束")
        if (ubgs.findByUser(companyId, bargainId, userId) != null) {
            throw BizException(ErrorCode.BIZ, "已参与过该砍价")
        }
        return db.transactional {
            val ub = UserBargainDO().apply {
                this.companyId = companyId
                this.userId = userId
                this.bargainId = bargainId
                currentPrice = act.itemPrice
                status = "going"
            }
            ubgs.insert(ub)
            detailView(ub, act)
        }
    }

    /** BG-F3 详情（frontnoauth 面：游客也能围观帮砍页；刀史逐条回显） */
    fun userDetail(companyId: Long, ubgId: Long): Map<String, Any?> {
        val ub = ubgs.find(companyId, ubgId) ?: throw BizException(ErrorCode.NOT_FOUND, "砍价单不存在")
        val act = bargains.find(companyId, ub.bargainId) ?: throw BizException(ErrorCode.NOT_FOUND, "砍价活动不存在")
        return detailView(ub, act)
    }

    /** BG-F4 帮砍一刀（定长折减、末刀钳地板；同一刀不再拆零头） */
    fun cut(
        companyId: Long,
        helperId: Long,
        ubgId: Long,
    ): Map<String, Any?> {
        val ub = ubgs.find(companyId, ubgId) ?: throw BizException(ErrorCode.NOT_FOUND, "砍价单不存在")
        val act = bargains.find(companyId, ub.bargainId) ?: throw BizException(ErrorCode.NOT_FOUND, "砍价活动不存在")
        return db.transactional {
            val stepsLeft = act.steps - logs.countByUbg(companyId, ub.id).toInt()
            if (ub.status == "reached") throw BizException(ErrorCode.BIZ, "已砍到底价")
            if (stepsLeft <= 0) throw BizException(ErrorCode.BIZ, "刀数已用完")
            val before = ub.currentPrice
            val after = maxOf(act.floorPrice, before - stepOf(act.itemPrice, act.floorPrice, act.steps))
            ub.currentPrice = after
            if (after <= act.floorPrice) ub.status = "reached"
            ubgs.update(ub)
            logs.insert(
                UserBargainLogDO().apply {
                    this.companyId = companyId
                    this.userBargainId = ub.id
                    this.userId = helperId
                    cutFee = before - after
                    beforePrice = before
                    this.afterPrice = after
                },
            )
            detailView(ub, act).toMutableMap().apply {
                put("cut_fee", before - after)
                put("reached", ub.status == "reached")
            }
        }
    }

    // ===================== 内部件与出参 =====================

    /** 每刀减价 = ceil(总可砍额 / 总刀数)；Long 数学自己搬，不落 floating */
    private fun stepOf(start: Long, floor: Long, steps: Int): Long {
        val span = start - floor
        val q = span / steps
        return if (span % steps == 0L) q else q + 1
    }

    private fun act(companyId: Long, id: Long): BargainPromotionDO =
        bargains.find(companyId, id) ?: throw BizException(ErrorCode.NOT_FOUND, "砍价活动不存在")

    private fun item(companyId: Long, itemId: Long): ItemDO {
        val it = items.findById(itemId) ?: throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        if (it.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "商品不存在")
        return it
    }

    /** 后台行视图 */
    private fun view(r: BargainPromotionDO): Map<String, Any?> {
        val (title, pic) = itemSnap(r.itemId)
        return linkedMapOf(
            "bargain_id" to r.id,
            "item_id" to r.itemId,
            "item_title" to title,
            "pic" to pic,
            "item_price" to r.itemPrice,
            "floor_price" to r.floorPrice,
            "steps" to r.steps,
            "disabled" to r.disabled,
            "joined_users" to ubgs.countByBargain(r.companyId, r.id),
            "created" to r.created,
        )
    }

    private fun frontRow(r: BargainPromotionDO): Map<String, Any?> {
        val (title, pic) = itemSnap(r.itemId)
        return linkedMapOf(
            "bargain_id" to r.id,
            "item_id" to r.itemId,
            "item_title" to title,
            "pic" to pic,
            "item_price" to r.itemPrice,
            "floor_price" to r.floorPrice,
            "steps" to r.steps,
            "joined_users" to ubgs.countByBargain(r.companyId, r.id),
        )
    }

    /** 详情视图（参与/帮砍/围观共用同一套键：缺失即失败、多出只回显） */
    private fun detailView(ub: UserBargainDO, act: BargainPromotionDO): Map<String, Any?> {
        val used = logs.countByUbg(ub.companyId, ub.id)
        val history = logs.listByUbg(ub.companyId, ub.id).map { l ->
            linkedMapOf(
                "user_id" to l.userId,
                "cut_fee" to l.cutFee,
                "after_price" to l.afterPrice,
                "created" to l.created,
            )
        }
        val (title, pic) = itemSnap(act.itemId)
        return linkedMapOf(
            "user_bargain_id" to ub.id,
            "bargain_id" to act.id,
            "status" to ub.status,
            "item_id" to act.itemId,
            "item_title" to title,
            "pic" to pic,
            "item_price" to act.itemPrice,
            "floor_price" to act.floorPrice,
            "steps" to act.steps,
            "steps_left" to act.steps - used.toInt(),
            "current_price" to ub.currentPrice,
            "logs" to history,
        )
    }

    /** 商品快照（title=pics 首图），pics 是 JSON 数组字符串 */
    private fun itemSnap(itemId: Long): Pair<String, String?> {
        val it = items.findById(itemId)
        return if (it == null) {
            "" to null
        } else {
            it.itemName to firstPic(it.pics)
        }
    }

    private fun firstPic(pics: String?): String? =
        pics?.trimStart('[')?.trimEnd(']')?.split(',')?.firstOrNull()?.trim(' ', '"')
}
