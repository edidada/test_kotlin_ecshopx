package com.ecshopx.kotlin.service.tx

import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Kotlin 事务模板 DSL —— 收编 PHP 侧 718 处 beginTransaction/commit/rollback
 * （docs/03-详细设计文档.md §3.2）。
 *
 * 规则：跨表编排优先 @Transactional；需要代码块级事务/独立提交（号段、扫描批内单单）用本类。
 */
@Component
class Db(private val txManager: PlatformTransactionManager) {

    fun <T> transactional(
        propagation: Int = TransactionDefinition.PROPAGATION_REQUIRED,
        block: () -> T,
    ): T {
        val tt = TransactionTemplate(txManager)
        tt.propagationBehavior = propagation
        tt.setIsolationLevelName("ISOLATION_READ_COMMITTED")
        return tt.execute { block() } ?: error("transactional block returned null")
    }

    /** 注册提交后回调（外部 HTTP / 发事件），事务回滚则不执行 */
    fun afterCommit(block: () -> Unit) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = block()
            },
        )
    }
}
