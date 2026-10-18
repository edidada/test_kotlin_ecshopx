package com.ecshopx.kotlin.service.promotion

import com.ecshopx.kotlin.common.domain.OrderClass
import com.ecshopx.kotlin.dao.promotion.GroupsActivityMapper
import com.ecshopx.kotlin.dao.promotion.GroupsMemberMapper
import com.ecshopx.kotlin.dao.promotion.SeckillActivityMapper
import com.ecshopx.kotlin.dao.promotion.SeckillGoodsMapper
import org.springframework.stereotype.Service

/**
 * 秒杀 / 拼团的**活动价视图**（docs/04 §14.5 的 order 15 输入）。
 *
 * 与满减的 [MarketingOffer] 同一个道理：算价规则链必须是纯函数，所以活动行、库存余量、
 * 本人已购这些"要查库才能知道的事"全部在这里取完，规则只看这个对象。
 *
 * 两种活动在这个模型里的差别只落在三个字段上，规则链因此不需要为它们分支：
 *  - 秒杀圈的是 `item_id`（一个场次多个 SKU），限购按**件数**（`limit_num`）；
 *  - 拼团圈的是 `goods_id`（一活动一商品），限购按**参团次数**（`limit_buy_num`，见 [boughtUnit]）；
 *  - `available`：秒杀 = `activity_store − sales_store − hold_store`（D24 三列池），拼团 = 活动剩余 `store`。
 */
data class ActivityOffer(
    /** `seckill_id` / `groups_activity_id` */
    val activityId: Long,
    /** [OrderClass].SECKILL / [OrderClass].GROUPS */
    val activityType: String,
    val activityName: String,
    val itemId: Long,
    val goodsId: Long,
    /** 活动成交价（分），覆盖行单价用 */
    val activityPrice: Long,
    /** 商品现价（分）：出参 `origin_price` 与"省了多少"的展示口径 */
    val originPrice: Long,
    /** 此刻还能卖多少（件 / 次） */
    val available: Long,
    /** 本人限购：秒杀是件数、拼团是次数；0=不限 */
    val limitNum: Int,
    /** 本人已用量（与 [limitNum] 同单位） */
    val bought: Long,
    /** 秒杀专用：下单后必须付款的分钟数（D24 用它代替 Redis 资格票）；0=不限 */
    val validityPeriod: Int = 0,
    /** 活动结束时间（秒）：`auto_cancel_time` 的第二个上限 */
    val endTime: Long = 0,
    /** 秒杀专用：本场次单笔限额（分），0=不限（下单侧 `total_fee > 限额` 直接拒） */
    val limitMoney: Long = 0,
    /** 拼团专用：成团人数（前台列表与开团校验都要） */
    val personNum: Int = 0,
) {
    /** 限购的单位文案（前台要说清"限购 2 件"还是"限购 1 次"） */
    val boughtUnit: String get() = if (activityType == OrderClass.GROUPS) "次" else "件"

    /** 本人还能用多少：`limit=0` 时视为不限，返回 [Long.MAX_VALUE] 让调用方直接比大小 */
    fun remainForMe(): Long = if (limitNum <= 0) Long.MAX_VALUE else (limitNum - bought).coerceAtLeast(0L)

    /** 覆盖是否成立：价必须比现价低（活动价配错成原价以上时不改基准，免得算出一个负折让） */
    fun covers(): Boolean = activityPrice in 1 until originPrice
}

/** 一条"申报了活动"的车内/直购行 —— 由 `CartService` 从 [com.ecshopx.kotlin.service.cart.PriceLine] 投影出来 */
data class ActivityLineKey(
    val activityType: String,
    val activityId: Long,
    val itemId: Long,
    val goodsId: Long,
    val price: Long,
)

/**
 * 活动预取（唯一的取数点，被 `CartService.buildContext` 调用）。
 *
 * 三条口径：
 *  1. **只认申报了活动的行**：`activity_type` 是 `normal` 的行永远拿不到活动价。
 *     秒杀入口在活动的商详页，加购/下单时必须带上 `activity_type/activity_id`（CART-1 早就接收这两个字段），
 *     静默给全场最低价等于把秒杀当满减用，池子会被普通单吃光；
 *  2. **在窗判定在这里做**（`listRunning` 已带 `disabled=FALSE AND start<=now<=end`），
 *     规则链读不到"已结束但库里还在"的行；
 *  3. **一行最多命中一个活动**：秒杀按 `(场次, 商品)` 唯一（`uk_seckill_goods`），拼团按 `(活动, goods)` 唯一。
 */
@Service
class ActivityOfferService(
    private val seckillActs: SeckillActivityMapper,
    private val seckillGoods: SeckillGoodsMapper,
    private val groupsActs: GroupsActivityMapper,
    private val groupsMembers: GroupsMemberMapper,
) {


    fun offers(
        companyId: Long,
        userId: Long,
        lines: List<ActivityLineKey>,
        now: Long,
    ): List<ActivityOffer> {
        if (lines.isEmpty()) return emptyList()
        val out = mutableListOf<ActivityOffer>()
        val seckill = lines.filter { it.activityType == OrderClass.SECKILL && it.activityId > 0 }
        if (seckill.isNotEmpty()) out += seckillOffers(companyId, userId, seckill, now)
        val groups = lines.filter { it.activityType == OrderClass.GROUPS && it.activityId > 0 }
        if (groups.isNotEmpty()) out += groupsOffers(companyId, userId, groups, now)
        return out
    }

    private fun seckillOffers(
        companyId: Long,
        userId: Long,
        lines: List<ActivityLineKey>,
        now: Long,
    ): List<ActivityOffer> {
        val acts = seckillActs.listRunning(companyId, now).associateBy { it.seckillId }
        if (acts.isEmpty()) return emptyList()
        val wanted = lines.filter { acts.containsKey(it.activityId) }
        if (wanted.isEmpty()) return emptyList()
        val rows = seckillGoods
            .listOffer(acts.keys.toList(), wanted.map { it.itemId }.distinct())
            .associateBy { it.seckillId to it.itemId }
        return wanted.mapNotNull { k ->
            val act = acts[k.activityId] ?: return@mapNotNull null
            val g = rows[k.activityId to k.itemId] ?: return@mapNotNull null
            if (!g.isShow || g.disabled) return@mapNotNull null
            val bought = seckillGoods.boughtByUser(companyId, userId, act.seckillId, k.itemId)
            ActivityOffer(
                activityId = act.seckillId,
                activityType = OrderClass.SECKILL,
                activityName = act.activityName,
                itemId = k.itemId,
                goodsId = k.goodsId,
                activityPrice = g.activityPrice,
                originPrice = k.price,
                available = (g.activityStore - g.salesStore - g.holdStore).coerceAtLeast(0L),
                limitNum = g.limitNum,
                bought = bought,
                validityPeriod = act.validityPeriod,
                endTime = act.activityEndTime,
                limitMoney = act.limitMoney,
            )
        }
    }

    private fun groupsOffers(
        companyId: Long,
        userId: Long,
        lines: List<ActivityLineKey>,
        now: Long,
    ): List<ActivityOffer> {
        val acts = groupsActs.listRunning(companyId, now).associateBy { it.groupsActivityId }
        if (acts.isEmpty()) return emptyList()
        return lines.mapNotNull { k ->
            val a = acts[k.activityId] ?: return@mapNotNull null
            // 一个拼团活动只圈一个 goods_id（源系统同形）：goods 对不上就是这场活动与本行无关
            if (a.goodsId != k.goodsId || a.disabled) return@mapNotNull null
            ActivityOffer(
                activityId = a.groupsActivityId,
                activityType = OrderClass.GROUPS,
                activityName = a.actName,
                itemId = k.itemId,
                goodsId = a.goodsId,
                activityPrice = a.actPrice,
                originPrice = k.price,
                available = a.store.coerceAtLeast(0L),
                limitNum = a.limitBuyNum,
                bought = groupsMembers.countJoinedInAct(a.groupsActivityId, userId),
                endTime = a.endTime,
                personNum = a.personNum,
            )
        }
    }
}
