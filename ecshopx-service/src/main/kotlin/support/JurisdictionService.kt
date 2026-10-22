package com.ecshopx.kotlin.service.support

import com.ecshopx.kotlin.dao.support.JurisdictionMapper
import org.springframework.stereotype.Service

/**
 * 地区库（M2-3 批E）：`ecx.jurisdiction.get` 的委托体。
 *
 * 一层递归把 `sys_jurisdiction` 的平表按 `parent_id` 装成省/市/区县三级树；
 * 力度约定写在 C237（精简 seed，演示租户的开业地域），不在这里另做分页——
 * 地区库的消费方式是"整棵拿下去本地过滤"，翻页反而添乱。
 */
@Service
class JurisdictionService(private val mapper: JurisdictionMapper) {

    fun tree(): List<Map<String, Any?>> {
        val rows = mapper.listAll()
        val byParent = rows.groupBy { it.parentId }

        fun of(parentId: Long): List<Map<String, Any?>> = byParent[parentId].orEmpty().map { r ->
            val m = linkedMapOf<String, Any?>("area_id" to r.id, "name" to r.name, "level" to r.level)
            val children = of(r.id)
            if (children.isNotEmpty()) m["children"] = children
            m
        }
        return of(0L)
    }
}
