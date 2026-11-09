package com.demo.zt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.runShell

/**
 * 自己给自己授予 `WRITE_SECURE_SETTINGS`。
 *
 * ### 为什么可行
 * `WRITE_SECURE_SETTINGS` 是 `signature|privileged|development` 级权限，
 * 不能弹窗申请，但**可以由 shell 用户（uid 2000）通过 `pm grant` 授予**——
 * 这正是平时在电脑上敲 `adb shell pm grant …` 的原理。
 *
 * 而本应用配对成功后本来就持有 ADB shell 通道，所以**根本不需要电脑**：
 * 自己通过这条通道把权限授给自己即可。
 *
 * ### 边界
 * - 需要先建立 ADB 连接（配对完成）。
 * - 部分 ROM 可能限制 `pm grant` 对 development 权限的生效，失败时退回手动命令。
 */
object SecureSettingsGrant {

    private const val TAG = "SecureSettingsGrant"

    private val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS

    data class Result(val ok: Boolean, val message: String)

    /** 当前是否已持有该权限。 */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * 通过已有的 ADB 通道给自己授权。阻塞调用，需在工作线程。
     *
     * 已经授权过会直接返回成功，不重复执行。
     */
    fun grant(context: Context, client: AdbClient?): Result {
        if (hasPermission(context)) {
            return Result(true, "已持有 WRITE_SECURE_SETTINGS")
        }
        if (client == null) {
            return Result(false, "尚未连接 ADB，无法自动授权")
        }

        val pkg = context.packageName
        val command = "pm grant $pkg $PERMISSION"

        val output = runCatching { client.runShell(command) }.getOrElse {
            return Result(false, "执行失败：${it::class.simpleName}: ${it.message}")
        }
        Log.i(TAG, "pm grant 输出：$output")

        // pm grant 成功时通常无输出；失败会带 Error/Exception 字样
        if (output.contains("Error", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true) ||
            output.contains("not a changeable permission", ignoreCase = true)
        ) {
            return Result(false, "授权被拒绝：${output.trim().take(160)}")
        }

        // 回读确认，不以命令无输出为准
        return if (hasPermission(context)) {
            Result(true, "已自动授予 WRITE_SECURE_SETTINGS")
        } else {
            Result(false, "命令已执行但权限未生效，可能被 ROM 限制；请用电脑执行一次授权命令")
        }
    }
}
