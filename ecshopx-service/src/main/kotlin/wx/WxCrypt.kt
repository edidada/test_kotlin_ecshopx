package com.ecshopx.kotlin.service.wx

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 微信回调的验签与 AES 解密（Wx-G1/G2，§27.3 WX-G1/G2 的"中间件等价物"）。
 *
 * **验签公式**（GET 握手与事件通知同一支）：`signature = sha1(字典序拼 token+timestamp+nonce) 的小写 hex`。
 * token 是平台侧配置值（`ecx.wx.component-token`），不是任何一方能从报文里推出来的东西——
 * 所以它审的只是"发件人 platform 配着同一把 token"，**不能**替代加密体的解密验证。
 *
 * **解密**是第三方平台那条链独有的：加密体为 Base64(AES-256-CBC(pkcs7))，
 * key = Base64Decode(aesKey + "=")（43 位 key 补齐到 44 位，微信官方约定），
 * IV 取 key 前 16 字节；明文去掉 PKCS7 再剥前 16 字节随机串与后端 appendedAppId：
 * `[16B random][4B msgLen BE][msg][appId]`。这是微信家独有的排布，不是通用 CBC。
 */
object WxCrypt {

    /** GET 握手 / 事件通知共用的验签：sorted(token, timestamp, nonce) 连拼取 sha1 小写 hex */
    fun verifySignature(token: String?, timestamp: String?, nonce: String?, signature: String?): Boolean {
        if (token.isNullOrBlank()) return true // 平台侧未配 token：mock profile 的开发形态，不检
        if (timestamp == null || nonce == null || signature.isNullOrBlank()) return false
        val joined = listOf(token, timestamp, nonce).sorted().joinToString("")
        return sha1Hex(joined) == signature.lowercase()
    }

    fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 解密消息体（公众号/第三方平台的 `Encrypt` 字段，Base64 进 Base64 出明文 XML/JSON）。
     * 任一步骤失败（Base64 / 长度 / PKCS7 / appendedAppId 不符）都返回 null，
     * 由调用方决定 400——判别"解不开"不区分步骤，报文里没有可供人读的原因。
     */
    fun decrypt(cipherText: String, aesKey43: String, expectAppId: String?): String? {
        val key = runCatching {
            Base64.getDecoder().decode(aesKey43 + "=")
        }.getOrNull() ?: return null
        if (key.size != 32) return null
        val cipherBody = runCatching {
            Base64.getMimeDecoder().decode(cipherText.trim())
        }.getOrNull() ?: return null
        val plain = runCatching {
            Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOf(16)))
            }.doFinal(cipherBody)
        }.getOrNull() ?: return null
        val unpad = unpkcs7(plain) ?: return null
        if (unpad.size < 20) return null
        val msgLen = ((plain[16].toInt() and 0xFF) shl 24) or ((plain[17].toInt() and 0xFF) shl 16) or
            ((plain[18].toInt() and 0xFF) shl 8) or (plain[19].toInt() and 0xFF)
        if (msgLen < 0 || 20 + msgLen > unpad.size) return null
        val msg = unpad.copyOfRange(20, 20 + msgLen).toString(StandardCharsets.UTF_8)
        val appended = unpad.copyOfRange(20 + msgLen, unpad.size).toString(StandardCharsets.UTF_8)
        if (!expectAppId.isNullOrBlank() && appended != expectAppId) return null
        return msg
    }

    /** 事件的加密应答（mock profile 用不到，real profile 的被动回复是加密体） */
    fun encrypt(plainXml: String, aesKey43: String, appId: String): String? {
        val key = runCatching { Base64.getDecoder().decode(aesKey43 + "=") }.getOrNull() ?: return null
        if (key.size != 32) return null
        val random = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val body = plainXml.toByteArray(StandardCharsets.UTF_8)
        val lenBytes = byteArrayOf(
            ((body.size shr 24) and 0xFF).toByte(),
            ((body.size shr 16) and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        )
        val appIdBytes = appId.toByteArray(StandardCharsets.UTF_8)
        val raw = random + lenBytes + body + appIdBytes
        val padded = pkcs7(raw)
        return runCatching {
            val out = Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOf(16)))
            }.doFinal(padded)
            Base64.getEncoder().encodeToString(out)
        }.getOrNull()
    }

    private fun unpkcs7(plain: ByteArray): ByteArray? {
        val pad = plain.last().toInt()
        if (pad < 1 || pad > 32 || pad > plain.size) return null
        for (i in (plain.size - pad) until plain.size) {
            if (plain[i].toInt() != pad) return null
        }
        return plain.copyOfRange(0, plain.size - pad)
    }

    private fun pkcs7(src: ByteArray): ByteArray {
        val pad = 32 - (src.size % 32)
        return src + ByteArray(pad) { pad.toByte() }
    }
}
