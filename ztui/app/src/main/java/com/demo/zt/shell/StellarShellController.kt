package com.demo.zt.shell

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.runShell
import kotlin.concurrent.thread

/**
 * 原版 StellarShellController 的外部 API 保持不变，
 * 内部改为走应用内无线 ADB（com.demo.zt.adb.AdbClient）。
 */
class StellarShellController : AutoCloseable {

    var status by mutableStateOf(StellarStatus.Unavailable)
        private set

    var isRunning by mutableStateOf(false)
        private set

    var output by mutableStateOf<List<OutputLine>>(emptyList())
        private set

    /** 每次命令开始自增，界面用它清空输入框。 */
    var commandStartToken by mutableStateOf(0)
        private set

    private var client: AdbClient? = null

    /** 供提权流程直接复用底层连接（推送载荷需要 sync 协议，不能只走 shell）。 */
    fun clientOrNull(): AdbClient? = client

    fun attach(adb: AdbClient?) {
        client = adb
        status = if (adb != null) StellarStatus.Granted else StellarStatus.Unavailable
    }

    private fun emit(text: String, isError: Boolean = false) {
        output = output + OutputLine(text, isError)
    }

    fun clear() {
        output = emptyList()
    }

    /** 供 PrivilegeEscalator 写入日志。 */
    fun appendExternal(text: String, isError: Boolean = false) {
        output = output + OutputLine(text, isError)
    }

    /** 同步执行并返回输出（不改变 isRunning 状态）。 */
    fun runRaw(command: String): String {
        val c = client ?: return ""
        return runCatching { c.runShell(command) }.getOrDefault("")
    }

    fun execute(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty() || isRunning) return
        val c = client ?: run { emit("通道未建立", true); return }
        isRunning = true
        commandStartToken++
        thread(isDaemon = true) {
            try {
                emit("$ $cmd")
                val out = c.runShell(cmd)
                out.lineSequence().forEach { if (it.isNotEmpty()) emit(it) }
            } catch (e: Throwable) {
                emit("执行失败：${e.message}", true)
            } finally {
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
    }

    /** 原版用于把文件推送到 /data/local/tmp 并赋权。 */
    fun installFile(remotePath: String, bytes: ByteArray, mode: String): ShellFileInstallResult {
        val c = client ?: return ShellFileInstallResult(-1, "通道未建立")
        return try {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val out = c.runShell(
                "echo '$b64' | base64 -d > '$remotePath' && chmod $mode '$remotePath' && ls -l '$remotePath'"
            )
            ShellFileInstallResult(0, out)
        } catch (e: Throwable) {
            ShellFileInstallResult(-1, e.message ?: "失败")
        }
    }

    override fun close() {
        client = null
        status = StellarStatus.Unavailable
    }
}