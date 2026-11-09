package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开机自动开启网络 ADB：脚本内容必须正确。
 *
 * 这个脚本写错一个字符，重启后设备就连不上，用户以为「功能坏了」，
 * 实际是脚本没生效。所以把关键行都钉住。
 */
class BootAdbEnablerTest {

    private val body = BootAdbEnabler.scriptBody

    @Test
    fun hasShebang() {
        assertTrue("缺少 shebang 时 service.d 不会执行", body.startsWith("#!/system/bin/sh"))
    }

    @Test
    fun setsTcpPort() {
        assertTrue(
            "必须设置 service.adb.tcp.port=${BootAdbEnabler.TCP_PORT}，实际：$body",
            body.contains("setprop service.adb.tcp.port ${BootAdbEnabler.TCP_PORT}"),
        )
    }

    @Test
    fun restartsAdbd() {
        assertTrue("必须 stop adbd", body.contains("stop adbd"))
        assertTrue("必须 start adbd", body.contains("start adbd"))
        // 顺序必须是先 stop 再 start
        assertTrue(
            "必须先 stop 后 start",
            body.indexOf("stop adbd") < body.indexOf("start adbd"),
        )
    }

    @Test
    fun usesLfLineEndings() {
        assertFalse("脚本不能含 CR，否则 /system/bin/sh 会解析失败", body.contains("\r"))
    }

    @Test
    fun portMatchesAutoConnectorFallback() {
        // AdbAutoConnector 的固定端口回退就是 5555；两边必须一致
        assertTrue(BootAdbEnabler.TCP_PORT == 5555)
    }

    @Test
    fun scriptPathIsInServiceD() {
        assertTrue(
            "脚本要放 service.d，实际=${BootAdbEnabler.SCRIPT_PATH}",
            BootAdbEnabler.SCRIPT_PATH.startsWith("/data/adb/service.d/"),
        )
        assertTrue(BootAdbEnabler.SCRIPT_PATH.endsWith(".sh"))
    }

    @Test
    fun limitationNoteStatesTheRootRequirement() {
        val note = BootAdbEnabler.LIMITATION_NOTE
        assertTrue("必须说明需要 root", note.contains("root"))
        assertTrue("必须说明系统重启会关闭无线调试", note.contains("重启"))
    }

    @Test
    fun nullClientFailsSafely() {
        val r = BootAdbEnabler.install(null)
        assertFalse(r.ok)
        assertTrue("应提示未连接，实际=${r.message}", r.message.contains("尚未连接"))
        assertFalse(BootAdbEnabler.isInstalled(null))
        assertFalse(BootAdbEnabler.uninstall(null).ok)
    }

    @Test
    fun scriptPathMatchesExpectedBootScript() {
        assertEquals("/data/adb/service.d/staticlog_adb.sh", BootAdbEnabler.SCRIPT_PATH)
    }

    @Test
    fun persistentRootFailureMessageExplainsTemporaryRootLimitation() {
        val message = BootAdbEnabler.NO_PERSISTENT_ROOT_MESSAGE
        assertTrue("失败文案必须提到 Magisk/KernelSU", message.contains("Magisk/KernelSU"))
        assertTrue("失败文案必须提到临时 root", message.contains("临时 root"))
        assertTrue("失败文案必须说明脚本不会在开机执行", message.contains("不会在开机执行"))
        assertTrue("失败文案必须引导免 root 方案", message.contains("免 root 的『重启后自动开无线调试』"))
    }

    @Test
    fun limitationNoteExplainsPersistentRootRequirement() {
        val note = BootAdbEnabler.LIMITATION_NOTE
        assertTrue(note.contains("Magisk/KernelSU"))
        assertTrue(note.contains("持久 root"))
        assertTrue(note.contains("临时 root"))
        assertTrue(note.contains("重启后失效"))
    }

    @Test
    fun nullClientLeavesPersistentRootUnknown() {
        assertNull(BootAdbEnabler.hasPersistentRootServiceDir(null))
    }

    @Test
    fun persistentRootProbeUsesBoundedRootCommandAndRequiredPaths() {
        var command = ""
        val result = BootAdbEnabler.probePersistentRootServiceDir {
            command = it
            "ZT_PERSISTENT_ROOT_PRESENT\r\n"
        }
        assertEquals(true, result)
        assertTrue("必须通过有超时的 root 通道探测", command.contains("timeout 5 /data/local/tmp/zt_su"))
        assertTrue("必须确认 root 身份", command.contains("id -u"))
        assertTrue(command.contains("[ -d /data/adb/magisk ]"))
        assertTrue(command.contains("[ -d /data/adb/ksu ]"))
        assertTrue(command.contains("[ -d /data/adb/service.d ]"))
        assertTrue("service.d 必须非空，含隐藏文件", command.contains("ls -A /data/adb/service.d"))
        assertFalse("探测阶段只读，不得创建目录", command.contains("mkdir"))
    }

    @Test
    fun persistentRootProbeReportsConfirmedAbsence() {
        assertEquals(false, BootAdbEnabler.probePersistentRootServiceDir { "ZT_PERSISTENT_ROOT_ABSENT\n" })
    }

    @Test
    fun persistentRootProbeIgnoresMissingOrEchoedResults() {
        for (output in listOf("", "permission denied", "echo ZT_PERSISTENT_ROOT_ABSENT")) {
            assertNull(BootAdbEnabler.probePersistentRootServiceDir { output })
        }
    }

    @Test
    fun persistentRootProbeHandlesTimeoutAndRootChannelException() {
        assertNull(BootAdbEnabler.probePersistentRootServiceDir { throw java.net.SocketTimeoutException("超时") })
        assertNull(BootAdbEnabler.probePersistentRootServiceDir { error("root 通道不可用") })
    }
}
