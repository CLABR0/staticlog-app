package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：「开机自动提权」开关的三道门禁。
 *
 * 需求：
 * 1. 开启前必须弹 **10 秒**倒计时警告（防误触导致设备反复重启）
 * 2. **前置条件**：必须先开启「重启后自动开无线调试」，否则开机没端口可连
 * 3. **内核不匹配时禁止启动这一项**（不是只弹提示，而是开关本身禁用）
 */
class AutoBootGatesTest {

    private fun settings(): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/SettingsMenu.kt"),
            File("app/src/main/java/com/demo/zt/SettingsMenu.kt"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 SettingsMenu.kt（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun switchIsDisabledWhenKernelUnsupported() {
        val s = settings()
        assertTrue(
            "内核不匹配时必须禁用开关本身（enabled = supported），而不是只弹窗",
            s.contains("enabled = supported"),
        )
    }

    @Test
    fun requiresWirelessAdbAutoBeforeEnabling() {
        val s = settings()
        assertTrue(
            "必须先判断无线调试自启是否开启",
            s.contains("!WirelessAdbAutoEnabler.isEnabled(context) -> showNeedWireless = true"),
        )
    }

    @Test
    fun warningCountsDownFromTenSeconds() {
        val s = settings()
        assertTrue("倒计时应从 10 开始", s.contains("warnRemaining = 10"))
        assertTrue("倒计时要递减", s.contains("warnRemaining--"))
    }

    @Test
    fun confirmIsBlockedDuringCountdown() {
        val s = settings()
        assertTrue(
            "倒计时未结束时确认按钮必须不可点",
            s.contains("enabled = warnRemaining <= 0"),
        )
    }

    @Test
    fun summaryExplainsWhyDisabled() {
        val s = settings()
        assertTrue("应在副标题说明内核不匹配", s.contains("内核不匹配，此项已禁用"))
        assertTrue("应提示先去开无线调试", s.contains("需先开启「重启后自动开无线调试」"))
    }

    @Test
    fun enablingStillReadsBackFromStorage() {
        val s = settings()
        assertTrue(
            "开启/关闭后都要以存储为准回读",
            s.contains("enabled = AutoBoot.isEnabled(context)"),
        )
    }

    @Test
    fun autoBootEnableStillChecksKernel() {
        // 双保险：即便 UI 被绕过，AutoBoot.enable 内部也要拒绝不适配的内核
        val p = listOf(
            File("src/main/java/com/demo/zt/AutoBoot.kt"),
            File("app/src/main/java/com/demo/zt/AutoBoot.kt"),
        ).firstOrNull { it.exists() }?.readText() ?: error("找不到 AutoBoot.kt")
        assertTrue(
            "AutoBoot.enable 必须自己再判一次内核",
            p.contains("KernelGate.check().supported"),
        )
        assertFalse(
            "不能在 enable 里无条件写 true",
            p.contains("fun enable(context: Context): Boolean {\n        prefs"),
        )
    }
}
