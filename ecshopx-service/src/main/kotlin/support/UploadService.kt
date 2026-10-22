package com.ecshopx.kotlin.service.support

import com.ecshopx.kotlin.common.api.BizException
import com.ecshopx.kotlin.common.api.ErrorCode
import com.ecshopx.kotlin.dao.support.UploadFileDO
import com.ecshopx.kotlin.dao.support.UploadFilesMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * SUP-2 图片上传（本地磁盘版）。
 *
 * M0 不接 OSS：文件落到 `ecx.upload.root`，DB 只记相对路径，前端拼 `{prefix}/{相对路径}` 访问
 * （boot 侧把该根目录挂成 `/storage` 前缀的静态资源）。换 OSS 时只改本类的 write 与 url 拼装。
 *
 * 三条安全底线：
 *  1. 落盘文件名**一律自己生成**（uuid + 白名单后缀），原始文件名只进 DB 展示，杜绝路径穿越；
 *  2. 后缀白名单 + content-type 前缀双重判定，非图片直接 422；
 *  3. 大小上限走配置，超限 413 语义由容器/服务层共同兜住。
 */
@Component
class UploadService(
    private val files: UploadFilesMapper,
    @Value("\${ecx.upload.root:data/storage}") private val root: String,
    @Value("\${ecx.upload.url-prefix:/storage}") private val prefix: String,
    @Value("\${ecx.upload.max-size:5242880}") private val maxSize: Long,
) {

    data class Stored(
        val id: Long,
        val url: String,
        val path: String,
        val fileName: String,
        val fileType: String,
        val fileSize: Long,
    )

    fun store(companyId: Long, userId: Long?, originalName: String?, contentType: String?, bytes: ByteArray): Stored {
        if (bytes.isEmpty()) throw BizException(ErrorCode.VALIDATION, "文件内容为空", mapOf("file" to listOf("不能为空")))
        if (bytes.size.toLong() > maxSize) {
            throw BizException(ErrorCode.VALIDATION, "图片过大（上限 ${maxSize / 1024 / 1024}MB）", mapOf("file" to listOf("超出大小限制")))
        }
        val ext = (originalName?.substringAfterLast('.', "") ?: "").lowercase()
        if (ext !in EXTENSIONS) throw BizException(ErrorCode.VALIDATION, "不支持的图片格式", mapOf("file" to listOf("仅允许 $EXTENSIONS")))
        if (contentType != null && !contentType.lowercase().startsWith("image/")) {
            throw BizException(ErrorCode.VALIDATION, "仅允许上传图片", mapOf("file" to listOf(contentType)))
        }
        val month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"))
        val name = UUID.randomUUID().toString().replace("-", "") + "." + ext
        val relative = "$month/$name"
        val target: Path = Paths.get(root).toAbsolutePath().normalize().resolve(relative)
        // resolve 后仍在根目录内才写（防任何意外的 ../ 组合）
        val base = Paths.get(root).toAbsolutePath().normalize()
        if (!target.startsWith(base)) throw BizException(ErrorCode.VALIDATION, "非法存储路径")
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
        val row = UploadFileDO().apply {
            this.companyId = companyId
            this.userId = userId
            fileName = sanitize(originalName)
            filePath = relative
            fileType = ext
            fileSize = bytes.size.toLong()
        }
        files.insert(row)
        return Stored(
            id = row.id,
            url = "$prefix/$relative",
            path = relative,
            fileName = row.fileName ?: "",
            fileType = ext,
            fileSize = row.fileSize,
        )
    }

    /** 绝对路径解析（静态资源映射与测试断言共用，避免两处拼法漂移） */
    fun absolutePath(relative: String): Path = Paths.get(root).toAbsolutePath().normalize().resolve(relative)

    /**
     * 图片库翻页（M2-3 批E：`ecx.image.list` 的委托体，与后台 SUP-2 台账同一张脸）。
     * `url` 由本类的 prefix 拼装（与 [store] 同一拼法，换 OSS 时两处一起变）。
     */
    fun page(companyId: Long, pq: com.ecshopx.kotlin.common.api.PageQuery): com.ecshopx.kotlin.common.api.PageResult<Map<String, Any?>> =
        com.ecshopx.kotlin.dao.paging.PageHelper.paginate(
            pq,
            count = { files.countByCompany(companyId) },
            query = { offset, limit -> files.pageByCompany(companyId, offset, limit) },
            map = { rows ->
                rows.map { r ->
                    linkedMapOf<String, Any?>(
                        "id" to r.id,
                        "file_name" to (r.fileName ?: ""),
                        "url" to "$prefix/${r.filePath}",
                        "path" to r.filePath,
                        "file_type" to (r.fileType ?: ""),
                        "file_size" to r.fileSize,
                        "created" to r.created,
                    )
                }
            },
        )

    /** 原始名只留可打印字符并截断，防脏名把 XSS 带进后台列表 */
    private fun sanitize(name: String?): String =
        (name ?: "image").map { if (it.isLetterOrDigit() || it in ".-_ ") it else '_' }.joinToString("").take(100)

    companion object {
        val EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}
