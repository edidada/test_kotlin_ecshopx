package com.ecshopx.kotlin.dao.support

import com.ecshopx.kotlin.dao.stamp.Stampable
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Options
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select

/**
 * M0-8 支撑域 DAO（SUP-2 上传台账）。
 *
 * sys_config（SUP-1）与 operator_logs（SUP-4）的读写方法挂在各自既有 Mapper 上：
 * 前者在 goods/SysConfigMapper（商品域先建的读取入口，避免同表两套接口），
 * 后者在 auth/OperatorLogsMapper（@Audited 切面写、这里读）。
 */

/** upload_files：一次上传一行，file_path 是相对 storage 根的路径，前端拿 /storage/{file_path} 访问 */
class UploadFileDO : Stampable {
    var id: Long = 0
    var companyId: Long = 0
    var userId: Long? = null
    var fileName: String? = null
    var filePath: String? = null
    var fileType: String? = null
    var fileSize: Long = 0
    override var created: Long = 0
    override var updated: Long = 0
}

@Mapper
interface UploadFilesMapper {

    @Insert("INSERT INTO upload_files(company_id, user_id, file_name, file_path, file_type, file_size, created) " +
        "VALUES(#{companyId}, #{userId}, #{fileName}, #{filePath}, #{fileType}, #{fileSize}, #{created})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    fun insert(f: UploadFileDO): Int

    @Select("SELECT id, company_id, user_id, file_name, file_path, file_type, file_size, created " +
        "FROM upload_files WHERE id = #{id}")
    fun findById(@Param("id") id: Long): UploadFileDO?

    @Select("SELECT COUNT(*) FROM upload_files WHERE company_id = #{companyId}")
    fun countByCompany(@Param("companyId") companyId: Long): Long
}
