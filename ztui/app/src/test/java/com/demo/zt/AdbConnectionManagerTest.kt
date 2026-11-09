package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：ADB 连接必须「单飞 + 可等待 + 有状态」。
 *
 * 真实问题：明明配对成功，界面仍提示「尚未连接 ADB」。
 * 除了配对后不写 AdbHolder，还有竞态：后台连接没跑完，界面就下结论。
 */
class AdbConnectionManagerTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun hasExplicitStates() {
        val s = src("AdbConnectionManager.kt")
        for (state in listOf("IDLE", "CONNECTING", "CONNECTED", "FAILED")) {
            assertTrue("状态机必须包含 $state", s.contains(state))
        }
    }

    @Test
    fun isSingleFlight() {
        val s = src("AdbConnectionManager.kt")
        assertTrue("必须有进行中标记，避免并发重复连接", s.contains("inFlight"))
        assertTrue("并发调用应等待同一次尝试", s.contains("mine.done.await"))
    }

    @Test
    fun retriesWithinBudget() {
        val s = src("AdbConnectionManager.kt")
        assertTrue("应带重试间隔", s.contains("RETRY_INTERVAL_MS"))
        assertTrue("应受总预算约束", s.contains("deadline"))
    }

    @Test
    fun exposesLastErrorForUi() {
        val s = src("AdbConnectionManager.kt")
        assertTrue("必须记录失败原因供界面展示", s.contains("lastError"))
        assertTrue("应提供一句话状态描述", s.contains("fun describe()"))
    }

    @Test
    fun pairingTriggersRealConnection() {
        val s = src("ui/StarterScreen.kt")
        assertTrue(
            "配对成功后必须发起正式连接并落库",
            s.contains("connectBlocking(context"),
        )
    }

    @Test
    fun settingsShowsConnectionStatus() {
        val s = src("SettingsMenu.kt")
        // 状态不再占独立条目，改为「关闭」按钮上方的一行小字
        assertTrue("设置面板仍应显示连接状态", s.contains("ADB 未连接"))
        assertTrue("并保留关闭按钮", s.contains("关闭"))
        assertTrue("状态来自统一管理器", s.contains("AdbConnectionManager.describe()"))
    }

    @Test
    fun callersGoThroughManager() {
        val main = src("MainActivity.kt")
        assertTrue("主界面取连接必须走管理器", main.contains("AdbConnectionManager.clientOrConnect"))
        val settings = src("SettingsMenu.kt")
        assertTrue("设置页授权前也必须走管理器", settings.contains("AdbConnectionManager.clientOrConnect"))
    }

    @Test
    fun describeReflectsState() {
        // 纯逻辑：未连接状态不能宣称已连接
        assertFalse(AdbConnectionManager.isConnected())
    }
}
