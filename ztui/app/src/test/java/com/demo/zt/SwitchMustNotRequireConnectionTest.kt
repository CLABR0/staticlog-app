package com.demo.zt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：设置开关不能被「必须先连上 ADB」卡死。
 *
 * 真实反馈：V27 里点「重启后自动开无线调试」打不开——因为代码在没连接时
 * 直接 return，连开关都没保存。
 *
 * 正确行为：开关是设置项，**先存下来**，授权等连接可用时再补。
 */
class SwitchMustNotRequireConnectionTest {

    private fun settings(): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/SettingsMenu.kt"),
            File("app/src/main/java/com/demo/zt/SettingsMenu.kt"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 SettingsMenu.kt（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun switchIsPersistedBeforeAnyConnectionAttempt() {
        val s = settings()
        val idxSet = s.indexOf("WirelessAdbAutoEnabler.setEnabled(context, true)")
        val idxConnect = s.indexOf("AdbConnectionManager.clientOrConnect")
        assertTrue("必须先保存开关状态", idxSet >= 0)
        assertTrue(
            "保存开关必须发生在连接尝试之前（否则没连接就点不动）",
            idxSet < idxConnect,
        )
    }

    @Test
    fun noEarlyReturnWhenNotConnected() {
        val s = settings()
        val idxConnect = s.indexOf("AdbConnectionManager.clientOrConnect")
        // 连接失败分支里不能直接 return@SwitchPreference 把状态丢掉
        val tail = s.substring(idxConnect, minOf(s.length, idxConnect + 2500))
        assertTrue(
            "连接失败时应给出说明并保留已保存的开关，而不是静默退出",
            tail.contains("开关已保存"),
        )
    }

    @Test
    fun userSeesImmediateFeedback() {
        val s = settings()
        assertTrue(
            "点击后要立刻有反馈，不能让用户以为没反应",
            s.contains("已开启。"),
        )
    }

    @Test
    fun launchSelfHealStillGrantsLater() {
        val main = listOf(
            File("src/main/java/com/demo/zt/MainActivity.kt"),
            File("app/src/main/java/com/demo/zt/MainActivity.kt"),
        ).firstOrNull { it.exists() }?.readText() ?: error("找不到 MainActivity.kt")
        assertTrue(
            "启动时应补授权，保证「先存后授权」最后真的能授上",
            main.contains("SecureSettingsGrant.hasPermission(context)"),
        )
    }
}
