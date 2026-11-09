package com.demo.zt.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** 原版 StellarSettings，保留启动方式与偏好存储。 */
@SuppressLint("StaticFieldLeak")
object StellarSettings {

    enum class LaunchMethod { ADB, ROOT }

    const val ADB_PORT = 5555
    const val TCPIP_PORT = "tcpip_port"
    const val TCPIP_PORT_ENABLED = "tcpip_port_enabled"

    var launchMethod: LaunchMethod = LaunchMethod.ADB

    private var appContext: Context? = null

    /** 由 Application 或首个 Activity 调用一次。 */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun getPreferences(): SharedPreferences {
        val ctx = appContext
            ?: error("StellarSettings 尚未初始化；请先调用 StellarSettings.init(context)")
        return ctx.getSharedPreferences("stellar_settings", Context.MODE_PRIVATE)
    }

    fun setLastLaunchMethod(method: LaunchMethod) {
        launchMethod = method
    }
}