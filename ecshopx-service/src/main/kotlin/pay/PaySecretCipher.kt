package com.ecshopx.kotlin.service.pay

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 支付渠道密钥的静态加密（docs/04 §29.6，批F；兑现 §27.5 留下的"密钥列加密留给 M2-4"）。
 *
 * **形态**：`enc-v1:{iv_b64}:{ct_b64}`，AES-256-GCM，密钥由 `ecx.pay.kek` 经 SHA-256 派生。
 * 为什么是 GCM 而不是 CBC：GCM 带认证标签，密文被改过（哪怕一比特）解密会**失败**而不是
 * 解出一段垃圾——密钥列是拿来签名的，解出垃圾比解不出更危险。
 *
 * **兼容判据**：库里没有 `enc-v1:` 前缀的按明文读（老行、e2e 直插的假密钥都还能用），
 * 有前缀的才解密。这条判据让"加密上线"不需要停机改表——旧行照样跑，新写的行自动是密文。
 *
 * **部署注意**：`ecx.pay.kek` 必须由部署侧注入且**全实例一致**——它不在这份代码里，
 * 代码里只有默认值（演示用）。换 KEK 之前先把旧密钥行的密文解开重写，否则旧行解不开。
 */
@Component
class PaySecretCipher(
    @Value("\${ecx.pay.kek:ecshopx-demo-kek}") private val kek: String,
) {

    private val random = SecureRandom()

    /** 明文 → 密文（空值原样空值；已是密文的不二次加密） */
    fun encrypt(plain: String?): String? {
        val p = plain ?: return null
        if (p.isEmpty()) return p
        if (p.startsWith(PREFIX)) return p
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("GCM")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(p.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct)
    }

    /** 密文 → 明文（非 `enc-v1:` 前缀按明文原样返回；解不开按原样返回，不抛——宁可签名失败也不 500） */
    fun decrypt(stored: String?): String? {
        val s = stored ?: return null
        if (!s.startsWith(PREFIX)) return s
        val parts = s.removePrefix(PREFIX).split(":")
        if (parts.size != 2) return s
        return runCatching {
            val iv = Base64.getDecoder().decode(parts[0])
            val ct = Base64.getDecoder().decode(parts[1])
            val cipher = Cipher.getInstance("GCM")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse { s }
    }

    private fun key(): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(kek.toByteArray(Charsets.UTF_8)), "AES")

    private companion object {
        const val PREFIX = "enc-v1:"
    }
}
