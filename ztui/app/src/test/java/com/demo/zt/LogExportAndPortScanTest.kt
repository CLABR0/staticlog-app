package com.demo.zt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：debug 包必须能一键导出完整日志；连接必须有 mDNS 之外的兜底。
 *
 * 背景：真机一直显示「ADB 未连接」，但没有日志就只能靠猜。
 * 同时 vivo 等 ROM 的 NSD 经常发现不到无线调试服务，需要端口扫描兜底。
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
    fun connectorScansLocalPortsWhenMdnsFails() {
        val s = src("AdbAutoConnector.kt")
        assertTrue("应读取 /proc/net/tcp 找监听端口", s.contains("/proc/net/tcp"))
        assertTrue("只接受 LISTEN 状态", s.contains("\"0A\""))
        assertTrue("只接受回环地址", s.contains("0100007F"))
        assertTrue("扫描结果要有限制，避免太慢", s.contains("take(12)"))
    }

    @Test
    fun scanReportsWhatItFound() {
        val s = src("AdbAutoConnector.kt")
        assertTrue("扫描到端口要写日志", s.contains("扫描到 "))
        assertTrue("没读到也要写日志", s.contains("未能从 /proc/net/tcp"))
    }
}
