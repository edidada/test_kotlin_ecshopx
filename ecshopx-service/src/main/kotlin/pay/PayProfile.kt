package com.ecshopx.kotlin.service.pay

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * 渠道 profile 开关（docs/05 §M1-2 的 `ecx.pay.active-channel-profile`）。
 *
 * 一个属性决定"哪个渠道实现被注册成 bean"，而不是在运行时到处 if：
 *  - `mock`（默认）：只有 M0-7 的假微信渠道，全套既有测试与冒烟脚本行为不变；
 *  - `wechatpay`：真实微信支付 v3（SDK 签名 + 回调验签解密）；
 *  - `alipay`：支付宝开放平台（沙箱与线上同一套代码，只差网关地址）；
 *  - 逗号分隔可叠加（生产常见形态是 `wechatpay,alipay`），`mock` 与真渠道**互斥使用**，
 *    同一 profile 里同时开 mock 与 wechatpay 没有意义（同一个 `pay_type=wxpay` 会有两个实现抢注册）。
 *
 * 为什么不用 Spring 原生 `@ConditionalOnProperty`：它只能等值匹配，无法表达
 * "profile 列表包含某项"。这里用 [Condition] 自己读列表，语义与逗号叠加一致。
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(PayChannelProfileCondition::class)
annotation class PayChannelProfile(val value: String)

/** profile 未配置时的缺省值：保持 M0 行为，真渠道一律不注册 */
const val DEFAULT_PAY_PROFILE = "mock"

class PayChannelProfileCondition : Condition {

    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val want = metadata.getAnnotationAttributes(PayChannelProfile::class.java.name)
            ?.get("value") as? String ?: return false
        val active = context.environment.getProperty(PAY_PROFILE_PROPERTY, DEFAULT_PAY_PROFILE)
        return active.split(',').any { it.trim().equals(want, ignoreCase = true) }
    }

    companion object {
        const val PAY_PROFILE_PROPERTY = "ecx.pay.active-channel-profile"
    }
}
