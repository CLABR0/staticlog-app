package com.demo.zt

import android.content.Context
import androidx.lifecycle.Observer
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.AdbKey
import com.demo.zt.adb.AdbMdns
import com.demo.zt.adb.AdbKeyProvider
import com.demo.zt.adb.runShellPublic
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 自动重连。
 *
 * 配对只需要一次：配对成功后设备侧已经保存了我们的 RSA 公钥
 * （无线调试的「已配对设备」列表），之后任何一次连接都只靠这把密钥，
 * 不需要再输配对码。
 *
 * 本类负责：
 *   1. mDNS 发现 _adb-tls-connect._tcp 端口
 *   2. 用本地保存的私钥直接握手
 *   3. 成功则写入 AdbHolder，供提权流程使用
 */
object AdbAutoConnector {

    private const val TIMEOUT_SECONDS = 15L

    /** mDNS 找不到时依次尝试的固定端口（adb tcpip 默认 5555）。 */
    private val FALLBACK_PORTS = intArrayOf(5555)

    /** 已配对过（本地存在密钥）。 */
    fun hasSavedKey(context: Context): Boolean = runCatching {
        AdbKeyProvider.adbKey(context)
        true
    }.getOrDefault(false)

    private fun key(context: Context): AdbKey = AdbKeyProvider.adbKey(context)

    /**
     * 阻塞式自动连接。成功返回 true 并把 client 写入 AdbHolder。
     * 应在后台线程调用。
     */
    fun connect(context: Context, onLog: (String) -> Unit = {}): Boolean {
        if (AdbHolder.client != null) return true

        // 快路径：系统属性里就写着无线调试端口。
        // 开机刚把 adb_wifi_enabled 打开时，mDNS 往往还没注册好，
        // 但属性已经是真实端口，先试一次能省掉十几秒等待。
        propertyPort()?.let { port ->
            onLog("[自动连接] 系统属性给出的端口：$port")
            if (tryPort(context, port, onLog)) return true
        }

        val mdnsPort = discoverPort(context, onLog)
        if (mdnsPort > 0) {
            if (tryPort(context, mdnsPort, onLog)) return true
        } else {
            onLog("[自动连接] 未发现无线调试服务（mDNS），尝试固定端口")
        }

        // mDNS 在部分 ROM 上不可靠：直接扫本机监听端口再逐个试
        val scanned = scanListeningPorts()
        if (scanned.isNotEmpty()) {
            onLog("[自动连接] mDNS 无结果，扫描到 ${scanned.size} 个本机监听端口：$scanned")
            for (port in scanned) {
                onLog("[自动连接] 尝试 127.0.0.1:$port（扫描）")
                if (tryPort(context, port, onLog)) {
                    onLog("[自动连接] 扫描命中端口 $port")
                    return true
                }
            }
        } else {
            onLog("[自动连接] 未能从 /proc/net/tcp 读到监听端口")
        }

        for (port in FALLBACK_PORTS) {
            onLog("[自动连接] 尝试 127.0.0.1:$port")
            if (tryPort(context, port, onLog)) return true
        }
        onLog("[自动连接] 失败：请确认已开启无线调试并完成过一次配对")
        return false
    }

    /**
     * 从 /proc/net/tcp 与 tcp6 里找出**本机监听**端口。
     *
     * 只取 127.0.0.1 / ::1 上的 LISTEN，并按端口倒序（无线调试端口通常较大）。
     * 读不到就返回空列表，不抛异常。
     */
    private fun scanListeningPorts(): List<Int> {
        val result = sortedSetOf<Int>(compareByDescending { it })
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
            runCatching {
                java.io.File(path).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 4) return@forEachLine
                    if (parts[0] == "sl") return@forEachLine          // 表头
                    // 状态 0A = TCP_LISTEN
                    if (parts[3] != "0A") return@forEachLine
                    val local = parts[1]                              // 形如 0100007F:1F90
                    val ip = local.substringBefore(":")
                    val portHex = local.substringAfter(":")
                    // 只接受回环地址：0100007F=127.0.0.1，00000000000000000000000001000000=::1
                    val loopback = ip == "0100007F" || ip == "00000000000000000000000001000000"
                    if (!loopback) return@forEachLine
                    portHex.toIntOrNull(16)?.takeIf { it in 1024..65535 }?.let { result.add(it) }
                }
            }
        }
        // 最多试 12 个，避免拖太久
        return result.take(12)
    }

    /** 读取系统属性里的无线调试端口；读不到返回 null。 */
    private fun propertyPort(): Int? = runCatching {
        val raw = com.demo.zt.compat.SystemProperties.get("service.adb.tls.port").trim()
        raw.toIntOrNull()?.takeIf { it in 1..65535 }
    }.getOrNull()

    private fun tryPort(context: Context, port: Int, onLog: (String) -> Unit): Boolean =
        runCatching {
            val client = AdbClient("127.0.0.1", port, key(context))
            client.connect()
            val uid = client.runShellPublic("id -u").trim()
            AdbHolder.set(client, port)
            onLog("[自动连接] $port 已连接，身份 uid=$uid")
            true
        }.getOrElse {
            onLog("[自动连接] $port 失败：${it.message}")
            false
        }

    /** mDNS 发现 connect 服务端口，超时返回 -1。 */
    private fun discoverPort(context: Context, onLog: (String) -> Unit): Int {
        var found = -1
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(
            context,
            AdbMdns.TLS_CONNECT,
            Observer<Int> { value ->
                val p = value ?: -1
                if (p > 0) {
                    found = p
                    latch.countDown()
                }
            },
            onMaxRefresh = { latch.countDown() },
            maxRefreshCount = 8
        )
        return try {
            mdns.start()
            latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            found
        } finally {
            runCatching { mdns.stop() }
        }
    }
}