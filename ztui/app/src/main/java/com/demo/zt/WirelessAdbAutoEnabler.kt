package com.demo.zt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 重启后自动重新开启「无线调试」（**不需要 root**）。
 *
 * ### 原理
 * Android 11+ 的无线调试开关由系统设置项 `adb_wifi_enabled` 控制。
 * 系统自己的 `AdbService` 监听它。应用只要拿到
 * `WRITE_SECURE_SETTINGS` 权限，就能直接写这个全局设置——
 * 效果等同于用户手动把开关拨上去。
 *
 * ### 代价：需要一次性 ADB 授权
 * `WRITE_SECURE_SETTINGS` 是 `signature|privileged|development` 级，
 * 不会弹窗，也不能在设置里授予。只能通过一次 ADB 命令：
 * ```
 * adb shell pm grant com.demo.zt android.permission.WRITE_SECURE_SETTINGS
 * ```
 * 授予一次即可长期有效（卸载重装后需重新授权）。
 *
 * ### 边界（必须说清）
 * - **这是「自动恢复开关」，不是让连接跨重启不断开**。重启后旧端口失效，
 *   但开关被重新打开后，我们的 ADB 密钥还在，mDNS 重新发现端口即可连上。
 * - 部分厂商 ROM 会拦截二次写入、或在开机后再次把开关关掉，
 *   **能否成功要实机验证**，不能保证所有机型通用。
 * - 没有该权限时什么都不做，不假装成功。
 */
object WirelessAdbAutoEnabler {

    private const val TAG = "WirelessAdbAuto"

    private const val PREFS = "staticlog_auto_adb"
    private const val KEY_ENABLED = "enabled"

    /** 系统用来表示「无线调试开关」的全局设置键（API 30+）。 */
    const val SETTING_KEY = "adb_wifi_enabled"

    /** 一次性授权命令，界面上直接展示给用户复制。 */
    const val GRANT_COMMAND =
        "adb shell pm grant com.demo.zt android.permission.WRITE_SECURE_SETTINGS"

    /** 是否已获得写安全设置的权限（决定这个功能能不能用）。 */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /** 系统当前无线调试开关状态；读不到返回 null。 */
    fun isWirelessDebuggingOn(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING_KEY, 0) == 1
    }.getOrNull()

    fun isEnabled(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }.getOrDefault(false)

    fun setEnabled(context: Context, enabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).commit()
        }
    }

    /** 启动自愈：已开启且有权限时，只对关闭或未知的系统状态补写。 */
    internal fun shouldApplyOnLaunch(
        enabled: Boolean,
        hasPermission: Boolean,
        current: Boolean?,
    ): Boolean = enabled && hasPermission && current != true

    data class Result(val ok: Boolean, val message: String)

    /**
     * 现在就按指定方向开启或关闭无线调试。
     *
     * 用于开机广播自动恢复、应用启动自愈，以及设置页手动验证。
     */
    fun applyNow(context: Context, enable: Boolean = true): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result(false, "系统版本过低（需 Android 11+），无法通过设置项控制无线调试")
        }
        if (!hasPermission(context)) {
            return Result(
                false,
                "缺少 WRITE_SECURE_SETTINGS 权限。请用一次 ADB 命令授权：\n$GRANT_COMMAND",
            )
        }

        return applySetting(
            enable = enable,
            writeSetting = { value ->
                Settings.Global.putInt(context.contentResolver, SETTING_KEY, value)
            },
            readSetting = {
                val now = isWirelessDebuggingOn(context)
                Log.i(TAG, "写入 $SETTING_KEY=${if (enable) 1 else 0}，回读=$now")
                now
            },
        )
    }

    /** 将设置读写与结果判定分开，便于 JVM 测试覆盖两个方向及厂商回读差异。 */
    internal fun applySetting(
        enable: Boolean,
        writeSetting: (Int) -> Boolean,
        readSetting: () -> Boolean?,
    ): Result {
        val value = if (enable) 1 else 0
        val wrote = runCatching { writeSetting(value) }.getOrDefault(false)

        if (!wrote) {
            return Result(false, "写入 $SETTING_KEY 失败，可能被厂商 ROM 拦截")
        }

        // 回读确认，避免「写成功但系统没生效」
        val now = runCatching { readSetting() }.getOrNull()

        // 关闭是幂等操作：写入 0 成功即表示关闭请求成功，不要求系统之前开启。
        // 仍保留回读；厂商延迟或拦截时在文案里如实说明，但不把成功写入判为失败。
        if (!enable) {
            val note = when (now) {
                false -> ""
                true -> "。系统回读仍为开启，可能尚未生效，请检查系统开关"
                null -> "，但未能回读确认"
            }
            return Result(true, "已关闭无线调试（$SETTING_KEY=0）$note")
        }

        return when (now) {
            true -> Result(true, "已开启无线调试（$SETTING_KEY=1）。若系统随后又关闭，说明该机型拦截了二次写入")
            false -> Result(false, "写入成功但系统未生效，该机型可能拦截此设置项")
            null -> Result(true, "已写入 $SETTING_KEY=1，但无法回读确认")
        }
    }

    /** 开机时调用：仅在用户开启且权限在时才动作。 */
    fun onBoot(context: Context): Result? {
        if (!isEnabled(context)) return null
        return applyNow(context)
    }
}
