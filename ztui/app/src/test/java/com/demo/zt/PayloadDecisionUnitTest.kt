package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动」载荷选择的纯逻辑单测（JVM，不依赖设备）。
 *
 * 与 androidTest 里的 PayloadPolicyTest 互补：这里只测策略层，
 * 不碰 SharedPreferences / ActivityManager，所以 `gradle test` 就能跑，
 * 模拟器挂了也能验证选择逻辑。
 */
class PayloadDecisionUnitTest {

    private fun probes(
        family: String? = null,
        fingerprint: String? = null,
        gib: Double? = 15.0,
        failed: String? = null,
    ) = PayloadProbes(
        readSuccessFamily = { family },
        readFingerprint = { fingerprint },
        readTotalMemGiB = { gib },
        readFailureFamily = { failed },
    )

    // ── 代数判定 ──

    @Test
    fun successHistoryWins() {
        val p = PayloadPolicy.decide(
            probes(family = "V6", fingerprint = "x", gib = 15.0),
            listOf("x" to FlowType.V7),
        )
        assertEquals(Variant.ORANGE6_16G, p.variant)
        assertTrue(p.confirmed)
        assertTrue(p.reason.contains("历史成功记录"))
    }

    @Test
    fun fingerprintRuleUsedWithoutHistory() {
        val p = PayloadPolicy.decide(
            probes(fingerprint = "exact", gib = 12.0),
            listOf("exact" to FlowType.V6),
        )
        assertEquals(Variant.ORANGE6_12G, p.variant)
        assertTrue(p.confirmed)
    }

    @Test
    fun fingerprintMustMatchExactly() {
        val p = PayloadPolicy.decide(
            probes(fingerprint = "exact-suffix", gib = 12.0),
            listOf("exact" to FlowType.V6),
        )
        assertEquals(Variant.ORANGE7_12G, p.variant)
        assertFalse(p.confirmed)
    }

    @Test
    fun fallbackIsNewerGenerationAndUnconfirmed() {
        val p = PayloadPolicy.decide(probes(gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE7_16G, p.variant)
        assertFalse(p.confirmed)
        assertTrue(p.reason.contains("兜底"))
    }

    // ── 失败记忆 ──

    @Test
    fun previousFailureSwitchesFamily() {
        val p = PayloadPolicy.decide(probes(failed = "V7", gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE6_16G, p.variant)
        assertFalse("换的另一代尚未证实", p.confirmed)
        assertTrue(p.reason.contains("上次失败"))
        assertTrue(p.summary.contains("换另一代"))
    }

    @Test
    fun failureOfV6SwitchesToV7() {
        val p = PayloadPolicy.decide(probes(failed = "V6", gib = 12.0), emptyList())
        assertEquals(Variant.ORANGE7_12G, p.variant)
    }

    @Test
    fun successHistoryBeatsFailureRecord() {
        val p = PayloadPolicy.decide(
            probes(family = "V7", failed = "V7", gib = 15.0),
            emptyList(),
        )
        assertEquals(Variant.ORANGE7_16G, p.variant)
        assertTrue(p.confirmed)
    }

    @Test
    fun dirtyValuesAreIgnored() {
        val p = PayloadPolicy.decide(probes(family = "V9", failed = "V99", gib = 12.0), emptyList())
        assertEquals(Variant.ORANGE7_12G, p.variant)
        assertFalse(p.confirmed)
        assertTrue(p.reason.contains("兜底"))
    }

    // ── 内存分档 ──

    @Test
    fun memoryThresholdAt13GiB() {
        assertEquals(
            Variant.ORANGE7_16G,
            PayloadPolicy.decide(probes(family = "V7", gib = 13.0), emptyList()).variant,
        )
        assertEquals(
            Variant.ORANGE7_16G,
            PayloadPolicy.decide(probes(family = "V7", gib = 13.97), emptyList()).variant,
        )
        assertEquals(
            Variant.ORANGE7_12G,
            PayloadPolicy.decide(probes(family = "V7", gib = 12.99), emptyList()).variant,
        )
        assertEquals(
            Variant.ORANGE7_12G,
            PayloadPolicy.decide(probes(family = "V7", gib = 10.8), emptyList()).variant,
        )
    }

    @Test
    fun memoryUnavailableFallsBackTo12G() {
        val p = PayloadPolicy.decide(probes(family = "V7", gib = null), emptyList())
        assertEquals(Variant.ORANGE7_12G, p.variant)
        assertFalse("内存读不到不能算确认", p.confirmed)
        assertTrue(p.summary.contains("内存未知"))
    }

    @Test
    fun memoryZeroAndNanTreatedAsUnknown() {
        assertEquals(
            Variant.ORANGE7_12G,
            PayloadPolicy.decide(probes(family = "V7", gib = 0.0), emptyList()).variant,
        )
        assertEquals(
            Variant.ORANGE7_12G,
            PayloadPolicy.decide(probes(family = "V7", gib = Double.NaN), emptyList()).variant,
        )
    }

    // ── 异常与文案 ──

    @Test
    fun probeExceptionsSwallowed() {
        val p = PayloadPolicy.decide(
            PayloadProbes(
                readSuccessFamily = { error("boom") },
                readFingerprint = { error("boom") },
                readTotalMemGiB = { error("boom") },
                readFailureFamily = { error("boom") },
            ),
            emptyList(),
        )
        assertEquals(Variant.ORANGE7_12G, p.variant)
        assertFalse(p.confirmed)
    }

    @Test
    fun reasonAndSummaryAreReadable() {
        val p = PayloadPolicy.decide(probes(family = "V6", gib = 15.0), emptyList())
        assertTrue(p.reason.isNotBlank())
        assertTrue(p.reason.contains("第6代"))
        assertTrue(p.reason.contains("16G"))
        assertTrue("摘要要含代数", p.summary.contains("第6代"))
        assertTrue("摘要要含规格", p.summary.contains("16G"))
        assertTrue("摘要要单行，不应有换行", !p.summary.contains("\n"))
    }

    @Test
    fun everyVariantHasAssets() {
        // AUTO 是选择器占位，没有资源目录；其余四个必须齐备
        for (v in Variant.values()) {
            if (v == Variant.AUTO) {
                assertTrue("AUTO 不应有资源目录", v.assetKey.isEmpty())
                assertTrue("AUTO 不应有文件清单", v.files.isEmpty())
            } else {
                assertTrue("${v.name} 缺 assetKey", v.assetKey.isNotEmpty())
                assertTrue("${v.name} 文件清单为空", v.files.isNotEmpty())
            }
        }
    }
}
