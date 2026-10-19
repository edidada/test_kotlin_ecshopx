package com.ecshopx.kotlin.service.openapi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * 网关的入参视图（M2-1 §25.1）。
 *
 * 源系统是 `$request->toArray()` —— **query 与请求体合并后的全集**，签名也覆盖这个合并集。
 * 这一类就是那个合并集的落地，另加一件事：**数字必须保留请求里的字面量文本**。
 *
 * 为什么要专门写一个类而不是直接传 `Map<String, String>`：
 * 签名侧要看结构（嵌套对象/数组要递归 `assemble`），委托侧要看字符串（既有 service 的筛选入参
 * 全是 `Map<String, String?>`）。两边共用一份解析结果，才能保证"签的就是收到的值"——
 * 分成两次解析时，很容易出现"签名用 A 形状、业务用 B 形状"，那种实现只能防住老实人。
 */
class OpenapiRequest(val params: Map<String, Any?>) {

    /** 标量视图：只取叶子（字符串/布尔/数字的字面量文本），嵌套结构不进这张表 */
    val scalars: Map<String, String> by lazy {
        LinkedHashMap<String, String>().apply {
            params.forEach { (k, v) -> OpenapiParams.leafText(v)?.let { put(k, it) } }
        }
    }

    /**
     * 委托侧要的 raw 形态。
     *
     * 值类型是 `String`（不是 `String?`）：既有 service 的筛选入参两种签名都有
     * （`Map<String, String>` 与 `Map<String, String?>`），而 `Map` 的值位是协变的，
     * 所以这张表能同时喂给两边——嵌套结构在这里天然缺席（[OpenapiParams.leafText] 返回 null 就不入表）。
     */
    fun raw(): Map<String, String> = scalars

    fun str(key: String): String? = OpenapiParams.leafText(params[key])

    fun long(key: String): Long? = str(key)?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()

    fun int(key: String): Int? = str(key)?.trim()?.toIntOrNull()

    /** 三态布尔：没传 = null（不是 false）。M1-5 的 C194 就是被"顺手压成 false"坑过 */
    fun bool(key: String): Boolean? = when (val v = params[key]) {
        null -> null
        is Boolean -> v
        else -> when (v.toString().trim().lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun list(key: String): List<Any?> = when (val v = params[key]) {
        is List<*> -> v
        is Map<*, *> -> (v as Map<String, Any?>).entries.sortedBy { it.key }.map { it.value }
        // 标量写法 `user_ids=1,2` 也认：对接方最常见的形态
        else -> str(key)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** 列表取 Long 形态：`user_ids=[1,2]` 与 `user_ids=1,2` 两种写法都认（对接方混用是常态） */
    fun longList(key: String): List<Long> = list(key).mapNotNull { OpenapiParams.leafText(it)?.trim()?.toLongOrNull() }

    /** 必填文本：空串与纯空白都算没传（源系统 `require` 校验器同判据） */
    fun requireText(key: String): String {
        val v = str(key)?.trim()
        if (v.isNullOrEmpty()) throw OpenapiException(OpenapiCode.PARAM, "$key 必填")
        return v
    }

    fun requireLong(key: String): Long {
        val v = long(key) ?: throw OpenapiException(OpenapiCode.PARAM, "$key 必填")
        return v
    }

    /**
     * 从入参里摘出租户位（第 9 道闸门的判据）。
     * 键名兼容 `company_id` 与 `cid` 两种写法（源系统两版都有出现），只认正整数。
     */
    fun requestedCompanyId(): Long? = long("company_id") ?: long("cid")
}

/**
 * 参数解析与叶子取值（§25.1 签名算法里"数字用字面量文本"那一条的落地处）。
 */
object OpenapiParams {

    /**
     * 合并 query（含表单字段）与 JSON 请求体为签名用的全集。
     *
     * 同键冲突时**请求体胜出**，与 Laravel `Request::all()` 的 `array_merge($query, $input)` 同序；
     * 这件事在源系统没有文档写过，但它是"对端把 method 放 query、把业务参数放 body 时能不能对上签"的分界，
     * 所以在这里定死并写进注释（门槛①的用例覆盖它）。
     */
    fun merge(query: Map<String, String>, bodyJson: String?, mapper: ObjectMapper): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        query.forEach { (k, v) -> out[k] = v }
        val node = parseObject(bodyJson, mapper) ?: return out
        node.fields().forEach { (k, v) -> out[k] = toValue(v) }
        return out
    }

    /** 只把 `{` 开头的报文当 JSON 对象；其余（表单/XML/空）交给 query 侧的 map，与 PHP 行为一致 */
    fun parseObject(body: String?, mapper: ObjectMapper): JsonNode? {
        val text = body?.trim() ?: return null
        if (!text.startsWith("{")) return null
        val node = runCatching { mapper.readTree(text) }.getOrNull() ?: return null
        return node.takeIf { it.isObject }
    }

    /**
     * JsonNode → 签名可用的值。
     * - 数字返回**文本**（`1` 与 `1.0` 是不同的串；PHP 侧 `json_decode` 后按字符串拼接，
     *   `(string)1.0` 得 `"1"`，所以整数值的浮点在这里去掉小数尾巴，两边才对得上）；
     * - 布尔返回 `Boolean`，由 [OpenapiSign.assemble] 折成 `"1"/"0"`；
     * - `null` 原样保留为 null（`assemble` 整体跳过该键，键名也不出现）；
     * - 对象/数组保持结构，交给 `assemble` 递归。
     */
    fun toValue(node: JsonNode?): Any? {
        if (node == null || node.isNull || node.isMissingNode) return null
        return when {
            node.isBoolean -> node.booleanValue()
            node.isNumber -> numericText(node)
            node.isTextual -> node.textValue()
            node.isObject -> {
                val m = LinkedHashMap<String, Any?>()
                node.fields().forEach { (k, v) -> m[k] = toValue(v) }
                m
            }
            node.isArray -> node.map { toValue(it) }
            else -> node.asText()
        }
    }

    private fun numericText(node: JsonNode): String = when {
        node.isIntegralNumber -> node.asText()
        else -> {
            val d = node.doubleValue()
            // 整值浮点去掉小数尾巴（PHP `(string)1.0 == "1"`）；非整值用最短十进制表示
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else node.asText()
        }
    }

    /** 叶子 → 字符串；结构（对象/数组）返回 null，表示"这不是一个标量入参" */
    fun leafText(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Boolean -> if (v) "1" else "0"
        is Map<*, *>, is List<*> -> null
        else -> v.toString()
    }
}
