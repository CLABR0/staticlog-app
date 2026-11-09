package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：下拉标签必须与「真正会执行的变体」同源。
 *
 * 背景：曾经出过一次真 bug —— 版本诊断能识别「软件版本号 16 → 第6代」，
 * 但「自动（…）」括弧里永远显示橘子7。
 * 原因：`Variant.resolve()` 自己拼了一套 probe（版本号传 null、规则传空表），
 * 与 `PayloadResolver.decide()` 不是同一条判定链。
 *
 * 现在标签接收调用方已算好的 [PayloadDecision]；这里断言两者不允许分叉。
 */
class AutoLabelConsistencyTest {

    private fun decision(variant: Variant, confirmed: Boolean = true) = PayloadDecision(
        variant = variant,
        reason = "测试用",
        summary = "测试用",
        confirmed = confirmed,
    )

    @Test
    fun autoLabelFollowsDecisionNotFallback() {
        // 判定说是第6代 16G，标签就必须是 橘子6 · 16G
        val label = Variant.AUTO.displayLabel(decision(Variant.ORANGE6_16G))
        assertEquals("自动（橘子6 · 16G）", label)
        assertTrue("不能出现兜底的橘子7", !label.contains("橘子7"))
    }

    @Test
    fun autoLabelCoversAllFour() {
        val pairs = listOf(
            Variant.ORANGE6_16G to "自动（橘子6 · 16G）",
            Variant.ORANGE6_12G to "自动（橘子6 · 12G）",
            Variant.ORANGE7_16G to "自动（橘子7 · 16G）",
            Variant.ORANGE7_12G to "自动（橘子7 · 12G）",
        )
        for ((variant, expected) in pairs) {
            assertEquals(expected, Variant.AUTO.displayLabel(decision(variant)))
        }
    }

    @Test
    fun nonAutoLabelsIgnoreDecision() {
        // 手动选中某一项时，标签就是它自己，不受自动判定影响
        for (v in Variant.values()) {
            if (v == Variant.AUTO) continue
            assertEquals(v.shortName, v.displayLabel(decision(Variant.ORANGE6_12G)))
            assertEquals(v.shortName, v.displayLabel(null))
        }
    }

    @Test
    fun decisionAndLabelStayConsistentAcrossPolicies() {
        // 对每一种判定输入，标签里的变体必须等于 decision.variant
        data class Case(val probes: PayloadProbes, val expected: Variant)

        val cases = listOf(
            // 版本号 16 → 第6代
            Case(
                PayloadProbes(
                    readSuccessFamily = { null },
                    readFingerprint = { null },
                    readTotalMemGiB = { 15.0 },
                    readSoftwareMajor = { 16 },
                ),
                Variant.ORANGE6_16G,
            ),
            // 版本号 17 → 第7代
            Case(
                PayloadProbes(
                    readSuccessFamily = { null },
                    readFingerprint = { null },
                    readTotalMemGiB = { 15.0 },
                    readSoftwareMajor = { 17 },
                ),
                Variant.ORANGE7_16G,
            ),
            // 版本号读不到 → 兜底第7代
            Case(
                PayloadProbes(
                    readSuccessFamily = { null },
                    readFingerprint = { null },
                    readTotalMemGiB = { 15.0 },
                    readSoftwareMajor = { null },
                ),
                Variant.ORANGE7_16G,
            ),
        )

        for (c in cases) {
            val plan = PayloadPolicy.decide(c.probes, emptyList())
            assertEquals("判定结果应符合预期", c.expected, plan.variant)
            val label = Variant.AUTO.displayLabel(
                PayloadDecision(plan.variant, plan.reason, plan.summary, plan.confirmed)
            )
            assertTrue(
                "标签[$label] 必须包含 ${c.expected.shortName}",
                label.contains(c.expected.shortName),
            )
        }
    }

    @Test
    fun confirmedFlagSurvivesDecisionObject() {
        val d = decision(Variant.ORANGE6_16G, confirmed = false)
        assertFalse("未确认状态不能丢", d.confirmed)
        assertEquals("自动（橘子6 · 16G）", Variant.AUTO.displayLabel(d))
    }
}
