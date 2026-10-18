package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.dao.distribution.ShopItemMapper
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import org.springframework.stereotype.Service

/**
 * 两个库存池的唯一出入口（docs/04 §18.4 的第②条硬约束）。
 *
 * §18.4 写的是"扣减与回补必须同一条腿对称"，这句话在代码里只有一种兑现方式：
 * **让扣和回补共用一个方法**。原来四处各自 `goodsItems.deductStock/restoreStock`，
 * 加了店铺池之后只要有哪一处忘了分流，就会出现"店铺卖光了、总部库存纹丝不动"，
 * 或者更糟的"取消一单把总部池加了两次"。所以这一层不是便利封装，是防漏的收口：
 *  1. `isTotalStore=TRUE` → `items.store`（总部池，M0-6 那条 CAS 一字未改）；
 *  2. `isTotalStore=FALSE` → `distribution_distributor_items.store`（店铺池，条件更新）；
 *  3. 两条腿都是**条件更新 + 返回 0 行即不足**，不做"读—判—写"，与营销域的额度 CAS 同一套纪律。
 *
 * 调用方只带 `(店, 品, 件数, is_total_store)` 四个数：池子归属是订单明细行自己的事实
 * （`orders_normal_items.is_total_store` + `distributor_id`），回补时读它，不回读挂品行 ——
 * 挂品行可能在两单之间被改被删，订单行才是当时的判定（与 D46 的"按行快照"同一族理由）。
 */
@Service
class StockPool(
    private val items: ItemsMapper,
    private val shopItems: ShopItemMapper,
) {

    /**
     * 扣减。`isTotalStore=false` 时 `distributorId` 必须是**这行商品所属的那家店**
     * （建单侧由 `PriceLine.shopId` 落进明细行，不是商品的 `items.distributor_id`），
     * 否则回补会去动别人家的池子。
     */
    fun deduct(
        distributorId: Long,
        itemId: Long,
        num: Long,
        isTotalStore: Boolean,
        itemName: String,
        now: Long,
    ) {
        if (num <= 0) return
        val hit = if (isTotalStore) {
            items.deductStock(itemId, num, now)
        } else {
            shopItems.deductShop(distributorId, itemId, num)
        }
        if (hit == 0) {
            throw BizException(
                ErrorCode.BIZ,
                if (isTotalStore) "商品库存不足：$itemName" else "店铺库存不足：$itemName",
                mapOf("item_id" to listOf(if (isTotalStore) "库存不足，请减少数量" else "该店铺库存不足，请减少数量")),
            )
        }
    }

    /**
     * 回补。与 [deduct] 逐字同一条判据，只是不设上限 ——
     * 调用方必须先做订单状态 CAS（谁都在防的那句"重复回补"，闸门在订单态而不在库存行）。
     */
    fun restore(
        distributorId: Long,
        itemId: Long,
        num: Long,
        isTotalStore: Boolean,
        now: Long,
    ) {
        if (num <= 0) return
        if (isTotalStore) items.restoreStock(itemId, num, now) else shopItems.restoreShop(distributorId, itemId, num)
    }
}
