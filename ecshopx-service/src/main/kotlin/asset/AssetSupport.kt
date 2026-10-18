package com.ecshopx.kotlin.service.asset

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

/**
 * M1-5 积分/储值域的入参与读配置小工具（docs/04 §23.1/§23.2）。
 *
 * 两个域共用一个文件：它们的表单都只有三五个字段，且共用同一条 `sys_config` 读法
 * （PT-A4 与 CZ 的 `enable` 都读开关键）。分成两个文件只会让"租户行→全局行"这条口径写两遍。
 */

/** PT-A2 后台调账 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PointAdjustForm(
    val userId: Long? = null,
    /** 正=加 负=减；0 直接拒（"调 0 分"只会留下一条没人看得懂的流水） */
    val changePoint: Long? = null,
    /** 必填：这是唯一"无订单来源"的动账口，对账脚本要靠它解释差额 */
    val remark: String? = null,
)

/** PT-A4 规则写回（整组写，落 `sys_config` 租户层） */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class PointRuleForm(
    /** '1'/'0' 词表，与库里现值同形；也收 true/false 与 1/0 */
    @JsonProperty("pay.point.enable") val pointEnable: String? = null,
    @JsonProperty("point.deduct.max_ratio") val deductMaxRatio: String? = null,
    @JsonProperty("deposit.recharge.point.rate") val rechargePointRate: String? = null,
)

/** CZ-A2 档位新建/修改 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class RechargeRuleForm(
    val id: Long? = null,
    val ruleName: String? = null,
    val rechargeAmount: Long? = null,
    val giftAmount: Long? = null,
    /** 1 上架 / 0 下架；缺省按 1 */
    val status: Int? = null,
)

/** CZ-A3 后台代客充值 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OperatorRechargeForm(
    val userId: Long? = null,
    /** 单位分，>0 */
    val amount: Long? = null,
    val giftAmount: Long? = null,
    /** 必填，落 `deposit_trade.remark` */
    val remark: String? = null,
)

/**
 * `sys_config` 的两层读法：租户行 → 全局（`company_id = 0`）行 → 调用方给的默认值。
 *
 * 这条口径在 `CartService.pointRatio` 里已经写了一遍，本批四个域都要读配置，
 * 所以提成扩展函数收在一处 —— 配置读法散在各服务里是"某个开关在某个页面不生效"这类问题的温床。
 */
fun SysConfigMapper.read(companyId: Long, key: String): String? =
    getValue(companyId, key) ?: getValue(0L, key)

/**
 * 开关值读取。
 *
 * 词表是 `'1'/'0'`（库里现值就是这么写的，见 §23.4 "值用现库的 '1'/'0' 词表，不要写成 'true'"），
 * 但出参要的是 JSON 布尔，所以在这里收口一次；`'true'/'false'` 也认，
 * 免得有人从 SUP-1 的通用配置接口用字符串写回一个 `true` 就把开关读成关。
 */
fun SysConfigMapper.flag(
    companyId: Long,
    key: String,
    def: Boolean,
): Boolean = read(companyId, key)?.trim()?.lowercase()?.let { it == "1" || it == "true" } ?: def

/** GET 查询串 → 分页参数（`pageSize`/`page_size` 两种写法并存，与源系统一致） */
fun pageOf(raw: Map<String, String>): PageQuery =
    PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())

/** 空白串当"没传"：筛选参数一律先过这里，不然 `keyword=` 会把列表筛成 0 行 */
fun blankToNull(v: String?): String? = v?.takeIf { it.isNotBlank() }

/**
 * 必填字符串校验。
 *
 * 名字不叫 `require`：Kotlin 标准库已有 `require(Boolean)`，同名重载会让读代码的人
 * 每一处都要去数参数个数才知道是哪个。
 */
fun requireText(
    field: String,
    value: String?,
    message: String = "不能为空",
): String = blankToNull(value) ?: throw BizException(ErrorCode.VALIDATION, "$field $message", mapOf(field to listOf(message)))

fun requirePositive(
    field: String,
    value: Long?,
): Long = value?.takeIf { it > 0 } ?: throw BizException(ErrorCode.VALIDATION, "$field 必须大于 0", mapOf(field to listOf("必须大于 0")))
