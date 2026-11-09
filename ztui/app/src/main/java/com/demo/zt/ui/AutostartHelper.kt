package com.demo.zt.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 跳转到厂商的「自启动 / 后台运行」设置页。
 *
 * Android 没有统一的「自启动权限」API，各家都是私有页面。
 * 这里按厂商逐个尝试，全部失败再退到应用详情页——**不假装能申请**，
 * 只能把用户带到正确的地方自己开。
 */
object AutostartHelper {

    private const val TAG = "AutostartHelper"

    /** 常见厂商的自启动管理页。顺序：最可能命中的放前面。 */
    private val candidates: List<Pair<String, String>> = listOf(
        // vivo / iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        // 其它常见厂商（同一台机器上装了才算）
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    )

    /**
     * 打开自启动设置。返回 true 表示成功跳转到某个厂商页面。
     */
    fun open(context: Context): Boolean {
        for ((pkg, cls) in candidates) {
            val intent = Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                Log.i(TAG, "已打开自启动设置：$pkg/$cls")
                return true
            }
        }

        // 退到应用详情页（用户可从这里进权限/电池）
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(fallback) }.isSuccess) {
            Log.w(TAG, "厂商自启动页不存在，已退到应用详情页")
            return false
        }

        // 最后退到设置首页
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return false
    }

    /** 打开「忽略电池优化」申请页。 */
    fun requestIgnoreBatteryOptimization(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return@runCatching false
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "无法打开电池优化申请页，改跳电池优化列表：${it.message}")
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }
}
