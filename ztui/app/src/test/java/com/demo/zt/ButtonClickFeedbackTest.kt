package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：按钮点击必须有肉眼可见的反馈，不能先阻塞再无声失败。
 *
 * 真实反馈：「点击本地 ADB 依旧没响应」。
 * 原因：点下去先 `ensureClient()`（最长 30 秒阻塞），期间界面毫无变化；
 * 而且 `checking` 这个状态设置了却从未渲染到界面。
 */
class ButtonClickFeedbackTest {

    private fun main(): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/MainActivity.kt"),
            File("app/src/main/java/com/demo/zt/MainActivity.kt"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 MainActivity.kt（cwd=${File(".").absolutePath}）")
    }

    private fun localAdbBlock(): String {
        val s = main()
        val start = s.indexOf("onOpenLocalAdb = {")
        assertTrue("找不到本地 ADB 回调", start >= 0)
        return s.substring(start, s.indexOf("onRun = {", start))
    }

    @Test
    fun clickGivesImmediateFeedback() {
        val b = localAdbBlock()
        assertTrue(
            "点击后必须马上写日志，让用户看到反应",
            b.contains("appendLog(\"[本地 ADB]"),
        )
    }

    @Test
    fun noBlockingConnectBeforeNavigating() {
        val b = localAdbBlock()
        // 未连接分支里，navigateTo 必须早于 connectBlocking（不能先卡 30 秒再跳）
        val idxNav = b.indexOf("navigateTo(StaticLogScreen.Starter)")
        val idxConnect = b.indexOf("connectBlocking")
        assertTrue("必须有跳转配对页", idxNav >= 0)
        assertTrue(
            "跳转配对页必须发生在后台连接之前（否则点击后长时间无反应）",
            idxNav < idxConnect,
        )
    }

    @Test
    fun noEnsureClientInClickPath() {
        val b = localAdbBlock()
        assertFalse(
            "点击路径里不能再调用阻塞的 ensureClient",
            b.contains("ensureClient()"),
        )
    }

    @Test
    fun connectResultIsReported() {
        val b = localAdbBlock()
        assertTrue(
            "后台连接无论成功失败都要写日志，不能静默",
            b.contains("已自动连上") && b.contains("自动重连失败"),
        )
    }

    @Test
    fun selfCheckRunsOnlyWhenClientExists() {
        val b = localAdbBlock()
        assertTrue("有连接时才做自检", b.contains("AdbSelfCheck.run(existing)"))
    }
}
