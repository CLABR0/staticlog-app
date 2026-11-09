package com.demo.zt

import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.runShell

/**
 * 「本地 ADB」自检：确认当前 ADB 通道拿到的身份是不是 uid 2000（shell）。
 *
 * 原来的「本地 ADB」入口会跳到月虹的终端页，现在按需求改成**只做自检**：
 * 连上之后跑一次 `id`，把结果和身份摆出来，不再提供命令终端。
 */
object AdbSelfCheck {

    /** ADB shell 用户的 uid。 */
    const val SHELL_UID = 2000

    data class Result(
        /** 解析出的 uid；解析失败为 null。 */
        val uid: Int?,
        /** 设备 `id` 命令的原始输出。 */
        val raw: String,
        /** 是否为预期的 uid 2000。 */
        val isShell: Boolean,
        /** 是否为 root。 */
        val isRoot: Boolean,
        /** 一句话结论，直接显示给用户。 */
        val message: String,
    ) {
        /** 通道本身可用（不论身份）。 */
        val channelOk: Boolean get() = uid != null
    }

    /**
     * 从 `id` 输出里取 uid。
     *
     * 兼容：
     *   `uid=2000(shell) gid=2000(shell) groups=...`
     *   `uid=0(root) ...`
     *   `2000`（`id -u` 的纯数字输出）
     */
    fun parseUid(output: String?): Int? {
        val text = output?.trim().orEmpty()
        if (text.isEmpty()) return null

        Regex("""uid=(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        // 纯数字（id -u）
        return text.toIntOrNull()
    }

    /** 纯逻辑：把 `id` 输出变成结论。 */
    fun evaluate(output: String?): Result {
        val raw = output?.trim().orEmpty()
        val uid = parseUid(raw)

        return when {
            uid == null -> Result(
                uid = null,
                raw = raw,
                isShell = false,
                isRoot = false,
                message = "自检失败：拿不到身份信息（通道可能已断开）",
            )
            uid == SHELL_UID -> Result(
                uid = uid,
                raw = raw,
                isShell = true,
                isRoot = false,
                message = "自检通过：当前身份为 uid 2000（shell），ADB 通道可用",
            )
            uid == 0 -> Result(
                uid = uid,
                raw = raw,
                isShell = false,
                isRoot = true,
                message = "自检通过：当前身份为 uid 0（root），ADB 通道可用",
            )
            else -> Result(
                uid = uid,
                raw = raw,
                isShell = false,
                isRoot = false,
                message = "自检异常：当前身份为 uid $uid，既不是 2000 也不是 0",
            )
        }
    }

    /**
     * 连上之后跑一次 `id` 做自检。
     *
     * 阻塞调用，需在工作线程执行。
     */
    fun run(client: AdbClient?): Result {
        if (client == null) {
            return Result(
                uid = null,
                raw = "",
                isShell = false,
                isRoot = false,
                message = "自检失败：尚未连接 ADB，请先完成配对",
            )
        }
        val output = runCatching { client.runShell("id") }.getOrElse {
            return Result(
                uid = null,
                raw = "",
                isShell = false,
                isRoot = false,
                message = "自检失败：${it::class.simpleName}: ${it.message}",
            )
        }
        return evaluate(output)
    }
}
