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

    /**
     * 密钥/口令的掩码（前 4 后 4，中间等长打星）。
     *
     * 为什么单独一条而不是复用 [taxNo]：密钥的**长度本身是敏感信息**——
     * 给人看"长度"就等于告诉对方"这是 16 位还是 32 位"，而税务号长度是公开规格。
     * 短于 8 位不是合法密钥形态，整体打星——**不把"我认不出格式"当成"可以不掩"**。
     */
    fun secret(v: String?): String? {
        val m = v?.takeIf { it.isNotBlank() } ?: return v
        return if (m.length < 8) "*".repeat(m.length) else "${m.take(4)}${"*".repeat(m.length - 8)}${m.takeLast(4)}"
    }

    /**
     * 纳税人识别号（M1-5 `INV-A1`/`INV-A3`，MASK-R1 同一判据）。
     *
     * 留前 4 位（登记管理局区划 + 机构类别，运营看一眼就知道是哪家地税管辖的）
     * 与后 3 位（校验位前的尾段，用来对上一张纸质发票），中间等长打星。
     * 短于 8 位不是合法识别号（15/18/20 位三族），这种脏值整体打星——
     * **不把"我认不出格式"当成"可以不掩"**。
     */
    fun taxNo(v: String?): String? {
        val m = v?.takeIf { it.isNotBlank() } ?: return v
        if (m.length < 8) return "*".repeat(m.length)
        return "${m.take(4)}${"*".repeat(m.length - 7)}${m.takeLast(3)}"
    }

    /**
     * 邮箱：只掩本地部分（`@` 之前），域名整留——运营要靠域名判断是不是公司邮箱。
     *
     * 本地部分留首字符、其余等长打星（`zhangsan@ex.com → z*******@ex.com`）。
     * 单字符本地部分（`a@ex.com`）没有可掩的位数，原样返回；
     * 没有 `@`（历史脏数据）整体打星，同 [taxNo] 那条"认不出格式也要掩"。
     */
    fun email(v: String?): String? {
        val m = v?.takeIf { it.isNotBlank() } ?: return v
        val at = m.indexOf('@')
        if (at <= 0) return "*".repeat(m.length)
        val local = m.substring(0, at)
        if (local.length == 1) return m
        return "${local.take(1)}${"*".repeat(local.length - 1)}${m.substring(at)}"
    }

    /**
     * 抬头名（企业名）：只留前 2 位与末 1 位，中间打星。
     *
     * 名称不像税号那样有可校验的形状，`上海某某贸易有限公司` 的中间部分才是"哪家客户"的信息量所在。
     */
    fun title(v: String?): String? {
        val m = v?.takeIf { it.isNotBlank() } ?: return v
        if (m.length <= 3) return "*".repeat(m.length)
        return "${m.take(2)}${"*".repeat(m.length - 3)}${m.takeLast(1)}"
    }
}
