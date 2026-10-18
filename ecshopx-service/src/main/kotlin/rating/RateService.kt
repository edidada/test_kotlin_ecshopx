package com.ecshopx.kotlin.service.rating

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.member.MembersInfoMapper
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.rating.PendingRateRow
import com.ecshopx.kotlin.dao.rating.RateRow
import com.ecshopx.kotlin.dao.rating.TradeRateDO
import com.ecshopx.kotlin.dao.rating.TradeRateMapper
import com.ecshopx.kotlin.dao.rating.TradeRateReplyDO
import com.ecshopx.kotlin.dao.rating.TradeRateReplyMapper
import com.ecshopx.kotlin.service.asset.blankToNull
import com.ecshopx.kotlin.service.asset.pageOf
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * M1-5 评价域（docs/04 §23.1 `RATE-F1..F5`、§23.2 `RATE-A1..A3`）。
 *
 * 这个类存在的理由有一半是补 M0 留下的洞：`orders_normal_orders.is_rate` 这一列
 * 建表就有、ORDER-8 的 `evaluation` 桶一直在读，但**全仓没有一个写口** ——
 * 于是"待评价数"恒等于"已完成数"。本批的 [submit] 是它第一次被真的写：
 * `UPDATE ... SET is_rate = TRUE WHERE order_id = ? AND is_rate = FALSE`，
 * 回 0 行就是重复提交（`RATE-R1`），所以这个闸门同时是幂等闸门。
 *
 * 四条纪律：
 *  1. **闸门顺序不能调**（§23.5 门槛③）：认单（非本人 404）→ 认状态（非 `DONE` 422）
 *     → 认 `items[]` 与订单行的对应关系（给了不在这一单里的 `item_id` → 422，**不静默丢弃**）
 *     → 最后才 CAS。先 CAS 会让"参数非法"的请求把订单标成已评价，会员下一次就评不了了；
 *     先判状态再判归属则会把别人的单的存在性泄露出去（所以是 404 不是 403）；
 *  2. **归属在落评价那刻定死**（C177 同一条纪律）：`distributor_id/shop_id` 从订单行带下来，
 *     不在读时 join 商品现挂哪家店 —— 加盟体系里"这条差评是谁家的"必须可追溯，
 *     也让 `RATE-A1/A2/A3` 的数据权限有列可判；
 *  3. **匿名只藏身份、不藏内容**（RATE-R3）：`anonymous=true` 的行对**别人**回 `匿名用户`、
 *     对**自己**回真名；后台面永远不匿名（datapass 只掩码手机号）；
 *  4. **下架不删行、不改 `is_rate`**（RATE-A3）：撤回后订单仍算已评价，
 *     否则用户可以拿同一条无限次重评、`evaluation` 桶还会来回跳。
 */
@Service
class RateService(
    private val rates: TradeRateMapper,
    private val replies: TradeRateReplyMapper,
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val members: MembersInfoMapper,
    private val mapper: ObjectMapper,
    private val db: Db,
) {

    /**
     * RATE-F1 提交评价（一单一提交、按行落）。
     *
     * `rate_count` 是**本次落库的行数**，不是这一单累计的行数：
     * 前台要的是"我这次提交成功了没"，累计数去 `RATE-F4/F5` 看。
     */
    fun submit(
        companyId: Long,
        userId: Long,
        form: RateSubmitForm,
    ): Map<String, Any?> {
        val orderId = form.orderId?.toLongOrNull() ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        // 越权回 404 而不是 403：403 等于承认"这一单存在且是别人的"（§23.5 门槛③）
        if (o.companyId != companyId || o.userId != userId) throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.orderStatus != OrderStatus.DONE) throw BizException(ErrorCode.BIZ, "订单完成后才能评价")
        val lines = form.items.orEmpty()
        if (lines.isEmpty()) throw BizException(ErrorCode.VALIDATION, "items 不能为空", mapOf("items" to listOf("不能为空")))
        val rowsByItem = orderItems.listByOrder(orderId).associateBy { it.itemId }
        val errors = linkedMapOf<String, List<String>>()
        lines.forEachIndexed { i, line ->
            val itemId = line.itemId
            if (itemId == null || itemId !in rowsByItem) {
                errors["items[$i].item_id"] = listOf("这一行不在该订单内")
            }
            val star = line.star
            if (star == null || star !in 1..5) {
                errors["items[$i].star"] = listOf("必须是 1..5")
            }
        }
        if (errors.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "评价参数非法", errors)
        val nickname = members.findByUserId(userId)?.username
        val orderIdStr = orderId.toString()
        return db.transactional {
            // CAS 排在所有校验之后：它是"这一单评过了"这个事实的唯一记录点
            if (orders.markRate(orderId, TimeSec.now()) == 0) throw BizException(ErrorCode.BIZ, "该订单已评价")
            var cnt = 0
            lines.forEach { line ->
                val row = rowsByItem.getValue(line.itemId!!)
                rates.insert(
                    TradeRateDO().apply {
                        this.companyId = companyId
                        // 函数形参与 DO 属性同名，必须用 this. 指向 DO —— 少了 this 就是给形参赋值
                        this.orderId = orderIdStr
                        itemId = row.itemId
                        goodsId = row.goodsId
                        this.userId = userId
                        star = line.star!!
                        content = blankToNull(line.content)
                        // 图片存 JSON 数组串（与 items.pics 同一惯例），出参再解析回数组
                        ratePic = line.ratePic?.takeIf { it.isNotEmpty() }?.let { mapper.writeValueAsString(it) }
                        anonymous = line.anonymous ?: false
                        this.nickname = nickname
                        // 归属从订单行带下来，不在读时现挂（C177）
                        distributorId = row.distributorId
                        shopId = row.shopId
                    },
                )
                cnt++
            }
            linkedMapOf("order_id" to orderIdStr, "rate_count" to cnt, "is_rate" to "true")
        }
    }

    /**
     * RATE-F2 商品评价列表（`item_id` 或 `distributor_id` 二选一必填）。
     *
     * 出参里**没有手机号**：这一面是给逛商品页的人看的，别人的联系方式与评价无关。
     * `order_id` 只在自己的行上出（`viewer` 与行的 `user_id` 相等），
     * 别人的订单号等于把他买过什么、什么时候买的告诉陌生人。
     */
    fun list(
        companyId: Long,
        raw: Map<String, String>,
        viewer: Long?,
    ): PageResult<Map<String, Any?>> {
        val itemId = raw["item_id"]?.toLongOrNull()
        val distributorId = raw["distributor_id"]?.toLongOrNull()
        if (itemId == null && distributorId == null) {
            throw BizException(ErrorCode.VALIDATION, "item_id 或 distributor_id 至少传一个", mapOf("item_id" to listOf("不能同时为空")))
        }
        val f = mapOf(
            "companyId" to companyId,
            "itemId" to itemId,
            "distributorId" to distributorId,
            "star" to raw["star"]?.toIntOrNull(),
            "hasPic" to (raw["has_pic"] == "1" || raw["has_pic"] == "true"),
        )
        return PageHelper.paginate(
            pageOf(raw),
            count = { rates.count(f) },
            query = { offset, limit -> rates.page(f, offset, limit) },
            map = { rows -> rows.map { frontView(it, viewer) } },
        )
    }

    /**
     * RATE-F3 评价详情。
     *
     * **只给本人**（非本人与不存在同样 404）：商品页看评价走 `RATE-F2`，
     * 这一条的用途是"我的评价"点进来看自己写过什么、商家回了什么。
     * 把它做成公开详情会让 `anonymous` 那一列失去意义 —— 下架/屏蔽的判定在 F2 的可见集上，
     * 而本条根本不过那道筛。
     */
    fun detail(
        companyId: Long,
        rateId: Long,
        userId: Long,
    ): Map<String, Any?> {
        val r = rates.findMine(rateId, companyId, userId) ?: throw BizException(ErrorCode.NOT_FOUND, "评价不存在")
        return linkedMapOf(
            "rate" to frontView(rowOf(r), userId),
            "replies" to replies.listByRate(rateId).map {
                linkedMapOf("role" to it.role, "content" to it.content, "created" to it.created)
            },
        )
    }

    /** RATE-F4 我的评价 */
    fun mine(
        companyId: Long,
        userId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val f = mapOf("companyId" to companyId, "userId" to userId, "includeDisabled" to true)
        return PageHelper.paginate(
            pageOf(raw),
            count = { rates.count(f) },
            query = { offset, limit -> rates.page(f, offset, limit) },
            map = { rows -> rows.map { frontView(it, userId) } },
        )
    }

    /**
     * RATE-F5 待评价行清单。
     *
     * 整单已评（`is_rate = true`）时直接回空集，**不再查缺集**：
     * `RATE-R1` 的 CAS 是整单位，会员只评了 3 行里的 1 行，这一单也算评完了。
     * 此时缺集里还剩 2 行，如果照常返回，前台会给出一条"提交即 422"的待评价行 ——
     * 那是把数据形状的问题转嫁成用户的困惑。补评剩下的行属 P2（要做就得连 CAS 粒度一起改）。
     */
    fun pending(
        companyId: Long,
        userId: Long,
        orderId: Long,
    ): Map<String, Any?> {
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.companyId != companyId || o.userId != userId) throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.isRate) return linkedMapOf("order_id" to orderId.toString(), "is_rate" to "true", "list" to emptyList<Map<String, Any?>>())
        val rows = rates.pendingItems(orderId, orderId.toString())
        return linkedMapOf(
            "order_id" to orderId.toString(),
            "is_rate" to "false",
            "list" to rows.map { pendingView(orderId.toString(), it) },
        )
    }

    /** RATE-A1 后台评价列表（datapass 判手机号，distributor 收窄判归属） */
    fun adminList(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
        full: Boolean,
    ): PageResult<Map<String, Any?>> {
        val f = mapOf(
            "companyId" to companyId,
            "orderId" to blankToNull(raw["order_id"]),
            "itemId" to raw["item_id"]?.toLongOrNull(),
            "userId" to raw["user_id"]?.toLongOrNull(),
            "star" to raw["star"]?.toIntOrNull(),
            "isReply" to when (raw["is_reply"]) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            },
            "hasPic" to (raw["has_pic"] == "1" || raw["has_pic"] == "true"),
            // 传了才置 true，没传留 null：`includeDisabled=false` 在 SQL 里是"非空"，
            // 那道隐藏下架行的 `<if == null>` 就永远不生效（默认列表把下架的也端出来）。
            // 判据同 `isReply`：三态筛（true / null）而不是布尔。
            "includeDisabled" to (raw["include_disabled"] == "1" || raw["include_disabled"] == "true").takeIf { it },
            "ids" to scope.narrow(listOfNotNull(blankToNull(raw["distributor_id"])?.toLongOrNull())),
        )
        return PageHelper.paginate(
            pageOf(raw),
            count = { rates.count(f) },
            query = { offset, limit -> rates.page(f, offset, limit) },
            map = { rows -> rows.map { adminView(it, full) } },
        )
    }

    /**
     * RATE-A2 商家回复（重复回复 = 覆盖最新，RATE-R4）。
     *
     * 越权回 404：`RATE-A2` 传别人的店的 `rate_id` 与"这个 rate_id 不存在"给同一个应答，
     * 免得把"哪家店有评价"枚举出来。
     */
    fun reply(
        companyId: Long,
        scope: DistributorScope,
        form: RateReplyForm,
    ): Map<String, Any?> {
        val rateId = form.rateId ?: throw BizException(ErrorCode.VALIDATION, "rate_id 必填", mapOf("rate_id" to listOf("不能为空")))
        val content = blankToNull(form.content)
            ?: throw BizException(ErrorCode.VALIDATION, "content 不能为空", mapOf("content" to listOf("不能为空")))
        val r = rates.find(rateId) ?: throw BizException(ErrorCode.NOT_FOUND, "评价不存在")
        if (r.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "评价不存在")
        scope.require(r.distributorId)
        val now = TimeSec.now()
        return db.transactional {
            // 先试着覆盖那条唯一的 seller 行；0 行说明还没回复过，才插新的
            if (replies.overwriteSeller(rateId, content, now) == 0) {
                replies.insert(
                    TradeRateReplyDO().apply {
                        this.rateId = rateId
                        role = "seller"
                        this.content = content
                        created = now
                    },
                )
            }
            rates.markReplied(rateId, now)
            linkedMapOf("rate_id" to rateId, "is_reply" to true, "content" to content, "created" to now)
        }
    }

    /** RATE-A3 上下架（只翻可见位，不删行、不动 is_rate） */
    fun setDisabled(
        companyId: Long,
        scope: DistributorScope,
        rateId: Long,
        disabled: Boolean,
    ): Map<String, Any?> {
        val r = rates.find(rateId) ?: throw BizException(ErrorCode.NOT_FOUND, "评价不存在")
        if (r.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "评价不存在")
        scope.require(r.distributorId)
        rates.transitDisabled(rateId, companyId, disabled, TimeSec.now())
        return linkedMapOf("rate_id" to rateId, "disabled" to disabled)
    }

    private fun frontView(
        r: RateRow,
        viewer: Long?,
    ): MutableMap<String, Any?> {
        val mine = viewer != null && viewer == r.userId
        return linkedMapOf(
            "rate_id" to r.rateId,
            "item_id" to r.itemId,
            "goods_id" to r.goodsId,
            "user_id" to r.userId,
            "star" to r.star,
            "content" to r.content,
            "rate_pic" to pics(r.ratePic),
            "anonymous" to r.anonymous,
            "is_reply" to r.isReply,
            // RATE-R3：匿名只对"别人"隐藏身份，自己看自己的行仍是真名
            "nickname" to if (r.anonymous && !mine) ANON else (r.nickname ?: if (mine) ANON_NONE else ANON),
            "created" to r.created,
            "seller_reply" to if (r.replyContent == null) null else linkedMapOf("content" to r.replyContent, "created" to r.replyCreated),
            // 别人的订单号不出（见类注释），自己的出
            "order_id" to if (mine) r.orderId else null,
        )
    }

    private fun adminView(
        r: RateRow,
        full: Boolean,
    ) = linkedMapOf<String, Any?>(
        "rate_id" to r.rateId,
        "order_id" to r.orderId,
        "item_id" to r.itemId,
        "goods_id" to r.goodsId,
        "user_id" to r.userId,
        "nickname" to r.nickname,
        "mobile" to (if (full) r.mobile else Mask.mobile(r.mobile)),
        "mobile_masked" to Mask.mobile(r.mobile),
        "star" to r.star,
        "content" to r.content,
        "rate_pic" to pics(r.ratePic),
        "anonymous" to r.anonymous,
        "is_reply" to r.isReply,
        "disabled" to r.disabled,
        "distributor_id" to r.distributorId,
        "shop_id" to r.shopId,
        "shop_name" to r.shopName,
        "item_name" to r.itemName,
        "created" to r.created,
        "seller_reply" to if (r.replyContent == null) null else linkedMapOf("content" to r.replyContent, "created" to r.replyCreated),
    )

    /** 行上没有 `order_id` 列（查询本来就按单号筛），出参按 §23.1 的形状从入参补上 */
    private fun pendingView(orderIdStr: String, r: PendingRateRow) = linkedMapOf<String, Any?>(
        "order_id" to orderIdStr,
        "id" to r.id,
        "item_id" to r.itemId,
        "goods_id" to r.goodsId,
        "goods_name" to r.itemName,
        "pics" to blankToNull(r.pic),
        "spec_desc" to r.itemSpecDesc,
        "num" to r.num,
    )

    /**
     * 图片列解析（RATE-R5 的 C137 教训）。
     *
     * 脏值（历史遗留的单串 URL、`null` 字面量、被截断的 JSON）一律回空集，
     * **不能把整行带崩**：一条评价解析失败就让整个列表 500，等于会员看不见任何评价。
     */
    private fun pics(raw: String?): List<String> {
        val v = blankToNull(raw) ?: return emptyList()
        return try {
            val parsed = mapper.readValue(v, object : TypeReference<List<Any?>>() {})
            parsed.mapNotNull { it?.toString() }
        } catch (e: Exception) {
            // 不是数组就当成"单个 URL 的历史写法"，原样给一条
            listOf(v)
        }
    }

    /** 详情面复用列表视图：把 DO 拍成 Row 的形状，省一份映射代码 */
    private fun rowOf(r: TradeRateDO) = RateRow().apply {
        rateId = r.rateId
        companyId = r.companyId
        orderId = r.orderId
        itemId = r.itemId
        goodsId = r.goodsId
        userId = r.userId
        star = r.star
        content = r.content
        ratePic = r.ratePic
        anonymous = r.anonymous
        isReply = r.isReply
        disabled = r.disabled
        distributorId = r.distributorId
        shopId = r.shopId
        nickname = r.nickname
        created = r.created
        updated = r.updated
    }

    companion object {
        /** RATE-R3 的匿名出参：库里没有"匿名用户"这一列，是渲染值 */
        const val ANON = "匿名用户"

        /** 自己看自己的匿名行时用的占位（真名为空时回落，不把昵称空缺出去） */
        private const val ANON_NONE = "我"
    }
}
