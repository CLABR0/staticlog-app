package com.demo.zt

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 开机轨迹（落盘）。
 *
 * ### 为什么需要
 * 开机自动提权跑在**解锁之前**，而 [AppLog] 只存在内存里：
 * 进程一旦被系统回收，那段日志就没了。用户解锁后打开应用，看到的只是
 * 「解锁之后」的记录，于是永远搞不清解锁前到底走到了哪一步、为什么停住。
 *
 * 这里把每一步写进**设备保护存储**（DE）的文件里，解锁前后都能写、都能读，
 * 应用启动时再并入界面日志。
 *
 * 文件大小有上限，只保留最近的部分，避免长期累积。
 */
object BootTrace {

    private const val FILE = "boot_trace.log"

    /** 超过这个大小就只保留后半段。 */
    private const val MAX_BYTES = 64 * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)

    /**
     * 一行轨迹的格式。抽成纯函数便于单测。
     *
     * @param wallMs 墙上时间，用于跨天对照
     * @param elapsedMs 开机以来的毫秒数，用于判断"开机第几秒做的这件事"
     */
    internal fun format(step: String, wallMs: Long, elapsedMs: Long, unlocked: Boolean): String {
        val sec = elapsedMs / 1000
        val flag = if (unlocked) "已解锁" else "未解锁"
        return "${stamp.format(Date(wallMs))} [+${sec}s $flag] $step"
    }

    private fun file(context: Context): File =
        File(
            context.applicationContext.createDeviceProtectedStorageContext().filesDir,
            FILE,
        )

    /** 追加一行。任何失败都吞掉——记日志本身不能影响提权流程。 */
    fun log(context: Context, step: String, unlocked: Boolean = UnlockWait.isUnlocked(context)) {
        runCatching {
            val line = format(step, System.currentTimeMillis(), SystemClock.elapsedRealtime(), unlocked)
            val f = file(context)
            f.appendText(line + "\n")
            if (f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(200)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        }
    }

    fun read(context: Context): List<String> = runCatching {
        val f = file(context)
        if (!f.isFile) emptyList() else f.readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
