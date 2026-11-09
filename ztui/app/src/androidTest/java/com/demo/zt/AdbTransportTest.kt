package com.demo.zt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.AdbKey
import com.demo.zt.adb.PreferenceAdbKeyStore
import com.demo.zt.adb.runShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * ADB 链路真机/模拟器验证。
 *
 * 前置：设备已开启 adb tcpip（127.0.0.1:5555 可连）或无线调试已配对。
 * 运行：./gradlew :app:connectedDebugAndroidTest
 *
 * 覆盖：
 *   1. 连接 + 鉴权（A_AUTH 签名 / 已授权密钥）
 *   2. shell 命令与输出回收
 *   3. **sync 协议 push**（这是载荷上机的关键路径，之前用 base64 单条 shell 命令必失败）
 */
@RunWith(AndroidJUnit4::class)
class AdbTransportTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newClient(port: Int = 5555): AdbClient {
        val prefs = context.getSharedPreferences("adb_keys", Context.MODE_PRIVATE)
        return AdbClient("127.0.0.1", port, AdbKey(PreferenceAdbKeyStore(prefs), "rootkit"))
    }

    @Test
    fun shellWorks() {
        val client = newClient()
        client.use {
            it.connect()
            val uid = it.runShell("id -u").trim()
            assertTrue("uid 应为数字，实际=$uid", uid.toIntOrNull() != null)
        }
    }

    @Test
    fun syncPushLargeFileIntegrity() {
        // 6 MB 随机字节，等同 zt_prep.zte 的量级
        val payload = ByteArray(6 * 1024 * 1024).also { java.util.Random(42).nextBytes(it) }
        val localSha = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

        val remote = "/data/local/tmp/zt_push_test.bin"
        val client = newClient()
        client.use {
            it.connect()
            it.pushFile(remote, payload, "644")

            // 不同 ROM 的 stat/wc 支持不一，逐个尝试取大小
            val remoteSize = sequenceOf(
                "stat -c %s $remote 2>/dev/null",
                "wc -c < $remote 2>/dev/null",
                "toybox stat -c %s $remote 2>/dev/null",
            ).map { cmd -> it.runShell(cmd).trim() }
                .firstOrNull { s -> s.isNotEmpty() && s.all(Char::isDigit) }
                .orEmpty()
            assertEquals("远端大小应与本地一致", payload.size.toString(), remoteSize)

            // 有 sha256sum 时做内容级校验
            val sha = it.runShell("sha256sum $remote 2>/dev/null").trim()
            if (sha.isNotEmpty()) {
                assertTrue("sha256 应一致，远端=$sha", sha.contains(localSha))
            }
            it.runShell("rm -f $remote")
        }
    }

    @Test
    fun syncPushExecutableAndRun() {
        val script = "#!/system/bin/sh\necho PUSH_EXEC_OK\n".toByteArray()
        val remote = "/data/local/tmp/zt_exec_test.sh"
        val client = newClient()
        client.use {
            it.connect()
            it.pushFile(remote, script, "755")
            val out = it.runShell("$remote 2>&1")
            assertTrue("推送后应可执行，输出=$out", out.contains("PUSH_EXEC_OK"))
            it.runShell("rm -f $remote")
        }
    }
}
