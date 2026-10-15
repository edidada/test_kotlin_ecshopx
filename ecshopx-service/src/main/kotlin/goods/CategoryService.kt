package com.ecshopx.kotlin.service.goods

import com.ecshopx.kotlin.dao.goods.ItemCategoryDO
import com.ecshopx.kotlin.dao.goods.ItemsCategoryMapper
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 分类树 + Caffeine 5m 缓存（M0-4 工作项"分类树缓存"）。
 * expandSelf：展开自身 + 全部后代（PHP category 筛选语义，按 parent_id 递归）。
 * 实例方法、实例级缓存：无静态/ThreadLocal 接线，请求线程与初始化线程无关。
 */
@Component
class CategoryService(private val cats: ItemsCategoryMapper) {

    fun tree(companyId: Long): List<CategoryNode> =
        treeCache.get(companyId) { id -> buildTree(cats.listByCompany(id)) }

    fun allCategories(companyId: Long): List<ItemCategoryDO> = cats.listByCompany(companyId)

    /** 叶子→子树 id 集合（含自身）；companyId 决定读哪份扁平缓存（缺失时直查库） */
    fun expandSelf(categoryId: Long, companyId: Long = 1L): Set<Long> {
        val flat = flatCache.get(companyId) { allCategories(companyId) }
        val out = linkedSetOf(categoryId)
        fun walk(parent: Long) {
            flat.filter { it.parentId == parent }.forEach { out += it.categoryId; walk(it.categoryId) }
        }
        walk(categoryId)
        return out
    }

    private val treeCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(256)
        .build<Long, List<CategoryNode>>()

    private val flatCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5))
        .maximumSize(8)
        .build<Long, List<ItemCategoryDO>>()

    fun invalidate(companyId: Long) {
        treeCache.invalidate(companyId)
        flatCache.invalidate(companyId)
    }

    /** 简单树节点（后台分类管理端点 M1 再做，这里先支撑展开与前台导航缓存） */
    data class CategoryNode(
        val category_id: Long,
        val category_name: String,
        val parent_id: Long,
        val is_main_category: Boolean,
        val children: List<CategoryNode>,
    )

    private fun buildTree(list: List<ItemCategoryDO>): List<CategoryNode> {
        fun node(parentId: Long): List<CategoryNode> =
            list.filter { it.parentId == parentId }.map { c ->
                CategoryNode(c.categoryId, c.categoryName, c.parentId, c.isMainCategory, node(c.categoryId))
            }
        return node(0L)
    }
}
