package com.demo.zt

import java.util.Locale

/**
 * 载荷版本。四套来自用户提供的四个官方包：
 *   66127-7741-橘子6-16g / 6-12g / 7-16g / 7-12g
 *
 * v6 流程：zt_run + zt_su + zt_fanout.zte + zt_su.zte，写 zt_cmds.txt 走 su 守护
 * v7 流程：zt_run + zt_fanout.zte + zt_prep.zte，直接 insmod 内核模块
 *
 * **重要前提（决定了"自动"能做到什么）**：
 * 两代包的文件名都是 `66127-7741-*`，面向**同一内核**（6.6.127）。
 * 所以「这台设备该用第几代」**无法从内核/SDK/机型推导** —— 它们不是机型差异，
 * 而是流程代数差异。任何"SDK≥X 就是第7代"的推断都是编造。
 *
 * 因此自动选择只接受三类依据，按优先级：
 *   A. 历史成功记录（上次某代真的跑成功过 → 优先它）—— 自愈且可验证
 *   B. 已验证映射规则表（完整 Build.FINGERPRINT 精确匹配，默认空表）
 *   C. 兜底取较新一代，并**明确标记为未确认**
 */
enum class FlowType { V6, V7 }

enum class Variant(
    val assetKey: String,
    val flow: FlowType,
    val files: List<String>,
    /** 选择器里显示的短名 */
    val shortName: String
) {
    /** AUTO.flow 仅为枚举结构占位，**不得**当作实际执行代数。 */
    AUTO("", FlowType.V7, emptyList(), "自动"),
    ORANGE6_16G("6-16g", FlowType.V6,
        listOf("zt_run", "zt_su", "zt_fanout.zte", "zt_su.zte"), "橘子6 · 16G"),
    ORANGE6_12G("6-12g", FlowType.V6,
        listOf("zt_run", "zt_su", "zt_fanout.zte", "zt_su.zte"), "橘子6 · 12G"),
    ORANGE7_16G("7-16g", FlowType.V7,
        listOf("zt_run", "zt_fanout.zte", "zt_prep.zte"), "橘子7 · 16G"),
    ORANGE7_12G("7-12g", FlowType.V7,
        listOf("zt_run", "zt_fanout.zte", "zt_prep.zte"), "橘子7 · 12G");

    /** 按代数 + 是否 16G 组装出具体变体。 */
    fun concrete(use16G: Boolean): Variant = when (this) {
        AUTO -> if (use16G) ORANGE7_16G else ORANGE7_12G
        else -> this
    }

    /**
     * 兼容入口。
     *
     * **必须与 [PayloadResolver.decide] 同源**：版本号、匹配规则、历史记录
     * 全部要走同一条判定链。
     * 之前这里自己拼了一套 probe（版本号传默认 null、规则传空表），
     * 结果诊断里能识别版本号 16、标签却永远显示橘子7 —— 就是这个重复实现造成的。
     */
    fun resolve(): Variant {
        if (this != AUTO) return this
        // 有 Context 就走完整判定链；没有才退回「只按内存 + 兜底」
        AppContextHolder.context?.let { ctx ->
            runCatching { return PayloadResolver.decide(ctx).variant }
        }
        return PayloadPolicy.decide(
            probes = PayloadProbes(
                readSuccessFamily = { null },
                readFingerprint = { null },
                readTotalMemGiB = { DeviceInfo.totalMemGb },
            ),
            rules = emptyList(),
        ).variant
    }

    /**
     * 下拉里显示的文字。自动项带括号说明当前会用到哪套：
     *   自动（橘子6 · 16G）
     *
     * **不要**在这里自己拼 probe：标签必须和「真正要执行的变体」同源，
     * 否则会出现「诊断能识别 16，标签却还显示橘子7」。
     * 优先用调用方算好的 [decision]；没有才退回 [resolve]。
     */
    fun displayLabel(decision: PayloadDecision? = null): String = when (this) {
        AUTO -> "自动（${(decision?.variant ?: resolve()).shortName}）"
        else -> shortName
    }

    fun hint(): String = when (flow) {
        FlowType.V6 -> "橘子6 流程：推送 4 个载荷，写命令文件，su 守护模式。**需已安装 KernelSU / ReSukiSU**"
        FlowType.V7 -> "橘子7 流程：推送 3 个载荷，直接 insmod 内核模块。**需已安装 SukiSU Ultra**"
    }

    companion object {
        val ordered = listOf(AUTO, ORANGE7_16G, ORANGE7_12G, ORANGE6_16G, ORANGE6_12G)

        /** 按 assets 目录名反查变体（BundledPayloadCatalog 用）。 */
        fun byAssetKey(key: String): Variant? =
            values().firstOrNull { it != AUTO && it.assetKey == key }
    }
}

/** 探测输入。抽成函数注入，便于纯 JVM 测试（含抛异常场景）。 */
internal data class PayloadProbes(
    val readSuccessFamily: () -> String?,
    val readFingerprint: () -> String?,
    val readTotalMemGiB: () -> Double?,
    /** 上一次失败的代数，用于本轮规避。 */
    val readFailureFamily: () -> String? = { null },
    /**
     * 软件版本号主版本（16 → 第6代，17 → 第7代）。
     * 读不到时返回 null，交由下一层依据。
     */
    val readSoftwareMajor: () -> Int? = { null },
)

/** 策略层输出。 */
internal data class PayloadPlan(
    val variant: Variant,
    val reason: String,
    /** 界面单行摘要：`第7代·兜底(未确认) · 8.75GiB→12G` */
    val summary: String,
    val confirmed: Boolean,
)

internal object PayloadPolicy {

    /**
     * 16G / 12G 分档阈值（GiB）。
     *
     * 厂商标「16G」的机型 `totalMem` 实报 15.0–15.5e9 B，即 13.97–14.41 GiB；
     * 取 14 会把报 15.0e9 的 16G 机错判成 12G。12G 机型实报约 10.8 GiB，
     * 与 13 之间余量充足。
     */
    private const val LARGE_MEMORY_MIN_GIB = 13.0

    private data class FamilyChoice(
        val flow: FlowType,
        val reason: String,
        val confirmed: Boolean,
    )

    fun decide(probes: PayloadProbes, rules: List<Pair<String, FlowType>>): PayloadPlan {
        // A. 历史成功记录：只接受严格的 V6/V7，脏值/异常一律当无记录
        val historyFamily = when (
            runCatching { probes.readSuccessFamily() }.getOrNull()
        ) {
            "V6" -> FlowType.V6
            "V7" -> FlowType.V7
            else -> null
        }

        // 失败记忆：上一次跑失败的那一代，本轮优先避开
        val failedFamily = when (
            runCatching { probes.readFailureFamily() }.getOrNull()
        ) {
            "V6" -> FlowType.V6
            "V7" -> FlowType.V7
            else -> null
        }

        val familyChoice = if (historyFamily != null) {
            FamilyChoice(historyFamily, "${familyName(historyFamily)}：历史成功记录", true)
        } else {
            // B. 软件版本号：前两位 16 → 第6代，17 → 第7代（设备方给出的确定对应）
            val major = runCatching { probes.readSoftwareMajor() }.getOrNull()
            val versionFamily = SoftwareVersion.familyFrom(major)

            // C. 已验证映射：完整指纹精确匹配，不裁剪、不前缀、不推测
            val fingerprint = runCatching { probes.readFingerprint() }.getOrNull()
            val matched = fingerprint
                ?.takeIf { it.isNotBlank() }
                ?.let { v -> rules.firstOrNull { it.first == v }?.second }

            when {
                versionFamily != null ->
                    FamilyChoice(
                        versionFamily,
                        "${familyName(versionFamily)}：软件版本号 $major 判定",
                        true,
                    )

                matched != null ->
                    FamilyChoice(matched, "${familyName(matched)}：已验证指纹精确匹配", true)

                // D. 上一代失败过 → 本轮换另一代试。这仍是观测依据，不是猜测，
                //    但「另一代能成」尚未证实，所以标未确认。
                failedFamily != null -> {
                    val other = if (failedFamily == FlowType.V7) FlowType.V6 else FlowType.V7
                    FamilyChoice(
                        other,
                        "${familyName(failedFamily)}上次失败，本轮改试${familyName(other)}（未确认）",
                        false,
                    )
                }

                // E. 显式兜底：不是兼容性结论
                else -> FamilyChoice(
                    FlowType.V7,
                    "第7代：无有效历史、版本号无法判定且未命中已验证规则，较新代数兜底（未确认）",
                    false,
                )
            }
        }

        // 内存只决定规格，不参与代数选择
        val memoryGiB = runCatching { probes.readTotalMemGiB() }
            .getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }

        val use16G = memoryGiB != null && memoryGiB >= LARGE_MEMORY_MIN_GIB

        val variant = when (familyChoice.flow) {
            FlowType.V6 -> if (use16G) Variant.ORANGE6_16G else Variant.ORANGE6_12G
            FlowType.V7 -> if (use16G) Variant.ORANGE7_16G else Variant.ORANGE7_12G
        }

        val memoryReason = if (memoryGiB == null) {
            "内存读取失败或无效，回落12G（未确认）"
        } else {
            val shown = String.format(Locale.ROOT, "%.2f", memoryGiB)
            val cmp = if (use16G) "≥" else "<"
            val size = if (use16G) "16G" else "12G"
            "内存约$shown GiB（原始值${cmp}13 GiB），选择$size"
        }

        val familyShort = when (familyChoice.flow) {
            FlowType.V6 -> "第6代"
            FlowType.V7 -> "第7代"
        }
        val sourceShort = when {
            familyChoice.reason.contains("历史") -> "历史记录"
            familyChoice.reason.contains("软件版本号") -> "版本号"
            familyChoice.reason.contains("指纹") -> "已验证规则"
            familyChoice.reason.contains("上次失败") -> "换另一代试"
            else -> "兜底(未确认)"
        }
        val sizeShort = if (use16G) "16G" else "12G"
        val memShort = if (memoryGiB == null) "内存未知" else String.format(Locale.ROOT, "%.2fGiB", memoryGiB)

        return PayloadPlan(
            variant = variant,
            reason = "${familyChoice.reason}；$memoryReason。",
            summary = "$familyShort·$sourceShort · $memShort→$sizeShort",
            confirmed = familyChoice.confirmed && memoryGiB != null,
        )
    }

    private fun familyName(flow: FlowType): String = when (flow) {
        FlowType.V6 -> "第6代"
        FlowType.V7 -> "第7代"
    }
}
