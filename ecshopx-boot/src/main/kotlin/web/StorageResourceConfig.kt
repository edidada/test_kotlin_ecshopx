package com.ecshopx.kotlin.boot.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Files
import java.nio.file.Paths

/**
 * M0-8：把上传根目录挂到 `/storage` 前缀做静态资源（等价 PHP 侧 storage/app/public 的软链）。
 *
 * 目录在启动时就建好（而不是等第一次上传），否则静态映射指向不存在的路径，
 * 排查时容易误判成"路由没注册"。真实 OSS 部署时这段配置整体换成 CDN 域名，代码路径不变。
 */
@Configuration
class StorageResourceConfig(
    @Value("\${ecx.upload.root:data/storage}") private val root: String,
    @Value("\${ecx.upload.url-prefix:/storage}") private val prefix: String,
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        val base = Paths.get(root).toAbsolutePath().normalize()
        Files.createDirectories(base)
        registry.addResourceHandler("$prefix/**").addResourceLocations("file:${base.toUri().rawPath.removeSuffix("/")}/")
    }
}
