package com.demo.zt

import android.os.Build
import com.demo.zt.compat.SystemProperties

/**
 * 从「软件版本号」判定流程代数的依据。
 *
 * 已确认对应关系（由设备方给出）：
 *   软件版本号前两位 **16** → 第 6 代（橘子6）
 *   软件版本号前两位 **17** → 第 7 代（橘子7 / OS7）
 *
 * 各厂商把「软件版本号」放在不同属性里，取值样式也不一样：
 *   `PD2408_A_16.9.1`  `16.9.1.W10.V000L1`  `V16.9.1`  `OriginOS 6.2.1`
 * 所以不能取字符串前两位（那是 `PD`），要抓「版本三段式」里的第一段。
 */
object SoftwareVersion {

    /** 一个候选来源的读取结果。raw 保留原样，供界面诊断展示。 */
    data class Reading(val source: String, val raw: String) {
        val major: Int? get() = majorFrom(raw)
        val family: FlowType? get() = familyFrom(major)
    }

    /**
     * 从任意版本串里挑出主版本号。
     *
     * 解析顺序：
     *   1. 三段式 `X.Y.Z`（字母前缀可有可无，如 `V16.9.1`）→ 取 X
     *   2. 两段式 `X.Y` → 取 X
     *   3. 整串就是 1–3 位数字 → 直接返回
     *
     * 型号数字不会误判：`PD2408` 里的 `2408` 是四位且后面没有点号，
     * 既不满足 1–3 位，也不满足「数字+点号」，所以取不到。
     */
    fun majorFrom(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 边界要求：数字左边不能紧贴字母或数字。
        // 这样 `RRE1.211202.001` 里的 `1.211202.001` 不会被误当成版本，
        // `PD2408_A_16.9.1` 里的 `16` 前面是下划线，仍然可以命中。
        val boundary = """(?<![0-9A-Za-z])"""

        // 1. 三段式：16.9.1 / PD2408_A_16.9.1.W10
        Regex("""$boundary(\d{1,3})\.(\d+)\.(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        // 2. 字母前缀的三段式：V16.9.1 / v17.0.3
        Regex("""[Vv](\d{1,3})\.(\d+)\.(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        // 3. 两段式：16.9
        Regex("""$boundary(\d{1,3})\.(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        // 4. 字母前缀的两段式：V17.0
        Regex("""[Vv](\d{1,3})\.(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        // 5. 整串就是数字：17
        return text.toIntOrNull()
    }

    /**
     * 主版本号 → 代数。
     * 16 → 第 6 代；17 → 第 7 代；其它一律 null（不认识，交由下一层依据）。
     */
    fun familyFrom(major: Int?): FlowType? = when (major) {
        16 -> FlowType.V6
        17 -> FlowType.V7
        else -> null
    }

    /**
     * 候选属性，按「最可能是软件版本号」排序。
     *
     * 前段是 vivo / iQOO / OriginOS 的常见位置；末两个是公开 API 兜底。
     */
    private fun candidates(): List<Pair<String, String>> = listOf(
        "ro.vivo.os.build.display.id" to SystemProperties.get("ro.vivo.os.build.display.id"),
        "ro.vivo.os.version" to SystemProperties.get("ro.vivo.os.version"),
        "ro.vivo.product.version" to SystemProperties.get("ro.vivo.product.version"),
        "ro.rom.version" to SystemProperties.get("ro.rom.version"),
        "ro.originos.version" to SystemProperties.get("ro.originos.version"),
        "ro.vivo.os.name" to SystemProperties.get("ro.vivo.os.name"),
        "ro.system.build.version.incremental" to SystemProperties.get("ro.system.build.version.incremental"),
        "ro.build.display.id" to runCatching { Build.DISPLAY }.getOrDefault(""),
        "ro.build.version.incremental" to runCatching { Build.VERSION.INCREMENTAL }.getOrDefault(""),
        "ro.build.description" to runCatching { Build.FINGERPRINT }.getOrDefault(""),
    )

    /**
     * 读取全部候选，保留原始值（含解析不出的）。
     *
     * 界面用它做「版本诊断」：用户看不到 logcat，必须能在屏幕上读出
     * 到底哪个属性有值、值长什么样。
     */
    fun probeAll(): List<Reading> = candidates().mapNotNull { (name, value) ->
        val text = value.trim()
        if (text.isEmpty()) null else Reading(name, text)
    }

    /** 第一条能判定出代数的读数。 */
    fun read(): Reading? = probeAll().firstOrNull { it.family != null }

    fun readFamily(): FlowType? = read()?.family
}
