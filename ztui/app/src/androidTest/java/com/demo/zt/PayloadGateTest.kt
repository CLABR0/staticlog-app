package com.demo.zt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.AdbKey
import com.demo.zt.adb.PreferenceAdbKeyStore
import com.demo.zt.adb.runShell
import com.demo.zt.shell.PayloadRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 载荷链门禁测试。
 *
 * 官方 zt_oneclick.sh 在跑 exploit 前会先校验 `uname -r` 必须匹配 6.6.127，
 * 不匹配就退出且不碰内核。APK 侧必须同样行为 —— 否则在错误机型上执行载荷
 * 可能直接变砖。这条测试就是守住这个边界。
 */
@RunWith(AndroidJUnit4::class)
class PayloadGateTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newClient(): AdbClient {
        val prefs = context.getSharedPreferences("adb_keys", Context.MODE_PRIVATE)
        return AdbClient("127.0.0.1", 5555, AdbKey(PreferenceAdbKeyStore(prefs), "rootkit"))
    }

    @Test
    fun mismatchedKernelIsRejectedBeforeWritingAnything() {
        val probes = listOf("/data/local/tmp/zt_run", "/data/local/tmp/zt_fanout.zte")
        val client = newClient()
        client.use {
            it.connect()
            // 先清干净，确保断言看到的是本次结果
            it.runShell("rm -f ${probes.joinToString(" ")} 2>/dev/null; true")

            val kernel = it.runShell("uname -r").trim()
            val result = PayloadRunner.run(context, it, Variant.ORANGE7_16G)

            if (kernel.contains("6.6.127")) {
                // 恰好在目标内核上跑：不在这里断言结果（需要真机）
                assertTrue("目标内核上应进入执行阶段", result.reason.isNotEmpty())
            } else {
                assertFalse("内核不匹配时必须拒绝执行", result.ok)
                assertTrue(
                    "拒绝原因应说明内核问题，实际=${result.reason}",
                    result.reason.contains("内核")
                )
                // 关键：被拒时不能有任何载荷落盘
                val leftovers = it.runShell(
                    "ls /data/local/tmp/zt_run /data/local/tmp/zt_fanout.zte 2>/dev/null"
                ).trim()
                assertTrue("门禁拒绝后不应有文件落盘，实际=$leftovers", leftovers.isEmpty())
            }
        }
    }
}
