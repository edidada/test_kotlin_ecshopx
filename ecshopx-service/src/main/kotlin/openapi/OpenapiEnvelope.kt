package com.ecshopx.kotlin.service.openapi

/**
 * 开放平台的应答码与信封（M2-1 §25.1 末段）。
 *
 * **码表是照抄源系统的**：`OpenapiBundle/Exceptions` 那一族 + `HandleResponseMiddleware` 里那套
 * `sprintf('E%04d', $code)`，HTTP 恒 200、错误语义全在 `code` 这一位。
 * 域码（5301/5401/5501）是源系统各控制器里散落的 `throw` 常量，本项目把它们收在一张表上，
 * 理由是注册表要按"委托抛出的是哪一类错"来映射，字符串散落没法映射。
 *
 * （KDoc 里别写目录通配那种 `斜杠紧跟星号` 的串 —— Kotlin 的块注释会嵌套，这一行会当场把后面的代码
 *   吃进注释里，报的还是整个文件 "Unclosed comment"。`SecurityConfig` 顶部记过它的孪生坑。）
 */
object OpenapiCode {
    const val SUCCESS = "E0000"

    /** 缺参 / 参数不合法（含 `company_id` 与密钥不符那条 fail-closed） */
    const val PARAM = "E4001"

    /** 时间戳超窗、nonce 重放 —— 源系统这两条同码，照抄 */
    const val TIMESTAMP = "E4002"

    /** app_key 不正确（不存在与已停用同码，不给探测密钥留回答） */
    const val APP_KEY = "E4003"

    /** 缺 sign 与签错 sign 同码（源系统 `SIGN_ERROR` 一条，两条都归它） */
    const val SIGN = "E4004"

    /** 找不到 API：method 不存在、verb 与注册不符都走这条 */
    const val API_NOT_FOUND = "E4005"

    /** 该版本号不存在 */
    const val VERSION = "E4006"

    /** 该方法在本项目未被允许调用（保留位，源系统 4007 是内部面 `exc.operator.resetpwd` 专用） */
    const val METHOD = "E4007"

    /** 服务内部错 / 业务异常 */
    const val INTERNAL = "E5000"

    const val ORDER_NOT_FOUND = "E5301"
    const val SHOP_NOT_FOUND = "E5401"
    const val MEMBER_NOT_FOUND = "E5501"
}

/**
 * 网关自己的异常类型。
 *
 * 为什么要单独一支，而不是复用 [com.ecshopx.kotlin.common.api.BizException]：
 * `GlobalExceptionHandler` 是 `@RestControllerAdvice(basePackages=["com.ecshopx.kotlin.web"])`，
 * 它一接住就把响应改成 `{message,errors,code,status_code}` 那套站内信封、HTTP 也按 ErrorCode 走 ——
 * 而开放面的契约恰恰是**HTTP 恒 200、信封只有 status/code/message/data 四个键**。
 * 所以网关必须在控制器之前把异常换成自己的信封（§25.1 盘点第 3 条），不能让站内异常处理器插手。
 */
class OpenapiException(
    val code: String,
    message: String,
) : RuntimeException(message)

/**
 * 信封装配。
 *
 * `data` 空值一律 `null` 而不是 `[]` —— 源系统 `HandleResponseMiddleware` 就为这件事写过注释
 * （"在其他语言里 data 属于对象"）：给 PHP 客户端返回空数组它不报错，给 Java/Go 客户端返回
 * `[]` 却会在反序列化成 struct 时炸掉。
 */
object OpenapiEnvelope {

    fun ok(data: Any?): Map<String, Any?> = linkedMapOf(
        "status" to "success",
        "code" to OpenapiCode.SUCCESS,
        "message" to "成功",
        "data" to data,
    )

    fun fail(code: String, message: String): Map<String, Any?> = linkedMapOf(
        "status" to "fail",
        "code" to code,
        "message" to message,
        "data" to null,
    )

    /** `wrap-response=false` 时的成功形态：直出 `data`（源系统豁免表那条 `image` 应答的同形状） */
    fun unwrap(data: Any?): Any? = data
}
