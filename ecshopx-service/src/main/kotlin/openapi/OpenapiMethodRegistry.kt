package com.ecshopx.kotlin.service.openapi

import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.auth.MemberListRow
import com.ecshopx.kotlin.dao.auth.MembersMapper
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.service.asset.DepositService
import com.ecshopx.kotlin.service.asset.PointService
import com.ecshopx.kotlin.service.asset.PointAdjustForm
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.distribution.DistributorService
import com.ecshopx.kotlin.service.goods.ItemService
import com.ecshopx.kotlin.service.member.MemberService
import com.ecshopx.kotlin.service.order.DeliveryItemForm
import com.ecshopx.kotlin.service.order.OrderDeliveryForm
import com.ecshopx.kotlin.service.order.OrderFlowService
import com.ecshopx.kotlin.service.order.OrderQueryService
import com.ecshopx.kotlin.service.order.WriteOffService
import com.ecshopx.kotlin.service.promotion.CouponService
import com.ecshopx.kotlin.service.promotion.CouponSendForm
import org.springframework.stereotype.Component

/**
 * 一次网关调用的身份上下文（验签通过之后才有值）。
 *
 * `companyId` **只来自密钥行**，不接受入参（§25.1 第 9 道闸门 fail-closed）；
 * `developerId` 进留痕与投递归属，`appKey` 只用于日志文本。
 */
data class OpenapiCaller(
    val developerId: Long,
    val companyId: Long,
    val appKey: String,
)

/**
 * method 注册表（M2-1 §25.2）。
 *
 * 源系统这张表是 `Traits/OpenapiRoutes.php` 里 `version → 'ecx.x.y.z' → 控制器@方法` 的常量数组，
 * 一共 148 条；本项目**不照搬那张表的规模**，只收既有实现撑得住的 19 条（§25.2 首段的裁定）。
 * 加一条 method 只该动这张表 + 一个委托函数，不该再开第二个入口 ——
 * 入口多了，"哪些面走验签、哪些面走令牌"就会重新变成需要人记住的事，而不是代码能回答的事。
 *
 * 两条形状上的说明：
 * - `versions` 是集合而不是单个版本：`ecx.order.list` 在源系统 v1/v2 各有一份实现，
 *   本项目两条读口其实是同一件事，**不为同名 method 写两套读口**（两套必然漂开）；
 * - `notFound` 是"委托抛 NOT_FOUND/NO_PERM 时对外回什么"的映射表项：
 *   跨租户与不存在必须同码同文案，否则开放面会变成"这一单在不在别的租户"的探测器。
 */
@Component
class OpenapiMethodRegistry(
    orders: OrderQueryService,
    flow: OrderFlowService,
    writeOff: WriteOffService,
    items: ItemService,
    itemsMapper: ItemsMapper,
    members: MemberService,
    membersMapper: MembersMapper,
    points: PointService,
    deposits: DepositService,
    coupons: CouponService,
    distributors: DistributorService,
) {

    /** 一条 method 的注册项 */
    data class Entry(
        val id: String,
        val method: String,
        val verb: String,
        val versions: Set<String>,
        val summary: String,
        /** 全部必填（缺任一条 → `E4001`，在委托之前判） */
        val required: List<String> = emptyList(),
        /** 其一必填（全缺 → `E4001`） */
        val requiredAny: List<String> = emptyList(),
        val notFound: OpenapiError? = null,
        val handler: (OpenapiRequest, OpenapiCaller) -> Any?,
    )

    /** 域错误映射项：委托抛 NOT_FOUND / NO_PERM 时的对外码与文案 */
    data class OpenapiError(val code: String, val message: String)

    private val all: List<Entry> = listOf(
        Entry(
            id = "OA-M1", method = "ecx.order.list", verb = GET, versions = setOf(V1, V2),
            summary = "按会员查订单列表", required = listOf("user_id"),
        ) { req, caller -> orders.list(req.raw(), caller.companyId, req.requireLong("user_id")) },

        Entry(
            id = "OA-M2", method = "ecx.orders.sold.get", verb = GET, versions = setOf(V2),
            summary = "全租户订单搜索面（不锁会员）",
        ) { req, caller -> orders.list(req.raw(), caller.companyId, null) },

        Entry(
            id = "OA-M3", method = "ecx.order.get", verb = GET, versions = setOf(V2),
            summary = "订单详情", requiredAny = listOf("order_id", "order_bn"),
            notFound = OpenapiError(OpenapiCode.ORDER_NOT_FOUND, "订单不存在"),
        ) { req, caller -> orders.detail(orderIdOf(req), null) },

        Entry(
            id = "OA-M4", method = "ecx.order.deliver", verb = POST, versions = setOf(V2),
            summary = "发货", required = listOf("order_id"),
            notFound = OpenapiError(OpenapiCode.ORDER_NOT_FOUND, "订单不存在"),
        ) { req, caller ->
            flow.ship(
                req.requireLong("order_id"),
                OrderDeliveryForm(
                    orderId = req.long("order_id"),
                    deliveryCorp = req.str("delivery_corp"),
                    deliveryCorpName = req.str("delivery_corp_name"),
                    deliveryCode = req.str("delivery_code"),
                    items = deliveryItems(req),
                    deliveryPics = req.list("delivery_pics").mapNotNull { OpenapiParams.leafText(it) }
                        .takeIf { it.isNotEmpty() },
                ),
                operatorId = null,
                // 留痕写明"开放接口发的货"（§25.2 OA-M4）：运营面与员工面的留痕形状不能混
                operator = "openapi:${caller.developerId}",
            )
        },

        Entry(
            id = "OA-M5", method = "ecx.order.writeoff", verb = POST, versions = setOf(V2),
            summary = "自提核销", required = listOf("order_id", "code"),
            notFound = OpenapiError(OpenapiCode.ORDER_NOT_FOUND, "订单不存在"),
        ) { req, caller ->
            writeOff.writeOff(
                companyId = caller.companyId,
                orderId = req.requireLong("order_id"),
                code = req.requireText("code"),
                // 不传 distributor_id = 全租户自提点（§25.2 OA-M5）；传了就把可见集合收窄到那几家
                scope = DistributorScope(req.longList("distributor_id").takeIf { it.isNotEmpty() }),
                operatorId = 0L,
                operator = "openapi:${caller.developerId}",
                operatorRole = OPERATOR_ROLE,
            )
        },

        Entry(
            id = "OA-M6", method = "ecx.product.sku_list", verb = GET, versions = setOf(V1),
            summary = "SKU 列表（与 GOODS-7 同一装配）",
        ) { req, caller -> items.skuList(req.raw(), caller.companyId) },

        Entry(
            id = "OA-M7", method = "ecx.items.entity.get", verb = GET, versions = setOf(V2),
            summary = "商品列表（后台口径，含未上架）",
        ) { req, caller -> items.list(req.raw(), caller.companyId, front = false) },

        Entry(
            id = "OA-M8", method = "ecx.item.entity.get", verb = GET, versions = setOf(V2),
            summary = "按 item_bn 精确取一个 SPU", requiredAny = listOf("item_bn", "item_id"),
            notFound = OpenapiError(OpenapiCode.INTERNAL, "商品不存在"),
        ) { req, caller ->
            val bn = req.str("item_bn")?.trim()?.takeIf { it.isNotEmpty() }
            val byId = req.long("item_id")
            val row = when {
                bn != null -> itemsMapper.findByBn(caller.companyId, bn)
                byId != null -> itemsMapper.findById(byId)?.takeIf { it.companyId == caller.companyId }
                else -> null
            } ?: throw OpenapiException(OpenapiCode.INTERNAL, "商品不存在")
            // 复用 GOODS-2 的装配函数，不在这里手搓字段：出参与后台面逐字段一致才算"同一张脸的两侧"
            items.adminDetail(row.itemId)
        },

        Entry(
            id = "OA-M9", method = "ecx.member.query", verb = GET, versions = setOf(V1),
            summary = "按手机号查会员（出参恒掩码）", required = listOf("mobile"),
            notFound = OpenapiError(OpenapiCode.MEMBER_NOT_FOUND, "会员不存在"),
        ) { req, caller ->
            // 走 `count`/`page` 那对读口而不是 `findByMobile`：后者的唯一索引是 (company_id, mobile)，
            // 不带租户条件就可能命中别的租户同号的人（§25.6 门槛②的 fail-closed 判据）
            val rows = membersMapper.page(
                linkedMapOf<String, Any?>("companyId" to caller.companyId, "mobile" to req.requireText("mobile")),
                offset = 0, limit = 1,
            )
            val m = rows.firstOrNull() ?: throw OpenapiException(OpenapiCode.MEMBER_NOT_FOUND, "会员不存在")
            memberRow(m)
        },

        Entry(
            id = "OA-M10", method = "ecx.member.detail", verb = GET, versions = setOf(V2),
            summary = "会员详情（脱敏）", required = listOf("user_id"),
            notFound = OpenapiError(OpenapiCode.MEMBER_NOT_FOUND, "会员不存在"),
        ) { req, caller -> members.profile(caller.companyId, req.requireLong("user_id")) },

        Entry(
            id = "OA-M11", method = "ecx.member.list", verb = GET, versions = setOf(V2),
            summary = "会员列表（分页）",
        ) { req, caller ->
            val f = linkedMapOf<String, Any?>("companyId" to caller.companyId)
            req.str("keyword")?.trim()?.takeIf { it.isNotEmpty() }?.let { f["keyword"] = it }
            req.long("mobile")?.let { f["mobile"] = it.toString() }
            req.long("user_id")?.let { f["userId"] = it }
            req.long("grade_id")?.let { f["gradeId"] = it }
            req.bool("disabled")?.let { f["disabled"] = it }
            req.long("time_start_begin")?.let { f["createdStart"] = it }
            req.long("time_start_end")?.let { f["createdEnd"] = it }
            val pq = pageQuery(req)
            PageHelper.paginate(
                pq,
                count = { membersMapper.count(f) },
                query = { offset, limit -> membersMapper.page(f, offset, limit) },
                map = { rows -> rows.map { memberRow(it) } },
            )
        },

        Entry(
            id = "OA-M12", method = "ecx.member_point.detail", verb = GET, versions = setOf(V2),
            summary = "会员积分概况（与 PT-F1 同装配）", required = listOf("user_id"),
        ) { req, caller -> points.overview(req.requireLong("user_id"), caller.companyId) },

        Entry(
            id = "OA-M13", method = "ecx.member_point_log.list", verb = GET, versions = setOf(V2),
            summary = "积分流水（`full` 恒 false）", required = listOf("user_id"),
        ) { req, caller -> points.adminLogs(caller.companyId, req.raw(), full = false) },

        Entry(
            id = "OA-M14", method = "ecx.member_point.update", verb = POST, versions = setOf(V2),
            summary = "积分调整（与 PT-A2 同一条动账口）", required = listOf("user_id", "change_point", "remark"),
        ) { req, caller ->
            points.adjust(
                caller.companyId,
                PointAdjustForm(
                    userId = req.long("user_id"),
                    changePoint = req.long("change_point"),
                    remark = req.str("remark"),
                ),
            )
        },

        Entry(
            id = "OA-M15", method = "ecx.member.recharge.trade.get", verb = GET, versions = setOf(V2),
            summary = "储值交易列表（`full` 恒 false）",
        ) { req, caller ->
            deposits.adminTrades(caller.companyId, req.raw(), DistributorScope.ALL, full = false)
        },

        Entry(
            id = "OA-M16", method = "ecx.discountcard.list", verb = GET, versions = setOf(V1, V2),
            summary = "券模板列表",
        ) { req, caller -> coupons.list(caller.companyId, req.raw()) },

        Entry(
            id = "OA-M17", method = "ecx.discountcard.send", verb = POST, versions = setOf(V2),
            summary = "发券（幂等判据沿用券域那条）", requiredAny = listOf("user_ids", "grade_id"),
            notFound = OpenapiError(OpenapiCode.INTERNAL, "券不存在"),
        ) { req, caller ->
            coupons.send(
                caller.companyId,
                CouponSendForm(
                    couponId = req.long("coupon_id"),
                    userIds = req.longList("user_ids").takeIf { it.isNotEmpty() },
                    gradeId = req.long("grade_id"),
                ),
            )
        },

        Entry(
            id = "OA-M18", method = "ecx.distributor.list", verb = GET, versions = setOf(V2),
            summary = "店铺列表（仅审核通过且在营业）",
        ) { req, caller -> distributors.list(caller.companyId, req.raw(), DistributorScope.ALL) },

        Entry(
            id = "OA-M19", method = "ecx.distributor.detail", verb = GET, versions = setOf(V2),
            summary = "店铺详情", required = listOf("distributor_id"),
            notFound = OpenapiError(OpenapiCode.SHOP_NOT_FOUND, "店铺不存在"),
        ) { req, caller -> distributors.detail(caller.companyId, req.requireLong("distributor_id"), DistributorScope.ALL) },
    )

    /** 注册表就是这条链的全部对外面：`method` 解析不出来的错，都从这张表的形状来 */
    val versions: Set<String> get() = all.flatMap { it.versions }.toSet()

    fun find(version: String, method: String): Entry? =
        all.firstOrNull { it.method == method && it.versions.contains(version) }

    /** 对接文档面（OP-A5）：只读、来自这张表，不另维护一份会漂的清单 */
    fun catalog(): List<Map<String, Any?>> = all.flatMap { e ->
        e.versions.sorted().map { v ->
            linkedMapOf<String, Any?>(
                "id" to e.id,
                "version" to v,
                "method" to e.method,
                "verb" to e.verb,
                "summary" to e.summary,
                "required" to e.required,
                "required_any" to e.requiredAny,
            )
        }
    }

    /** 校验项检查（第 10 道闸门之后的"参数齐不齐"），返回错误信封项；`null` = 通过 */
    fun checkParams(e: Entry, req: OpenapiRequest): OpenapiException? {
        e.required.firstOrNull { req.str(it).isNullOrBlank() }?.let {
            return OpenapiException(OpenapiCode.PARAM, "$it 必填")
        }
        if (e.requiredAny.isNotEmpty() && e.requiredAny.all { req.str(it).isNullOrBlank() }) {
            return OpenapiException(OpenapiCode.PARAM, "${e.requiredAny.joinToString("/")} 至少填一个")
        }
        return null
    }

    private fun pageQuery(req: OpenapiRequest): PageQuery =
        PageQuery.of(req.int("page"), req.int("pageSize") ?: req.int("page_size"))

    /**
     * 订单号取数（§25.2 OA-M3）。
     *
     * 本仓 `orders.order_id` 就是发号器出来的**单号本身**（BIGINT），没有 PHP 那根 `order_bn` 列，
     * 所以 `order_bn` 在这里按同值别名处理 —— 对端两种写法都能拿到同一份答案，
     * 这比"造一个不存在的列"诚实。
     */
    private fun orderIdOf(req: OpenapiRequest): Long =
        req.long("order_id") ?: req.str("order_bn")?.trim()?.toLongOrNull()
            ?: throw OpenapiException(OpenapiCode.PARAM, "order_id 必填")

    /**
     * 分包发货的 `items`：`[{order_items_id, num}]`，缺省=整单未发行全发。
     * 键与 ORDER-9 那一条**逐字相同**（`order_items_id`，不是 `item_id`）——
     * 一个包裹发的是"这一单的哪一行"，同一 item 在本单可能有两行（改过价的单就是这样）。
     */
    private fun deliveryItems(req: OpenapiRequest): List<DeliveryItemForm>? =
        req.list("items").filterIsInstance<Map<*, *>>().takeIf { it.isNotEmpty() }?.map { raw ->
            DeliveryItemForm(
                orderItemsId = (raw["order_items_id"] ?: raw["orderItemsId"])?.toString()?.toLongOrNull(),
                num = (raw["num"] ?: raw["quantity"])?.toString()?.toLongOrNull(),
            )
        }

    /** 会员列表/查询的出参：蛇形键 + 手机号恒掩码（§25.6 门槛②：与 `datapass` 无关） */
    private fun memberRow(m: MemberListRow): Map<String, Any?> = linkedMapOf(
        "user_id" to m.userId,
        "grade_id" to m.gradeId,
        "mobile" to Mask.mobile(m.mobile),
        "nickname" to (m.nickname ?: ""),
        "avatar" to (m.avatar ?: ""),
        "grade_name" to (m.gradeName ?: ""),
        "disabled" to m.disabled,
        "salesman_id" to m.salesmanId,
        "source_from" to (m.sourceFrom ?: ""),
        "created" to m.created,
    )

    private companion object {
        const val GET = "GET"
        const val POST = "POST"
        const val V1 = "1.0"
        const val V2 = "2.0"

        /** 核销留痕里的操作人角色（§18.6 那条角色列，开放接口自成一档） */
        const val OPERATOR_ROLE = "openapi"
    }
}
