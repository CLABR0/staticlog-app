package com.demo.zt

import com.demo.zt.adb.AdbClient

/** 全局持有应用内无线 ADB 连接，供 ADB 页与提权页共用。 */
object AdbHolder {
    @Volatile
    var client: AdbClient? = null
        private set

    @Volatile
    var connectPort: Int = 0

    fun set(c: AdbClient, port: Int) {
        client = c
        connectPort = port
    }

    fun clear() {
        runCatching { client?.close() }
        client = null
    }
}