package com.ecshopx.kotlin.service.wx

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.domain.TimeSec
import com.ecshopx.kotlin.dao.wx.WxAppDO
import com.ecshopx.kotlin.dao.wx.WxAppMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 模板面（WX-A5..A7，docs/04 §27.3）。
 *
 * **模板清单在微信侧，不在本系统**——这是它只进进程内的全部理由：
 * 源系统的 `TemplateMessageService` 是按 `authorizerAppId` 每次去微信拿，
 * 本系统没有任何一张表存它；mock profile 里这份进程内注册表就是"微信侧"的替身，
 * 它是**外部存储的 mock**，不是本系统的存储。真链的查看/增删也全部直接对微信说话。
 *
 * 注册表按 (company_id, app_type) 分桶：公众号模板（oa）与订阅消息模板（wxa）是**两套词表**，
 * 各家过各家的 API，混在一起就分不清"A6 建出来的东西 A5 该不该显示"。
 */
@Service
class WxTemplateService(
    private val apps: WxAppMapper,
    private val client: WxApiClient,
    private val mapper: ObjectMapper,
) {

    private val rows = ConcurrentHashMap<TemplateKey, Template>()
    private val seq = AtomicLong(0)

    fun isMock(): Boolean = !client.isReal()

    // ===================== WX-A5 模板列表 =====================

    fun list(companyId: Long, type: String?, page: Int?, pageSize: Int?): Map<String, Any?> {
        val t = validType(type)
        return if (isMock()) {
            rowsForMock(companyId, t)
        } else {
            val app = pickApp(companyId, t)
            val token = client.authorizerAccessToken(app)
            if (t == "wxa") {
                val r = client.postJson("/wxaapi/newtmpl/gettemplate", token, "{}")
                val list = r.path("data").get(0)?.map { n ->
                    linkedMapOf(
                        "template_id" to n.path("priTmplId").asText(""),
                        "title" to n.path("title").asText(""),
                        "short_id" to n.path("tid").asText(""),
                    )
                } ?: emptyList()
                linkedMapOf("type" to t, "total_count" to list.size.toLong(), "list" to list)
            } else {
                val r = client.getJson("/cgi-bin/template/get_all_private_template?access_token=${WxApiClient.enc(token)}")
                val list = r.path("template_list").map { n ->
                    linkedMapOf(
                        "template_id" to n.path("template_id").asText(""),
                        "title" to n.path("title").asText(""),
                        "short_id" to n.path("content").asText("").take(64),
                    )
                }
                linkedMapOf("type" to t, "total_count" to list.size.toLong(), "list" to list)
            }
        }
    }

    // ===================== WX-A6 新增模板 =====================

    fun add(companyId: Long, form: WxTemplateForm): Map<String, Any?> {
        val t = validType(form.type)
        val givenShort = form.shortId?.takeIf { it.isNotBlank() }
        val givenTmpl = form.templateId?.takeIf { it.isNotBlank() }
        if (givenShort == null && givenTmpl == null) {
            throw BizException(
                ErrorCode.VALIDATION, "short_id 与 template_id 至少给一个",
                mapOf("short_id" to listOf("short_id/template_id 至少给一个")),
            )
        }
        if (isMock()) {
            // mock：确定性 id。templateId 给了就用，shortId 兜"短码在微信侧决定全名"这一现实
            val tmpl = givenTmpl ?: ("TG" + t + WxCrypt.sha1Hex("$givenShort|$t|$companyId").take(12))
            val id = seq.incrementAndGet()
            val key = TemplateKey(companyId, t, tmpl)
            val existing = rows[key]
            val realId = existing?.id ?: id
            rows[key] = Template(realId, companyId, t, tmpl, givenShort, form.title, TimeSec.now())
            return linkedMapOf("id" to realId, "template_id" to tmpl, "short_id" to givenShort, "type" to t)
        }
        val app = pickApp(companyId, t)
        return if (t == "wxa") {
            // 订阅消息：`short_id` 是公版 tid；真链必须有 kidList（类目词条），只给短码就拒
            if (givenTmpl == null) {
                throw BizException(
                    ErrorCode.VALIDATION, "wxa 的新增模板必须给 template_id（真链不接受纯 short_id）",
                    mapOf("template_id" to listOf("template_id 必填")),
                )
            }
            val appSecret = app.appSecret.orEmpty()
            if (appSecret.isBlank()) {
                throw WxConfigException("wxa 新增模板需要应用凭据")
            }
            client.postJson("/wxaapi/newtmpl/addtemplate", client.authorizerAccessToken(app),
                mapper.writeValueAsString(mapOf("tid" to givenShort, "kidList" to listOf(1))))
            linkedMapOf("template_id" to givenTmpl, "short_id" to givenShort, "type" to t, "id" to 0L)
        } else {
            // 公众号模板消息：api_add_template 需要"模板库主号"（oi 是 short_id 的真名）
            val oid = givenShort
                ?: throw BizException(
                    ErrorCode.VALIDATION, "oa 的新增模板必须给 short_id（模板库主号）",
                    mapOf("short_id" to listOf("short_id 必填")),
                )
            val r = client.postJson(
                "/cgi-bin/template/api_add_template",
                client.authorizerAccessToken(app),
                mapper.writeValueAsString(mapOf("template_id_short" to oid)),
            )
            linkedMapOf(
                "template_id" to r.path("template_id").asText(givenTmpl ?: ""),
                "short_id" to givenShort,
                "type" to t,
                "id" to 0L,
            )
        }
    }

    // ===================== WX-A7 删模板 =====================

    fun delete(companyId: Long, id: Long): Map<String, Any?> {
        if (isMock()) {
            val hit = rows.values.filter { it.companyId == companyId && it.id == id }.firstOrNull()
                ?: throw BizException(
                    ErrorCode.NOT_FOUND, "模板不存在",
                    mapOf("id" to listOf("模板不存在")),
                )
            rows.entries.removeIf { it.key.companyId == companyId && it.value.id == id }
            return linkedMapOf("id" to hit.id, "template_id" to hit.templateId, "type" to hit.appType)
        }
        // 真链按 A6 出参给的 id 判不了——真链删除靠 template_id，这里只接"它给过"的形态
        throw BizException(
            ErrorCode.BIZ, "真链删除模板需要 template_id，请用 WX-A7 传 template_id 形态（这是 mock 的限定）",
            mapOf("id" to listOf("请配置 real profile 并给出 template_id")),
        )
    }

    // ===================== 内部 =====================

    private fun validType(raw: String?): String = when (raw?.takeIf { it.isNotBlank() }?.lowercase()) {
        null, "wxa" -> "wxa"
        "oa" -> "oa"
        else -> throw BizException(
            ErrorCode.VALIDATION, "type 只能是 oa 或 wxa",
            mapOf("type" to listOf("仅支持 oa/wxa")),
        )
    }

    /** mock 分支的出参形状与真链同族：total_count + list，字段与真链映射后一一对应 */
    private fun rowsForMock(companyId: Long, t: String): Map<String, Any?> {
        val list = rows.values.filter { it.companyId == companyId && it.appType == t }.sortedBy { it.id }
            .map {
                linkedMapOf(
                    "id" to it.id,
                    "template_id" to it.templateId,
                    "short_id" to it.shortId,
                    "title" to it.title,
                    "type" to it.appType,
                )
            }
        return linkedMapOf("type" to t, "total_count" to list.size.toLong(), "list" to list)
    }

    /** wxa/oa 各自需要一条活的应用行；没有 → 422（§27.6 门槛⑤同判据：没配置不是发失败） */
    private fun pickApp(companyId: Long, type: String): WxAppDO =
        apps.listByCompany(companyId).firstOrNull { it.appType == type && it.status == 1 }
            ?: apps.listByCompany(companyId).firstOrNull { it.status == 1 }
            ?: throw BizException(
                ErrorCode.BIZ, "当前租户未绑定${if (type == "oa") "公众号" else "小程序"}",
                mapOf("app_id" to listOf("未绑定应用")),
            )

    fun hasTemplate(companyId: Long, templateId: String, type: String): Boolean =
        rows.containsKey(TemplateKey(companyId, type, templateId)) ||
            rows.values.any { it.companyId == companyId && it.templateId == templateId }

    data class TemplateKey(val companyId: Long, val appType: String, val templateId: String)

    class Template(
        val id: Long,
        val companyId: Long,
        val appType: String,
        val templateId: String,
        val shortId: String?,
        val title: String?,
        val created: Long,
    )
}

/** WX-A6 入参 */
class WxTemplateForm(
    val type: String? = null,
    val shortId: String? = null,
    val templateId: String? = null,
    val title: String? = null,
)
