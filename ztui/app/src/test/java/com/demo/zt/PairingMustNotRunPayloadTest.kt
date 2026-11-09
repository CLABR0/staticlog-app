package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：配对路径绝对不能执行载荷。
 *
 * 真实事故：配对页连上之后执行的是 `Starter.wirelessDebuggingCommand`，
 * 该常量曾经等于 `userCommand` = `/data/local/tmp/zt_run --run`，
 * 结果「一配对就自动开始提权」，用户完全没点过「执行」。
 *
 * 这条在源码层面钉死，防止有人图省事又把它接回去。
 */
class PairingMustNotRunPayloadTest {

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/$relative"),
            File("app/src/main/java/com/demo/zt/$relative"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到源码：$relative（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun pairingCommandIsNotThePayloadLauncher() {
        val src = source("shell/Starter.kt")
        val line = src.lineSequence()
            .firstOrNull { it.contains("wirelessDebuggingCommand") && it.contains("const val") }
            ?: error("找不到 wirelessDebuggingCommand 定义")

        assertFalse(
            "配对命令不能是载荷启动器，实际：$line",
            line.contains("zt_run"),
        )
        assertTrue(
            "配对命令应指向只读检查（id），实际：$line",
            line.contains("\"id\""),
        )
    }

    @Test
    fun pairingCommandIsNotAliasedToUserCommand() {
        val src = source("shell/Starter.kt")
        val line = src.lineSequence()
            .firstOrNull { it.contains("wirelessDebuggingCommand") && it.contains("const val") }
            ?: error("找不到 wirelessDebuggingCommand 定义")
        assertFalse(
            "配对命令不能直接别名到 userCommand（那正是事故原因），实际：$line",
            line.contains("userCommand"),
        )
    }

    @Test
    fun popBackHasNoPayloadSideEffect() {
        val src = source("MainActivity.kt")
        val start = src.indexOf("fun popBack()")
        assertTrue("找不到 popBack", start >= 0)
        val end = src.indexOf("\n    }", start)
        val body = src.substring(start, end)
        assertFalse("popBack 里不能调 runPayload", body.contains("runPayload"))
        assertFalse("popBack 里不能碰 PayloadRunner", body.contains("PayloadRunner"))
    }
}
