package com.ecshopx.kotlin.common.security

/**
 * 手机号/联系电话的掩码（docs/04 §23.3 MASK-R1）。
 *
 * 收敛前本仓六处各写了一遍，**位数并不相同**，所以这里保留两支语义、调用点各归各的，
 * 不趁收敛顺手改位数（出参断言已在十四支冒烟与 e2e 里钉住）：
 *
 * - [mobile]：固定四位星号，`13800000001 → 138****0001`；不足 7 位原样返回。
 *   MemberService / CommissionService / SalesmanService / PickupLocationService / GroupsService 走这一支。
 *   （PickupLocation 原来的守卫是 `<=4`、其余是 `<7`，只在 5~6 位这种非手机号脏值上有差别，
 *   合同电话实际都是 7~12 位，按多数那一支统一。）
 * - [phone]：星号位数随号码长度补齐，`057188886666 → 057*****6666`（长度不变）。
 *   AftersalesService 一直如此，因为它掩的是联系电话、可能是固话，压成固定四位会让出参比原值短。
 *
 * 判据层永远读原值：这两个函数只在**出参渲染**时调用，筛选、排序、对账一律用原列（MASK-R1）。
 * 是否给全值由权限码 `datapass.pass` 决定，判在调用点，不在这里。
 */
object Mask {

    /** `138****0001`；null 原样 null（后台行里手机号可能就没有，不能用空串冒充"掩过"） */
    fun mobile(v: String?): String? =
        v?.takeIf { it.length >= 7 }?.let { "${it.take(3)}****${it.takeLast(4)}" } ?: v

    /** [mobile] 的非空形态：会员资料面历史上把缺失手机号出成空串，这里保留那个形状 */
    fun mobileOrEmpty(v: String?): String = mobile(v) ?: ""

    /** 等长掩码（固话/未知格式的电话）；blank 与 null 原样返回 */
    fun phone(v: String?): String? {
        val m = v?.takeIf { it.isNotBlank() } ?: return v
        return if (m.length < 7) m else "${m.take(3)}${"*".repeat(m.length - 7)}${m.takeLast(4)}"
    }
}
