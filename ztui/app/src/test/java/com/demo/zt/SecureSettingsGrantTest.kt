package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：`WRITE_SECURE_SETTINGS` 必须**自己通过 ADB 通道授予**，而不是让用户接电脑。
 *
 * 真实问题：用户点「重启后自动开无线调试」时被弹窗要求「用一次 ADB 命令授权」，
 * 但配对之后应用本来就持有 shell 通道——`pm grant` 完全可以自己做。
 */
class SecureSettingsGrantTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun grantUsesPmGrantOverOwnAdbChannel() {
        val s = src("SecureSettingsGrant.kt")
        assertTrue("必须通过 ADB 执行 pm grant", s.contains("pm grant"))
        assertTrue("必须使用自己的包名", s.contains("context.packageName"))
        assertTrue("必须带上目标权限", s.contains("WRITE_SECURE_SETTINGS"))
    }

    @Test
    fun grantVerifiesResultInsteadOfTrustingSilence() {
        val s = src("SecureSettingsGrant.kt")
        assertTrue(
            "pm grant 成功时无输出，必须回读权限确认，不能只看命令有没有报错",
            s.contains("hasPermission(context)"),
        )
    }

    @Test
    fun grantShortCircuitsWhenAlreadyGranted() {
        val s = src("SecureSettingsGrant.kt")
        assertTrue(
            "已授权时不应重复执行",
            s.contains("已持有 WRITE_SECURE_SETTINGS"),
        )
    }

    @Test
    fun settingsPrefersAutoGrantBeforeShowingManualCommand() {
        val s = src("SettingsMenu.kt")
        val idxAuto = s.indexOf("SecureSettingsGrant.grant")
        // 「备用手动命令」是自动授权失败后的兜底，必须排在自动授权之后
        val idxFallback = s.indexOf("也可用电脑执行一次")
        assertTrue("应调用自动授权", idxAuto >= 0)
        assertTrue("应保留电脑端命令兜底", idxFallback >= 0)
        assertTrue(
            "兜底手动命令必须排在自动授权之后",
            idxAuto < idxFallback,
        )
    }

    @Test
    fun manualCommandStillOfferedAsFallback() {
        val s = src("SettingsMenu.kt")
        assertTrue(
            "自动授权失败时仍要给出电脑端命令（文案：也可用电脑执行一次）",
            s.contains("也可用电脑执行一次"),
        )
    }

    @Test
    fun launchSelfHealAlsoGrants() {
        val s = src("MainActivity.kt")
        assertTrue(
            "启动自愈里也应补一次授权，配对后即可用",
            s.contains("SecureSettingsGrant.hasPermission(context)"),
        )
    }

}
