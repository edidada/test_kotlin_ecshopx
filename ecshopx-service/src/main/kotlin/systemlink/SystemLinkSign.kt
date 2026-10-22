package com.ecshopx.kotlin.service.systemlink

import java.security.MessageDigest

/**
 * SystemLink 入站签名（M2-2 §27.1）。
 *
 * 源系统这一族有**两支** `gen_sign`，公式一模一样、`assemble` 不一样：
 * `SystemLinkBundle/Middleware/OpenApiCheck.php`（前缀 `/api/systemlink/openapi`）与
 * `ShopexErpCheck.php`（前缀 `/api/systemlink/ome`）。公式都是
 * `strtoupper(md5(strtoupper(md5(assemble(params))) + token))`，差异只有两处：
 *
 * | 输入 | [Mode.OPENAPI] | [Mode.OME] |
 * |---|---|---|
 * | `null` 值 | 保留，拼成 `键` + 空串 | 整体跳过（键也不出现） |
 * | 布尔值 | 保留，PHP 串化：`true→"1"`、`false→""` | 显式转 `1` / `0` |
 *
 * 这两处差异**不是笔误也不是可以统一的历史包袱**：对端是两份不同的代码，
 * 各按自己那一支算签。把它们合成一支，症状是"换一条入口就报签名错误"，
 * 而两边各自看代码都觉得自己对——所以这里用 [Mode] 把差异显式摆出来，
 * 并且 e2e 用**手写期望串**把两支的差别钉死（§27.6 门槛①）。
 *
 * 键序与嵌套规则同 [com.ecshopx.kotlin.service.openapi.OpenapiSign]（PHP `ksort(..., SORT_STRING)`、
 * 列表下标按字符串序 `"0" < "1" < "10" < "2"`、数字用字面量文本）。
 */
enum class SystemLinkSignMode {
    /** 前缀 `/api/systemlink/openapi`：`OpenApiCheck` 的语义 */
    OPENAPI,

    /** 前缀 `/api/systemlink/ome`：`ShopexErpCheck` 的语义 */
    OME,
}

object SystemLinkSign {

    /** UTF-8 字节序比较器（等价 PHP `SORT_STRING`） */
    private val BYTE_ORDER: Comparator<String> = Comparator { a, b ->
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return@Comparator c
        }
        x.size - y.size
    }

    fun assemble(params: Map<String, Any?>, mode: SystemLinkSignMode): String {
        val sb = StringBuilder()
        params.keys.sortedWith(BYTE_ORDER).forEach { key ->
            val v = params[key]
            if (v == null && mode == SystemLinkSignMode.OME) return@forEach  // OME：整体跳过
            sb.append(key).append(leaf(v, mode))
        }
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun leaf(v: Any?, mode: SystemLinkSignMode): String = when (v) {
        null -> ""                                             // OPENAPI：键在、值为空串
        is String -> v
        is Boolean -> when {
            mode == SystemLinkSignMode.OME -> if (v) "1" else "0"
            else -> if (v) "1" else ""                         // PHP 把 false 串成空串
        }
        is Map<*, *> -> assemble(v as Map<String, Any?>, mode)
        is List<*> -> assemble(v.mapIndexed { i, e -> i.toString() to e }.toMap(), mode)
        else -> v.toString()
    }

    /** `strtoupper(md5(strtoupper(md5(assemble)) + token))` */
    fun sign(params: Map<String, Any?>, token: String, mode: SystemLinkSignMode): String =
        md5Upper(md5Upper(assemble(params, mode)) + token)

    fun md5Upper(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        d.forEach { b -> hex.append("%02x".format(b.toInt() and 0xff)) }
        return hex.toString().uppercase()
    }
}

/**
 * IP 白名单（§27.1 末段）：逗号分隔，支持单个 IPv4 与 CIDR（`10.0.0.0/8`），空 = 不限制。
 *
 * 判据放在**签名之后**：先证明"你是你"，再判"你从哪来"。
 * 顺序反了会把两件事混成一个错误——白名单里的陌生人拿错 token 会收到 `ip access denied`，
 * 而对端会去查网络，实际错的是密钥。
 *
 * IPv6 只支持整串精确匹配（不展开缩写形式）：这一族对端是 ERP，实际来源都是 IPv4；
 * 真要为 IPv6 写展开逻辑，值得单开一批而不是塞在这里。
 */
object IpWhitelist {

    fun allows(whitelist: String?, ip: String?): Boolean {
        val rules = whitelist?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return true
        if (rules.isEmpty()) return true
        val target = normalize(ip ?: return false) ?: return false
        return rules.any { rule ->
            if (rule.contains('/')) inCidr(target, rule) else normalize(rule) == target
        }
    }

    /** `::ffff:127.0.0.1`（IPv4-mapped）折回点分十进制；其余原样返回 */
    private fun normalize(ip: String): String? {
        val v = ip.trim()
        if (v.isEmpty()) return null
        val mapped = "::ffff:"
        if (v.startsWith(mapped, ignoreCase = true)) return v.substring(mapped.length)
        return v
    }

    private fun inCidr(ip: String, rule: String): Boolean {
        val parts = rule.split('/')
        if (parts.size != 2) return false
        val base = toLong(parts[0]) ?: return false
        val bits = parts[1].toIntOrNull() ?: return false
        if (bits !in 0..32) return false
        val target = toLong(ip) ?: return false
        if (bits == 0) return true
        val mask = (0xffffffffL shl (32 - bits)) and 0xffffffffL
        return (base and mask) == (target and mask)
    }

    private fun toLong(ip: String): Long? {
        val seg = ip.split('.')
        if (seg.size != 4) return null
        var v = 0L
        for (s in seg) {
            val n = s.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }
}
