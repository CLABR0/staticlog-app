package com.demo.zt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：开机自启必须「先等网络，再连 ADB」。
 *
 * 真实事故：V17 装到真机上，服务起来了，但一直停在「等待 ADB 就绪」直到超时。
 * 原因是开机广播触发时 Wi-Fi 往往还没连上，mDNS 发现不到无线调试服务；
 * 而原来的总超时只有 90 秒、单次发现只等 15 秒，窗口根本不够。
 */
class BootServiceNetworkWaitTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码：$relative（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun serviceWaitsForNetworkBeforeConnecting() {
        val src = source("AutoBootService.kt")
        assertTrue("必须有网络就绪判断", src.contains("isNetworkReady"))
        assertTrue("等待网络时不能直接进入连接循环", src.contains("网络未就绪"))
    }

    @Test
    fun connectTimeoutIsLongEnoughForBoot() {
        val src = source("AutoBootService.kt")
        val ms = Regex("""CONNECT_TIMEOUT_MS\s*=\s*([0-9_]+)L""")
            .find(src)?.groupValues?.get(1)?.replace("_", "")?.toLongOrNull()
            ?: error("找不到 CONNECT_TIMEOUT_MS")
        assertTrue(
            "开机后 Wi-Fi + adbd 就绪常常超过 90 秒，总超时不能太短，实际=${ms}ms",
            ms >= 180_000L,
        )
    }

    @Test
    fun notifiesProgressSoUserCanSeeTheStage() {
        val src = source("AutoBootService.kt")
        assertTrue("等待过程要更新通知，否则用户只看到卡住", src.contains("updateProgress"))
        assertTrue("要区分「等网络」和「等 ADB」两种阶段", src.contains("等待 ADB 就绪"))
    }

    @Test
    fun connectorTriesPropertyPortBeforeMdns() {
        val src = source("AdbAutoConnector.kt")
        assertTrue("应读取 service.adb.tls.port 快路径", src.contains("service.adb.tls.port"))
        val propIdx = src.indexOf("propertyPort()")
        val mdnsIdx = src.indexOf("discoverPort(context, onLog)")
        assertTrue("属性快路径必须在 mDNS 之前", propIdx in 0 until mdnsIdx)
    }

    @Test
    fun failureMessageTellsUserWhatToCheck() {
        val src = source("AutoBootService.kt")
        assertTrue("失败文案要给出可操作的检查项", src.contains("始终允许"))
    }
}
