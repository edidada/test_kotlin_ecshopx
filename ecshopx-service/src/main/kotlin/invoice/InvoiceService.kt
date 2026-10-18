package com.ecshopx.kotlin.service.invoice

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.InvoiceApplyStatus
import com.ecshopx.kotlin.common.domain.InvoiceHeadType
import com.ecshopx.kotlin.common.domain.OrderStatus
import com.ecshopx.kotlin.common.domain.PayStatus
import com.ecshopx.kotlin.common.domain.SeqName
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.invoice.InvoiceRow
import com.ecshopx.kotlin.dao.invoice.MembersInvoiceDO
import com.ecshopx.kotlin.dao.invoice.MembersInvoiceMapper
import com.ecshopx.kotlin.dao.invoice.OrdersInvoiceDO
import com.ecshopx.kotlin.dao.invoice.OrdersInvoiceMapper
import com.ecshopx.kotlin.dao.order.OrdersMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.service.asset.blankToNull
import com.ecshopx.kotlin.service.asset.flag
import com.ecshopx.kotlin.service.asset.pageOf
import com.ecshopx.kotlin.service.asset.read
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.seq.SeqGenerator
import com.ecshopx.kotlin.service.tx.Db
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

/**
 * M1-5 发票域（docs/04 §23.1 `INV-F1..F6`、§23.2 `INV-A1..A3`、§23.3 第 8~10 条）。
 *
 * 两张表、两种生命周期，本类是它们的唯一写口：
 *  - `orders_invoice` = 申请单（`pending → issued/rejected`，单向、终态不可回退）；
 *  - `members_invoices` = 抬头本（会员自己的可复用抬头，被申请单引用过就不能删）。
 *
 * 五条纪律：
 *  1. **一单一申请**（INV-R1）：`uk_invoice_order(company_id, order_id)` 保证库里至多一行，
 *     服务层按"查得到就改、查不到就插"走，**pending 态重复申请不产生第二行**；
 *     `issued/rejected` 是终态，再改 → 422（不是 404——这一单确实开过票，会员需要看到这个事实）。
 *  2. **申请即置订单侧的位**（INV-R2）：`is_invoiced`、`invoice_status`、`orders.invoice` 快照
 *     在同一次事务里写；后台处理只回写状态、**不回写快照**——会员看到的"我申请时填了什么"
 *     必须是那一刻的内容。
 *  3. **两套抬头词表不合并**（INV-R3）：对外各说各的话，映射只在本类里发生
 *     （[InvoiceHeadType]），抬头带出来的类型走 `personal/corporate → enterprise/individual`。
 *  4. **越权一律 404**：别人的申请单、别人的抬头、不存在的 id 给同一个应答，
 *     不给"这个 id 存在但不是你的"这种可枚举信息（§23.5 门槛③同一条纪律）。
 *  5. **掩码只在出参**（MASK-R1）：`tax_number/email/mobile/company_title` 四个敏感列
 *     在后台列表与详情上默认掩码，拿到 `datapass.pass` 的账号看全值；
 *     筛选与关键字检索打的都是原列（见 `InvoiceMappers.INV_WHERE`）。
 */
@Service
class InvoiceService(
    private val applies: OrdersInvoiceMapper,
    private val heads: MembersInvoiceMapper,
    private val orders: OrdersMapper,
    private val configs: SysConfigMapper,
    private val seq: SeqGenerator,
    private val mapper: ObjectMapper,
    private val db: Db,
) {

    /**
     * INV-F1 申请/修改发票。
     *
     * 闸门顺序：开关 → 认单（非本人 404）→ 认可开票状态（422）→ 认抬头（越权 404）
     * → 认字段与票种（400/422）→ 才落库。
     * 把"终态不能再改"放在最后是因为它读的是**已有申请单**，而申请单是不是终态
     * 只有在 upsert 那一步才判得到；前面几步失败时库里那一行原样不动，
     * 会员改对参数还能再提。
     */
    fun apply(
        companyId: Long,
        userId: Long,
        form: InvoiceApplyForm,
    ): Map<String, Any?> {
        if (!configs.flag(companyId, KEY_ENABLE, true)) throw BizException(ErrorCode.BIZ, "当前未开放发票申请")
        val orderId = parseOrderId(form.orderId)
        val o = orders.findById(orderId) ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        if (o.companyId != companyId || o.userId != userId) throw BizException(ErrorCode.NOT_FOUND, "订单不存在")
        // 未支付/已取消的单没有可开票金额（pay_fee 可能是 0 或退款后的残值），先把状态认掉
        if (o.orderStatus == OrderStatus.CANCEL || o.payStatus == PayStatus.NOTPAY || o.payStatus == PayStatus.REFUND) {
            throw BizException(ErrorCode.BIZ, "订单未支付或已完成退款，暂不能开票")
        }

        // 抬头本带出：表单里显式给的字段覆盖带出来的值（"我这次要改抬头名"是常态）
        val head = form.invoicesId?.takeIf { it > 0 }?.let {
            heads.findMine(it, userId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "抬头不存在")
        }
        val invoiceType = (blankToNull(form.invoiceType) ?: head?.let { InvoiceHeadType.toApply(it.invoicesType) })
            ?: throw BizException(ErrorCode.VALIDATION, "invoice_type 不能为空", mapOf("invoice_type" to listOf("不能为空")))
        if (invoiceType !in InvoiceHeadType.APPLY_VALUES) {
            throw BizException(ErrorCode.VALIDATION, "invoice_type 只能是 enterprise/individual", mapOf("invoice_type" to listOf("取值非法")))
        }
        val companyTitle = blankToNull(form.companyTitle) ?: head?.name
        val taxNumber = blankToNull(form.taxNumber) ?: head?.taxNumber
        val email = requireField("email", form.email)
        if (blankToNull(companyTitle) == null) {
            throw BizException(ErrorCode.VALIDATION, "company_title 不能为空", mapOf("company_title" to listOf("不能为空")))
        }
        if (!email.contains('@')) throw BizException(ErrorCode.VALIDATION, "email 格式不正确", mapOf("email" to listOf("格式不正确")))
        // 跨字段规则用 422（BIZ）：不是格式错，是"个人抬头不该有税号 / 企业抬头必须有税号"这条业务规则
        if (invoiceType == InvoiceHeadType.ENTERPRISE && blankToNull(taxNumber) == null) {
            throw BizException(ErrorCode.BIZ, "企业抬头必须填写纳税人识别号", mapOf("tax_number" to listOf("企业抬头必填")))
        }
        val typeCode = resolveTypeCode(companyId, form.invoiceTypeCode)

        val now = TimeSec.now()
        val orderIdStr = orderId.toString()
        val id = db.transactional {
            val existing = applies.findByOrder(companyId, orderIdStr)
            if (existing == null) {
                val d = OrdersInvoiceDO().apply {
                    this.invoiceApplyBn = seq.nextBn(SeqName.INVOICE_APPLY_BN)
                    this.userId = userId
                    this.companyId = companyId
                    this.orderId = orderIdStr
                    this.invoiceType = invoiceType
                    this.invoiceTypeCode = typeCode
                    this.companyTitle = companyTitle
                    this.taxNumber = taxNumber
                    this.email = email
                    this.mobile = blankToNull(form.mobile) ?: head?.telephone
                    this.invoiceStatus = InvoiceApplyStatus.PENDING
                    // 金额只能对着这一单算（INV-R1 的代价与收益都在这行）
                    this.invoiceAmount = o.payFee
                    this.remark = blankToNull(form.remark)
                    this.invoicesId = head?.invoicesId ?: 0
                    this.createTime = now
                    this.updateTime = now
                }
                applies.insert(d)
                // 新行 id 由 @Options(useGeneratedKeys) 写回 DO，不靠"再查一次"拿
                d.id
            } else {
                if (existing.invoiceStatus != InvoiceApplyStatus.PENDING) {
                    throw BizException(ErrorCode.BIZ, "该申请已处理，不能再修改")
                }
                // updatePending 的 CAS 在 invoice_status='pending' 上：0 行 = 有人比这次请求先处理完
                if (applies.updatePending(pendingEdit(existing.id, invoiceType, typeCode, companyTitle, taxNumber, email, blankToNull(form.remark), head, o.payFee), now) == 0) {
                    throw BizException(ErrorCode.BIZ, "该申请已处理，不能再修改")
                }
                existing.id
            }
        }
        // INV-R2：申请成功就把订单侧的三个位一起写掉，快照存的是**这一次**的申请内容
        orders.markInvoice(orderId, InvoiceApplyStatus.PENDING, snapshot(invoiceType, typeCode, companyTitle, taxNumber, email, o.payFee), now)
        return frontView(applies.findRow(id) ?: throw BizException(ErrorCode.BIZ, "发票申请写入失败"))
    }

    /** INV-F2 我的申请列表（`status?`，分页） */
    fun myList(
        companyId: Long,
        userId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> {
        val f = mapOf(
            "companyId" to companyId,
            "userId" to userId,
            "status" to statusFilter(raw["invoice_status"] ?: raw["status"]),
        )
        return PageHelper.paginate(
            pageOf(raw),
            count = { applies.count(f) },
            query = { offset, limit -> applies.page(f, offset, limit) },
            map = { rows -> rows.map { frontView(it) } },
        )
    }

    /**
     * INV-F3 申请详情。
     *
     * `invoice_file_url` 只在 `issued` 后才有值，未开票时这一位是 null 而不是空串：
     * 前台靠"有没有这一位"决定要不要显示"下载发票"。
     */
    fun detail(
        companyId: Long,
        userId: Long,
        id: Long,
    ): Map<String, Any?> {
        val r = applies.findRow(id) ?: throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")
        if (r.companyId != companyId || r.userId != userId) throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")
        return frontView(r)
    }

    /** INV-F4 抬头本列表（只出本人，默认抬头排第一） */
    fun headList(
        companyId: Long,
        userId: Long,
    ): List<Map<String, Any?>> = heads.listMine(userId, companyId).map { headView(it) }

    /**
     * INV-F5 新增/修改抬头。
     *
     * 置默认走"先清其余、再置自己"两步（都在同一事务里），
     * 所以并发下最坏是两个人都把对方清掉、库里 0 个默认，**不会出现 2 个默认**（§23.5 门槛④）。
     */
    fun saveHead(
        companyId: Long,
        userId: Long,
        form: InvoiceHeadForm,
    ): Map<String, Any?> {
        val type = blankToNull(form.invoicesType) ?: InvoiceHeadType.PERSONAL
        if (type !in InvoiceHeadType.HEAD_VALUES) {
            throw BizException(ErrorCode.VALIDATION, "invoices_type 只能是 personal/corporate", mapOf("invoices_type" to listOf("取值非法")))
        }
        val name = requireField("name", form.name)
        val taxNumber = blankToNull(form.taxNumber)
        if (type == InvoiceHeadType.CORPORATE && taxNumber == null) {
            throw BizException(ErrorCode.BIZ, "企业抬头必须填写纳税人识别号", mapOf("tax_number" to listOf("企业抬头必填")))
        }
        val isDef = form.isDef ?: false
        val now = TimeSec.now()
        val id = db.transactional {
            val editing = form.invoicesId?.takeIf { it > 0 }
            if (editing != null) {
                heads.findMine(editing, userId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "抬头不存在")
                if (heads.update(headEdit(editing, userId, companyId, type, name, taxNumber, form, isDef)) == 0) {
                    throw BizException(ErrorCode.NOT_FOUND, "抬头不存在")
                }
                if (isDef) heads.clearDefault(userId, companyId, editing)
                editing
            } else {
                if (isDef) heads.clearDefault(userId, companyId, 0)
                val d = MembersInvoiceDO().apply {
                    this.userId = userId
                    this.companyId = companyId
                    this.invoicesType = type
                    this.name = name
                    this.telephone = blankToNull(form.telephone)
                    this.taxNumber = taxNumber
                    this.businessAddress = blankToNull(form.businessAddress)
                    this.bank = blankToNull(form.bank)
                    this.bankAccount = blankToNull(form.bankAccount)
                    this.isDef = isDef
                    this.created = now
                }
                heads.insert(d)
                d.invoicesId
            }
        }
        return headView(heads.find(id) ?: throw BizException(ErrorCode.BIZ, "抬头写入失败"))
    }

    /**
     * INV-F6 删除抬头。
     *
     * **被引用过就不删**（§23.5 门槛④）：判据是 `orders_invoice.invoices_id`，
     * 不按"抬头名 + 税号"匹配——改名会漏判、两个公司重号会误判。
     * 历史发票上的抬头内容必须留得住，否则会员删了抬头，已开的发票就成了没有抬头的发票。
     */
    fun deleteHead(
        companyId: Long,
        userId: Long,
        invoicesId: Long,
    ): Map<String, Any?> {
        heads.findMine(invoicesId, userId, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "抬头不存在")
        val used = applies.countReferenced(companyId, invoicesId)
        if (used > 0) {
            throw BizException(ErrorCode.BIZ, "该抬头已被 $used 张发票申请引用，不能删除", mapOf("invoices_id" to listOf("已被发票申请引用")))
        }
        if (heads.delete(invoicesId, userId, companyId) == 0) throw BizException(ErrorCode.NOT_FOUND, "抬头不存在")
        return linkedMapOf("invoices_id" to invoicesId, "deleted" to true)
    }

    /**
     * INV-A1 后台发票申请列表。
     *
     * 数据权限与积分/储值那两处同一条口径：这张表没有店铺归属列，受限账号按
     * "这个会员在我名下的店有过单"收窄（`INV_WHERE` 的 EXISTS 分支），
     * 卡头与明细共用同一份筛选字典，所以不会出现"总数比明细大"。
     */
    fun adminList(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
        full: Boolean,
    ): PageResult<Map<String, Any?>> {
        val f = mapOf(
            "companyId" to companyId,
            "status" to statusFilter(raw["invoice_status"] ?: raw["status"]),
            "orderId" to blankToNull(raw["order_id"]),
            "userId" to raw["user_id"]?.toLongOrNull(),
            "invoiceType" to blankToNull(raw["invoice_type"])?.takeIf { it in InvoiceHeadType.APPLY_VALUES },
            "createdStart" to raw["time_start_begin"]?.toLongOrNull(),
            "createdEnd" to raw["time_start_end"]?.toLongOrNull(),
            "keyword" to blankToNull(raw["keyword"]),
            "distributorIds" to scope.narrow(listOfNotNull(blankToNull(raw["distributor_id"])?.toLongOrNull())),
        )
        return PageHelper.paginate(
            pageOf(raw),
            count = { applies.count(f) },
            query = { offset, limit -> applies.page(f, offset, limit) },
            map = { rows -> rows.map { adminView(it, full) } },
        )
    }

    /**
     * INV-A3 后台详情（同一权限码下面值，MASK-R1）。
     *
     * 受限账号要能看到这一条，唯一的判据就是"这个会员在我名下的店有没有单"——
     * 与 [adminList] 的 EXISTS 完全同一条，所以列表里出现的行在详情里一定看得到。
     */
    fun adminDetail(
        companyId: Long,
        id: Long,
        scope: DistributorScope,
        full: Boolean,
    ): Map<String, Any?> {
        val r = applies.findRow(id) ?: throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")
        if (r.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")
        if (!scope.unrestricted) {
            val mine = orders.count(
                mapOf("companyId" to companyId, "userId" to r.userId, "distributorIds" to scope.ids),
            )
            if (mine == 0L) throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")
        }
        return adminView(r, full)
    }

    /**
     * INV-A2 后台处理（`pending → issued/rejected`）。
     *
     * 三条判据的形状都是"状态迁移"而不是"字段校验"，所以用 422：
     *  - `issued` 必须带 `invoice_file_url` 或 `email` —— "开了票但会员什么都收不到"不接受；
     *  - `rejected` 必须带 `remark` —— 驳回不给理由，会员只能反复重新申请；
     *  - 双审并发只有一个能成：`transit` 的 WHERE 带着 `invoice_status = 'pending'`，回 0 行 → 422。
     */
    fun handle(
        companyId: Long,
        id: Long,
        form: InvoiceHandleForm,
    ): Map<String, Any?> {
        val to = blankToNull(form.invoiceStatus)
            ?: throw BizException(ErrorCode.VALIDATION, "invoice_status 不能为空", mapOf("invoice_status" to listOf("不能为空")))
        if (to != InvoiceApplyStatus.ISSUED && to != InvoiceApplyStatus.REJECTED) {
            throw BizException(ErrorCode.VALIDATION, "invoice_status 只能是 issued/rejected", mapOf("invoice_status" to listOf("取值非法")))
        }
        val a = applies.find(id) ?: throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")
        if (a.companyId != companyId) throw BizException(ErrorCode.NOT_FOUND, "发票申请不存在")
        if (a.invoiceStatus != InvoiceApplyStatus.PENDING) throw BizException(ErrorCode.BIZ, "该申请已处理")
        val fileUrl = blankToNull(form.invoiceFileUrl)
        val email = blankToNull(form.email)
        val remark = blankToNull(form.remark)
        if (to == InvoiceApplyStatus.ISSUED && fileUrl == null && email == null) {
            throw BizException(ErrorCode.BIZ, "开票必须带发票文件或收件邮箱", mapOf("invoice_file_url" to listOf("与 email 至少填一个")))
        }
        if (to == InvoiceApplyStatus.REJECTED && remark == null) {
            throw BizException(ErrorCode.BIZ, "驳回必须填写备注", mapOf("remark" to listOf("驳回时必填")))
        }
        val now = TimeSec.now()
        db.transactional {
            if (applies.transit(id, InvoiceApplyStatus.PENDING, to, fileUrl, email, remark, now) == 0) {
                throw BizException(ErrorCode.BIZ, "该申请已处理")
            }
            // 订单侧只同步状态、不回写快照（INV-R2 第 3 条）
            a.orderId?.toLongOrNull()?.let { orders.syncInvoiceStatus(it, to, now) }
        }
        return adminView(applies.findRow(id) ?: throw BizException(ErrorCode.BIZ, "发票申请不存在"), full = true)
    }

    /** 配置面：`invoice.types` 是 JSON 数组串（seed 现值 `["02"]`），脏值当"未限制"处理 */
    private fun allowedTypes(companyId: Long): List<String> {
        val raw = blankToNull(configs.read(companyId, KEY_TYPES)) ?: return emptyList()
        return try {
            mapper.readValue(raw, object : TypeReference<List<String>>() {})
        } catch (e: Exception) {
            // 不是数组就按单值读（历史上有人往这一列塞过裸 `02`）
            listOfNotNull(raw.takeIf { it != "[]" })
        }
    }

    /**
     * 票种判定：白名单为空 = 租户没限制，`invoice_type_code` 可空；
     * 白名单非空时，没传就落第一个（seed 只开普票 → `02`），传了不在白名单里 → 422。
     */
    private fun resolveTypeCode(
        companyId: Long,
        requested: String?,
    ): String? {
        val code = blankToNull(requested)
        val allowed = allowedTypes(companyId)
        if (allowed.isEmpty()) return code
        if (code == null) return allowed.first()
        if (code !in allowed) throw BizException(ErrorCode.BIZ, "该票种未开放申请", mapOf("invoice_type_code" to listOf("不在开放票种内")))
        return code
    }

    /** 状态筛选归一化：非法值当"没筛"而不是把列表筛成 0 行 */
    private fun statusFilter(v: String?): String? = blankToNull(v)?.takeIf { it in InvoiceApplyStatus.KNOWN }

    private fun parseOrderId(v: String?): Long =
        blankToNull(v)?.toLongOrNull() ?: throw BizException(ErrorCode.NOT_FOUND, "订单不存在")

    private fun requireField(
        field: String,
        value: String?,
    ): String = blankToNull(value) ?: throw BizException(ErrorCode.VALIDATION, "$field 不能为空", mapOf(field to listOf("不能为空")))

    /** 改 pending 那一行（只带上这次传了的字段，必填项一定非 null） */
    private fun pendingEdit(
        id: Long,
        invoiceType: String,
        typeCode: String?,
        companyTitle: String?,
        taxNumber: String?,
        email: String,
        remark: String?,
        head: MembersInvoiceDO?,
        amount: Long,
    ) = OrdersInvoiceDO().apply {
        this.id = id
        this.invoiceType = invoiceType
        this.invoiceTypeCode = typeCode
        this.companyTitle = companyTitle
        this.taxNumber = taxNumber
        this.email = email
        // 备注只在会员这一侧改（后台那条写的是驳回理由，发生在终态迁移上，两者不同时生效）
        this.remark = remark
        this.invoiceAmount = amount
        this.invoicesId = head?.invoicesId ?: 0
    }

    private fun headEdit(
        id: Long,
        userId: Long,
        companyId: Long,
        type: String,
        name: String,
        taxNumber: String?,
        form: InvoiceHeadForm,
        isDef: Boolean,
    ) = MembersInvoiceDO().apply {
        this.invoicesId = id
        this.userId = userId
        this.companyId = companyId
        this.invoicesType = type
        this.name = name
        this.telephone = blankToNull(form.telephone)
        this.taxNumber = taxNumber
        this.businessAddress = blankToNull(form.businessAddress)
        this.bank = blankToNull(form.bank)
        this.bankAccount = blankToNull(form.bankAccount)
        this.isDef = isDef
    }

    /**
     * 订单侧快照（INV-R2）。
     *
     * 保留 `type`/`content` 两个键：这一列历史上是下单时手填的开票信息
     * （`OrderCreateService.invoiceOf` 写的就是这两个键），ORDER-4 的出参把它整列原样吐给前台；
     * 正式申请落地后同一列存"申请那一刻的内容"，键名不换，前台不用改读法。
     */
    private fun snapshot(
        invoiceType: String,
        typeCode: String?,
        companyTitle: String?,
        taxNumber: String?,
        email: String,
        amount: Long,
    ): String = mapper.writeValueAsString(
        linkedMapOf(
            "type" to invoiceType,
            "content" to companyTitle,
            "invoice_type_code" to typeCode,
            "tax_number" to taxNumber,
            "email" to email,
            "invoice_amount" to amount,
        ),
    )

    /** 会员面：自己的值不掩码（这就是他自己填的） */
    private fun frontView(r: InvoiceRow): MutableMap<String, Any?> = linkedMapOf(
        "id" to r.id,
        "invoice_apply_bn" to r.invoiceApplyBn,
        "order_id" to r.orderId,
        "invoice_type" to r.invoiceType,
        "invoice_type_code" to r.invoiceTypeCode,
        "company_title" to r.companyTitle,
        "tax_number" to r.taxNumber,
        "email" to r.email,
        "mobile" to r.mobile,
        "invoice_status" to r.invoiceStatus,
        "invoice_amount" to r.invoiceAmount,
        "invoice_file_url" to r.invoiceFileUrl,
        "remark" to r.remark,
        "invoices_id" to r.invoicesId,
        "create_time" to r.createTime,
        "update_time" to r.updateTime,
    )

    /** 后台面：敏感四列按 datapass 决定全值还是掩码（MASK-R1） */
    private fun adminView(
        r: InvoiceRow,
        full: Boolean,
    ): MutableMap<String, Any?> = linkedMapOf(
        "id" to r.id,
        "invoice_apply_bn" to r.invoiceApplyBn,
        "user_id" to r.userId,
        "nickname" to r.nickname,
        "order_id" to r.orderId,
        "invoice_type" to r.invoiceType,
        "invoice_type_code" to r.invoiceTypeCode,
        "company_title" to (if (full) r.companyTitle else Mask.title(r.companyTitle)),
        "tax_number" to (if (full) r.taxNumber else Mask.taxNo(r.taxNumber)),
        "email" to (if (full) r.email else Mask.email(r.email)),
        "mobile" to (if (full) r.mobile else Mask.mobile(r.mobile)),
        "invoice_status" to r.invoiceStatus,
        "invoice_amount" to r.invoiceAmount,
        "invoice_file_url" to r.invoiceFileUrl,
        "remark" to r.remark,
        "invoices_id" to r.invoicesId,
        "create_time" to r.createTime,
        "update_time" to r.updateTime,
    )

    private fun headView(d: MembersInvoiceDO) = linkedMapOf<String, Any?>(
        "invoices_id" to d.invoicesId,
        "invoices_type" to d.invoicesType,
        "name" to d.name,
        "telephone" to d.telephone,
        "tax_number" to d.taxNumber,
        "business_address" to d.businessAddress,
        "bank" to d.bank,
        "bank_account" to d.bankAccount,
        "is_def" to d.isDef,
        "created" to d.created,
    )

    companion object {
        /** 发票功能开关（§23.4：seed 写 `1`） */
        const val KEY_ENABLE = "invoice.enable"

        /** 开放票种白名单（§23.4：seed 写 `["02"]` = 只开普票） */
        const val KEY_TYPES = "invoice.types"
    }
}
