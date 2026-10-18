package com.ecshopx.kotlin.service.distribution

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.distribution.DistributionType
import com.ecshopx.kotlin.dao.distribution.DistributorDO
import com.ecshopx.kotlin.dao.distribution.DistributorMapper
import com.ecshopx.kotlin.dao.distribution.DistributorSource
import com.ecshopx.kotlin.dao.distribution.DistributorValid
import com.ecshopx.kotlin.dao.distribution.PaymentSubject
import com.ecshopx.kotlin.dao.distribution.ShopItemMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import org.springframework.stereotype.Service

/**
 * M1-4 店铺档案（DS-A1..A8 后台 + DS-F1/F2 会员面，docs/04 §18.2/§18.1）。
 *
 * 三列状态各有各的出口（D42），服务层把它们拆成三个方法、各查各的闸门：
 *  - [review] 只动 `review_status`；
 *  - [updateValid] 只动 `is_valid`，且 `closed` 要过"先结后关"；
 *  - [updateSubject] 只动 `payment_subject/rate`，且**不回溯**已落地的佣金日志（D46）。
 * 源系统把三件事塞进同一条 `updateDistributor`，于是"改个联系电话"能把未审核的店顺手过审。
 *
 * 前台与后台读的是**同一份列**（§18.0 的三筛纪律）：`is_valid='true' AND review_status=TRUE`
 * 才进 [frontList]，不会出现"配置台列出了这家店、下单却说店铺未启用"。
 */
@Service
class DistributorService(
    private val distributors: DistributorMapper,
    private val shopItems: ShopItemMapper,
) {

    // ===================== DS-A1 / DS-A4 =====================

    /**
     * DS-A1 建店。`source_from` 固定 1（管理端），`is_distributor` 由 `distribution_type` 派生：
     * 自营=商城主店铺位，全场只许一条（已有则 422），加盟一律 0。
     */
    fun create(
        companyId: Long,
        form: DistributorForm,
    ): Map<String, Any?> {
        val type = form.distributionType ?: DistributionType.FRANCHISE
        val errs = validate(companyId, form, type, rate = form.rate ?: 0, creating = true)
        if (type == DistributionType.SELF && distributors.countSelf(companyId) > 0) {
            errs["distribution_type"] = listOf("自营主店铺已存在，全场只能有一家")
        }
        if (errs.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "店铺信息不合法", errs)

        val d = DistributorDO().apply {
            this.companyId = companyId
            isDistributor = type == DistributionType.SELF
            distributionType = type
            applyForm(form)
            sourceFrom = DistributorSource.ADMIN
            // 建店即审：管理端建的店默认直接可营业（DS-A1），小程序申请来的店走 DS-A5 过审
            reviewStatus = form.reviewStatus ?: true
            paymentSubject = form.paymentSubject ?: PaymentSubject.PLATFORM
            rate = form.rate ?: 0
        }
        distributors.insert(d)
        return detail(companyId, d.distributorId, DistributorScope.ALL)
    }

    /**
     * DS-A4 改店：档案可改，`review_status`/`is_distributor`/`company_id`/`distribution_type`
     * 一个都不碰（那条 UPDATE 语句里也没有它们，改不动是结构上的而不是校验上的）。
     */
    fun update(
        companyId: Long,
        distributorId: Long,
        form: DistributorForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val cur = one(companyId, distributorId, scope)
        val errs = validate(companyId, form, cur.distributionType, rate = cur.rate, creating = false)
        val reqType = form.distributionType
        if (reqType != null && reqType != cur.distributionType) {
            errs["distribution_type"] = listOf("店铺类型建后不可改")
        }
        if (errs.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "店铺信息不合法", errs)
        cur.applyForm(form)
        distributors.update(cur)
        return detail(companyId, distributorId, scope)
    }

    /** 表单 → DO 的档案部分（不含状态三列）；null 字段=不改，空串=清空 */
    private fun DistributorDO.applyForm(form: DistributorForm) {
        form.name?.trim()?.let { if (it.isNotEmpty()) name = it }
        form.mobile?.trim()?.let { if (it.isNotEmpty()) mobile = it }
        form.province?.let { province = it }
        form.city?.let { city = it }
        form.area?.let { area = it }
        form.address?.let { address = it }
        form.lng?.let { lng = it }
        form.lat?.let { lat = it }
        form.logo?.let { logo = it }
        form.banner?.let { banner = it }
        form.introduce?.let { introduce = it }
        form.isZiti?.let { isZiti = it }
        form.isDelivery?.let { isDelivery = it }
        form.isSelfDelivery?.let { isSelfDelivery = it }
        form.autoSyncGoods?.let { autoSyncGoods = it }
        form.shopId?.let { if (it >= 0) shopId = it }
    }

    private fun validate(
        companyId: Long,
        form: DistributorForm,
        type: Int,
        rate: Int,
        creating: Boolean,
    ): LinkedHashMap<String, List<String>> {
        val e = linkedMapOf<String, List<String>>()
        if (creating) {
            if (form.name.isNullOrBlank()) e["name"] = listOf("不能为空")
            if (form.mobile.isNullOrBlank()) e["mobile"] = listOf("不能为空")
        } else {
            if (form.name?.isBlank() == false && form.name!!.length > 128) e["name"] = listOf("最长 128 字")
        }
        if (type !in setOf(DistributionType.SELF, DistributionType.FRANCHISE)) e["distribution_type"] = listOf("只支持 0 自营 / 1 加盟")
        val reqSubject = form.paymentSubject
        if (reqSubject != null && reqSubject !in setOf(PaymentSubject.PLATFORM, PaymentSubject.SHOP)) {
            e["payment_subject"] = listOf("只支持 0 平台收款 / 1 店铺收款")
        }
        val reqRate = form.rate ?: rate
        if (reqRate !in 0..10000) e["rate"] = listOf("费率是万分比，取值 0..10000")
        if (e.isEmpty() && creating && !form.mobile.isNullOrBlank() && form.mobile!!.length > 32) e["mobile"] = listOf("最长 32 字")
        return e
    }

    // ===================== DS-A2 / DS-A3 =====================

    /**
     * DS-A2 列表：行带三个派生数（订单数、未结佣金、可提现余额）。
     *
     * 一次 GROUP BY 批量回捞（本页 ids），不给每店补一条 COUNT —— 与商品域 `ItemService.enrich`
     * 同一条防 N+1 纪律。
     */
    fun list(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val f = filters(companyId, raw, scope)
        return PageHelper.paginate(
            pq,
            count = { distributors.count(f) },
            query = { offset, limit -> distributors.page(f, offset, limit) },
            map = { rows ->
                val ids = rows.map { it.distributorId }
                val st = statsOf(companyId, ids)
                rows.map { row(it, st[it.distributorId], itemCnt(it.distributorId)) }
            },
        )
    }

    /** DS-A3 详情（`?distributor_id=`，也复用做 DS-A1/A4 的应答体） */
    fun detail(
        companyId: Long,
        distributorId: Long,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val d = one(companyId, distributorId, scope)
        return LinkedHashMap(row(d, statsOf(companyId, listOf(distributorId))[distributorId], itemCnt(distributorId))).apply {
            put("introduce", d.introduce)
        }
    }

    // ===================== DS-A5 / DS-A6 / DS-A7 =====================

    /**
     * DS-A5 审核。重复审核由那条 `review_status <> #{to}` 的 UPDATE 自然回 0 行，
     * 服务层据此报 422 —— 不去"先查再判"，否则并发下两次审核都能通过判据。
     */
    fun review(
        companyId: Long,
        distributorId: Long,
        reviewStatus: Boolean,
        scope: DistributorScope,
    ): Map<String, Any?> {
        one(companyId, distributorId, scope)
        if (distributors.review(distributorId, companyId, reviewStatus, TimeSec.now()) == 0) {
            throw BizException(ErrorCode.BIZ, "无需重复审核", mapOf("review_status" to listOf("当前审核态已是这个值")))
        }
        return detail(companyId, distributorId, scope)
    }

    /**
     * DS-A6 运营开关。`closed` 是终态关店，必须先过"先结后关"两道闸门（D42）：
     * 还有未结佣金或还有在途提现都不许关，否则账上会留下"店关了、钱还挂着"的悬空数。
     */
    fun updateValid(
        companyId: Long,
        distributorId: Long,
        isValid: String,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val d = one(companyId, distributorId, scope)
        if (isValid !in VALID_STATES) {
            throw BizException(ErrorCode.VALIDATION, "is_valid 非法", mapOf("is_valid" to listOf("只支持 ${VALID_STATES.joinToString("/")}")))
        }
        if (isValid != DistributorValid.CLOSED && d.isValid == DistributorValid.CLOSED) {
            throw BizException(ErrorCode.BIZ, "已关店的店铺不能直接改启停，请走重新开店（DS-A1）")
        }
        if (isValid == DistributorValid.CLOSED) {
            val st = statsOf(companyId, listOf(distributorId))[distributorId]
            if ((st?.commissionOpen ?: 0) > 0) {
                throw BizException(ErrorCode.BIZ, "该店还有未结算佣金，请先结算再关店")
            }
            if ((st?.withdrawInFlight ?: 0) > 0) {
                throw BizException(ErrorCode.BIZ, "该店还有处理中的提现申请，请先处理完再关店")
            }
        }
        if (distributors.updateValid(distributorId, companyId, isValid, d.isValid, TimeSec.now()) == 0) {
            throw BizException(ErrorCode.BIZ, "店铺状态已变更，请刷新后重试")
        }
        return detail(companyId, distributorId, scope)
    }

    /** DS-A7 收款主体与平台服务费率（万分比）。只影响之后的佣金，不回溯已落日志（D46） */
    fun updateSubject(
        companyId: Long,
        distributorId: Long,
        form: DistributorSubjectForm,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val d = one(companyId, distributorId, scope)
        val subject = form.paymentSubject ?: d.paymentSubject
        val rate = form.rate ?: d.rate
        val e = linkedMapOf<String, List<String>>()
        if (subject !in setOf(PaymentSubject.PLATFORM, PaymentSubject.SHOP)) e["payment_subject"] = listOf("只支持 0 平台收款 / 1 店铺收款")
        if (rate !in 0..10000) e["rate"] = listOf("费率是万分比，取值 0..10000")
        if (e.isNotEmpty()) throw BizException(ErrorCode.VALIDATION, "收款配置不合法", e)
        distributors.updateSubject(distributorId, companyId, subject, rate, TimeSec.now())
        return detail(companyId, distributorId, scope)
    }

    // ===================== DS-A8 统计 =====================

    /** DS-A8 店铺统计：七个派生数，全部来自聚合，本域不额外落"余额"列 */
    fun stats(
        companyId: Long,
        distributorId: Long,
        scope: DistributorScope,
    ): Map<String, Any?> {
        one(companyId, distributorId, scope)
        val s = statsOf(companyId, listOf(distributorId))[distributorId] ?: Stats()
        return linkedMapOf(
            "distributor_id" to distributorId,
            "order_count" to s.orderCount,
            "pay_fee" to s.payFee,
            "refund_fee" to s.refundFee,
            "commission_total" to (s.commissionOpen + s.commissionClosed),
            "commission_open" to s.commissionOpen,
            "commission_closed" to s.commissionClosed,
            "balance" to s.balance,
            "withdraw_in_flight" to s.withdrawInFlight,
        )
    }

    /**
     * 批量装配四张账：订单（`orders_normal_orders`）、已退（`trade.refunded_fee`）、
     * 佣金（`distribute_logs` 按 `is_close` 分栏）、提现占用（`applied/confirmed/paid`）。
     *
     * `ids` 为空直接返回空表 —— 每条 SQL 都是 `IN (...)`，空集合会拼出非法语句。
     */
    fun statsOf(
        companyId: Long,
        ids: List<Long>,
    ): Map<Long, Stats> {
        if (ids.isEmpty()) return emptyMap()
        val orders = distributors.orderStats(companyId, ids).associateBy { it.distributorId }
        val refunds = distributors.refundStats(companyId, ids).associateBy { it.distributorId }
        val comm = distributors.commissionStats(companyId, ids).associateBy { it.distributorId }
        val wdr = distributors.withdrawingStats(companyId, ids).associateBy { it.distributorId }
        return ids.associateWith { id ->
            val o = orders[id]
            val c = comm[id]
            Stats(
                orderCount = o?.orderCount ?: 0,
                payFee = o?.payFee ?: 0,
                refundFee = refunds[id]?.refundFee ?: 0,
                commissionOpen = c?.openFee ?: 0,
                commissionClosed = c?.closedFee ?: 0,
                withdrawInFlight = wdr[id]?.withdrawingFee ?: 0,
            )
        }
    }

    // ===================== DS-F1 / DS-F2 会员面 =====================

    /** DS-F1 营业中店铺列表（匿名可访）：三筛 + 分页 + `keyword` */
    fun frontList(
        companyId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val pq = pageQuery(raw)
        val keyword = raw["keyword"]?.takeIf { it.isNotBlank() }
        return PageHelper.paginate(
            pq,
            count = { distributors.countOpen(companyId, keyword) },
            query = { offset, limit -> distributors.listOpen(companyId, null, keyword, offset, limit) },
            map = { rows -> rows.map { frontRow(it) } },
        )
    }

    /** DS-F2 店铺主页：档案 + 简介 + 挂品数；未营业的店对前台就是 404 */
    fun frontDetail(
        companyId: Long,
        distributorId: Long,
    ): Map<String, Any?> {
        val d = distributors.findById(distributorId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
        if (d.isValid != DistributorValid.TRUE || !d.reviewStatus) throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
        return LinkedHashMap(frontRow(d)).apply {
            put("introduce", d.introduce)
            put("item_count", itemCnt(distributorId))
        }
    }

    // ===================== 出参装配 =====================

    private fun row(
        d: DistributorDO,
        s: Stats?,
        itemCount: Long,
    ): Map<String, Any?> = linkedMapOf(
        "distributor_id" to d.distributorId,
        "company_id" to d.companyId,
        "is_distributor" to d.isDistributor,
        "name" to d.name,
        "mobile" to d.mobile,
        "province" to d.province,
        "city" to d.city,
        "area" to d.area,
        "address" to d.address,
        "lng" to d.lng,
        "lat" to d.lat,
        "logo" to d.logo,
        "banner" to d.banner,
        "is_valid" to d.isValid,
        "review_status" to d.reviewStatus,
        "source_from" to d.sourceFrom,
        "shop_id" to d.shopId,
        "distribution_type" to d.distributionType,
        "distribution_type_text" to if (d.distributionType == DistributionType.SELF) "自营" else "加盟",
        "auto_sync_goods" to d.autoSyncGoods,
        "is_ziti" to d.isZiti,
        "is_delivery" to d.isDelivery,
        "is_self_delivery" to d.isSelfDelivery,
        "payment_subject" to d.paymentSubject,
        "payment_subject_text" to if (d.paymentSubject == PaymentSubject.SHOP) "店铺收款" else "平台收款",
        "rate" to d.rate,
        // 万分比列 + 百分比文案同时给：后台表单回填用列，展示用人话
        "rate_text" to rateText(d.rate),
        "item_count" to itemCount,
        "order_count" to (s?.orderCount ?: 0),
        "pay_fee" to (s?.payFee ?: 0),
        "refund_fee" to (s?.refundFee ?: 0),
        "commission_fee" to (s?.commissionOpen ?: 0),
        "commission_closed" to (s?.commissionClosed ?: 0),
        "balance" to (s?.balance ?: 0),
        "created" to d.created,
        "updated" to d.updated,
    )

    private fun frontRow(d: DistributorDO): Map<String, Any?> = linkedMapOf(
        "distributor_id" to d.distributorId,
        "name" to d.name,
        "logo" to d.logo,
        "banner" to d.banner,
        "is_valid" to d.isValid,
        "distribution_type" to d.distributionType,
        "is_ziti" to d.isZiti,
        "is_delivery" to d.isDelivery,
        "province" to d.province,
        "city" to d.city,
        "address" to d.address,
        "mobile" to d.mobile,
    )

    private fun rateText(rate: Int): String = if (rate % 100 == 0) "${rate / 100}%" else "${rate / 100.0}%"

    private fun itemCnt(distributorId: Long): Long = shopItems.countByShop(distributorId)

    private fun one(
        companyId: Long,
        distributorId: Long,
        scope: DistributorScope,
    ): DistributorDO {
        scope.require(distributorId)
        return distributors.findById(distributorId, companyId)
            ?: throw BizException(ErrorCode.NOT_FOUND, "店铺不存在")
    }

    private fun filters(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
    ): Map<String, Any?> {
        val requested = (raw["distributor_id"] ?: raw["ids"])
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.filter { it > 0 }
            ?.distinct()
        return linkedMapOf(
            "companyId" to companyId,
            "valid" to raw["is_valid"]?.takeIf { it.isNotBlank() },
            "reviewStatus" to raw["review_status"]?.let { v -> if (v.isBlank()) null else v == "true" || v == "1" },
            "type" to raw["distribution_type"]?.toIntOrNull(),
            "sourceFrom" to raw["source_from"]?.toIntOrNull(),
            "name" to (raw["keyword"] ?: raw["name"])?.takeIf { it.isNotBlank() },
            "ids" to scope.narrow(requested),
        )
    }

    private fun pageQuery(raw: Map<String, String>): PageQuery =
        PageQuery.of(raw["page"]?.toIntOrNull(), (raw["page_size"] ?: raw["pageSize"])?.toIntOrNull())

    /** 一家店的四张账快照（DS-A2/A3/A8 与后面的提现闸门共用这一个中间结构） */
    class Stats(
        var orderCount: Long = 0,
        var payFee: Long = 0,
        var refundFee: Long = 0,
        var commissionOpen: Long = 0,
        var commissionClosed: Long = 0,
        /** 处理中+已打款的提现占用额（§18.5 余额公式的后半段） */
        var withdrawInFlight: Long = 0,
    ) {
        /** 可提现余额 = 已结算佣金 − 提现占用（含已打款，见 [DistributorMapper.withdrawingStats] 的注释） */
        val balance: Long get() = (commissionClosed - withdrawInFlight).coerceAtLeast(0L)
    }

    companion object {
        val VALID_STATES = setOf(DistributorValid.TRUE, DistributorValid.FALSE, DistributorValid.CLOSED)
    }
}
