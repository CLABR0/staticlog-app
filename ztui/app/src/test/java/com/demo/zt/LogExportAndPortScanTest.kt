package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：debug 包必须能一键导出完整日志；自动连接不能依赖应用域读不到的东西。
 *
 * 背景：真机一直显示「ADB 未连接」，但没有日志就只能靠猜。
 * 曾经用 `/proc/net/tcp` 扫描作兜底，后来查 AOSP `private/app.te`
 * 发现普通应用域被 neverallow 禁止读取该文件，这条兜底等于死代码，已删除。
 */
class LogExportAndPortScanTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（cwd=${File(".").absolutePath}）")
    }

    private fun manifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.exists() }?.readText() ?: error("找不到 Manifest")
    }

    @Test
    fun exportIsDebugOnly() {
        val s = src("SettingsMenu.kt")
        assertTrue("导出入口必须受 BuildConfig.DEBUG 限制", s.contains("BuildConfig.DEBUG"))
        assertTrue("应提供复制到剪贴板", s.contains("copyToClipboard"))
        // 导出改为单击复制；exportToFile 仍保留在 LogExporter 里供将来使用
        assertTrue("应仍具备导出文件能力", src("LogExporter.kt").contains("exportToFile"))
    }

    @Test
    fun exportIncludesDeviceAndVersionContext() {
        val s = src("LogExporter.kt")
        for (key in listOf("版本", "设备", "系统", "内核", "连接")) {
            assertTrue("导出内容应包含「$key」等上下文", s.contains(key))
        }
    }

    @Test
    fun logsAreCollectedGlobally() {
        val s = src("MainActivity.kt")
        assertTrue("appendLog 要同步进全局缓冲区", s.contains("AppLog.add(line)"))
        val g = src("AppLog.kt")
        assertTrue("全局缓冲区应有上限，避免无限增长", g.contains("MAX_LINES"))
    }

    @Test
    fun fileProviderIsDeclared() {
        val m = manifest()
        assertTrue("分享文件需要 FileProvider", m.contains("FileProvider"))
        assertTrue("authorities 必须用 applicationId 占位", m.contains("\${applicationId}.fileprovider"))
    }

    @Test
    fun connectorDoesNotDependOnProcNetScan() {
        val s = src("AdbAutoConnector.kt")
        // AOSP private/app.te 有 neverallow { appdomain -shell } proc_net_tcp_udp:file *，
        // 普通应用读不到 /proc/net/tcp，所以这条路径必须删掉而不是留作兜底。
        assertFalse("不应再调用端口扫描", s.contains("scanListeningPorts()"))
        assertTrue("要写明为什么放弃扫描", s.contains("应用域无读权限"))
        assertTrue("要指明只有 shell 域能读", s.contains("仅 shell 可读"))
    }

    @Test
    fun scanSkipPointsAtRealCauses() {
        val s = src("AdbAutoConnector.kt")
        assertTrue("跳过扫描要写日志", s.contains("跳过 /proc/net/tcp 扫描"))
        // 端口拿不到时要引导去看 adb_wifi_enabled，而不是继续找端口
        assertTrue("注释里要指向真正的原因", s.contains("adb_wifi_enabled"))
    }
}
