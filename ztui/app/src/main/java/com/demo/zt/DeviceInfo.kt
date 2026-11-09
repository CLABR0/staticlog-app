package com.demo.zt

import android.os.Build
import java.io.File

/** 运行时读取真实设备信息，不再写死。 */
object DeviceInfo {

    private fun prop(key: String): String = runCatching {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")

    /** 机型：优先 ro.product.marketname，否则 厂商 + 型号。 */
    val deviceName: String by lazy {
        val market = prop("ro.product.marketname")
        if (market.isNotBlank()) market
        else {
            val m = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            listOf(m, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "未知设备" }
        }
    }

    /** 芯片：优先 ro.soc.model，否则 ro.board.platform。 */
    val socName: String by lazy {
        listOf(
            prop("ro.soc.model"),
            prop("ro.soc.manufacturer"),
            prop("ro.board.platform"),
            Build.HARDWARE.orEmpty()
        ).firstOrNull { it.isNotBlank() } ?: "未知"
    }

    /** 内核：System.getProperty("os.version") 与 uname 一致。 */
    val kernelRelease: String by lazy {
        System.getProperty("os.version").orEmpty().ifBlank {
            runCatching {
                File("/proc/version").readText().split(" ").getOrNull(2)
            }.getOrNull().orEmpty().ifBlank { "未知" }
        }
    }

    /**
     * 系统大版本号（Android 15 → 15）。
     *
     * 内核串里缺 `androidNN` 时用它兜底，和上游 `read_device_versions`
     * 的退路一致：先看 `uname -r`，读不到再看属性。
     */
    fun androidReleaseNumber(): Int? {
        fun leadingInt(text: String): Int? =
            Regex("""^\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()

        leadingInt(Build.VERSION.RELEASE.orEmpty())?.let { return it }
        leadingInt(prop("ro.vendor.build.version.release"))?.let { return it }
        leadingInt(prop("ro.build.version.release"))?.let { return it }
        return null
    }

    /**
     * 总内存（字节）。用于自动选择 16G / 12G 载荷。
     *
     * 必须用 ActivityManager.MemoryInfo：Android 10+ 读 /proc/meminfo 常被
     * SELinux 拦住（返回空 → 静默变成 0 → 永远选 12G）。
     * 兜底顺序：ActivityManager → /proc/meminfo → 0。
     */
    val memoryBytes: Long by lazy {
        val fromAm = memTotalFromActivityManager()
        if (fromAm != null) {
            memorySource = "ActivityManager"
            android.util.Log.i(
                "DeviceInfo",
                "内存读取成功：source=ActivityManager totalMem=$fromAm B (${"%.2f".format(fromAm / 1024.0 / 1024.0 / 1024.0)} GiB)",
            )
            fromAm
        } else {
            val fromProc = memTotalFromProc()
            memorySource = if (fromProc != null) "/proc/meminfo" else "未知"
            android.util.Log.w(
                "DeviceInfo",
                "ActivityManager 读取失败，回退：source=$memorySource totalMem=$fromProc",
            )
            fromProc ?: 0L
        }
    }

    /** 内存来源，便于排查（"ActivityManager" / "/proc/meminfo" / "未知"）。 */
    @Volatile
    var memorySource: String = "未读取"
        private set

    /** 内存 GiB（二进制单位）。 */
    val totalMemGb: Double by lazy { memoryBytes / 1024.0 / 1024.0 / 1024.0 }

    /** 展示用文本，例如 "14.4 GB"；读不到时显示"未知"。 */
    val memoryText: String by lazy {
        if (memoryBytes <= 0) "未知"
        else "%.1f GB".format(memoryBytes / 1000.0 / 1000.0 / 1000.0)
    }

    private fun memTotalFromActivityManager(): Long? = runCatching {
        val ctx = AppContextHolder.context ?: return@runCatching null
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            ?: return@runCatching null
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem.takeIf { it > 0 }
    }.getOrNull()

    private fun memTotalFromProc(): Long? = runCatching {
        File("/proc/meminfo").readLines()
            .firstOrNull { it.startsWith("MemTotal:") }
            ?.replace(Regex("[^0-9]"), "")
            ?.toLongOrNull()
            ?.times(1024L)      // kB → B
            ?.takeIf { it > 0 }
    }.getOrNull()

    /** 内核尾号（abogki 后面的数字末四位）。 */
    val kernelTail: String by lazy {
        Regex("""abogki(\d+)""").find(kernelRelease)?.groupValues?.get(1)?.takeLast(4)
            ?: Regex("""(\d+)(?!.*\d)""").find(kernelRelease)?.groupValues?.get(1)?.takeLast(4)
            ?: ""
    }
}