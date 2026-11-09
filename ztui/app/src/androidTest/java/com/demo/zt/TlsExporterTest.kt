package com.demo.zt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.demo.zt.adb.TlsExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.net.ssl.SSLSocket

/**
 * 验证 TLS 1.3 exporter 通道真的可用。
 *
 * 背景：ADB 无线配对靠 TLS exporter 派生密钥。平台把
 * `exportKeyingMaterial` 放在隐藏类里，**Android 16 已删除该方法**，
 * 真机上报 `NoSuchMethodException` 导致配对永远失败。
 *
 * 现在自带 Conscrypt 提供公开 API。这组测试保证：
 *   1. 自带 Conscrypt 能初始化
 *   2. 它造出的上下文协议是 TLSv1.3
 *   3. 平台方法缺失也不影响我们（因为我们不依赖它）
 */
@RunWith(AndroidJUnit4::class)
class TlsExporterTest {

    @Test
    fun bundledConscryptIsAvailable() {
        assertTrue(
            "自带 Conscrypt 未初始化成功——配对会直接失败。诊断：${TlsExporter.describe()}",
            TlsExporter.bundledAvailable,
        )
    }

    @Test
    fun sslContextIsTls13() {
        val ctx = TlsExporter.newSslContext()
        assertEquals("TLSv1.3", ctx.protocol)
    }

    @Test
    fun describeMentionsBothChannels() {
        val d = TlsExporter.describe()
        assertTrue("诊断应包含自带 Conscrypt 状态，实际=$d", d.contains("自带 Conscrypt"))
        assertTrue("诊断应包含平台状态，实际=$d", d.contains("平台 exporter"))
    }

    @Test
    fun platformMethodPresenceIsReportedNotAssumed() {
        // 平台有没有这个方法不重要，重要的是我们不会因它缺失而崩。
        // 这里只断言检测本身不抛异常。
        val hasPlatform = runCatching {
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
        // 打印在报告里，方便对照真机版本
        println("平台 exportKeyingMaterial 存在=$hasPlatform；${TlsExporter.describe()}")
        assertTrue("自带 Conscrypt 必须可用（与平台无关）", TlsExporter.bundledAvailable)
    }
}
