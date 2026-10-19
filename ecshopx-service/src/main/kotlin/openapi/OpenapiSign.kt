package com.ecshopx.kotlin.service.openapi

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

/**
 * 开放平台签名（M2-1 §25.1）。**入向校验与出向包裹共用这一支函数**，
 * 因为源系统就是同一支：入向 `OpenapiBundle/Middleware/OpenapiCheck.php::gen_sign/assemble`，
 * 出向 `ThirdPartyBundle/Services/MarketingCenter/Request.php` 里那行
 * `$input['sign'] = strtoupper(md5($appSecret.$this->assemble($input).$appSecret))`。
 * 分两支写迟早会在"嵌套数组怎么折"这种细节上漂开，而这类漂移的症状是对端报"签名错误"，
 * 两边各自看代码都觉得自己对。
 *
 * 规则一条都不许"顺手改"（§25.1 逐字抄自 PHP）：
 * - 键按**字节序**升序（PHP `ksort($params, SORT_STRING)`）；
 * - 值为 `null` 的项整体跳过（键也不出现）；
 * - 布尔 → `"1"`/`"0"`；
 * - 值是对象/数组 → 递归（同样排序）。**数组在 PHP 里是"键为 0..n-1 的 map"**，
 *   于是列表下标同样按字符串序排：`"0" < "1" < "10" < "2"` —— 这条最反直觉，
 *   但它是"十个以上元素的数组"能不能对上签的分水岭，门槛①专门测它；
 * - 数字用**请求里的字面量文本**（`1` 与 `1.0` 是不同串，PHP 侧全程按字符串拼），
 *   所以解析阶段就把数字节点存成原文，不在这里 `toString()` 一个二次解析出来的 double。
 */
object OpenapiSign {

    /** UTF-8 字节序比较器（等价 PHP `SORT_STRING`）。键名一般是 ASCII，但中文键也必须对得上 */
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

    fun assemble(params: Map<String, Any?>): String {
        val sb = StringBuilder()
        params.keys.sortedWith(BYTE_ORDER).forEach { key ->
            val v = params[key]
            if (v == null) return@forEach          // 整体跳过：键也不出现
            sb.append(key).append(leaf(v))
        }
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun leaf(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Boolean -> if (v) "1" else "0"
        is Map<*, *> -> assemble(v as Map<String, Any?>)
        is List<*> -> assemble(v.mapIndexed { i, e -> i.toString() to e }.toMap())
        else -> v.toString()
    }

    /** `strtoupper(md5(secret + assemble(params) + secret))` —— `sign` 自身必须已从 params 移除 */
    fun genSign(params: Map<String, Any?>, secret: String): String =
        md5Hex(secret + assemble(params) + secret).uppercase()

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).toHexLower()

    /**
     * 出站 HMAC-SHA256（§25.4 两层签名的第二层）。覆盖 `timestamp + "\n" + 原始请求体字节`，
     * 不覆盖"重新序列化后的 JSON"——序列化不稳定（键序、空格、转义）会让对端永远验不过。
     */
    fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHexLower()
    }

    private fun ByteArray.toHexLower(): String {
        val sb = StringBuilder(size * 2)
        forEach { b -> sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xf]).append("0123456789abcdef"[b.toInt() and 0xf]) }
        return sb.toString()
    }

    private val random = SecureRandom()

    /** 演示密钥的生成形状（§25.5）：`ak_` + 16 位十六进制 / `sk_` + 32 位十六进制 */
    fun newAppKey(): String = "ak_" + hex(8)

    fun newAppSecret(): String = "sk_" + hex(16)

    private fun hex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.toHexLower()
    }
}

/**
 * nonce 防重放（§25.1 第 7/8 道闸门）。
 *
 * 源系统用 Laravel 的 cache（`CacheOpenapiNonceStore`：键 `openapi:request_nonce:sha256(nonce)`、`add(key,1,600)`），
 * 本项目这台单机没有 Redis 依赖，于是用进程内表 + 过期清扫。
 * **代价写清楚**：多实例部署时两台机器各记各的，同一个 nonce 能打进来两次——
 * 这与 `order.paid` 那种"业务侧幂等"不冲突（结清本身是 CAS），但"防重放"这件事在多实例下是打折的，
 * 归 M2-4 生产化时统一外置（docs/05 §M2-4 的"缓存外置 Redis 可选"就是这一条）。
 *
 * 记的是 `sha256(nonce)` 而不是 nonce 原文：这张表在内存里可被 dump，
 * 对接方的 nonce 串常常是 `app_key + 时间` 这种可猜形状，存原文等于把它的调用节奏留在进程里。
 */
@Component
class OpenapiNonceStore {

    private val seen = ConcurrentHashMap<String, Long>()   // sha256(nonce) → 过期时刻（unix 秒）

    /** true = 这次可以用；false = 已用过（或与并发提交撞上了同一个位） */
    fun consume(nonce: String, ttlSeconds: Long, now: Long): Boolean {
        sweep(now)
        val key = sha256Hex(nonce)
        val expiry = now + ttlSeconds
        // compute 是原子的：两个并发请求打同一个 nonce 时，只有一个能把自己的 expiry 写进去
        val stored = seen.compute(key) { _, old -> if (old != null && old > now) old else expiry }
        return stored == expiry
    }

    private fun sweep(now: Long) {
        if (seen.size < SWEEP_THRESHOLD) return
        seen.entries.removeIf { it.value <= now }
    }

    /** 测试与运维看一眼当前占用了多少个 nonce 位（不出现在任何对外面上） */
    fun size(): Int = seen.size

    private companion object {
        const val SWEEP_THRESHOLD = 4096

        /** 与源系统同形：`hash('sha256', $nonce)`，不是 md5 */
        fun sha256Hex(s: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
