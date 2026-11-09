package com.demo.zt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：决定「开机行为」的开关必须用同步写盘。
 *
 * 曾经的隐患：`SharedPreferences.apply()` 是异步的。用户关掉「开机自动提权」
 * 后如果应用立刻被杀，这次写入会丢失 —— 重启后照旧执行，现象就是「关不掉」。
 * 这类 bug 只在真机重启后才暴露，很难复现，所以在源码层面加静态断言。
 */
class BootFlagPersistenceTest {

    private fun source(relative: String): String {
        // 单测运行在模块目录下，源码在 src/main/java/...
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码文件：$relative（工作目录=${File(".").absolutePath}）")
    }

    @Test
    fun autoBootFlagUsesCommitNotApply() {
        val src = source("AutoBoot.kt")
        assertTrue("AutoBoot 的开关必须用 commit() 同步写盘", src.contains("commit()"))
        assertTrue(
            "AutoBoot.kt 里不应再用 apply() 写开关",
            !src.contains("putBoolean(KEY_ENABLED, true).apply()") &&
                !src.contains("putBoolean(KEY_ENABLED, false).apply()"),
        )
    }

    @Test
    fun wirelessAdbFlagUsesCommitNotApply() {
        val src = source("WirelessAdbAutoEnabler.kt")
        assertTrue(
            "无线调试自动开关也必须同步写盘",
            src.contains("putBoolean(KEY_ENABLED, enabled).commit()"),
        )
    }

    @Test
    fun bootReceiverConsultsTheFlag() {
        val src = source("BootReceiver.kt")
        assertTrue(
            "开机广播必须检查开关状态，否则关掉也没用",
            src.contains("AutoBoot.shouldRunOnBootAndConsume"),
        )
    }

    @Test
    fun settingsToggleResyncsFromStorage() {
        val src = source("SettingsMenu.kt")
        assertTrue(
            "开关切换后必须从存储回读，避免显示与实际脱节",
            src.contains("enabled = AutoBoot.isEnabled(context)"),
        )
    }
}
