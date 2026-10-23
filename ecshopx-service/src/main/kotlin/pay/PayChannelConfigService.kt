package com.ecshopx.kotlin.service.pay

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.security.Mask
import com.ecshopx.kotlin.dao.pay.PayChannelConfigDO
import com.ecshopx.kotlin.dao.pay.PayChannelConfigMapper
import com.ecshopx.kotlin.common.domain.TimeSec
import org.springframework.stereotype.Service

/**
 * 支付渠道配置的服务面（批F §29.6）：**加密的写在这一层，解密的读也在这一层**。
 *
 * 为什么要单独一层而不是在 DAO 里做：
 *  1. **写侧加密、读侧解密必须成对**，散在各处迟早有一处忘了——密钥列里躺着明文，
 *     等于"加密了但没生效"，比没加密更危险（运维以为安全了）；
 *  2. 判据层永远读原值（MASK-R1 同一纪律）：掩码只发生在出参渲染时，
 *     签名/验签用的是**解出来的明文**，掩码后的串拿去签名必然验不过。
 *
 * 渠道开户与开关是运营动作（权限码 90 读 / 91 写），所以这一层同时是后台面的委托体。
 */
@Service
class PayChannelConfigService(
    private val configs: PayChannelConfigMapper,
    private val cipher: PaySecretCipher,
) {

    /** 渠道白名单（M2-2 三家聚合渠道名，`@PayChannelProfile` 的语义集合；不在册的一律 422，不静默建行） */
    private val channels = setOf("adapay", "bspay", "hfpay")

    /** 读配置（**解出明文**，供签名/验签用；不是给后台看的） */
    fun find(companyId: Long, channel: String): PayChannelConfigDO? =
        configs.find(companyId, channel)?.let { row ->
            row.apiKey = cipher.decrypt(row.apiKey)
            row
        }

    /** 后台台账读（**掩码**；密钥只出掩码形态，密文前缀也不回显——前缀是内部格式） */
    fun listForAdmin(companyId: Long): List<Map<String, Any?>> =
        configs.listByCompany(companyId).map { row ->
            linkedMapOf<String, Any?>(
                "id" to row.id,
                "channel" to row.channel,
                "merchant_no" to row.merchantNo,
                "app_id" to row.appId,
                "api_key_masked" to Mask.secret(cipher.decrypt(row.apiKey)),
                "notify_url" to row.notifyUrl,
                "extra" to row.extra,
                "status" to row.status,
                "created" to row.created,
                "updated" to row.updated,
            )
        }

    /**
     * 开户/改配置（upsert by company+channel）。
     *
     * `api_key` 传空串 = **不改**（后台改个开关不该把密钥抹掉）；传非空 = 覆盖并加密落库。
     * 这是"改一个字段"与"改一行"的区别，不能按整行覆盖做。
     */
    fun upsert(companyId: Long, form: PayChannelForm): Map<String, Any?> {
        val channel = form.channel?.trim()?.lowercase().orEmpty()
        if (channel !in channels) {
            throw BizException(
                ErrorCode.VALIDATION, "渠道不合法",
                mapOf("channel" to listOf("只支持 ${channels.sorted().joinToString("/")}")),
            )
        }
        val now = TimeSec.now()
        val cur = configs.find(companyId, channel)
        val row = PayChannelConfigDO().apply {
            this.companyId = companyId
            this.channel = channel
            merchantNo = form.merchantNo ?: cur?.merchantNo
            appId = form.appId ?: cur?.appId
            apiKey = when {
                !form.apiKey.isNullOrBlank() -> cipher.encrypt(form.apiKey)  // 非空：覆盖并加密
                    // 空串/缺省：保持原值原样（已是密文的不动，老明文行也不强转加密）
                else -> cur?.apiKey
            }
            notifyUrl = form.notifyUrl ?: cur?.notifyUrl
            extra = form.extra ?: cur?.extra
            status = form.status ?: cur?.status ?: 1
        }
        if (cur == null) {
            row.created = now
            row.updated = now
            configs.insert(row)
        } else {
            row.id = cur.id
            row.created = cur.created
            row.updated = now
            configs.update(row)
        }
        return linkedMapOf(
            "channel" to channel,
            "merchant_no" to row.merchantNo,
            "app_id" to row.appId,
            "api_key_masked" to Mask.secret(cipher.decrypt(row.apiKey)),
            "notify_url" to row.notifyUrl,
            "status" to row.status,
        )
    }

    /** 渠道开关（开=1/关=0）：关掉的渠道在支付面上是"未配置"（422），不静默换渠道 */
    fun toggle(companyId: Long, channel: String, status: Int): Map<String, Any?> {
        if (status != 0 && status != 1) {
            throw BizException(ErrorCode.VALIDATION, "状态不合法", mapOf("status" to listOf("只支持 0/1")))
        }
        val cur = configs.find(companyId, channel)
            ?: throw BizException(ErrorCode.NOT_FOUND, "渠道配置不存在")
        cur.status = status
        cur.updated = TimeSec.now()
        configs.update(cur)
        return linkedMapOf("channel" to cur.channel, "status" to cur.status)
    }
}

/** 渠道配置入参（`api_key` 空串=不改；其余空=保持原值） */
@com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy::class)
class PayChannelForm {
    var channel: String? = null
    var merchantNo: String? = null
    var appId: String? = null
    var apiKey: String? = null
    var notifyUrl: String? = null
    var extra: String? = null
    var status: Int? = null
}
