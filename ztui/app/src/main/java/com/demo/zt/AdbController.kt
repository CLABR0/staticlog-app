package com.demo.zt

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.AdbKey
import com.demo.zt.adb.AdbKeyProvider
import com.demo.zt.adb.runShell
import kotlin.concurrent.thread

/**
 * ADB 界面状态与动作。持有配对/连接/命令三条通路的状态。
 *
 * 复用 Stellar 的 AdbKey / AdbClient（已在 adb/ 包内）。
 */
class AdbController(private val context: Context) {

    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var pairPort by mutableStateOf("")
    var pairCode by mutableStateOf("")
    var connectPort by mutableStateOf("")
    var command by mutableStateOf("id")
    var output by mutableStateOf<List<String>>(emptyList())
        private set

    private var client: AdbClient? = null

    private fun log(line: String) {
        output = output + line
    }

    fun clear() {
        output = emptyList()
    }

    private fun key(): AdbKey = AdbKeyProvider.adbKey(context)

    /** 配对：连 127.0.0.1:pairPort，用 6 位配对码完成 TLS 配对。 */
    fun pair() {
        val port = pairPort.trim().toIntOrNull()
        val code = pairCode.trim()
        if (port == null || port !in 1..65535) { log("[X] 配对端口无效"); return }
        if (code.isEmpty()) { log("[X] 请填配对码"); return }
        busy = true
        thread(isDaemon = true) {
            try {
                log("[pair] 127.0.0.1:$port code=$code")
                val keys = key()
                // AdbPairingClient 已在 ztapk 中实现；此处若未引入则提示
                log("[pair] 正在建立 TLS 配对 ...")
                com.demo.zt.adb.AdbPairing.pair("127.0.0.1", port, code, keys)
                log("[pair] 配对成功")
            } catch (e: Throwable) {
                log("[X] 配对失败：${e.message}")
            } finally {
                busy = false
            }
        }
    }

    /** 连接：连 127.0.0.1:connectPort，握手后验证 uid。 */
    fun connect() {
        val port = connectPort.trim().toIntOrNull()
        if (port == null || port !in 1..65535) { log("[X] 连接端口无效"); return }
        busy = true
        thread(isDaemon = true) {
            try {
                log("[connect] 127.0.0.1:$port ...")
                val c = AdbClient("127.0.0.1", port, key())
                c.connect()
                client = c
                connected = true
                val uid = c.runShell("id -u").trim()
                log("[connect] 已连接，身份 uid=$uid")
            } catch (e: Throwable) {
                log("[X] 连接失败：${e.message}")
                connected = false
            } finally {
                busy = false
            }
        }
    }

    /** 在已连接通道上执行命令。 */
    fun runCommand() {
        val c = client
        if (c == null) { log("[X] 尚未连接"); return }
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        busy = true
        thread(isDaemon = true) {
            try {
                log("$ $cmd")
                val out = c.runShell(cmd)
                out.lineSequence().filter { it.isNotEmpty() }.forEach { log(it) }
            } catch (e: Throwable) {
                log("[X] 执行失败：${e.message}")
            } finally {
                busy = false
            }
        }
    }

    fun close() {
        runCatching { client?.close() }
        client = null
        connected = false
    }
}

/** Compose 侧用到的不可变快照 + 回调集合。 */
class AdbUiState(
    val connected: Boolean,
    val busy: Boolean,
    val pairPort: String,
    val pairCode: String,
    val connectPort: String,
    val command: String,
    val output: List<String>,
    val onPairPortChange: (String) -> Unit,
    val onPairCodeChange: (String) -> Unit,
    val onConnectPortChange: (String) -> Unit,
    val onCommandChange: (String) -> Unit,
    val onPair: () -> Unit,
    val onConnect: () -> Unit,
    val onRunCommand: () -> Unit,
    val onClear: () -> Unit
)