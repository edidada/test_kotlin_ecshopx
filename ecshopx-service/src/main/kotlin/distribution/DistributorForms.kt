package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.service.auth.Kind
import com.ecshopx.kotlin.service.auth.Principal
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * M1-4 店铺与分销域入参（docs/04 §18.1/§18.2）。
 *
 * 与营销域同一口径：snake_case 绑定、字段全可空、**校验只在服务层**（Bean Validation 的
 * 报错形状不是源系统的 `{message, errors}`）。金额与库存一律整数（分 / 件），不允许「元」。
 */

/** DS-A1 建店 / DS-A4 改店（同一张表单，`distributor_id` 只在改店时用） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DistributorForm(
    val distributorId: Long? = null,
    val name: String? = null,
    val mobile: String? = null,
    val province: String? = null,
    val city: String? = null,
    val area: String? = null,
    val address: String? = null,
    val lng: String? = null,
    val lat: String? = null,
    val logo: String? = null,
    val banner: String? = null,
    val introduce: String? = null,
    /** 0 自营（全场唯一）/ 1 加盟；建后不可改（D42） */
    val distributionType: Int? = null,
    val isZiti: Boolean? = null,
    val isDelivery: Boolean? = null,
    val isSelfDelivery: Boolean? = null,
    val autoSyncGoods: Boolean? = null,
    val shopId: Long? = null,
    /** 0 平台收款 / 1 店铺收款（DS-A1 建店时可给，改店走 DS-A7） */
    val paymentSubject: Int? = null,
    /** 平台服务费率，万分比 0..10000 */
    val rate: Int? = null,
    /** DS-A1 专用：建店即审（缺省 true）；改店时忽略，审核位只由 DS-A5 动 */
    val reviewStatus: Boolean? = null,
)

/** DS-A5 审核 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DistributorReviewForm(
    val reviewStatus: Boolean? = null,
    val reason: String? = null,
)

/** DS-A6 运营开关（三态字符串，照抄源系统列语义） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DistributorStatusForm(val isValid: String? = null)

/** DS-A7 收款主体与费率 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class DistributorSubjectForm(
    val paymentSubject: Int? = null,
    val rate: Int? = null,
)

/** SI-A1 挂品：整表按 (店, 商品) upsert（与 SK-A6 同一口径） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ShopItemsForm(
    val distributorId: Long? = null,
    val items: List<ShopItemLine>? = null,
)

/**
 * 挂品行。`price` 不给（或 0）= 跟随总部现价；`is_total_store` 不给 = TRUE（扣总部池），
 * 与源系统"新挂品默认共用总店库存"一致，也让批量挂品时少写一栏。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ShopItemLine(
    val itemId: Long? = null,
    val price: Long? = null,
    val store: Long? = null,
    val isShow: Boolean? = null,
    val isCanSale: Boolean? = null,
    val isTotalStore: Boolean? = null,
)

/** SI-A3 配置价格/库存/上下架（`store` 是**绝对值**，与 SK-A5 的增量口径不同） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ShopItemConfigForm(
    val distributorId: Long? = null,
    val itemId: Long? = null,
    val price: Long? = null,
    val store: Long? = null,
    val isShow: Boolean? = null,
    val isCanSale: Boolean? = null,
    val isTotalStore: Boolean? = null,
)

/** SI-A4 取消挂品：只删关联行，不动 `items`，也不动订单历史快照 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class ShopItemDeleteForm(
    val distributorId: Long? = null,
    val itemIds: List<Long>? = null,
)

/**
 * 数据权限收窄（docs/05 §M1-4 门槛用例②：「`distributor_id` 过滤越权」）。
 *
 * 三条判据：
 *  - `operator_type='admin'` → [unrestricted]，看全场（独立部署的默认形态）；
 *  - 其余类型（`distributor` 等）→ 只看 `operators.distributor_ids` 绑定的那几家店；
 *    **空集合就是看不见任何店**，不是"不筛" —— 把空当成不筛等于给未配置的账号开了全场权限；
 *  - 越界的显式 id 报 **404** 而不是 403：不给"这家店存在但不给你看"这种可枚举的信息
 *    （与商品域"别人的商品就是不存在的商品"同一条纪律）。
 *
 * `viewer`（seed 里无角色）根本进不到这里 —— `@RequiresResource` 先把它挡成 403。
 */
data class DistributorScope(val ids: List<Long>?) {
    val unrestricted: Boolean get() = ids == null

    fun allows(id: Long): Boolean = ids == null || id in ids

    /**
     * 列表侧的 `distributor_id` 过滤：与可见集合取交集，交集为空即"这一页没有任何一行可看"。
     *
     * **空集合与 `null` 同义 = "这次没指名要哪家店"**，一律回落成整个可见集合。
     * 调用方拼这个入参时很容易写出 `listOfNotNull(raw[...]?.toLongOrNull())` —— 没传时它是 `[]` 而不是 null，
     * 若按"空集合就是不要任何店"解释，店铺账号的列表会当场变成 0 行（症状与"他店里真没人"一模一样，最难查）。
     * 要表达"什么都不给看"请用 [DistributorScope]`(emptyList())` 构造本身，而不是传一个空筛选。
     */
    fun narrow(requested: List<Long>?): List<Long>? {
        if (ids == null) return requested?.takeIf { it.isNotEmpty() }
        val r = if (requested.isNullOrEmpty()) ids else ids.filter { it in requested }
        return if (r.isEmpty()) listOf(UNWATCHABLE) else r
    }

    /** 交集为空时塞一个永不可能的 id，让 SQL 自然回 0 行而不是拼出 `IN ()` */
    fun require(id: Long) {
        if (!allows(id)) throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
    }

    companion object {
        private const val UNWATCHABLE = -1L
        val ALL = DistributorScope(null)

        fun of(operatorType: String?, distributorIds: List<Long>): DistributorScope =
            if (operatorType == "admin") ALL else DistributorScope(distributorIds)

        /**
         * 从令牌取数据权限。
         *
         * `admin` 型（seed 的 admin 账号）看全场；`distributor`/`staff` 型只看
         * `operators.distributor_ids` 绑定的店。这里**不认"没绑店铺=全场"** ——
         * 一个新建成、还没配店铺的分销商账号应当什么都看不见，而不是顺手拿到全场数据。
         */
        fun ofPrincipal(p: Principal?): DistributorScope =
            if (p == null || p.kind != Kind.OPERATOR) ALL else of(p.operatorType, p.distributorIds)
    }
}
