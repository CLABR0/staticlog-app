package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提权成功后的收尾：解锁屏幕 + 拉起 root 管理器。
 *
 * 这段命令写错一个字符，用户提权成功了却看不到任何东西（屏幕还锁着），
 * 所以把脚本内容、包名优先级、无包名时的兜底都钉住。
 */
class PostEscalationTest {

    @Test
    fun scriptWakesScreenAndDismissesKeyguard() {
        val s = PostEscalation.buildScript("me.weishu.kernelsu")
        assertTrue("必须唤醒屏幕（keyevent 224）", s.contains("input keyevent 224"))
        assertTrue("必须解除锁屏（wm dismiss-keyguard）", s.contains("wm dismiss-keyguard"))
    }

    @Test
    fun unlockHappensBeforeLaunchingManager() {
        val s = PostEscalation.buildScript("me.weishu.kernelsu")
        val wake = s.indexOf("keyevent 224")
        val dismiss = s.indexOf("dismiss-keyguard")
        val launch = s.indexOf("monkey -p")
        assertTrue("唤醒必须在最前", wake in 0 until dismiss)
        assertTrue("必须先解锁再拉起管理器", dismiss in 0 until launch)
    }

    @Test
    fun launchesManagerByPackageWithLauncherCategory() {
        val s = PostEscalation.buildScript("me.weishu.kernelsu")
        assertTrue(
            "要用 monkey 按包名启动，实际=$s",
            s.contains("monkey -p me.weishu.kernelsu -c android.intent.category.LAUNCHER 1"),
        )
    }

    @Test
    fun withoutManagerItDoesNotTryToLaunchAnything() {
        val s = PostEscalation.buildScript(null)
        assertFalse("没有管理器时不能尝试拉起", s.contains("monkey"))
        assertTrue("应给出 NO_MANAGER 标记", s.contains("NO_MANAGER"))
        // 但解锁动作仍要执行——用户至少能看到屏幕
        assertTrue(s.contains("dismiss-keyguard"))
    }

    @Test
    fun managerCandidatesPreferKernelSu() {
        val list = PostEscalation.MANAGER_CANDIDATES
        assertEquals("KernelSU 官方应排第一", "me.weishu.kernelsu", list.first())
        assertTrue("应包含 KernelSU Next", list.contains("com.rifsxd.ksunext"))
        assertTrue("应包含 Magisk 兜底", list.contains("com.topjohnwu.magisk"))
        assertEquals("候选不应重复", list.size, list.toSet().size)
    }

    @Test
    fun nullClientSkipsSafely() {
        // 没有 client 时不能崩，也不能声称做了什么
        val r = runCatching { PostEscalation.buildScript(null) }
        assertTrue("buildScript 不能抛异常", r.isSuccess)
    }

    @Test
    fun scriptIsMultiLineForReadability() {
        val s = PostEscalation.buildScript("me.weishu.kernelsu")
        assertTrue("脚本应有换行，便于排查", s.lines().size >= 4)
        assertFalse("不应出现 CRLF", s.contains("\r"))
    }
}
