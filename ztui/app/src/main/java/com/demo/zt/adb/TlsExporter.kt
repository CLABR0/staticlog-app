package com.demo.zt.adb

import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Provider
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * TLS 1.3 keying material 导出。
 *
 * **为什么需要这个类**：
 * ADB 无线配对要用 TLS 1.3 的 exporter（label `adb-label`）派生会话密钥。
 * 平台把 `exportKeyingMaterial` 放在隐藏类
 * `com.android.org.conscrypt.Conscrypt` 里，Android 16 上该方法已被移除，
 * 直接反射会抛 `NoSuchMethodException`，配对必然失败。
 *
 * 解决办法：**自带一份 Conscrypt**（`org.conscrypt:conscrypt-android`），
 * 它的 `exportKeyingMaterial` 是公开 API。用它建 TLS 上下文，
 * 导出的 socket 类型匹配，exporter 才可用。
 *
 * 顺序：
 *   1. 自带 Conscrypt（首选，跨版本稳定）
 *   2. 平台隐藏类（老版本 Android 有）
 *   3. 反射扫描任意同名静态方法（最后兜底）
 */
internal object TlsExporter {

    private const val TAG = "TlsExporter"

    /** 自带 Conscrypt 的 Provider；初始化失败为 null。 */
    private val bundledProvider: Provider? by lazy {
        runCatching { Conscrypt.newProvider() }
            .onFailure { Log.w(TAG, "自带 Conscrypt 不可用：${it.message}") }
            .getOrNull()
    }

    val bundledAvailable: Boolean get() = bundledProvider != null

    /**
     * 用来建 TLS 连接的 SSLContext。
     *
     * 优先走自带 Conscrypt：只有它的 socket 才能导出 keying material。
     */
    fun newSslContext(): SSLContext {
        val provider = bundledProvider
        val ctx = if (provider != null) {
            runCatching { SSLContext.getInstance("TLSv1.3", provider) }
                .getOrElse { SSLContext.getInstance("TLSv1.3") }
        } else {
            SSLContext.getInstance("TLSv1.3")
        }
        return ctx
    }

    /**
     * 导出 keying material。
     *
     * @param socket 已完成握手的 TLS socket
     * @param label  标签（ADB 用 `adb-label\u0000`）
     * @param length 需要的字节数
     */
    fun exportKeyingMaterial(socket: SSLSocket, label: String, length: Int): ByteArray {
        // 1. 自带 Conscrypt 的公开 API
        runCatching {
            return Conscrypt.exportKeyingMaterial(socket, label, null, length)
        }.onFailure { Log.d(TAG, "自带 Conscrypt exporter 失败：${it.javaClass.simpleName}") }

        // 2. 平台隐藏类（老版本 Android）
        for (className in listOf(
            "com.android.org.conscrypt.Conscrypt",
            "org.conscrypt.Conscrypt",
        )) {
            runCatching {
                val cls = Class.forName(className)
                val m = cls.getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
                (m.invoke(null, socket, label, null, length) as? ByteArray)?.let { return it }
            }.onFailure { Log.d(TAG, "$className exporter 失败：${it.javaClass.simpleName}") }
        }

        // 3. 反射扫描：任意同名静态方法，参数能凑上就试
        for (className in listOf(
            "org.conscrypt.Conscrypt",
            "com.android.org.conscrypt.Conscrypt",
        )) {
            val cls = runCatching { Class.forName(className) }.getOrNull() ?: continue
            for (m in cls.methods) {
                if (m.name != "exportKeyingMaterial") continue
                if (m.parameterCount != 4) continue
                runCatching {
                    val out = m.invoke(null, socket, label, null, length) as? ByteArray
                    if (out != null && out.size == length) return out
                }
            }
        }

        throw IllegalStateException(
            "无法导出 TLS keying material（自带 Conscrypt=" + (if (bundledAvailable) "可用" else "不可用") + "）"
        )
    }

    /** 供诊断展示：当前能用的导出通道。 */
    fun describe(): String = buildString {
        append("自带 Conscrypt：").append(if (bundledAvailable) "可用" else "不可用")
        val platformOk = runCatching {
            Class.forName("com.android.org.conscrypt.Conscrypt")
                .getMethod(
                    "exportKeyingMaterial",
                    SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                )
            true
        }.getOrDefault(false)
        append("；平台 exporter：").append(if (platformOk) "可用" else "缺失")
    }

    /** 未使用，仅为避免 SecureRandom 被优化提示；实际随机源在 AdbKey 里。 */
    @Suppress("unused")
    private val random = SecureRandom()
}
