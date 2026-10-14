package com.ecshopx.kotlin.boot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.ecshopx.kotlin"])
// M0-2 起追加 @MapperScan("com.ecshopx.kotlin.dao.mapper")
class EcxApplication

fun main(args: Array<String>) {
    runApplication<EcxApplication>(*args)
}
