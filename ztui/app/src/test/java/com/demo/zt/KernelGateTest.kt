package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核判定：**只看 6.6.127**，不再校验尾号 7741。
 *
 * 演进过程：
 *   1. 最初要求「`abogki` 后面的数字末四位 = 7741」——不同内核串格式下会误判
 *   2. 后来放宽为「版本对 + 串里含 7741」——仍会因为尾号位置变化把人挡在门外
 *   3. 现在**尾号校验整体删除**，只要版本是 6.6.127 就放行
 *
 * 这些用例把各版本的真实串都钉住，确保「版本对就能进」。
 */
class KernelGateTest {

    private fun ok(release: String) = KernelGate.evaluate(release).supported

    @Test
    fun acceptsCanonicalFormat() {
        // 文档里记录的本机格式
        val v = KernelGate.evaluate(
            "6.6.127-android15-8-g24b70dd1cb81-abogki543627741-4k",
        )
        assertTrue("本机内核串必须通过，实际=${v.reason}", v.supported)
        assertTrue(v.reason.contains("内核匹配"))
    }

    @Test
    fun acceptsWithoutAnyTailNumber() {
        // 关键回归：没有尾号也必须放行
        val v = KernelGate.evaluate("6.6.127-android16-8-gabc-4k")
        assertTrue("尾号校验已删除，不该再拦，实际=${v.reason}", v.supported)
        assertTrue("版本应判为匹配", v.prefixOk)
    }

    @Test
    fun acceptsShortAbogki() {
        assertTrue(ok("6.6.127-android16-8-gabc-abogki7741-4k"))
    }

    @Test
    fun acceptsArbitraryTail() {
        // 尾号是别的数字也要通过
        assertTrue(ok("6.6.127-android15-8-gabc-abogki12345678-4k"))
        assertTrue(ok("6.6.127-android15-8-gabc-abogki9999-4k"))
    }

    @Test
    fun rejectsWrongVersion() {
        val v = KernelGate.evaluate("5.4.32-perf-gda349bfae95e")
        assertFalse("版本不符必须拦", v.supported)
        assertFalse(v.prefixOk)
        assertTrue("原因应说明不匹配，实际=${v.reason}", v.reason.contains("不匹配"))
        assertTrue("原因应带上内核原文，实际=${v.reason}", v.reason.contains("5.4.32"))
    }

    @Test
    fun rejectsOtherSixSixVersion() {
        val v = KernelGate.evaluate("6.6.100-android16-gabc-7741-4k")
        assertFalse("6.6.100 不是 6.6.127，仍要拦", v.supported)
        assertFalse(v.prefixOk)
    }

    @Test
    fun emptyReleaseIsRejectedSafely() {
        val v = KernelGate.evaluate("")
        assertFalse(v.supported)
        assertEquals("", v.release)
    }

    @Test
    fun nullReleaseIsRejectedSafely() {
        assertFalse(KernelGate.evaluate(null).supported)
    }

    @Test
    fun reasonAlwaysIncludesRawReleaseWhenRejected() {
        val raw = "5.4.32-perf-gda349bfae95e"
        val v = KernelGate.evaluate(raw)
        assertFalse(v.supported)
        assertTrue("失败原因必须带上内核原文，否则无法排查：${v.reason}", v.reason.contains(raw))
    }

    @Test
    fun prefixConstantIsStable() {
        // 这个值来自载荷包名 66127，不能改
        assertEquals("6.6.127", KernelGate.EXPECTED_PREFIX)
    }
}
