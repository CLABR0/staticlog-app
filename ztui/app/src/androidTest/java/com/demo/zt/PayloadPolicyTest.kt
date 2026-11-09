package com.demo.zt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「自动」载荷选择的判定逻辑。
 *
 * 覆盖：历史成功记录 / 已验证规则 / 兜底 / 脏值 / 内存边界 / 探测抛异常。
 * 用的是 [PayloadPolicy.decide]（纯逻辑，探测值注入），不依赖真实设备状态。
 */
@RunWith(AndroidJUnit4::class)
class PayloadPolicyTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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

    @Test
    fun failureMakesAutoTryOtherFamily() {
        // 上次第 7 代失败 → 本轮改试第 6 代
        val plan = PayloadPolicy.decide(probes(failed = "V7", gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE6_16G, plan.variant)
        assertFalse("换另一代尚未证实，应标未确认", plan.confirmed)
        assertTrue("理由应说明是上代失败", plan.reason.contains("上次失败"))
        assertTrue("摘要应体现换代", plan.summary.contains("换另一代"))
    }

    @Test
    fun failureOfV6SwitchesToV7() {
        val plan = PayloadPolicy.decide(probes(failed = "V6", gib = 12.0), emptyList())
        assertEquals(Variant.ORANGE7_12G, plan.variant)
        assertFalse(plan.confirmed)
    }

    @Test
    fun successHistoryOverridesFailureRecord() {
        // 有成功记录时，失败记录不再影响选择
        val plan = PayloadPolicy.decide(
            probes(family = "V7", failed = "V7", gib = 15.0),
            emptyList(),
        )
        assertEquals(Variant.ORANGE7_16G, plan.variant)
        assertTrue("有成功记录应确认", plan.confirmed)
    }

    @Test
    fun dirtyFailureValueIgnored() {
        val plan = PayloadPolicy.decide(probes(failed = "V99", gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE7_16G, plan.variant)
        assertTrue("脏值应走兜底", plan.reason.contains("兜底"))
    }

    @Test
    fun successClearsFailureRecord() {
        val history = PayloadResolver.PayloadHistory(context)
        history.clear()
        history.recordFailure(FlowType.V7)
        assertEquals("V7", history.readFailureFamily())

        history.recordSuccess(FlowType.V6)
        assertEquals("V6", history.readSuccessFamily())
        assertTrue("成功后应清掉失败记录", history.readFailureFamily() == null)

        history.clear()
        assertTrue(history.readFailureFamily() == null)
    }

    @Test
    fun historyWinsOverRules() {
        val plan = PayloadPolicy.decide(
            probes(family = "V6", fingerprint = "fp-any", gib = 15.0),
            rules = listOf("fp-any" to FlowType.V7),
        )
        assertEquals(Variant.ORANGE6_16G, plan.variant)
        assertTrue("有历史记录应标记为已确认", plan.confirmed)
        assertTrue("理由应提到历史", plan.reason.contains("历史成功记录"))
    }

    @Test
    fun verifiedRuleUsedWhenNoHistory() {
        val plan = PayloadPolicy.decide(
            probes(family = null, fingerprint = "exact-fp", gib = 12.0),
            rules = listOf("exact-fp" to FlowType.V6),
        )
        assertEquals(Variant.ORANGE6_12G, plan.variant)
        assertTrue(plan.confirmed)
        assertTrue("理由应提到指纹匹配", plan.reason.contains("指纹"))
    }

    @Test
    fun fingerprintMustMatchExactly() {
        val plan = PayloadPolicy.decide(
            probes(family = null, fingerprint = "exact-fp-suffix", gib = 12.0),
            rules = listOf("exact-fp" to FlowType.V6),
        )
        // 前缀不算命中 → 走兜底 V7
        assertEquals(Variant.ORANGE7_12G, plan.variant)
        assertFalse("兜底不应标记为已确认", plan.confirmed)
    }

    @Test
    fun fallbackIsUnconfirmed() {
        val plan = PayloadPolicy.decide(
            probes(family = null, fingerprint = "unknown", gib = 15.0),
            rules = emptyList(),
        )
        assertEquals(Variant.ORANGE7_16G, plan.variant)
        assertFalse(plan.confirmed)
        assertTrue("理由应说明是兜底", plan.reason.contains("兜底"))
    }

    @Test
    fun dirtyHistoryIsIgnored() {
        val plan = PayloadPolicy.decide(
            probes(family = "V9", fingerprint = "unknown", gib = 12.0),
            rules = emptyList(),
        )
        assertEquals(Variant.ORANGE7_12G, plan.variant)
        assertFalse(plan.confirmed)
    }

    @Test
    fun memoryBoundary() {
        // 16G 机型实报下限 15.0e9 B ≈ 13.97 GiB → 16G
        val big = PayloadPolicy.decide(probes(family = "V7", gib = 13.97), emptyList())
        assertEquals(Variant.ORANGE7_16G, big.variant)

        // 刚好在阈值下 → 12G
        val small = PayloadPolicy.decide(probes(family = "V7", gib = 12.9), emptyList())
        assertEquals(Variant.ORANGE7_12G, small.variant)

        // 阈值本身 → 16G（>= 13.0）
        val edge = PayloadPolicy.decide(probes(family = "V7", gib = 13.0), emptyList())
        assertEquals(Variant.ORANGE7_16G, edge.variant)
    }

    @Test
    fun memoryUnavailableFallsBackTo12GAndUnconfirmed() {
        val plan = PayloadPolicy.decide(probes(family = "V7", gib = null), emptyList())
        assertEquals(Variant.ORANGE7_12G, plan.variant)
        assertFalse("内存读不到不应标记为已确认", plan.confirmed)
        assertTrue("理由应说明内存失败", plan.reason.contains("内存"))
    }

    @Test
    fun probeExceptionsAreSwallowed() {
        val plan = PayloadPolicy.decide(
            PayloadProbes(
                readSuccessFamily = { throw IllegalStateException("boom") },
                readFingerprint = { throw IllegalStateException("boom") },
                readTotalMemGiB = { throw IllegalStateException("boom") },
            ),
            rules = emptyList(),
        )
        assertEquals(Variant.ORANGE7_12G, plan.variant)
        assertFalse(plan.confirmed)
    }

    @Test
    fun historyPersistenceRoundTrip() {
        val history = PayloadResolver.PayloadHistory(context)
        history.clear()

        assertTrue("清空后应无记录", history.readSuccessFamily() == null)
        assertTrue(history.recordSuccess(FlowType.V6))
        assertEquals("V6", history.readSuccessFamily())
        assertTrue(history.lastSuccessAt > 0L)

        history.recordSuccess(FlowType.V7)
        assertEquals("V7", history.readSuccessFamily())

        history.clear()
        assertTrue(history.readSuccessFamily() == null)
    }

    @Test
    fun decisionsReasonIsHumanReadable() {
        val plan = PayloadPolicy.decide(probes(family = "V6", gib = 15.0), emptyList())
        assertTrue("理由不应为空", plan.reason.isNotBlank())
        assertTrue("理由应含代数", plan.reason.contains("第6代"))
        assertTrue("理由应含内存结论", plan.reason.contains("16G"))
    }
}
