package com.ecshopx.kotlin.service.auth

import com.ecshopx.kotlin.common.security.Mask
import org.springframework.stereotype.Component

/**
 * `datapass`（M1-5 `MASK-R1`，docs/04 §23.4 末条）—— 源系统那层中间件在本项目的对应物。
 *
 * 它只做一件事：**同一行数据出全值还是出掩码值**。三条纪律：
 *  1. **不参与归属判定**。看得见哪些行仍由 `DistributorScope`（`operators.distributor_ids`）判，
 *     本类只在行已确定可见之后决定手机号怎么印 —— 权限码判在归属之前（C167），
 *     但那是"能不能进这个接口"，这里是"进来的行怎么出参"，两件事不能混成一个开关；
 *  2. **默认拒绝**。`operator_type = admin` 穿透（独立部署形态，平台唯一超管），
 *     其余类型必须显式持有 `datapass.pass` 才看全值；非运营主体（会员令牌）一律 `false` ——
 *     会员面的手机号有自己的规则（自己的号回原值、别人的号根本不出），不走这里；
 *  3. **只作用在出参字符串上**。筛选、排序、对账一律读原列，
 *     所以 `keyword` 搜手机号能搜到（SQL 里比的是原值），而列表印出来是 `138****0001`。
 */
@Component
class Datapass(private val auth: OperatorAuthService) {

    /** 这个主体能不能看全值 */
    fun full(p: Principal?): Boolean = p != null &&
        p.kind == Kind.OPERATOR &&
        (p.operatorType == "admin" || auth.hasResource(p, CODE))

    /** 手机号出参：拿不到全值就掩码，能拿到就原样（掩码形状见 [Mask.mobile]） */
    fun mobile(p: Principal?, v: String?): String? = if (full(p)) v else Mask.mobile(v)

    companion object {
        /** 权限码（seed 的 `resource_id = 63`） */
        const val CODE = "datapass.pass"
    }
}
