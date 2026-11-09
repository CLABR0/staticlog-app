package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码审查中确认的缺陷，逐条用测试钉住，防止改回去。
 *
 * 这些是**静态守卫**（读源码文本断言结构），不是行为测试：
 * 它们拦的是「又写成主线程阻塞 / 又漏 close / 日志又无限涨 / 状态又放回 item 里」。
 * 行为层面仍需真机与 instrumented test 覆盖，这里不冒充。
 */
class ReviewRegressionTest {

    private fun src(path: String): String =
        java.io.File("src/main/java/com/demo/zt/$path").readText()

    /** 从某个锚点开始截一段，避免对整文件做宽泛匹配。 */
    private fun slice(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        val b = text.indexOf(to, a + from.length)
        assertTrue("找不到片段：$from .. $to", a >= 0 && b > a)
        return text.substring(a, b)
    }

    // ── #1 「执行」不得在主线程里做阻塞连接 ──

    @Test
    fun runPayloadDoesConnectInsideWorkerThread() {
        val body = slice(src("MainActivity.kt"), "fun runPayload()", "fun popBack()")
        val threadAt = body.indexOf("thread(isDaemon = true)")
        val connectAt = body.indexOf("AdbAutoConnector.connect")
        assertTrue("runPayload 里应出现后台线程", threadAt > 0)
        assertTrue("runPayload 里应出现自动连接", connectAt > 0)
        assertTrue(
            "连接必须在 thread{} 之内：onClick 是主线程，connect 会 latch.await(15s)",
            connectAt > threadAt,
        )
        // 函数开头不能再有"先连再进后台"的写法
        assertFalse(
            "runPayload 开头不得先同步连接",
            body.substring(0, threadAt).contains("AdbAutoConnector.connect"),
        )
    }

    @Test
    fun runPayloadGuardsReentry() {
        val body = slice(src("MainActivity.kt"), "fun runPayload()", "fun popBack()")
        assertTrue("必须防重复点击", body.contains("if (running) return"))
    }

    @Test
    fun connectIsMarkedBlockingInItsOwnDocs() {
        // 文档写明「应在后台线程调用」，调用处就必须遵守
        val conn = src("AdbAutoConnector.kt")
        assertTrue(conn.contains("应在后台线程调用"))
        assertTrue(conn.contains("latch.await"))
    }

    // ── #3 配对客户端必须被关闭 ──

    @Test
    fun pairingClientIsAlwaysClosed() {
        val service = src("adb/AdbPairingService.kt")
        val uses = Regex("""AdbPairingClient\([^)]*\)\s*\.use""").findAll(service).count()
        val bare = Regex("""AdbPairingClient\([^)]*\)\s*\.start""").findAll(service).count()
        assertTrue("至少一处要用 use{} 包裹", uses >= 1)
        assertEqualsMsg("不允许再出现裸 .start() 不关闭", 0, bare)
    }

    private fun assertEqualsMsg(msg: String, expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(msg, expected.toLong(), actual.toLong())
    }

    // ── #9 自检状态必须被读取（既做门禁也做提示）──

    @Test
    fun checkingStateIsReadNotJustWritten() {
        val main = src("MainActivity.kt")
        val writes = Regex("""checking\s*=\s*true""").findAll(main).count()
        assertTrue("应该有写入", writes >= 1)
        assertTrue(
            "checking 必须被读取（门禁），否则等于没有状态",
            main.contains("if (checking)"),
        )
        assertTrue(
            "要有可见反馈：adbChecking 传进界面",
            main.contains("adbChecking = checking"),
        )
        assertTrue("界面里要渲染出来", main.contains("正在自检 ADB"))
    }

    // ── #10 主界面日志必须有上限 ──

    @Test
    fun mainScreenLogListIsBounded() {
        val main = src("MainActivity.kt")
        assertTrue("必须定义上限常量", main.contains("LOG_MAX_LINES"))
        assertTrue(
            "appendLog 必须截断，否则跑一次载荷会一直涨",
            main.contains("takeLast(LOG_MAX_LINES)"),
        )
        assertFalse(
            "不允许再做无界追加",
            main.contains("logLines = logLines + line }"),
        )
    }

    // ── #2 拔探针状态不得放在 item 内的 remember 里 ──

    @Test
    fun probeRemoveStateSurvivesItemRecycling() {
        val ui = src("ProbeRemoveUi.kt")
        assertTrue("状态要挂到进程级对象", ui.contains("object ProbeRemoveState"))
        assertTrue("busy 要来自该对象", ui.contains("ProbeRemoveState.busy"))
        assertTrue("结果也要来自该对象", ui.contains("ProbeRemoveState.result"))
        assertFalse(
            "不能再把 busy 声明成局部 remember",
            ui.contains("var busy by remember"),
        )
        assertFalse(
            "不能再把结果声明成局部 remember",
            ui.contains("var resultMsg by remember"),
        )
        // 并发保护：busy 时点一下只记日志，不再开第二个任务
        assertTrue(ui.contains("正在执行中"))
    }

    // ── #11 异常要留下类型，不能只有 message ──

    @Test
    fun exceptionLogKeepsType() {
        val body = slice(src("MainActivity.kt"), "fun runPayload()", "fun popBack()")
        assertTrue(
            "只记 e.message 在 message 为空时等于没记",
            body.contains("e::class.simpleName"),
        )
    }
}
