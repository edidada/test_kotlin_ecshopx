package com.ecshopx.kotlin.web.admin

import com.ecshopx.kotlin.common.api.ApiResponse
import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.common.api.PageResult
import com.ecshopx.kotlin.common.security.Audited
import com.ecshopx.kotlin.common.security.RequiresResource
import com.ecshopx.kotlin.service.auth.Principal
import com.ecshopx.kotlin.service.auth.TenantContext
import com.ecshopx.kotlin.service.support.ConfigService
import com.ecshopx.kotlin.service.support.OperatorLogService
import com.ecshopx.kotlin.service.support.UploadService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * SUP-1/2/4 支撑端点（docs/04 §2.7）。
 *
 * 上传面单独说明：`@Audited` 不挂在 SUP-2 上——审计切面会把方法入参整体序列化进
 * `operator_logs.params`，图片字节流没必要（也不应该）进日志表。
 */
@RestController
class SupportAdminController(
    private val configs: ConfigService,
    private val uploads: UploadService,
    private val logs: OperatorLogService,
) {

    /** SUP-1 读配置：全局层 + 本租户层合并后的键值清单 */
    @RequiresResource("config.lists")
    @GetMapping("/api/config")
    fun config(@AuthenticationPrincipal p: Principal?): ApiResponse<PageResult<Map<String, Any?>>> =
        ApiResponse.ok(configs.read(cid(p)))

    /** SUP-1 写配置：只落租户层，返回写后的全量视图（前端直接刷新表单） */
    @Audited(route = "config.update")
    @RequiresResource("config.update")
    @PutMapping("/api/config")
    fun updateConfig(
        @RequestBody raw: Map<String, Any?>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(configs.write(cid(p), raw))

    /** SUP-4 操作审计日志分页 */
    @RequiresResource("operator.logs")
    @GetMapping("/api/operator/logs")
    fun operatorLogs(
        @RequestParam raw: Map<String, String>,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<PageResult<Map<String, Any?>>> = ApiResponse.ok(logs.list(cid(p), raw))

    /**
     * SUP-2 图片上传：`multipart/form-data`，字段名 `file`（源系统同名）。
     * 返回 url 即可直接给 <img src>，path 是相对存储根的路径，便于二次搬运。
     */
    @RequiresResource("upload.image")
    @PostMapping("/api/upload/image")
    fun upload(
        @RequestParam(name = "file", required = false) file: MultipartFile?,
        @AuthenticationPrincipal p: Principal?,
    ): ApiResponse<Map<String, Any?>> {
        val f = file ?: throw BizException(ErrorCode.VALIDATION, "请选择图片", mapOf("file" to listOf("不能为空")))
        val stored = uploads.store(cid(p), p?.id, f.originalFilename, f.contentType, f.bytes)
        return ApiResponse.ok(
            linkedMapOf(
                "id" to stored.id,
                "url" to stored.url,
                "path" to stored.path,
                "file_name" to stored.fileName,
                "file_type" to stored.fileType,
                "file_size" to stored.fileSize,
            ),
        )
    }

    private fun cid(p: Principal?): Long = p?.companyId ?: TenantContext.companyId
}
