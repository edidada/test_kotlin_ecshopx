package com.ecshopx.kotlin.service.systemlink

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.goods.ItemsMapper
import com.ecshopx.kotlin.dao.order.OrderItemsMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.systemlink.CompanyLicenseDO
import com.ecshopx.kotlin.dao.systemlink.CompanyLicenseMapper
import com.ecshopx.kotlin.dao.systemlink.OmsQueueLogDO
import com.ecshopx.kotlin.dao.systemlink.OmsQueueLogMapper
import com.ecshopx.kotlin.dao.systemlink.SiteCompanyDO
import com.ecshopx.kotlin.dao.systemlink.SiteTenantMapper
import com.ecshopx.kotlin.dao.systemlink.SystemLinkSettingDO
import com.ecshopx.kotlin.dao.systemlink.SystemLinkSettingMapper
import com.ecshopx.kotlin.service.order.OrderDeliveryForm
import com.ecshopx.kotlin.service.order.OrderFlowService
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.security.crypto.bcrypt.BCrypt
import java.util.UUID

/**
 * SystemLink 对接设置（SL-A1/A2，docs/04 §27.3）。
 *
 * 两把 token 在出参里**恒掩码**：运营要的是"这把 token 配没配、配的是哪一版"，
 * 不是它的值。真要看全值只有一个合法动作——重置（把旧的作废、新的记下来），
 * 而那件事必须留痕，所以它不该藏在"看一眼"里。
 */
@Service
class SystemLinkSettingService(
    private val settings: SystemLinkSettingMapper,
) {

    fun get(companyId: Long): Map<String, Any?> {
        val s = settings.findByCompany(companyId)
        return linkedMapOf(
            "is_open" to (s?.isOpen ?: 0),
            "openapi_token" to maskToken(s?.openapiToken),
            "oms_token" to maskToken(s?.omsToken),
            "ip_whitelist" to (s?.ipWhitelist ?: ""),
            "updated" to (s?.updated ?: 0L),
        )
    }

    /**
     * 保存。`openapi_token`/`oms_token` 传空 = **不改**（见 `SystemLinkSettingMapper.update` 的注释）；
     * `is_open` 缺省保持原值。返回落库之后的掩码形态，与 SL-A1 同一形状。
     */
    fun save(companyId: Long, form: SystemLinkSettingForm): Map<String, Any?> {
        val now = TimeSec.now()
        val exist = settings.findByCompany(companyId)
        val open = form.isOpen ?: exist?.isOpen ?: 0
        if (exist == null) {
            val d = SystemLinkSettingDO().apply {
                this.companyId = companyId
                isOpen = open
                openapiToken = form.openapiToken?.takeIf { it.isNotBlank() } ?: randomToken("gy_openapi")
                omsToken = form.omsToken?.takeIf { it.isNotBlank() } ?: randomToken("gy_oms")
                ipWhitelist = form.ipWhitelist ?: ""
                created = now
                updated = now
            }
            settings.insert(d)
        } else {
            settings.update(
                companyId = companyId,
                isOpen = open,
                openapiToken = form.openapiToken?.takeIf { it.isNotBlank() },
                omsToken = form.omsToken?.takeIf { it.isNotBlank() },
                ipWhitelist = form.ipWhitelist ?: exist.ipWhitelist ?: "",
                updated = now,
            )
        }
        return get(companyId)
    }

    /** 新建租户时的两把随机 token：`前缀 + 16 位十六进制`（与 M2-1 的密钥同一形状） */
    private fun randomToken(prefix: String): String =
        prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun maskToken(v: String?): String {
        val t = v?.takeIf { it.isNotBlank() } ?: return ""
        if (t.length <= 6) return "*".repeat(t.length)
        return "${t.take(4)}****${t.takeLast(2)}"
    }
}

/** SL-A2 入参（`null` 与空串都表示"不改"，判定在服务里） */
class SystemLinkSettingForm(
    val isOpen: Int? = null,
    val openapiToken: String? = null,
    val omsToken: String? = null,
    val ipWhitelist: String? = null,
)

/** 一次入站调用的结果（`data` 是业务载荷，信封由服务拼） */
class SlResult(val ok: Boolean, val message: String, val data: Any? = null)

/**
 * SystemLink 入站（SL-G1..G3 + SL-M1..M8，docs/04 §27.1/27.2）。
 *
 * 这一族最反直觉的一件事是**租户是从签名反推出来的**：入站报文里没有 `company_id`
 * （源系统更没有），token 也不随报文发送。所以验签不是"取一把 token 算一遍"，
 * 而是"逐个启用中的租户算一遍，第一个对上的就是它"——`company_id` 不是对端声明出来的，
 * 是它的密钥**证明**出来的。反过来做（先按入参 `company_id` 定位租户再验签）
 * 等于让对端自己声明自己是哪家，签对也没有意义。
 *
 * 留痕（`systemlink_oms_queuelog`）只挂在 OME 那条链上：`ecapi.site.*` 是平台侧对本站的操作，
 * 源系统同样不记（`OpenApiCheck` 里没有任何落库动作）。这不是漏写，是"哪条链需要排障证据"的区别。
 */
@Service
class SystemLinkInboundService(
    private val settings: SystemLinkSettingMapper,
    private val queueLogs: OmsQueueLogMapper,
    private val licenses: CompanyLicenseMapper,
    private val tenants: SiteTenantMapper,
    private val orders: OrdersMapper,
    private val orderItems: OrderItemsMapper,
    private val flow: OrderFlowService,
    private val items: ItemsMapper,
    private val seq: SeqGenerator,
    private val db: Db,
    private val mapper: ObjectMapper,
    @Value("\${ecx.systemlink.enabled:true}") private val enabled: Boolean,
    @Value("\${ecx.systemlink.require-ip-whitelist:false}") private val requireWhitelist: Boolean,
) {

    // ===================== 入口 =====================

    /** SL-G1：`/api/systemlink/openapi/{method}`（签名 A，`openapi_token`） */
    fun openapi(method: String?, params: Map<String, Any?>, ip: String?): Map<String, Any?> {
        if (!enabled) return fail("对接未开启", params)
        val tenant = identify(params, SystemLinkSignMode.OPENAPI) ?: return fail("sign error", params)
        val m = method?.trim()?.takeIf { it.isNotEmpty() } ?: (params["method"] as? String)?.trim()
        if (m.isNullOrEmpty()) return fail("接口不存在", params)
        val r = when (m) {
            "ecapi.site.create" -> siteCreate(tenant, params)
            "ecapi.site.close" -> siteClose(tenant, params)
            "ecapi.site.renew" -> siteRenew(tenant, params)
            "ecapi.site.checkisopen" -> siteCheckOpen(tenant, params)
            else -> SlResult(false, "接口不存在")
        }
        return if (r.ok) ok(r.message.ifEmpty { "操作成功" }, r.data) else fail(r.message, params)
    }

    /** SL-G2/G3：`/api/systemlink/ome` 与 `/api/systemlink/ome/{method}`（签名 B，`oms_token` + IP 白名单） */
    fun ome(method: String?, params: Map<String, Any?>, ip: String?): Map<String, Any?> {
        if (!enabled) return fail("对接未开启", params)
        val tenant = identify(params, SystemLinkSignMode.OME) ?: return fail("sign error", params)
        // 白名单在签名**之后**：先证明"你是你"，再判"你从哪来"（§27.1 末段）
        val allowAll = tenant.ipWhitelist.isNullOrBlank()
        if (!IpWhitelist.allows(tenant.ipWhitelist, ip) || (allowAll && requireWhitelist)) {
            return fail("ip access denied", params)
        }
        val m = method?.trim()?.takeIf { it.isNotEmpty() } ?: (params["method"] as? String)?.trim()
        if (m.isNullOrEmpty()) return fail("接口不存在", params)

        val msgId = seq.nextBn(SeqName.OMS_MSG)
        val started = System.currentTimeMillis()
        val reqLog = OmsQueueLogDO().apply {
            companyId = tenant.companyId
            apiType = "request"
            worker = m
            this.params = json(params)
            status = "running"
            this.msgId = msgId
        }
        queueLogs.insert(reqLog)

        val r = try {
            dispatchOme(tenant, m, params)
        } catch (e: BizException) {
            SlResult(false, e.message ?: "操作失败")
        } catch (e: Exception) {
            SlResult(false, e.message ?: "操作失败")
        }
        val runtime = (System.currentTimeMillis() - started).toInt()
        val envelope = if (r.ok) ok(r.message.ifEmpty { "操作成功" }, r.data) else fail(r.message, params)
        // 应答行与请求行共用 `msg_id`；`runtime` 只写应答行（请求行那一刻量不出耗时）
        queueLogs.insert(
            OmsQueueLogDO().apply {
                companyId = tenant.companyId
                apiType = "response"
                worker = m
                this.params = null
                result = json(envelope)
                status = if (r.ok) "success" else "fail"
                this.runtime = runtime
                this.msgId = msgId
            },
        )
        queueLogs.finish(reqLog.id, null, if (r.ok) "success" else "fail", 0, TimeSec.now())
        return envelope
    }

    /**
     * 认租户：逐个启用中的设置按各自 token 算一遍签，第一个对上的就是它。
     * 一把都没对上 = `sign error`（**不区分**"token 不存在"与"签不对"：
     * 分开答等于告诉对端"这把 token 存在"，而它本来就不该知道别家有哪几把）。
     */
    private fun identify(params: Map<String, Any?>, mode: SystemLinkSignMode): SystemLinkSettingDO? {
        val given = (params["sign"] as? String)?.trim().orEmpty()
        if (given.isEmpty()) return null
        val signed = params.filterKeys { it != "sign" }
        return settings.listOpen().firstOrNull { s ->
            val token = when (mode) {
                SystemLinkSignMode.OPENAPI -> s.openapiToken
                SystemLinkSignMode.OME -> s.omsToken
            }?.takeIf { it.isNotBlank() } ?: return@firstOrNull false
            SystemLinkSign.sign(signed, token, mode).equals(given, ignoreCase = true)
        }
    }

    // ===================== SL-M1..M4 站点 =====================

    private fun siteCreate(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val eid = str(p, "eid") ?: return SlResult(false, "企业id必填")
        val shopexid = str(p, "shopexid") ?: return SlResult(false, "shopexid必填")
        val tel = str(p, "tel") ?: return SlResult(false, "手机号必填")
        val name = str(p, "company_name") ?: return SlResult(false, "公司名称必填")
        val days = (p["days"] as? String)?.toIntOrNull() ?: (p["days"] as? Number)?.toInt() ?: DEFAULT_DAYS
        if (tenants.findAdminByMobile(tel) != null) return SlResult(false, "账号已开通")
        if (licenses.findByEidOrPassport(eid) != null || licenses.findByEidOrPassport(shopexid) != null) {
            return SlResult(false, "账号已开通")
        }
        val now = TimeSec.now()
        val companyId = db.transactional {
            val c = SiteCompanyDO().apply {
                companyName = name
                licenseCode = eid
                status = "normal"
            }
            tenants.insertCompany(c)
            // 口令是随机串的 bcrypt：源系统也是 `uniqid()`，开出来的账号在重置口令之前登不进去
            tenants.insertAdminOperator(
                companyId = c.companyId,
                username = tel,
                password = BCrypt.hashpw(UUID.randomUUID().toString(), BCrypt.gensalt(10)),
                mobile = tel,
                now = now,
            )
            licenses.insert(
                CompanyLicenseDO().apply {
                    this.companyId = c.companyId
                    this.eid = eid
                    passportUid = shopexid
                    issueId = str(p, "issue_id") ?: "shuyun_$shopexid"
                    availableDays = days
                    activeAt = now
                    expiredAt = now + days.toLong() * 86400L
                    goodsCode = str(p, "good_code")
                    productCode = str(p, "product_code")
                    source = "purchased"
                },
            )
            c.companyId
        }
        // 源系统答的是"开通成功"，且不返回 company_id；这里补一个（对端要拿它去对账）
        return SlResult(true, "开通成功", linkedMapOf("company_id" to companyId, "eid" to eid, "expired_at" to (now + days * 86400L)))
    }

    private fun siteClose(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val lic = locate(p) ?: return SlResult(false, "账号不存在")
        tenants.updateCompanyStatus(lic.companyId, "closed", TimeSec.now())
        return SlResult(true, "操作成功", linkedMapOf("company_id" to lic.companyId, "status" to "closed"))
    }

    private fun siteRenew(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val days = (p["days"] as? String)?.toIntOrNull() ?: (p["days"] as? Number)?.toInt()
        if (days == null || days <= 0) return SlResult(false, "站点开通天数必填")
        val lic = locate(p) ?: return SlResult(false, "账号不存在")
        val now = TimeSec.now()
        // 从**原到期时间**起算（已过期的从今天起算）：少给一天也是少给
        val base = maxOf(lic.expiredAt, now)
        val expired = base + days.toLong() * 86400L
        licenses.renew(lic.companyId, expired, days, now)
        return SlResult(true, "操作成功", linkedMapOf("company_id" to lic.companyId, "expired_at" to expired))
    }

    private fun siteCheckOpen(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val lic = locate(p) ?: return SlResult(false, "账号不存在")
        val c = tenants.findCompany(lic.companyId)
        val open = c?.status == "normal" && lic.expiredAt > TimeSec.now()
        return SlResult(
            true, "操作成功",
            linkedMapOf(
                "is_open" to open,
                "company_id" to lic.companyId,
                "expired_at" to lic.expiredAt,
                "available_days" to lic.availableDays,
            ),
        )
    }

    private fun locate(p: Map<String, Any?>): CompanyLicenseDO? {
        val eid = str(p, "eid")
        val passport = str(p, "shopexid") ?: str(p, "passport_uid")
        return eid?.let { licenses.findByEidOrPassport(it) }
            ?: passport?.let { licenses.findByEidOrPassport(it) }
    }

    // ===================== SL-M5..M8 OME =====================

    private fun dispatchOme(t: SystemLinkSettingDO, method: String, p: Map<String, Any?>): SlResult = when (method) {
        "store.trade.fullinfo.get" -> omeOrderInfo(t, p)
        "store.logistics.offline.send" -> omeDelivery(t, p)
        "store.trade.status.update" -> omeTradeStatus(t, p)
        "store.items.quantity.list.update" -> omeItemStore(t, p)
        else -> SlResult(false, "接口不存在")
    }

    /** SL-M5：按 `tid` 出 OME 形状的 `{trade:{…}}` */
    private fun omeOrderInfo(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val tid = str(p, "tid") ?: return SlResult(false, "订单号缺少！")
        val id = tid.toLongOrNull() ?: return SlResult(false, "未找到订单")
        val o = orders.findById(id)?.takeIf { it.companyId == t.companyId }
            ?: return SlResult(false, "未找到订单")
        if (o.orderClass !in OME_ORDER_CLASS) return SlResult(false, "${o.orderClass}类型订单禁止同步")
        val rows = orderItems.listByOrder(o.orderId)
        val trade = linkedMapOf<String, Any?>(
            "tid" to o.orderId.toString(),
            "shop_id" to o.shopId,
            "user_id" to o.userId,
            "order_class" to o.orderClass,
            "status" to o.orderStatus,
            "pay_status" to o.payStatus,
            "delivery_status" to o.deliveryStatus,
            "cancel_status" to o.cancelStatus,
            "total_fee" to o.totalFee,
            "pay_fee" to o.payFee,
            "freight_fee" to o.freightFee,
            "discount_fee" to o.discountFee,
            "created" to o.created,
            "modified" to o.updated,
            "receiver_name" to o.receiverName,
            "receiver_mobile" to com.ecshopx.kotlin.common.security.Mask.mobile(o.receiverMobile),
            "receiver_state" to o.receiverState,
            "receiver_city" to o.receiverCity,
            "receiver_district" to o.receiverDistrict,
            "receiver_address" to o.receiverAddress,
            "delivery_corp" to o.deliveryCorp,
            "delivery_code" to o.deliveryCode,
            "delivery_time" to o.deliveryTime,
            "items" to rows.map { it2 ->
                linkedMapOf<String, Any?>(
                    "oid" to it2.id.toString(),
                    "item_id" to it2.itemId,
                    "item_bn" to it2.itemBn,
                    "title" to it2.itemName,
                    "num" to it2.num,
                    "price" to it2.price,
                    "total_fee" to it2.totalFee,
                    "delivery_status" to it2.deliveryStatus,
                )
            },
        )
        return SlResult(true, "操作成功", linkedMapOf("trade" to trade))
    }

    /** SL-M6：ERP 回传发货 → 委托 `OrderFlowService.ship`（状态机与留痕都归它） */
    private fun omeDelivery(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val tid = str(p, "tid") ?: return SlResult(false, "订单号缺少！")
        val corp = str(p, "company_code") ?: return SlResult(false, "缺少物流编码")
        val code = str(p, "logistics_no") ?: return SlResult(false, "缺少物流单号")
        val id = tid.toLongOrNull() ?: return SlResult(false, "此订单不存在")
        val o = orders.findById(id)?.takeIf { it.companyId == t.companyId }
            ?: return SlResult(false, "此订单不存在")
        if (o.deliveryStatus == "DONE") return SlResult(false, "订单已发货，请勿重复发货")
        val parts = parseOidList(str(p, "oid_list"))
            ?: return SlResult(false, "拆单发货，oid_list数据格式错误")
        flow.ship(
            o.orderId,
            OrderDeliveryForm(
                orderId = o.orderId,
                deliveryCorp = corp,
                deliveryCode = code,
                items = parts,
            ),
            operatorId = null,
            operator = "systemlink:ome",
        )
        return SlResult(true, "操作成功", linkedMapOf("tid" to o.orderId.toString(), "delivery_code" to code))
    }

    /**
     * SL-M7：**只读不写**（§27 盘点结论 4）。
     *
     * 源系统拿到 `TRADE_CLOSED` 只查本地 `cancel_status` 就答 `true`，一行数据都不改。
     * 照抄它是为了让"发完就收到成功"这个行为不变；真关单走售后那条链。
     * 想"顺手改成真关单"之前先想清楚：ERP 认为关了、本地没关，两边对账时差的是谁的单。
     */
    private fun omeTradeStatus(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val tid = str(p, "tid") ?: return SlResult(false, "订单信息有误")
        val status = str(p, "status")
        val type = str(p, "type")
        val id = tid.toLongOrNull() ?: return SlResult(false, "订单信息有误")
        val o = orders.findById(id)?.takeIf { it.companyId == t.companyId }
            ?: return SlResult(false, "订单信息有误")
        val hit = status == "TRADE_CLOSED" && type == "status" && o.cancelStatus == "SUCCESS"
        return if (hit) SlResult(true, "操作成功", linkedMapOf("status" to true)) else SlResult(false, "订单信息有误")
    }

    /** SL-M8：按 `item_bn` 回写库存；不存在的 bn 进 `no_update`，不整单失败 */
    private fun omeItemStore(t: SystemLinkSettingDO, p: Map<String, Any?>): SlResult {
        val raw = str(p, "list_quantity") ?: return SlResult(false, "缺少数据")
        val arr = runCatching { mapper.readTree(raw) }.getOrNull()
            ?: return SlResult(false, "数据有误")
        if (!arr.isArray || arr.isEmpty) return SlResult(false, "数据有误")
        val noUpdate = mutableListOf<String>()
        var updated = 0
        val now = TimeSec.now()
        arr.forEach { node ->
            val bn = node.path("bn").asText("").trim()
            val qty = node.path("quantity").asLong(-1)
            if (bn.isEmpty() || qty < 0) {
                noUpdate += bn
                return@forEach
            }
            val it = items.findByBn(t.companyId, bn)
            if (it == null) {
                noUpdate += bn
                return@forEach
            }
            items.setStore(it.itemId, qty, now)
            updated++
        }
        return SlResult(
            true, "操作成功",
            linkedMapOf("updated" to updated, "no_update" to noUpdate, "total" to arr.size()),
        )
    }

    /**
     * `oid_list` 解析：源系统是 `[{"oid": 行id, "nums": 件数}]` 的 JSON 串。
     * 返回 `null` = 给了但格式不对（要拒），返回空列表 = 没给（整单发）。
     */
    private fun parseOidList(raw: String?): List<com.ecshopx.kotlin.service.order.DeliveryItemForm>? {
        if (raw.isNullOrBlank()) return emptyList()
        val node = runCatching { mapper.readTree(raw) }.getOrNull() ?: return null
        if (!node.isArray) return null
        return node.map { n ->
            val oid = n.path("oid").asText("").toLongOrNull() ?: return null
            com.ecshopx.kotlin.service.order.DeliveryItemForm(
                orderItemsId = oid,
                num = n.path("nums").asLong(0).takeIf { it > 0 },
            )
        }
    }

    // ===================== 后台面 SL-A3/A4 =====================

    fun listLogs(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        val f = mutableMapOf<String, Any?>("companyId" to companyId)
        raw["api_type"]?.takeIf { it.isNotBlank() }?.let { f["apiType"] = it }
        raw["worker"]?.takeIf { it.isNotBlank() }?.let { f["worker"] = it }
        raw["status"]?.takeIf { it.isNotBlank() }?.let { f["status"] = it }
        raw["msg_id"]?.takeIf { it.isNotBlank() }?.let { f["msgId"] = it }
        raw["created_start"]?.takeIf { it.isNotBlank() }?.let { f["createdStart"] = it.toLongOrNull() }
        raw["created_end"]?.takeIf { it.isNotBlank() }?.let { f["createdEnd"] = it.toLongOrNull() }
        return PageHelper.paginate(pq, { queueLogs.count(f) }, { o, l -> queueLogs.page(f, o, l) }) { rows ->
            rows.map {
                linkedMapOf(
                    "id" to it.id,
                    "api_type" to it.apiType,
                    "worker" to it.worker,
                    "status" to it.status,
                    "runtime" to it.runtime,
                    "msg_id" to it.msgId,
                    "created" to it.created,
                )
            }
        }
    }

    fun logDetail(companyId: Long, id: Long): Map<String, Any?> {
        val l = queueLogs.find(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "日志不存在")
        return linkedMapOf(
            "id" to l.id,
            "api_type" to l.apiType,
            "worker" to l.worker,
            "status" to l.status,
            "runtime" to l.runtime,
            "msg_id" to l.msgId,
            "params" to l.params,
            "result" to l.result,
            "created" to l.created,
            "updated" to l.updated,
        )
    }

    // ===================== 信封与工具 =====================

    /**
     * 应答信封：`{rsp, code, err_msg, data}`，**`data` 永远是 JSON 字符串**（源系统 `json_encode($data)`）。
     * 这一点照抄而不是"顺手改成对象"：对端是按字符串解析 `data` 再二次 `json_decode` 的，
     * 换成对象会让它的第一次解析就失败，而它报出来的错是"返回格式不对"——查不到我们这边。
     */
    fun ok(message: String, data: Any?): Map<String, Any?> = linkedMapOf(
        "rsp" to "succ", "code" to 0, "err_msg" to message, "data" to json(data),
    )

    fun fail(message: String, data: Any?): Map<String, Any?> = linkedMapOf(
        "rsp" to "fail", "code" to 0, "err_msg" to message, "data" to json(data),
    )

    private fun json(v: Any?): String = mapper.writeValueAsString(v)

    private fun str(p: Map<String, Any?>, k: String): String? =
        (p[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        /** 源系统把 `days` 写死成 `365 * 20`（20 年），本项目沿用 */
        const val DEFAULT_DAYS = 7300
        val OME_ORDER_CLASS = setOf("normal", "groups", "seckill")
    }
}
