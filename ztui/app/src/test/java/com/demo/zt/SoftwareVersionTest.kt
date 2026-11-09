package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 软件版本号 → 流程代数的判定（JVM 单测，不依赖设备）。
 *
 * 已确认对应关系：版本号前两位 16 → 第6代，17 → 第7代。
 */
class SoftwareVersionTest {

    // ── 解析器：从各种版本串里取主版本号 ──

    @Test
    fun parsesVivoBuildString() {
        // vivo / iQOO 固件常见格式：型号_A_版本
        assertEquals(16, SoftwareVersion.majorFrom("PD2408_A_16.9.1"))
        assertEquals(17, SoftwareVersion.majorFrom("PD2408_A_17.0.3"))
    }

    @Test
    fun parsesPlainVersion() {
        assertEquals(16, SoftwareVersion.majorFrom("16.9.1"))
        assertEquals(17, SoftwareVersion.majorFrom("17.0.3"))
        assertEquals(16, SoftwareVersion.majorFrom("16"))
        assertEquals(17, SoftwareVersion.majorFrom("17"))
    }

    @Test
    fun parsesLongVendorVersion() {
        // vivo 常见长版本串：16.9.1.W10.V000L1
        assertEquals(16, SoftwareVersion.majorFrom("16.9.1.W10.V000L1"))
        assertEquals(17, SoftwareVersion.majorFrom("17.0.3.W20.V001L1"))
        // 带 V 前缀
        assertEquals(16, SoftwareVersion.majorFrom("V16.9.1"))
        assertEquals(17, SoftwareVersion.majorFrom("v17.0"))
    }

    @Test
    fun ignoresAospBuildId() {
        // AOSP 构建号 SP1A.210812.016：没有任何 1-3 位的三段式主版本
        assertNull(SoftwareVersion.majorFrom("SP1A.210812.016"))
        assertNull(SoftwareVersion.majorFrom("RRE1.211202.001"))
    }

    @Test
    fun modelNumberNeverLeaksThrough() {
        // 四位型号数字（2408）不能被当成主版本
        assertEquals(16, SoftwareVersion.majorFrom("PD2408_A_16.9.1"))
        assertNull(SoftwareVersion.majorFrom("PD2408"))
        assertNull(SoftwareVersion.majorFrom("PD2408A"))
    }

    @Test
    fun ignoresModelNumberDigits() {
        // PD2408 里的 2408 不能被当成版本号
        assertEquals(16, SoftwareVersion.majorFrom("PD2408_A_16.9.1"))
        // 只有型号、没有版本时，不应误判
        assertNull(SoftwareVersion.majorFrom("PD2408"))
    }

    @Test
    fun handlesWhitespaceAndEmpty() {
        assertEquals(16, SoftwareVersion.majorFrom("  PD2408_A_16.9.1  "))
        assertNull(SoftwareVersion.majorFrom(""))
        assertNull(SoftwareVersion.majorFrom("   "))
        assertNull(SoftwareVersion.majorFrom(null))
    }

    @Test
    fun unrecognizedVersionReturnsItsOwnMajor() {
        // OriginOS 版本号（6.x）会被解析成 6，随后 familyFrom 判为不认识
        assertEquals(6, SoftwareVersion.majorFrom("OriginOS 6.2.1"))
        assertNull(SoftwareVersion.familyFrom(6))
    }

    // ── 映射：16 → V6，17 → V7 ──

    @Test
    fun mapsMajorToFamily() {
        assertEquals(FlowType.V6, SoftwareVersion.familyFrom(16))
        assertEquals(FlowType.V7, SoftwareVersion.familyFrom(17))
    }

    @Test
    fun otherMajorsAreUnknown() {
        for (m in listOf(0, 1, 6, 7, 15, 18, 24, -1)) {
            assertNull("主版本 $m 不应被判定", SoftwareVersion.familyFrom(m))
        }
        assertNull(SoftwareVersion.familyFrom(null))
    }

    // ── 判定优先级 ──

    private fun probes(
        family: String? = null,
        fingerprint: String? = null,
        gib: Double? = 15.0,
        failed: String? = null,
        major: Int? = null,
    ) = PayloadProbes(
        readSuccessFamily = { family },
        readFingerprint = { fingerprint },
        readTotalMemGiB = { gib },
        readFailureFamily = { failed },
        readSoftwareMajor = { major },
    )

    @Test
    fun version16SelectsOrange6() {
        val p = PayloadPolicy.decide(probes(major = 16, gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE6_16G, p.variant)
        assertTrue("版本号判定应算已确认", p.confirmed)
        assertTrue(p.reason.contains("软件版本号"))
        assertTrue(p.summary.contains("版本号"))
    }

    @Test
    fun version17SelectsOrange7() {
        val p = PayloadPolicy.decide(probes(major = 17, gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE7_16G, p.variant)
        assertTrue(p.confirmed)
    }

    @Test
    fun versionDecidedFamilyRespectsMemory() {
        // 版本定代数，内存定规格，两者独立
        assertEquals(
            Variant.ORANGE6_12G,
            PayloadPolicy.decide(probes(major = 16, gib = 10.8), emptyList()).variant,
        )
        assertEquals(
            Variant.ORANGE6_16G,
            PayloadPolicy.decide(probes(major = 16, gib = 13.97), emptyList()).variant,
        )
    }

    @Test
    fun unknownVersionFallsThroughToNextBasis() {
        // 版本号 15 → 不认识 → 走失败规避
        val p = PayloadPolicy.decide(probes(major = 15, failed = "V7", gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE6_16G, p.variant)
        assertFalse(p.confirmed)
        assertTrue(p.reason.contains("上次失败"))
    }

    @Test
    fun unknownVersionWithoutHistoryFallsToDefault() {
        val p = PayloadPolicy.decide(probes(major = 15, gib = 15.0), emptyList())
        assertEquals(Variant.ORANGE7_16G, p.variant)
        assertFalse(p.confirmed)
        assertTrue(p.reason.contains("兜底"))
    }

    @Test
    fun successHistoryBeatsVersionRule() {
        // 实测成功比版本号推断更可信
        val p = PayloadPolicy.decide(
            probes(family = "V7", major = 16, gib = 15.0),
            emptyList(),
        )
        assertEquals(Variant.ORANGE7_16G, p.variant)
        assertTrue(p.reason.contains("历史成功记录"))
    }

    @Test
    fun versionRuleBeatsFingerprintTable() {
        val p = PayloadPolicy.decide(
            probes(major = 16, fingerprint = "fp", gib = 15.0),
            listOf("fp" to FlowType.V7),
        )
        assertEquals(Variant.ORANGE6_16G, p.variant)
        assertTrue(p.reason.contains("软件版本号"))
    }

    @Test
    fun versionProbeExceptionIsSwallowed() {
        val p = PayloadPolicy.decide(
            PayloadProbes(
                readSuccessFamily = { null },
                readFingerprint = { null },
                readTotalMemGiB = { 15.0 },
                readFailureFamily = { null },
                readSoftwareMajor = { error("boom") },
            ),
            emptyList(),
        )
        assertEquals(Variant.ORANGE7_16G, p.variant)
        assertFalse(p.confirmed)
    }
}
