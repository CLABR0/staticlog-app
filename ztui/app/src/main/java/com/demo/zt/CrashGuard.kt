package com.demo.zt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃守卫：把未捕获异常写进文件，下次启动展示出来。
 *
 * 没有它，用户只能描述「用一会儿就崩」，拿不到任何堆栈，
 * 只能靠猜。现在崩溃会被记录到 `filesDir/crash_last.txt`，
 * 下次打开应用时在主界面日志面板里显示，用户可以截图或复制发出来。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val FILE_NAME = "crash_last.txt"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 在 Application / Activity 启动时调用一次（重复调用无副作用）。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
                .onFailure { Log.e(TAG, "写崩溃记录失败", it) }
            // 交回原处理器，保持系统行为（否则会吞掉崩溃导致卡死）
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val text = buildString {
            appendLine("时间：${java.util.Date()}")
            appendLine("线程：${thread.name}")
            appendLine("版本：${BuildConfig.VERSION_NAME}")
            appendLine("设备：${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("---")
            append(sw.toString())
        }
        runCatching { file(context).writeText(text) }
        Log.e(TAG, "已记录崩溃：\n$text")
    }

    /** 读取上次崩溃记录；没有返回 null。 */
    fun readLast(context: Context): String? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        f.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 展示过就清掉，避免每次都弹。 */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
