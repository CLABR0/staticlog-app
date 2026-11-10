package com.demo.zt

import android.content.Context
import android.os.UserManager
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

    /** 已配对过（DE 存储里存在密钥密文）。锁屏态也安全：不会生成新密钥。 */
    fun hasSavedKey(context: Context): Boolean = runCatching {
        if (isUnlocked(context)) {
            AdbKeyProvider.adbKey(context)
            true
        } else {
            AdbKeyProvider.adbKeyForBootOrNull(context) != null
        }
    }.getOrDefault(false)

    private fun isUnlocked(context: Context): Boolean = runCatching {
        context.getSystemService(UserManager::class.java).isUserUnlocked
    }.getOrDefault(false)

    /**
     * 按解锁状态选密钥入口。
     *
     * 解锁前**必须**走 [AdbKeyProvider.adbKeyForBootOrNull]：
     * CE 存储此刻不可读，普通入口读不到密文会当场生成新密钥并写回，
     * 把原来那把已被 adbd 授权的密钥覆盖掉——下次就再也连不上了。
     */
    private fun keyOrNull(context: Context): AdbKey? =
        if (isUnlocked(context)) {
            runCatching { AdbKeyProvider.adbKey(context) }.getOrNull()
        } else {
            AdbKeyProvider.adbKeyForBootOrNull(context)
        }

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

        // 端口扫描这条路径在应用身份下是死的：AOSP private/app.te 有
        // neverallow { appdomain -shell } proc_net_tcp_udp:file *，普通应用
        // 永远读不到 /proc/net/tcp（解锁后也一样）。保留代码只会白等，直接跳过。
        onLog("[自动连接] 跳过 /proc/net/tcp 扫描（应用域无读权限，仅 shell 可读）")

        for (port in FALLBACK_PORTS) {
            onLog("[自动连接] 尝试 127.0.0.1:$port")
            if (tryPort(context, port, onLog)) return true
        }
        onLog("[自动连接] 失败：请确认已开启无线调试并完成过一次配对")
        return false
    }

    /**
     * 这里曾经有一份 `/proc/net/tcp` 端口扫描实现，已删除。
     *
     * 原因：AOSP `system/sepolicy/private/app.te` 里有
     * `neverallow { appdomain -shell } proc_net_tcp_udp:file *;`
     * ——普通应用域对 `/proc/net/tcp` 的任何操作都被编译期禁止，
     * 只有 shell/root 域能读。也就是说这段代码在 `com.demo.zt` 进程里
     * 永远只返回空列表，解锁后也一样，留着只是白等十几秒。
     *
     * 端口发现现在只有两条路：`service.adb.tls.port` 系统属性、mDNS
     * （`_adb-tls-connect._tcp`）。两者都拿不到就说明 adbd 的 TLS 服务
     * 没起来，该去查 `adb_wifi_enabled` 和网络信任状态，而不是继续找端口。
     */

    /** 读取系统属性里的无线调试端口；读不到返回 null。 */
    private fun propertyPort(): Int? = runCatching {
        val raw = com.demo.zt.compat.SystemProperties.get("service.adb.tls.port").trim()
        raw.toIntOrNull()?.takeIf { it in 1..65535 }
    }.getOrNull()

    private fun tryPort(context: Context, port: Int, onLog: (String) -> Unit): Boolean =
        runCatching {
            val adbKey = keyOrNull(context)
            if (adbKey == null) {
                onLog("[自动连接] 当前状态没有可用私钥（锁屏且 DE 无密文？），放弃连接")
                return@runCatching false
            }
            val client = AdbClient("127.0.0.1", port, adbKey)
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