package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 使用统计的隐私与开关约束。
 *
 * 这里守的是"别把不该发的东西发出去"和"没配置服务器时一个包都别发"，
 * 不是统计功能本身好不好用——后者只能在真机上验。
 */
class UsageReporterContractTest {

    private fun reporterSrc(): String =
        java.io.File("src/main/java/com/demo/zt/stats/UsageReporter.kt").readText()

    /** 去掉注释行：文档里会写"不采集 ANDROID_ID"，那不是在采集。 */
    private fun codeOnly(src: String): String =
        src.lineSequence()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    @Test
    fun payloadUsesOnlyTheSixWhitelistedFieldNames() {
        val src = reporterSrc()
        // 字段集合由 UsagePayloadTest 用真实 JSON 解析验证；这里守两件事：
        // 1) 组装逻辑集中在纯函数里，方便上面那个测试覆盖
        assertTrue(
            "buildPayload 必须委托给纯函数",
            src.contains("return buildPayloadFrom(installId(context), event, version, sdk, model, kernel)"),
        )
        // 2) 源码里不得出现白名单之外的敏感字段名
        val extra = listOf("phone", "imei", "mac_address", "serial", "android_id", "email", "account")
        for (bad in extra) {
            assertFalse("不允许出现字段 $bad", codeOnly(src).contains("\"$bad\"", ignoreCase = true))
        }
    }

    @Test
    fun neverCollectsSensitiveIdentifiers() {
        val src = codeOnly(reporterSrc())
        val forbidden = listOf(
            "ANDROID_ID", "Settings.Secure", "IMEI", "getImei", "getDeviceId",
            "getSubscriberId", "getMacAddress", "WifiInfo", "getLine1Number",
            "getAccounts", "InstalledPackages", "getInstalledPackages",
            "LocationManager", "getLastLocation",
        )
        for (bad in forbidden) {
            assertFalse("不允许采集 $bad", src.contains(bad))
        }
    }

    @Test
    fun installIdIsRandomNotDeviceDerived() {
        val src = codeOnly(reporterSrc())
        assertTrue("安装 ID 必须是随机 UUID", src.contains("UUID.randomUUID()"))
        assertFalse("不能从设备标识派生", src.contains("ANDROID_ID"))
    }

    @Test
    fun blankEndpointDisablesEverything() {
        val src = reporterSrc()
        // 已配置判定必须基于 BuildConfig.STATS_ENDPOINT 是否为空
        assertTrue(src.contains("fun isConfigured(): Boolean = BuildConfig.STATS_ENDPOINT.isNotBlank()"))
        // isEnabled 在未配置时必须直接 false
        assertTrue(
            "未配置端点时 isEnabled 必须为 false",
            Regex("fun isEnabled\\(context: Context\\): Boolean \\{\\s*\\n\\s*if \\(!isConfigured\\(\\)\\) return false")
                .containsMatchIn(src),
        )
        // report 入口必须先过开关
        assertTrue(
            "report 必须先检查开关",
            Regex("fun report\\(context: Context, event: String\\) \\{\\s*\\n\\s*if \\(!isEnabled\\(context\\)\\) return")
                .containsMatchIn(src),
        )
    }

    @Test
    fun reportingNeverBlocksCaller() {
        val src = reporterSrc()
        assertTrue("必须起后台线程", src.contains("thread(isDaemon = true"))
        assertTrue("异常必须被吞掉", src.contains("runCatching { post("))
    }

    @Test
    fun eventsMatchServerWhitelist() {
        val src = reporterSrc()
        assertTrue(src.contains("EVENT_INSTALL = \"install\""))
        assertTrue(src.contains("EVENT_HEARTBEAT = \"heartbeat\""))
        assertTrue(src.contains("EVENT_ESCALATE = \"escalate\""))

        val server = java.io.File("../../server/staticlog_stats.py").readText()
        assertTrue("服务端白名单必须包含 install", server.contains("\"install\""))
        assertTrue("服务端白名单必须包含 heartbeat", server.contains("\"heartbeat\""))
        assertTrue("服务端白名单必须包含 escalate", server.contains("\"escalate\""))
    }

    @Test
    fun settingsHasOptOutSwitch() {
        val src = java.io.File("src/main/java/com/demo/zt/SettingsMenu.kt").readText()
        assertTrue("设置里必须有开关", src.contains("匿名使用统计"))
        assertTrue("关闭后要说明不发请求", src.contains("不会向服务器发送任何请求"))
    }

    @Test
    fun escalationSuccessIsReported() {
        val src = java.io.File("src/main/java/com/demo/zt/MainActivity.kt").readText()
        assertTrue(
            "提权成功要记一次 escalate",
            src.contains("UsageReporter.EVENT_ESCALATE"),
        )
        assertTrue("启动时要报 install/heartbeat", src.contains("UsageReporter.reportLaunch"))
    }
}
