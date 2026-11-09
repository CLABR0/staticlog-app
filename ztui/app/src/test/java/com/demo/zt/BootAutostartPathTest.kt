package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：开机自启必须走「前台服务」，不能靠「后台拉界面」。
 *
 * 真实事故：无线调试自启成功了，但提权没跑。原因两层：
 *   1. 开机广播里 `startActivity()` 在 Android 10+ 被系统**静默拒绝**；
 *   2. 一旦内核被判不支持，`AutoBoot.disable()` 会把自启**永久关掉**，
 *      叠加上当时的判据 bug，开关看起来是开的、实际早被关了。
 *
 * 这两条都在源码层面钉住。
 */
class BootAutostartPathTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码：$relative（cwd=${File(".").absolutePath}）")
    }

    private fun manifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 AndroidManifest.xml")
    }

    @Test
    fun bootReceiverDoesNotStartActivity() {
        val src = source("BootReceiver.kt")
        // 只检查代码行：注释里说明「为什么不用 startActivity」是正常的
        val codeLines = src.lineSequence()
            .filter { line ->
                val t = line.trim()
                !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
            }
            .toList()
        assertFalse(
            "开机广播的代码里不能有 startActivity（Android 10+ 会被静默拒绝）",
            codeLines.any { it.contains("startActivity") },
        )
    }

    @Test
    fun bootReceiverStartsTheService() {
        val src = source("BootReceiver.kt")
        assertTrue("开机广播必须启动 AutoBootService", src.contains("AutoBootService.start"))
    }

    @Test
    fun serviceIsDeclaredWithForegroundType() {
        val m = manifest()
        assertTrue("Manifest 必须注册 AutoBootService", m.contains(".AutoBootService"))
        assertTrue(
            "AutoBootService 必须是 specialUse 前台服务，否则 Android 12+ 起不来",
            m.contains("foregroundServiceType=\"specialUse\""),
        )
    }

    @Test
    fun bootReceiverListensToVendorQuickBoot() {
        val m = manifest()
        assertTrue(
            "部分厂商用 QUICKBOOT_POWERON 代替 BOOT_COMPLETED",
            m.contains("QUICKBOOT_POWERON"),
        )
    }

    @Test
    fun unsupportedKernelNoLongerDisablesAutoboot() {
        val src = source("MainActivity.kt")
        assertFalse(
            "内核判不支持时不能自动关闭自启——一次误判会把开关永久关掉",
            src.contains("if (!supported && autoRunOnStart) {\n        LaunchedEffect(Unit) { AutoBoot.disable(context) }"),
        )
    }

    @Test
    fun serviceWaitsForAdbBeforeRunning() {
        val src = source("AutoBootService.kt")
        assertTrue("服务必须等 ADB 就绪（开机后 adbd 有延迟）", src.contains("awaitClient"))
        assertTrue("必须有连接超时上限", src.contains("CONNECT_TIMEOUT_MS"))
        assertTrue("必须在后台线程执行，不能阻塞主线程", src.contains("thread(") || src.contains("Thread("))
    }
}
