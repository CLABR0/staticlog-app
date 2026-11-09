package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：配对页不得等待 Stellar Binder。
 *
 * 真实事故：配对命令已改成只读的 `id`（不再拉起 Stellar 服务），
 * 但步骤 UI 仍会进入 `waitForService()` 等 Binder——那个 Binder 永远不会出现，
 * 于是永远卡在「等待 Binder 响应」。
 */
class PairingNoBinderWaitTest {

    private fun starter(): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/ui/StarterScreen.kt"),
            File("app/src/main/java/com/demo/zt/ui/StarterScreen.kt"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 StarterScreen.kt（cwd=${File(".").absolutePath}）")
    }

    /** 取出 `output.contains("uid=")` 那个分支的代码块。 */
    private fun uidBranch(): String {
        val src = starter()
        val start = src.indexOf("output.contains(\"uid=\")")
        assertTrue("找不到 uid= 分支", start >= 0)
        // 分支体到下一个 output.contains 之前
        val next = src.indexOf("output.contains(", start + 10)
        return if (next > start) src.substring(start, next) else src.substring(start)
    }

    @Test
    fun uidBranchFinishesImmediately() {
        val branch = uidBranch()
        assertTrue(
            "连接自检成功后必须直接收尾（setSuccess），实际分支：$branch",
            branch.contains("setSuccess()"),
        )
    }

    @Test
    fun uidBranchDoesNotWaitForBinder() {
        val branch = uidBranch()
        assertFalse(
            "只读的 id 命令不会拉起 Stellar 服务，不能等 Binder，实际分支：$branch",
            branch.contains("waitForService()"),
        )
    }

    @Test
    fun pairingCommandIsStillReadOnly() {
        val starterShell = listOf(
            File("src/main/java/com/demo/zt/shell/Starter.kt"),
            File("app/src/main/java/com/demo/zt/shell/Starter.kt"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("找不到 Starter.kt")
        val line = starterShell.lineSequence()
            .firstOrNull { it.contains("wirelessDebuggingCommand") && it.contains("const val") }
            ?: error("找不到 wirelessDebuggingCommand")
        assertTrue("配对命令必须仍是只读的 id，实际：$line", line.contains("\"id\""))
        assertFalse("配对命令不能是载荷启动器，实际：$line", line.contains("zt_run"))
    }
}
