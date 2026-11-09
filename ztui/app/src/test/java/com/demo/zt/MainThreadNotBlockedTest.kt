package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：主线程绝不能被 ADB 连接阻塞。
 *
 * 真实事故：设置页在 `onCheckedChange`（主线程）里直接调
 * `AdbConnectionManager.clientOrConnect(context, 30_000L)`，
 * 最长阻塞 30 秒 → ANR → 系统杀进程，用户看到的就是「运行一会儿就崩溃」。
 */
class MainThreadNotBlockedTest {

    private fun src(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $relative（cwd=${File(".").absolutePath}）")
    }

    /** 取出指定标记之后的若干行，用于检查某段代码里有没有阻塞调用。 */
    private fun blockAfter(text: String, marker: String, lines: Int): String {
        val idx = text.indexOf(marker)
        assertTrue("找不到标记：$marker", idx >= 0)
        return text.substring(idx).lineSequence().take(lines).joinToString("\n")
    }

    @Test
    fun settingsDoesNotConnectOnMainThread() {
        val s = src("SettingsMenu.kt")
        val idx = s.indexOf("onCheckedChange = { want ->")
        assertTrue("找不到开关回调", idx >= 0)
        // 从回调开始到出现 thread( 之前的代码，不能含 clientOrConnect
        val head = s.substring(idx, s.indexOf("thread(isDaemon = true)", idx))
        assertFalse(
            "主线程回调里不能直接调 clientOrConnect（会 ANR）",
            head.contains("clientOrConnect"),
        )
    }

    @Test
    fun settingsShowsProgressWhileConnecting() {
        val s = src("SettingsMenu.kt")
        assertTrue(
            "连接放到后台后，界面应提示「正在连接」，否则用户以为没反应",
            s.contains("正在连接 ADB"),
        )
    }

    @Test
    fun connectBlockingIsDocumentedAsBackgroundOnly() {
        val s = src("AdbConnectionManager.kt")
        assertTrue(
            "阻塞方法必须注明只能在后台线程调用",
            s.contains("必须在后台线程"),
        )
    }

    @Test
    fun crashGuardIsInstalled() {
        val s = src("CrashGuard.kt")
        assertTrue("必须设置未捕获异常处理器", s.contains("setDefaultUncaughtExceptionHandler"))
        assertTrue("崩溃要落盘，否则拿不到堆栈", s.contains("crash_last.txt"))
        val main = src("MainActivity.kt")
        assertTrue("启动时安装崩溃守卫", main.contains("CrashGuard.install(this)"))
        assertTrue("启动时展示上次崩溃", main.contains("CrashGuard.readLast(context)"))
    }
}
