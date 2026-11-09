package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：内核门禁不得把「读取失败」当成「内核不匹配」。
 *
 * 真实现象：界面显示 6.6.127-...-abogki543627741-4k（完全匹配），
 * 执行时却报「[门禁] 设备内核：（空）」→「内核不匹配，已拒绝」。
 * 根因是 runShell 抛异常后被 getOrDefault("") 吞成空串。
 */
class RunnerKernelGateTest {

    private fun runner(): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/shell/PayloadRunner.kt"),
            File("app/src/main/java/com/demo/zt/shell/PayloadRunner.kt"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 PayloadRunner.kt（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun shellFailureIsDistinguishedFromMismatch() {
        val s = runner()
        assertTrue("要区分 shell 读取失败", s.contains("shell 读取内核失败"))
        // 只检查内核门禁这一段：文件别处（root 探测）仍在用 getOrDefault
        val start = s.indexOf("内核门禁")
        val end = s.indexOf("采用内核")
        assertTrue("找不到内核门禁段", start >= 0 && end > start)
        assertFalse(
            "内核门禁段不得用 getOrDefault 吞异常",
            s.substring(start, end).contains("getOrDefault"),
        )
    }

    @Test
    fun fallsBackToLocalKernelRelease() {
        val s = runner()
        assertTrue("读不到时应回退本机内核串", s.contains("DeviceInfo.kernelRelease"))
    }

    @Test
    fun usesSharedKernelGate() {
        val s = runner()
        assertTrue("判定应走统一的 KernelGate", s.contains("KernelGate.evaluate"))
        assertTrue("要打印判定理由", s.contains("verdict.reason"))
    }

    @Test
    fun logsAdoptedKernel() {
        val s = runner()
        assertTrue("要打印实际采用的内核串，便于排查", s.contains("[门禁] 采用内核"))
    }

    @Test
    fun blankShellResultStillFallsBack() {
        val s = runner()
        assertTrue("空串也应触发回退", s.contains("takeIf { it.isNotBlank() }"))
    }

    @Test
    fun mismatchMessageKeepsReason() {
        val s = runner()
        assertTrue("拒绝理由要带具体原因", s.contains("内核不匹配："))
        assertFalse("不应残留旧的硬编码文案", s.contains("内核不匹配（需要 6.6.127），已拒绝"))
    }
}
