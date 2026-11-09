package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 管理器门禁：橘子6 要 KernelSU 系，橘子7 要 SukiSU Ultra。
 *
 * 这些断言直接决定"能不能开跑"，所以覆盖到：正常命中、缺包、
 * 读取失败（必须与"没装"区分开）、以及 12g/16g 共用同一套判定。
 */
class RootManagerGateTest {

    private fun pm(vararg packages: String) =
        packages.joinToString("\n") { "package:$it" } + "\n"

    @Test
    fun orange6RequiresKsuFamily() {
        assertEquals(RootManagerKind.KSU, RootManagerGate.requirement(FlowType.V6))

        val ok = RootManagerGate.evaluate(FlowType.V6, pm("me.weishu.kernelsu"))
        assertTrue("装了 KernelSU 应放行", ok.ok)
        assertEquals("me.weishu.kernelsu", ok.foundPackage)

        val resukisu = RootManagerGate.evaluate(FlowType.V6, pm("com.resukisu.resukisu"))
        assertTrue("ReSukiSU 也算", resukisu.ok)

        val next = RootManagerGate.evaluate(FlowType.V6, pm("com.rifsxd.ksunext"))
        assertTrue("KernelSU-Next 也算", next.ok)
    }

    @Test
    fun orange6RejectsWhenOnlySukiSuInstalled() {
        val v = RootManagerGate.evaluate(FlowType.V6, pm("com.sukisu.ultra"))
        assertFalse("只有 SukiSU 不足以跑橘子6", v.ok)
        assertNull(v.foundPackage)
        assertTrue("报错要点名缺什么", v.message.contains("KernelSU"))
    }

    @Test
    fun orange7RequiresSukiSu() {
        assertEquals(RootManagerKind.SUKISU, RootManagerGate.requirement(FlowType.V7))

        val ok = RootManagerGate.evaluate(FlowType.V7, pm("com.sukisu.ultra"))
        assertTrue("装了 SukiSU Ultra 应放行", ok.ok)
        assertEquals("com.sukisu.ultra", ok.foundPackage)
    }

    @Test
    fun orange7RejectsPlainKernelsu() {
        val v = RootManagerGate.evaluate(FlowType.V7, pm("me.weishu.kernelsu"))
        assertFalse("只有 KernelSU 不足以跑橘子7（新包要求 SukiSU Ultra）", v.ok)
        assertTrue(v.message.contains("SukiSU Ultra"))
    }

    @Test
    fun emptyPackageListIsUnverifiedNotBlocked() {
        val v6 = RootManagerGate.evaluate(FlowType.V6, pm())
        val v7 = RootManagerGate.evaluate(FlowType.V7, pm())
        // 空输出 = 没读到，不等于"没装"：放行但标未确认
        assertTrue(v6.ok)
        assertTrue(v7.ok)
        assertFalse(v6.verified)
        assertFalse(v7.verified)
        assertTrue("要说明已放行", v6.message.contains("已放行"))
    }

    @Test
    fun readFailureWarnsButDoesNotBlock() {
        val v = RootManagerGate.evaluate(FlowType.V7, null)
        assertTrue("读不到时不能拦死（会把能用的环境一起挡掉）", v.ok)
        assertFalse("但必须标成未确认", v.verified)
        assertTrue("必须说'无法确认'，不能说'没装'", v.message.contains("无法读取"))
        assertFalse("不能误报成没装", v.message.contains("缺少"))
        assertTrue("要写明已放行", v.message.contains("已放行"))
    }

    @Test
    fun emptyOutputIsAlsoTreatedAsUnverified() {
        val v = RootManagerGate.evaluate(FlowType.V6, "Error: Could not access the package manager\n")
        assertTrue("有输出但没有 package: 行，同样不能拦死", v.ok)
        assertFalse(v.verified)
        assertTrue(v.message.contains("应用列表为空"))
    }

    @Test
    fun probeFallsBackThroughCommands() {
        val tried = mutableListOf<String>()
        val probe = RootManagerGate.probe(retriesPerCommand = 1, runShell = { cmd ->
            tried += cmd
            if (cmd == "pm list packages") "Error: Could not access the package manager"
            else "package:com.sukisu.ultra\npackage:android\n"
        })
        assertEquals(
            "第一条失败后必须换 cmd package 再试",
            listOf("pm list packages", "cmd package list packages"),
            tried,
        )
        assertTrue("应读到列表", probe.readable)
        assertEquals("cmd package list packages", probe.command)
        assertEquals("com.sukisu.ultra", RootManagerGate.parseInstalled(probe.raw!!).first { it.contains("sukisu") })
    }

    @Test
    fun probeReportsAllFailuresWithoutThrowing() {
        val probe = RootManagerGate.probe(runShell = { throw IllegalStateException("连接已断开") })
        assertFalse(probe.readable)
        assertNull(probe.raw)
        assertEquals(
            "每条命令都要试过 retries 次",
            RootManagerGate.PROBE_COMMANDS.size * 2,
            probe.attempts,
        )
        assertTrue("诊断里要带出错原因", probe.summary().contains("连接已断开"))
    }

    @Test
    fun probeSummaryIsHumanReadable() {
        val probe = RootManagerGate.probe(runShell = { "package:com.sukisu.ultra\npackage:android\n" }, retriesPerCommand = 1)
        val s = probe.summary()
        assertTrue("要写命令", s.contains("探测命令=pm list packages"))
        assertTrue("要写字节数", s.contains("输出"))
        assertTrue("要给前几行", s.contains("package:com.sukisu.ultra"))
    }

    @Test
    fun parseIgnoresNoiseAndKeepsAllPackages() {
        val raw = """
            package:android
            package:com.demo.zt

            package:me.weishu.kernelsu
              package:com.sukisu.ultra
            Error: something
        """.trimIndent()
        val set = RootManagerGate.parseInstalled(raw)
        assertTrue(set.contains("android"))
        assertTrue(set.contains("com.demo.zt"))
        assertTrue(set.contains("me.weishu.kernelsu"))
        assertTrue("行首空格不能影响识别", set.contains("com.sukisu.ultra"))
        assertFalse(set.contains("Error: something"))
    }

    @Test
    fun messagesNameTheRequiredManager() {
        assertTrue(
            RootManagerGate.evaluate(FlowType.V6, pm()).message.contains("KernelSU"),
        )
        assertTrue(
            RootManagerGate.evaluate(FlowType.V7, pm()).message.contains("SukiSU Ultra"),
        )
    }

    @Test
    fun gateRunsBeforePushingFiles() {
        val src = java.io.File("src/main/java/com/demo/zt/shell/PayloadRunner.kt").readText()
        val gateIdx = src.indexOf("RootManagerGate.evaluate")
        val pushIdx = src.indexOf("// 3. 推送载荷")
        assertTrue("必须调用管理器门禁", gateIdx > 0)
        assertTrue("门禁必须在推送之前", gateIdx in 1 until pushIdx)
        assertTrue("读不到列表时要留痕", src.contains("probe.summary()"))
        assertTrue("未确认时必须提醒", src.contains("未能确认管理器"))
    }
}
