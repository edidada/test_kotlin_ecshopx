package com.ecshopx.kotlin.service.support

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageQuery
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.auth.OperatorLogsMapper
import com.ecshopx.kotlin.dao.goods.SysConfigDO
import com.ecshopx.kotlin.dao.goods.SysConfigMapper
import com.ecshopx.kotlin.dao.paging.PageHelper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * SUP-1 配置读写（`sys_config`）。
 *
 * 两层模型：`company_id = 0` 是全局默认，租户行覆盖同名全局键。
 * 读接口把两层**合并后**吐出（保留来源 company_id，便于后台区分"这是默认值还是本租户改过"），
 * 写接口只落租户层——后台永远不该改到全局行，否则一个租户的运营动作会打到所有租户。
 */
@Component
class ConfigService(
    private val configs: SysConfigMapper,
    private val mapper: ObjectMapper,
) {

    fun read(companyId: Long): PageResult<Map<String, Any?>> {
        val merged = linkedMapOf<String, MutableMap<String, Any?>>()
        configs.listForCompany(companyId).sortedBy { it.companyId }.forEach { c ->
            merged[c.configKey] = linkedMapOf(
                "id" to c.id,
                "company_id" to c.companyId,
                "config_key" to c.configKey,
                "config_value" to c.configValue,
                "updated" to c.updated,
            )
        }
        val list = merged.values.sortedBy { it["config_key"].toString() }
        return PageResult(list.size.toLong(), list)
    }

    /** 键值对象写入：{"order.auto_cancel_minutes":"45", …}；值为对象/数组时序列化存 JSON 文本 */
    fun write(companyId: Long, raw: Map<String, Any?>): PageResult<Map<String, Any?>> {
        if (raw.isEmpty()) throw BizException(ErrorCode.VALIDATION, "config 不能为空", mapOf("config" to listOf("不能为空")))
        raw.forEach { (k, v) ->
            if (k.isBlank()) throw BizException(ErrorCode.VALIDATION, "config_key 不能为空", mapOf(k to listOf("不能为空")))
            configs.upsert(
                SysConfigDO().apply {
                    this.companyId = companyId
                    configKey = k
                    configValue = stringify(v)
                    updated = TimeSec.now()
                },
            )
        }
        return read(companyId)
    }

    private fun stringify(v: Any?): String? = when (v) {
        null -> ""
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> mapper.writeValueAsString(v)
    }
}

/**
 * SUP-4 操作审计日志分页（数据由 `@Audited` 切面写入 operator_logs）。
 * 只读，不提供删除——审计日志的可追溯性优先于后台"清理"便利性。
 */
@Component
class OperatorLogService(private val logs: OperatorLogsMapper) {

    fun list(companyId: Long, raw: Map<String, String>): PageResult<Map<String, Any?>> {
        val pq = PageQuery.of(raw["page"]?.toIntOrNull(), raw["pageSize"]?.toIntOrNull() ?: raw["page_size"]?.toIntOrNull())
        val f = mapOf(
            "operatorId" to raw["operator_id"]?.toLongOrNull(),
            "keyword" to raw["keyword"]?.takeIf { it.isNotBlank() },
            "from" to raw["start_time"]?.toLongOrNull(),
            "to" to raw["end_time"]?.toLongOrNull(),
        )
        return PageHelper.paginate(
            pq,
            count = { logs.count(companyId, f) },
            query = { offset, limit -> logs.page(companyId, f, offset, limit) },
            map = { rows -> rows.map { linkedMapOf<String, Any?>(
                "id" to it.id,
                "company_id" to it.companyId,
                "operator_id" to it.operatorId,
                "operator_name" to it.operatorName,
                "route_name" to it.routeName,
                "method" to it.method,
                "params" to it.params,
                "ip" to it.ip,
                "created" to it.created,
            ) } },
        )
    }
}
