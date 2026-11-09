package com.demo.zt

import android.util.Log
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.runShell

/**
 * 开机自动开启「网络 ADB」。
 *
 * ### 前提
 * 设备已安装 Magisk / KernelSU 等持久 root 管理器，由管理器在开机时执行脚本。
 * 应用载荷获得的临时 root 重启即失效，也不会让 `service.d` 在开机时执行。
 * 用这个脚本恢复 ADB、再靠 ADB 重新获取临时 root 是循环依赖，并不能解决问题。
 *
 * ### 做法
 * 拿到 root 后，写一个开机脚本，内容等价于：
 * ```
 * setprop service.adb.tcp.port 5555
 * stop adbd
 * start adbd
 * ```
 * 脚本放到 `/data/adb/service.d/`（Magisk / KernelSU 的开机服务目录，以 root 执行）。
 * 持久 root 管理器执行脚本后 adbd 会监听 5555，`AdbAutoConnector` 的固定端口回退正好是 5555，
 * 不需要配对、不需要用户再点任何开关。
 *
 * ### 边界（必须说清）
 * - 写入时有 root 不代表开机时仍有 root；必须先探测持久 root 基础设施。
 * - 没有持久 root 管理器时，应使用免 root 的「重启后自动开无线调试」。
 * - 目录探测只是启发式判据，不保证管理器健康或脚本一定会执行，仍需实机重启验证。
 * - 5555 是**明文 tcp adb**，只在可信网络使用；比配对加密通道弱。
 */
object BootAdbEnabler {

    private const val TAG = "BootAdbEnabler"

    /** 脚本路径：Magisk / KernelSU 的开机服务目录。 */
    const val SCRIPT_PATH = "/data/adb/service.d/staticlog_adb.sh"

    /** 备用路径：部分 ROM 仍支持 init.d。 */
    const val INIT_D_SCRIPT_PATH = "/system/etc/init.d/staticlog_adb.sh"

    /** 监听端口，与 AdbAutoConnector 的固定端口回退保持一致。 */
    const val TCP_PORT = 5555

    /** 明确缺少持久 root 基础设施时，直接展示给用户的失败原因。 */
    internal const val NO_PERSISTENT_ROOT_MESSAGE =
        "本机没有 Magisk/KernelSU 的 service.d，脚本写了也不会在开机执行；" +
            "如果靠载荷获得临时 root，重启后 root 同样失效，请改用免 root 的『重启后自动开无线调试』。"

    private const val PERSISTENT_ROOT_PRESENT = "ZT_PERSISTENT_ROOT_PRESENT"
    private const val PERSISTENT_ROOT_ABSENT = "ZT_PERSISTENT_ROOT_ABSENT"

    data class Result(
        val ok: Boolean,
        val message: String,
        /** 实际写入的路径；失败时为 null。 */
        val path: String? = null,
    )

    /** 脚本正文。注意用 LF，且首行 shebang 必须在。 */
    val scriptBody: String = """
        #!/system/bin/sh
        # 由 StaticLog 生成：开机后自动开启网络 ADB（明文 tcp，仅限可信网络）
        # 想要关闭：删除本文件并重启
        setprop service.adb.tcp.port $TCP_PORT
        stop adbd
        start adbd
    """.trimIndent() + "\n"

    /** 是否有会在开机执行 service.d 的持久 root 管理器。 */
    fun hasPersistentRootServiceDir(client: AdbClient?): Boolean? {
        if (client == null) return null
        return probePersistentRootServiceDir { client.runShell(it) }
    }

    /** 独立的只读探测入口，便于单测模拟 root 通道失败和超时。阻塞调用，需在工作线程执行。 */
    internal fun probePersistentRootServiceDir(runShell: (String) -> String): Boolean? {
        // 先确认实际为 root，避免把权限不足误判为目录不存在；目录读取失败也返回未知。
        val command = """
            [ "${'$'}(id -u)" = "0" ] || exit 1
            if [ -d /data/adb/magisk ] || [ -d /data/adb/ksu ]; then
                echo $PERSISTENT_ROOT_PRESENT
            elif [ -d /data/adb/service.d ]; then
                entries=${'$'}(ls -A /data/adb/service.d 2>/dev/null) || exit 1
                if [ -n "${'$'}entries" ]; then
                    echo $PERSISTENT_ROOT_PRESENT
                else
                    echo $PERSISTENT_ROOT_ABSENT
                fi
            else
                echo $PERSISTENT_ROOT_ABSENT
            fi
        """.trimIndent()
        // 限制设备端 root 命令执行时间；缺少 timeout、root 通道异常或无有效结果均视为未知。
        val output = runCatching {
            runShell("echo '$command' | timeout 5 /data/local/tmp/zt_su 2>&1")
        }.getOrNull() ?: return null
        // 只接受完整结果行，避免命令回显或错误文本中碰巧含有标记导致误判。
        return when (output.lineSequence().map { it.trim() }.lastOrNull {
            it == PERSISTENT_ROOT_PRESENT || it == PERSISTENT_ROOT_ABSENT
        }) {
            PERSISTENT_ROOT_PRESENT -> true
            PERSISTENT_ROOT_ABSENT -> false
            else -> null
        }
    }

    /**
     * 把脚本写进设备（需要持久 root 管理器在开机执行）。
     *
     * 通过 `/data/local/tmp/zt_su` 这个 root 通道执行；该通道由载荷成功后建立。
     * 阻塞调用，需在工作线程执行。
     */
    fun install(client: AdbClient?): Result {
        if (client == null) {
            return Result(false, "尚未连接 ADB，无法写入开机脚本")
        }

        val persistentRoot = hasPersistentRootServiceDir(client)
        if (persistentRoot == false) {
            // 必须在 mkdir / 写文件之前返回；自行创建 service.d 并不等于安装了持久 root。
            return Result(false, NO_PERSISTENT_ROOT_MESSAGE)
        }

        val dir = SCRIPT_PATH.substringBeforeLast('/')
        val base64 = android.util.Base64.encodeToString(
            scriptBody.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )

        // 用 base64 传内容，避免 shell 引号/换行问题
        val command = buildString {
            append("mkdir -p $dir; ")
            append("echo '$base64' | base64 -d > $SCRIPT_PATH; ")
            append("chmod 755 $SCRIPT_PATH; ")
            append("ls -l $SCRIPT_PATH")
        }

        val output = runCatching {
            client.runShell("echo '${command}' | /data/local/tmp/zt_su 2>&1")
        }.getOrElse {
            return Result(false, "root 通道不可用：${it::class.simpleName}: ${it.message}")
        }

        Log.i(TAG, "写入结果：$output")
        val ok = output.contains(SCRIPT_PATH)
        return if (ok) {
            val message = if (persistentRoot == null) {
                "已写入开机脚本：$SCRIPT_PATH（用于开机监听 $TCP_PORT）。" +
                    "未能确认本机是否有持久 root 管理器，重启后可能不生效"
            } else {
                "已写入开机脚本：$SCRIPT_PATH（重启后自动监听 $TCP_PORT）"
            }
            Result(true, message, SCRIPT_PATH)
        } else {
            Result(false, "写入未确认，设备返回：${output.trim().take(200)}")
        }
    }

    /** 检查脚本是否已存在（需要 root）。 */
    fun isInstalled(client: AdbClient?): Boolean {
        if (client == null) return false
        val out = runCatching {
            client.runShell("echo '[ -f $SCRIPT_PATH ] && echo PRESENT || echo ABSENT' | /data/local/tmp/zt_su 2>&1")
        }.getOrDefault("")
        return out.contains("PRESENT")
    }

    /** 删除开机脚本（需要 root）。 */
    fun uninstall(client: AdbClient?): Result {
        if (client == null) return Result(false, "尚未连接 ADB")
        val out = runCatching {
            client.runShell("echo 'rm -f $SCRIPT_PATH && echo REMOVED' | /data/local/tmp/zt_su 2>&1")
        }.getOrElse {
            return Result(false, "root 通道不可用：${it.message}")
        }
        return if (out.contains("REMOVED")) {
            Result(true, "已删除开机脚本，重启后不再自动开启", null)
        } else {
            Result(false, "删除未确认，设备返回：${out.trim().take(200)}")
        }
    }

    /** 给界面用的一句话说明。 */
    const val LIMITATION_NOTE: String =
        "仅在已安装 Magisk/KernelSU 等持久 root 管理器时，service.d 脚本才会在开机执行。" +
            "靠载荷获得的临时 root 重启后失效，脚本也不会执行；请使用免 root 的『重启后自动开无线调试』。"
}
