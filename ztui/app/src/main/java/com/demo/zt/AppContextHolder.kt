package com.demo.zt

import android.content.Context

/**
 * 最小化的 Application Context 持有者。
 *
 * `DeviceInfo` 这类工具对象需要 Context 才能拿到 ActivityManager 等系统服务，
 * 但它的调用点遍布 Compose 与后台线程。这里在 Application 启动时注入一次，
 * 避免把 Context 参数加到每个调用链上。
 */
internal object AppContextHolder {
    @Volatile
    var context: Context? = null
        private set

    fun init(ctx: Context) {
        if (context == null) context = ctx.applicationContext
    }
}
