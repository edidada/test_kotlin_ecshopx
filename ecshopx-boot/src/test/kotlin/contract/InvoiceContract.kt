package com.ecshopx.kotlin.boot.contract

/**
 * docs/04 §23.1/§23.2 抄录的发票字段清单（M1-5 INV 族）。
 * 判定沿用 [ContractDiff]：缺失即失败，多出只回显。
 *
 * 两件事靠这两份清单钉住：
 *  - **申请单的票种取值是 `enterprise/individual`，抬头本的取值是 `personal/corporate`**
 *    （源系统就是两套词，本项目不统一，统一等于改历史数据）——
 *    所以 [INVOICE_APPLY_ROW] 与 [INVOICE_HEAD_ROW] 上的类型列名字都不一样，
 *    `invoice_type` vs `invoices_type`，一个字母之差，契约测试正是防这个；
 *  - 后台面比会员面多 `user_id/nickname` 两列，敏感四列（`company_title/tax_number/email/mobile`）
 *    **键永远在**，只是按 datapass 决定出全值还是掩码（MASK-R1）——
 *    出参形状不随权限变化，前端才能在同一套代码上渲染两种账号。
 */
object InvoiceContract {

    /** 分页信封（PageResult 只有这两键） */
    val LIST_ENVELOPE = setOf("total_count", "list")

    /** INV-F1/F2/F3 会员面申请单 */
    val INVOICE_APPLY_ROW = setOf(
        "id", "invoice_apply_bn", "order_id", "invoice_type", "invoice_type_code",
        "company_title", "tax_number", "email", "mobile", "invoice_status",
        "invoice_amount", "invoice_file_url", "remark", "invoices_id",
        "create_time", "update_time",
    )

    /** INV-A1/A2 后台面申请单 = 会员面 + 身份两列 */
    val INVOICE_ADMIN_ROW = INVOICE_APPLY_ROW + setOf("user_id", "nickname")

    /** INV-F4/F5 抬头本（`is_def` 是布尔，不是 0/1） */
    val INVOICE_HEAD_ROW = setOf(
        "invoices_id", "invoices_type", "name", "telephone", "tax_number",
        "business_address", "bank", "bank_account", "is_def", "created",
    )

    /** INV-F6 删除抬头出参 */
    val INVOICE_HEAD_DELETE = setOf("invoices_id", "deleted")
}
