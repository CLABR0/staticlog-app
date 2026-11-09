package com.demo.zt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志导出（**仅供 debug 包使用**）。
 *
 * 目的：真机出问题时，用户能一键把完整日志复制或导出成文件发给作者，
 * 而不是只描述「没反应」「崩了」。
 *
 * release 包不暴露这个入口。
 */
object LogExporter {

    private const val TAG = "LogExporter"
    private const val FILE_NAME = "staticlog-log.txt"

    /** 导出用的完整文本：设备信息 + 版本 + 全部日志行。 */
    fun buildText(context: Context, lines: List<String>): String = buildString {
        appendLine("===== StaticLog 日志 =====")
        appendLine("版本：${BuildConfig.VERSION_NAME}")
        appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("系统：Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        appendLine("内核：${DeviceInfo.kernelRelease}")
        appendLine("连接：${AdbConnectionManager.describe()}")
        appendLine("————————————")
        if (lines.isEmpty()) {
            appendLine("（暂无日志行）")
        } else {
            lines.forEach { appendLine(it) }
        }
        appendLine("===== 结束 =====")
    }

    /** 复制到剪贴板。返回 true 表示成功。 */
    fun copyToClipboard(context: Context, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("StaticLog 日志", text))
        true
    }.getOrElse {
        Log.e(TAG, "复制失败", it)
        false
    }

    /** 写入应用外部私有目录，返回文件路径（便于分享）。 */
    fun exportToFile(context: Context, text: String): File? = runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, FILE_NAME)
        f.writeText(text)
        f
    }.getOrElse {
        Log.e(TAG, "导出失败", it)
        null
    }

    /** 拉起系统分享，把导出文件发出去。 */
    fun share(context: Context, file: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享日志").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure { Log.e(TAG, "分享失败", it) }
    }
}
