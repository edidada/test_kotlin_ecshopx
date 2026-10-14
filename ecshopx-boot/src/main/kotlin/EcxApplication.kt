package com.ecshopx.kotlin.boot

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.ecshopx.kotlin"])
@MapperScan("com.ecshopx.kotlin.dao") // dao 包内约定：仅 interface 注册为 Mapper，非接口组件被跳过（仅 WARN）
class EcxApplication

fun main(args: Array<String>) {
    runApplication<EcxApplication>(*args)
}
