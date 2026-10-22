package com.ecshopx.kotlin.dao.pay

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/**
 * 支付渠道实现位的配置行（M2-2 §27.5）。
 *
 * 一个租户每家渠道一行（`uk(company_id, channel)`）。`extra` 是各家的差异化字段
 * （e2e 假服务器的 query_url 之类），按 JSON 文本存——真正逐家联调是 M2-4 的事，
 * 本批只保证"四件事"有一个可验的落点。
 */
class PayChannelConfigDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var channel: String = ""
    var merchantNo: String? = null
    var appId: String? = null
    var apiKey: String? = null
    var notifyUrl: String? = null
    var extra: String? = null
    var status: Int = 1
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface PayChannelConfigMapper {

    @Select(
        "SELECT id, company_id, channel, merchant_no, app_id, api_key, notify_url, extra, status, created, updated " +
            "FROM pay_channel_config WHERE company_id = #{companyId} AND channel = #{channel}",
    )
    fun find(@Param("companyId") companyId: Long, @Param("channel") channel: String): PayChannelConfigDO?

    @Insert(
        "INSERT INTO pay_channel_config(company_id, channel, merchant_no, app_id, api_key, notify_url, extra, " +
            "status, created, updated) " +
            "VALUES(#{companyId}, #{channel}, #{merchantNo}, #{appId}, #{apiKey}, #{notifyUrl}, #{extra}, " +
            "#{status}, #{created}, #{updated})",
    )
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(c: PayChannelConfigDO): Int

    /** e2e 现建现改用的清理口（e2e 独立库跑完即清，不带 COMPANY 前置也安全） */
    @Select("SELECT id, company_id, channel, merchant_no, app_id, api_key, notify_url, extra, status, created, updated FROM pay_channel_config")
    fun list(): List<PayChannelConfigDO>
}
