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

    @Select("SELECT id, company_id, user_id, file_name, file_path, file_type, file_size, created " +
        "FROM upload_files WHERE company_id = #{companyId} ORDER BY id DESC LIMIT #{offset}, #{limit}")
    fun pageByCompany(@Param("companyId") companyId: Long, @Param("offset") offset: Long, @Param("limit") limit: Int): List<UploadFileDO>

    @Select("SELECT COUNT(*) FROM upload_files WHERE company_id = #{companyId}")
    fun countByCompany(@Param("companyId") companyId: Long): Long
}

/**
 * `sys_jurisdiction` 地区库（M2-3 批E，`ecx.jurisdiction.get` 的数据源）。
 *
 * 差异 C237：源系统这张库是全量国内省/市/区县三级行政区（数千行，来自腾讯地区服务快照）；
 * 本项目只收**演示租户的开业地域**（浙江/江苏/上海三省级 + 各自的市县若干行）——
 * 对接方拿它当"返回什么省哪市"的样例足够，真上线运营地区随部署侧数据灌入，代码形状不变。
 */
class JurisdictionDO {
    var id: Long = 0
    var parentId: Long = 0
    var name: String = ""
    var level: Int = 1
    var sort: Int = 0
}

@Mapper
interface JurisdictionMapper {

    @Select("SELECT id, parent_id, name, level, sort FROM sys_jurisdiction ORDER BY level, sort, id")
    fun listAll(): List<JurisdictionDO>
}
