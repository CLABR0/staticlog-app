package com.demo.zt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：配对完成后必须真正建立并持有 ADB 连接。
 *
 * 真实问题：明明配对成功了，设置页却提示「尚未连接 ADB」。
 * 根因是配对页用的是它自己内部的临时连接，用完即关，**从不写入 AdbHolder**；
 * 而主界面只在启动时连一次，配对完成后没人再连。
 */
class AdbHolderAfterPairingTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun popBackEstablishesConnectionAfterPairing() {
        val s = src("MainActivity.kt")
        val start = s.indexOf("fun popBack()")
        assertTrue("找不到 popBack", start >= 0)
        val body = s.substring(start, s.indexOf("\n    }", start))
        assertTrue("从配对页返回后必须主动连接", body.contains("AdbConnectionManager.connectBlocking"))
        assertTrue("要记录连接结果，便于排查", body.contains("[连接]"))
    }

    @Test
    fun ensureClientFallsBackToConnector() {
        val s = src("MainActivity.kt")
        assertTrue("应存在 ensureClient 辅助方法", s.contains("fun ensureClient()"))
        assertTrue(
            "没有现成连接时必须调用 AdbAutoConnector.connect",
            s.contains("AdbAutoConnector.connect(context)"),
        )
    }

    @Test
    fun selfCheckTriesConnectingFirst() {
        val s = src("MainActivity.kt")
        val start = s.indexOf("onOpenLocalAdb = {")
        val body = s.substring(start, start + 1600)
        // 新版设计：点击不再阻塞等待连接，而是「立即跳配对页 + 后台重连」
        assertTrue("点未连接时应立即进入配对页", body.contains("navigateTo(StaticLogScreen.Starter)"))
        assertTrue("同时后台尝试重连", body.contains("connectBlocking"))
    }

    @Test
    fun settingsGrantTriesConnectingBeforeDeclaringNotConnected() {
        val s = src("SettingsMenu.kt")
        assertTrue(
            "设置页授权前应通过统一管理器尝试连接，而不是直接报「尚未连接」",
            s.contains("AdbConnectionManager.clientOrConnect"),
        )
    }

    @Test
    fun holderIsStillOnlyWrittenByConnector() {
        // AdbHolder 应由统一连接器写入，避免多处各自 set 出不一致状态
        val s = src("AdbAutoConnector.kt")
        assertTrue("连接器负责写入 AdbHolder", s.contains("AdbHolder.set(client, port)"))
    }
}
