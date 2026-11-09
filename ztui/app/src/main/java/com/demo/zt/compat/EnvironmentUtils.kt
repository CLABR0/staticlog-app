package com.demo.zt.compat

import android.content.Context
import android.os.Build

object EnvironmentUtils {
    fun isRoot(): Boolean = runCatching {
        java.io.File("/system/xbin/su").exists() || java.io.File("/system/bin/su").exists()
    }.getOrDefault(false)

    fun androidVersion(): String = Build.VERSION.RELEASE.orEmpty()

    fun isVivo(): Boolean = Build.MANUFACTURER.equals("vivo", true)

    fun deviceModel(context: Context): String = Build.MODEL.orEmpty()

    fun getAdbTcpPort(): Int = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, "service.adb.tcp.port") as? String)?.toIntOrNull() ?: 0
    }.getOrDefault(0)

}