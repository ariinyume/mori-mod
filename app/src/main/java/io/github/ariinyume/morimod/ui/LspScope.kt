package io.github.ariinyume.morimod.ui

import android.os.Handler
import android.os.Looper
import io.github.ariinyume.morimod.config.Protocol
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 向 LSPosed 问「本模块有没有被授权注入宿主」。
 *
 * 状态卡不再用宿主心跳判断在不在跑：宿主退到后台后心跳会停，
 * 45 秒一过就被写成未运行。授权是作用域里的事实，进程冻不冻结不影响。
 */
internal object LspScope {

    /** null = 框架还没把服务送过来。 */
    @Volatile
    var granted: Boolean? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    fun start(onUpdate: (Boolean) -> Unit) {
        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    try {
                        val scope = service.scope
                        val ok = containsHost(scope)
                        granted = ok
                        val sample = scope.firstOrNull()?.javaClass?.simpleName ?: "empty"
                        UiDiag.log("lsp scope host=$ok type=$sample n=${scope.size}")
                        main.post { onUpdate(ok) }
                    } catch (t: Throwable) {
                        UiDiag.log("lsp scope read failed: " + t.javaClass.simpleName)
                    }
                }

                override fun onServiceDied(service: XposedService) {
                    UiDiag.log("lsp scope died")
                }
            })
        } catch (t: Throwable) {
            UiDiag.log("lsp scope bind failed: " + t.javaClass.simpleName)
        }
    }

    private fun containsHost(scope: List<*>): Boolean {
        val host = Protocol.HOST_PKG
        for (item in scope) {
            if (item == null) continue
            if (item is String) {
                if (item == host) return true
                continue
            }
            if (packageNameOf(item) == host) return true
        }
        return false
    }

    private fun packageNameOf(item: Any): String? {
        try {
            val m = item.javaClass.getMethod("getPackageName")
            val v = m.invoke(item)
            if (v is String) return v
        } catch (_: Throwable) {
        }
        try {
            val f = item.javaClass.getField("packageName")
            val v = f.get(item)
            if (v is String) return v
        } catch (_: Throwable) {
        }
        val text = item.toString()
        return if (text == Protocol.HOST_PKG || text.contains(Protocol.HOST_PKG)) {
            Protocol.HOST_PKG
        } else {
            null
        }
    }
}
