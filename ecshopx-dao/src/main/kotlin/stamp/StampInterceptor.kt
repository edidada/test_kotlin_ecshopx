package com.ecshopx.kotlin.dao.stamp

import com.ecshopx.kotlin.common.domain.TimeSec
import org.apache.ibatis.executor.Executor
import org.apache.ibatis.mapping.MappedStatement
import org.apache.ibatis.mapping.SqlCommandType
import org.apache.ibatis.plugin.Interceptor
import org.apache.ibatis.plugin.Intercepts
import org.apache.ibatis.plugin.Invocation
import org.apache.ibatis.plugin.Signature
import org.springframework.stereotype.Component

/** 需要 Gedmo 式自动时间戳的 DO 实现本接口（created/updated 用 var）。 */
interface Stampable {
    var created: Long
    var updated: Long
}

/**
 * 等价 PHP Gedmo Timestampable（详细设计 §8：写路径统一由拦截器填 created/updated，unix 秒）。
 * INSERT 填 created+updated；UPDATE 只填 updated。
 * 支持三种参数形态：Stampable 对象、含 Stampable 的 ParamMap、带 created/updated 键的可变 Map。
 */
@Intercepts(
    Signature(type = Executor::class, method = "update", args = [MappedStatement::class, Any::class]),
)
@Component
class StampInterceptor : Interceptor {

    override fun intercept(invocation: Invocation): Any? {
        val ms = invocation.args[0] as MappedStatement
        stamp(ms.sqlCommandType, invocation.args[1], TimeSec.now())
        return invocation.proceed()
    }

    fun stamp(cmd: SqlCommandType, param: Any?, now: Long) {
        val touchCreated = cmd == SqlCommandType.INSERT
        if (cmd != SqlCommandType.INSERT && cmd != SqlCommandType.UPDATE) return
        when (param) {
            is Stampable -> apply(param, touchCreated, now)
            is Map<*, *> -> {
                // ParamMap(@Param 多参) 里找 Stampable；找不到且本身是可变参数字典则直接写字典键
                val stampable = param.values.filterIsInstance<Stampable>()
                if (stampable.isNotEmpty()) stampable.forEach { apply(it, touchCreated, now) }
                else writeKeys(param, touchCreated, now)
            }
            else -> Unit
        }
    }

    private fun apply(s: Stampable, touchCreated: Boolean, now: Long) {
        if (touchCreated && s.created == 0L) s.created = now
        s.updated = now
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeKeys(map: Map<*, *>, touchCreated: Boolean, now: Long) {
        val m = map as? MutableMap<String, Any?> ?: return
        if (!m.containsKey("created") && !m.containsKey("updated")) return
        if (touchCreated) m["created"] = now
        m["updated"] = now
    }
}
