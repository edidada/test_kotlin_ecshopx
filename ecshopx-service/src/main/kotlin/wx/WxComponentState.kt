package com.ecshopx.kotlin.service.wx

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 第三方平台回调事件的进程内状态（M2-2 §27.5 未建表的那个部分）。
 *
 * **`component_verify_ticket` 不落库**：它是平台级凭据，`wx_app` 是租户级表，放不下它；
 * 源系统也没有任何一张表收它（收に行的服务是 InstagramTokenComponent 的进程内字段）。
 * 它的保鲜期只有微信侧的 10 分钟重发一次，重启后等下一次事件即可——
 * 这是"重启后排到下一个事件才恢复"的形态，不是丢配。
 *
 * （§27.6 门槛⑤原话"component_verify_ticket 落 wx_app"落地时修正为回调**事件**落
 * `wx_app`：`authorized`/`updateauthorized`/`unauthorized` 写租户授权行；ticket 只进本条状态。
 * 差异记 docs/04 §28。）
 */
@Component
class WxComponentState {
    private val tickets = ConcurrentHashMap<String, Pair<String, Long>>() // appId → (ticket, receivedAtSec)

    fun offer(appId: String, ticket: String, atSec: Long) {
        tickets[appId] = ticket to atSec
    }

    /** 组件 token 只需要"最近一次的 ticket"——同一部署一般只有一个 component_appid */
    fun latestTicket(): String? =
        tickets.values.maxByOrNull { it.second }?.first
}
