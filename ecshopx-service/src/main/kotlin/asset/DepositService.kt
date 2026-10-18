package com.ecshopx.kotlin.service.asset

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.ecshopx.kotlin.dao.pay.DepositDO
import com.ecshopx.kotlin.dao.pay.DepositMapper
import com.ecshopx.kotlin.dao.pay.DepositStatsDO
import com.ecshopx.kotlin.dao.pay.DepositTradeRow
import com.ecshopx.kotlin.dao.pay.RechargeRuleDO
import com.ecshopx.kotlin.service.distribution.DistributorScope
import com.ecshopx.kotlin.service.pay.PayService
import org.springframework.stereotype.Service

/**
 * M1-5 储值域（docs/04 §23.1 `CZ-F1..F3`、§23.2 `CZ-A1..A4`）。
 *
 * 账的一半（`user_deposit` 的 CAS 扣款、`deposit_trade` 的留痕、充值结清）在 M0-7/M0-8 就有了，
 * 本批补读口、档位 CRUD 与后台代客充值。两条纪律：
 *  1. **CZ-R1 赠金与本金在账上分列、在支付面上合并**：`balance = deposit_amount + gift_amount`，
 *     两个数分开存是因为退款要按原比例回补（`DepositMapper.refund`），
 *     合并成一个数就没法把"充值送的"和"自己充的"分开了；
 *  2. **代客充值不开第二条入账路径**：[operatorRecharge] 只是把参数交给 `PayService`，
 *     真正动余额的仍是会员自己充值走的那条结清腿 —— 两处各写一遍 `UPDATE user_deposit`
 *     迟早有一处漏掉 `total_recharge`。
 */
@Service
class DepositService(
    private val deposits: DepositMapper,
    private val configs: SysConfigMapper,
    private val pays: PayService,
) {

    /** CZ-F1 我的余额 */
    fun overview(
        userId: Long,
        companyId: Long,
    ): Map<String, Any?> {
        val acc = deposits.find(companyId, userId) ?: DepositDO()
        return linkedMapOf(
            "deposit_amount" to acc.depositAmount,
            "gift_amount" to acc.giftAmount,
            // 赠金也是钱，只在前端分栏显示；扣款顺序"先赠金后本金"在 DepositChannel 里定
            "balance" to acc.depositAmount + acc.giftAmount,
            "total_recharge" to acc.totalRecharge,
            "enable" to configs.flag(companyId, KEY_DEPOSIT_ENABLE, true),
        )
    }

    /** CZ-F2 我的余额流水（`NOTPAY` 的充值单也在内 —— 他还在等支付） */
    fun logs(
        userId: Long,
        companyId: Long,
        raw: Map<String, String>,
    ): PageResult<Map<String, Any?>> = paged(
        raw,
        mapOf(
            "companyId" to companyId,
            "userId" to userId,
            "tradeType" to blankToNull(raw["trade_type"]),
        ),
        admin = false,
        full = false,
    )

    /** CZ-F3 充值档位（只出上架的，按金额升序；无档位回空集，前台自己决定要不要放开自定义金额） */
    fun rules(companyId: Long): Map<String, Any?> = linkedMapOf(
        "list" to deposits.listRules(companyId).map { ruleView(it) },
        // 括号是必需的：`a to b ?: c` 里 elvis 比中缀 `to` 结合得更松，不加括号就变成了
        // `("键" to 值) ?: 兜底`，编译期报的是"参数类型不匹配"，看不出错在哪
        "recharge_point_rate" to ((configs.read(companyId, PointService.KEY_RECHARGE_POINT_RATE)?.toDoubleOrNull()) ?: 0.0),
    )

    /** CZ-A1 储值交易列表（后台） */
    fun adminTrades(
        companyId: Long,
        raw: Map<String, String>,
        scope: DistributorScope,
        full: Boolean,
    ): PageResult<Map<String, Any?>> = paged(
        raw,
        mapOf(
            "companyId" to companyId,
            "userId" to raw["user_id"]?.toLongOrNull(),
            "tradeType" to blankToNull(raw["trade_type"]),
            "status" to blankToNull(raw["status"]),
            "relId" to blankToNull(raw["rel_id"]),
            "keyword" to blankToNull(raw["keyword"]),
            "createdStart" to raw["time_start_begin"]?.toLongOrNull(),
            "createdEnd" to raw["time_start_end"]?.toLongOrNull(),
            "distributorIds" to scope.narrow(null),
        ),
        admin = true,
        full = full,
    )

    /**
     * CZ-A4 储值总览：两个时间轴各说一件事，不拿一个去减另一个。
     *
     * `principal_sum`/`gift_sum` 读 `user_deposit` 的**现值**（此刻账上还有多少），
     * `recharge_sum_7d` 读 `deposit_trade` 的**历史**（近 N 天进来了多少）——
     * 前者会被消费与退款拉低，后者只增不减，两者相减没有任何意义（§23.2 CZ-A4 原话）。
     */
    fun count(
        companyId: Long,
        raw: Map<String, String>,
    ): Map<String, Any?> {
        val stats = deposits.stats(companyId)
        val days = raw["days"]?.toIntOrNull()?.coerceIn(1, 90) ?: 7
        return linkedMapOf(
            "principal_sum" to stats.principalSum,
            "gift_sum" to stats.giftSum,
            "notpay_count" to deposits.notpayCount(companyId),
            "recharge_sum_7d" to deposits.rechargeSumSince(companyId, TimeSec.now() - days * 86400L),
            "recharge_days" to days,
            "member_count" to stats.memberCount,
            "balance_sum" to stats.principalSum + stats.giftSum,
        )
    }

    /** CZ-A2 档位列表（含下架的） */
    fun adminRules(
        companyId: Long,
        raw: Map<String, String>,
    ): Map<String, Any?> {
        val rows = deposits.listRulesAll(companyId, raw["status"]?.toIntOrNull())
        return linkedMapOf("list" to rows.map { ruleView(it) }, "total_count" to rows.size.toLong())
    }

    /** CZ-A2 新建档位 */
    fun createRule(
        companyId: Long,
        form: RechargeRuleForm,
    ): Map<String, Any?> {
        val amount = requirePositive("recharge_amount", form.rechargeAmount)
        val gift = form.giftAmount ?: 0L
        if (gift < 0L) throw BizException(ErrorCode.VALIDATION, "gift_amount 不能为负", mapOf("gift_amount" to listOf("不能为负")))
        val r = RechargeRuleDO().apply {
            this.companyId = companyId
            ruleName = blankToNull(form.ruleName) ?: "充 ${amount / 100} 送 ${gift / 100}"
            rechargeAmount = amount
            giftAmount = gift
            status = form.status ?: 1
        }
        deposits.insertRule(r)
        return ruleView(r)
    }

    /** CZ-A2 修改档位 */
    fun updateRule(
        companyId: Long,
        id: Long,
        form: RechargeRuleForm,
    ): Map<String, Any?> {
        val r = deposits.findRule(id, companyId) ?: throw BizException(ErrorCode.NOT_FOUND, "档位不存在")
        val amount = requirePositive("recharge_amount", form.rechargeAmount ?: r.rechargeAmount)
        val gift = form.giftAmount ?: r.giftAmount
        if (gift < 0L) throw BizException(ErrorCode.VALIDATION, "gift_amount 不能为负", mapOf("gift_amount" to listOf("不能为负")))
        r.ruleName = blankToNull(form.ruleName) ?: r.ruleName
        r.rechargeAmount = amount
        r.giftAmount = gift
        r.status = form.status ?: r.status
        deposits.updateRule(r)
        return ruleView(r)
    }

    /**
     * CZ-A2 删除档位：**不设守卫**。
     *
     * 档位是价目表不是账 —— 历史 `deposit_trade` 已把**当时**的 amount/gift_amount 写进自己那一行，
     * 删掉规则不会让任何一笔流水失去解释。这一点与 `INV-F6`（抬头删不掉）正好相反：
     * 流水引用的是数值快照，发票引用的是一个身份。
     */
    fun deleteRule(
        companyId: Long,
        id: Long,
    ): Map<String, Any?> {
        // 删除影响 0 行只可能是"这一档不在这个租户下"：按 404 报，不回"删除成功"
        if (deposits.deleteRule(id, companyId) == 0) throw BizException(ErrorCode.NOT_FOUND, "档位不存在")
        return linkedMapOf("id" to id, "deleted" to true)
    }

    /**
     * CZ-A3 后台代客充值（不走支付渠道，当场落账）。
     *
     * `remark` 必填并落进 `deposit_trade.remark`：这条与 PT-A2 调账同属"没有订单来源的动账"，
     * 对账脚本要靠它把 `Σ 成功动账 == 余额` 这条恒等式里的差额讲清楚。
     * 入账腿复用 `PayService.rechargeByOperator`（内部走会员充值的同一条结清），见类注释第 2 条。
     */
    fun operatorRecharge(
        companyId: Long,
        form: OperatorRechargeForm,
    ): Map<String, Any?> {
        val userId = form.userId ?: throw BizException(ErrorCode.VALIDATION, "user_id 必填", mapOf("user_id" to listOf("不能为空")))
        val amount = requirePositive("amount", form.amount)
        val gift = form.giftAmount ?: 0L
        if (gift < 0L) throw BizException(ErrorCode.VALIDATION, "gift_amount 不能为负", mapOf("gift_amount" to listOf("不能为负")))
        val remark = requireText("remark", form.remark)
        val settled = pays.rechargeByOperator(userId, companyId, amount, gift, remark)
        val deposit = (settled["deposit"] as? Map<String, Any?>) ?: mapOf()
        return linkedMapOf(
            "trade_id" to settled["trade_id"],
            "user_id" to userId,
            "amount" to amount,
            "gift_amount" to gift,
            "remark" to remark,
            "trade_state" to settled["trade_state"],
            "deposit_amount" to (deposit["deposit_amount"] ?: 0L),
            "gift_balance" to (deposit["gift_amount"] ?: 0L),
            "balance" to (deposit["balance"] ?: 0L),
        )
    }

    private fun paged(
        raw: Map<String, String>,
        f: Map<String, Any?>,
        admin: Boolean,
        full: Boolean,
    ): PageResult<Map<String, Any?>> = PageHelper.paginate(
        pageOf(raw),
        count = { deposits.tradeCount(f) },
        query = { offset, limit -> deposits.tradePage(f, offset, limit) },
        map = { rows -> rows.map { tradeView(it, admin, full) } },
    )

    /**
     * 动账出参。
     *
     * `amount` 是**总动账**、`gift_amount` 是其中赠金那部分（CZ-R1 的记法），
     * 所以"这次动了多少本金"= `amount - gift_amount`，不在出参里另起一列 ——
     * 起了就有人会去改它，而它是个派生值。
     */
    private fun tradeView(
        r: DepositTradeRow,
        admin: Boolean,
        full: Boolean,
    ): MutableMap<String, Any?> {
        val m = linkedMapOf<String, Any?>(
            "trade_id" to r.tradeId,
            "user_id" to r.userId,
            "trade_type" to r.tradeType,
            "rel_id" to r.relId,
            "amount" to r.amount,
            "gift_amount" to r.giftAmount,
            "before_balance" to r.beforeBalance,
            "after_balance" to r.afterBalance,
            "status" to r.status,
            "remark" to r.remark,
            "created" to r.created,
        )
        if (admin) {
            m["nickname"] = r.nickname
            m["mobile"] = if (full) r.mobile else Mask.mobile(r.mobile)
            m["mobile_masked"] = Mask.mobile(r.mobile)
        }
        return m
    }

    private fun ruleView(r: RechargeRuleDO) = linkedMapOf<String, Any?>(
        "id" to r.id,
        "rule_name" to r.ruleName,
        "recharge_amount" to r.rechargeAmount,
        "gift_amount" to r.giftAmount,
        "status" to r.status,
    )

    companion object {
        /** 余额支付开关（库里已有键，不另起同义键） */
        const val KEY_DEPOSIT_ENABLE = "pay.deposit.enable"
    }
}
