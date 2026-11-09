package com.demo.zt.stats

import android.content.Context
import android.os.Build
import android.util.Log
import com.demo.zt.BuildConfig
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

/**
 * 匿名使用统计上报。
 *
 * ### 只报这些
 * - 随机生成的安装 ID（首次运行创建，不来自 ANDROID_ID / IMEI / 序列号）
 * - 版本号、Android SDK、机型、内核 release 串
 * - 事件名（install / heartbeat / escalate）
 *
 * ### 不报这些
 * 不采集手机号、账号、IMEI、MAC、位置、联系人、已安装应用列表、任何文件内容；
 * 不接第三方统计 SDK；服务端也不存 IP。
 *
 * ### 开关
 * [isEnabled] 默认 true，但**前提是 [BuildConfig.STATS_ENDPOINT] 非空**。
 * 端点为空时整条路径直接返回，一个包都不发——这是默认出包状态。
 *
 * ### 失败策略
 * 网络失败一律静默，不重试、不排队、不打扰用户：统计不该影响主功能。
 */
object UsageReporter {

    private const val TAG = "UsageReporter"
    private const val PREF = "stats"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_ENABLED = "enabled"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /** 事件名白名单，与服务端保持一致。 */
    const val EVENT_INSTALL = "install"
    const val EVENT_HEARTBEAT = "heartbeat"
    const val EVENT_ESCALATE = "escalate"

    /** 服务器地址是否已配置。没配置就等于整个功能关闭。 */
    fun isConfigured(): Boolean = BuildConfig.STATS_ENDPOINT.isNotBlank()

    fun isEnabled(context: Context): Boolean {
        if (!isConfigured()) return false
        return prefs(context).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 稳定的安装 ID。首次调用生成并落盘，之后一直复用。
     * 用户卸载重装会变——这是刻意的，不做跨安装追踪。
     */
    fun installId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_INSTALL_ID, id).commit()
        return id
    }

    /** 组装上报体。**单独暴露出来是为了能被单元测试断言字段集合**。 */
    fun buildPayload(context: Context, event: String): String {
        val sdk = Build.VERSION.SDK_INT.toString()
        val model = safe { Build.MODEL }.orEmpty()
        val version = BuildConfig.VERSION_NAME
        val kernel = safe { System.getProperty("os.version") }.orEmpty()
        return buildPayloadFrom(installId(context), event, version, sdk, model, kernel)
    }

    /**
     * 纯函数版本：不碰 Context / Build，方便 JVM 单测直接断言 JSON 结构与转义。
     */
    internal fun buildPayloadFrom(
        installId: String,
        event: String,
        version: String,
        sdk: String,
        model: String,
        kernel: String,
    ): String = buildString {
        append('{')
        append("\"install_id\":\"").append(jsonEscape(installId)).append("\",")
        append("\"event\":\"").append(jsonEscape(event)).append("\",")
        append("\"version\":\"").append(jsonEscape(version)).append("\",")
        append("\"sdk\":\"").append(jsonEscape(sdk)).append("\",")
        append("\"model\":\"").append(jsonEscape(model)).append("\",")
        append("\"kernel\":\"").append(jsonEscape(kernel)).append('"')
        append('}')
    }

    /**
     * 上报一个事件。**永远不抛异常，永远不阻塞调用线程**（内部起 daemon 线程）。
     * 未配置端点或用户关闭开关时直接返回。
     */
    fun report(context: Context, event: String) {
        if (!isEnabled(context)) return
        val app = context.applicationContext
        thread(isDaemon = true, name = "stats-$event") {
            runCatching { post(app, buildPayload(app, event)) }
                .onFailure { Log.i(TAG, "上报失败（忽略）：${it::class.simpleName}") }
        }
    }

    /**
     * 首次运行报 install，之后每次启动报 heartbeat。
     * 用 prefs 里的标记区分，不依赖服务端判断。
     */
    fun reportLaunch(context: Context) {
        if (!isEnabled(context)) return
        val p = prefs(context)
        val event = if (p.getBoolean("reported_install", false)) EVENT_HEARTBEAT else EVENT_INSTALL
        p.edit().putBoolean("reported_install", true).commit()
        report(context, event)
    }

    private fun post(context: Context, body: String) {
        val endpoint = BuildConfig.STATS_ENDPOINT.trimEnd('/') + "/v1/event"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "StaticLog/${BuildConfig.VERSION_NAME}")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) Log.i(TAG, "服务端返回 $code")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun safe(block: () -> String?): String? = runCatching { block() }.getOrNull()

    /** 极简 JSON 转义：机型串里偶尔有引号或反斜杠。 */
    internal fun jsonEscape(raw: String): String = buildString {
        for (c in raw) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append(' ') else append(c)
            }
        }
    }
}
