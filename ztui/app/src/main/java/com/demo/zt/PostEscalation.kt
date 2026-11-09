package com.demo.zt

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.runShell

/**
 * 提权成功后的收尾动作：**解锁屏幕并打开 root 管理器**。
 *
 * ### 为什么必须走 root
 * 普通应用无法解除**安全锁屏**（有密码/图案）。只有拿到 uid 0 之后，
 * 才能用系统命令：
 * ```
 * input keyevent 224        # 唤醒屏幕
 * wm dismiss-keyguard       # 解除锁屏
 * monkey -p <包名> ...       # 拉起管理器
 * ```
 * 这些命令通过载荷建立的 root 通道 `/data/local/tmp/zt_su` 执行。
 *
 * 同时也绕开了「后台启动 Activity 被限制」的问题——root shell 发起的
 * `monkey`/`am start` 不受该限制。
 *
 * ### 边界
 * - 只有**确实拿到 root** 才有效；失败时静默记录，不影响提权结果。
 * - 管理器包名按候选列表匹配，优先 KernelSU 系。
 */
object PostEscalation {

    private const val TAG = "PostEscalation"

    /** 按优先级排列的管理器包名。 */
    val MANAGER_CANDIDATES = listOf(
        "me.weishu.kernelsu",      // KernelSU 官方
        "com.rifsxd.ksunext",      // KernelSU Next
        "me.bmax.apatch",          // APatch
        "com.topjohnwu.magisk",    // Magisk
    )

    data class Result(val ok: Boolean, val message: String, val managerPackage: String? = null)

    /** 找到本机已安装的管理器；没有返回 null。 */
    fun detectManager(context: Context): String? {
        val pm = context.packageManager
        for (pkg in MANAGER_CANDIDATES) {
            val installed = runCatching {
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            if (installed) return pkg
        }
        return null
    }

    /** 是否装了任意一个管理器（用于界面提示）。 */
    fun hasManager(context: Context): Boolean = detectManager(context) != null

    /**
     * 生成要交给 root 执行的脚本。
     *
     * 抽成纯函数便于单测：命令顺序、包名、无包名时的兜底行为都能断言。
     */
    fun buildScript(managerPackage: String?): String = buildString {
        appendLine("input keyevent 224")          // 唤醒屏幕
        appendLine("sleep 1")
        appendLine("wm dismiss-keyguard")         // 解除锁屏（root 下有效）
        appendLine("sleep 1")
        if (managerPackage != null) {
            // monkey 按包名拉起 launcher activity，不需要知道具体 Activity 类名
            appendLine("monkey -p $managerPackage -c android.intent.category.LAUNCHER 1")
        } else {
            append("echo NO_MANAGER")
        }
    }

    /**
     * 执行收尾动作。阻塞调用，需在工作线程。
     *
     * @param client 已连接的 ADB 通道（root 命令通过 zt_su 执行）
     */
    fun unlockAndOpenManager(context: Context, client: AdbClient?): Result {
        if (client == null) {
            return Result(false, "尚未连接 ADB，跳过解锁与跳转")
        }

        val pkg = detectManager(context)
        val script = buildScript(pkg)

        // 用 base64 传脚本，避免引号/换行在 shell 里被拆坏
        val encoded = android.util.Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
        val command = "echo '$encoded' | base64 -d > /data/local/tmp/zt_post.sh; " +
            "chmod 755 /data/local/tmp/zt_post.sh; " +
            "echo 'sh /data/local/tmp/zt_post.sh' | /data/local/tmp/zt_su 2>&1"

        val output = runCatching { client.runShell(command) }.getOrElse {
            return Result(false, "执行失败：${it::class.simpleName}: ${it.message}", pkg)
        }

        Log.i(TAG, "收尾输出：$output（管理器=$pkg）")

        return when {
            pkg == null -> Result(
                false,
                "已解锁屏幕，但本机未安装 KernelSU / Magisk 管理器，无法跳转",
                null,
            )
            output.contains("No activities found") || output.contains("monkey aborted") ->
                Result(false, "解锁成功，但拉起 $pkg 失败：$output", pkg)
            else -> Result(true, "已解锁屏幕并打开 $pkg", pkg)
        }
    }
}
