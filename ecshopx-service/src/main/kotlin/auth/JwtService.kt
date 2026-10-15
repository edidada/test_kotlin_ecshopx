package com.ecshopx.kotlin.service.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

/**
 * HS256 JWT 签发/校验（替代 Dingo jwt provider）。
 * claims：sub=账号 id、kind、company_id；TTL 默认 12h（对齐 PHP dingo jwt.ttl）。
 * 生产部署必须覆盖 ecx.security.jwt-secret（≥32 字节随机串）。
 */
@Component
class JwtService(
    @Value("\${ecx.security.jwt-secret:M0-dev-secret-change-me-0123456789abcdef}") secret: String,
    @Value("\${ecx.security.jwt-ttl-seconds:43200}") private val ttlSeconds: Long,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    fun issue(kind: Kind, id: Long, companyId: Long, openId: String? = null): String {
        val now = Date()
        return Jwts.builder()
            .subject(id.toString())
            .claim("kind", kind.name)
            .claim("company_id", companyId)
            .claim("open_id", openId)
            .issuedAt(now)
            .expiration(Date(now.time + ttlSeconds * 1000))
            .signWith(key)
            .compact()
    }

    /** 无效/过期 token 返回 null（JwtFilter 视为未认证） */
    @Suppress("TooGenericExceptionCaught")
    fun parse(token: String): Pair<Principal, String>? = try {
        val c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        Principal(
            kind = Kind.valueOf(c["kind"].toString()),
            id = c.subject.toLong(),
            companyId = (c["company_id"] as? Int)?.toLong() ?: c["company_id"] as Long,
            openId = c["open_id"] as? String,
        ) to token
    } catch (e: Exception) {
        null
    }
}
