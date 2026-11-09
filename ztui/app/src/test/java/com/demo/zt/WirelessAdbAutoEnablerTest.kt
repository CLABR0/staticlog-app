package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「重启后自动开无线调试」的关键契约。
 *
 * 这条路径不依赖 root，靠的是 `WRITE_SECURE_SETTINGS` + 系统设置项
 * `adb_wifi_enabled`。写错键名或命令，用户授权后也不会生效，
 * 而且现象是「看起来点了但没反应」，很难排查，所以把常量钉住。
 */
class WirelessAdbAutoEnablerTest {

    @Test
    fun launchAppliesWhenEnabledAndPermittedButDebuggingIsOff() {
        assertTrue(WirelessAdbAutoEnabler.shouldApplyOnLaunch(true, true, false))
    }

    @Test
    fun launchSkipsWhenWirelessDebuggingIsAlreadyOn() {
        assertFalse(WirelessAdbAutoEnabler.shouldApplyOnLaunch(true, true, true))
    }

    @Test
    fun launchSkipsWithoutPermission() {
        // 即使系统状态未知，没有权限也不尝试补写。
        for (current in listOf(true, false, null)) {
            assertFalse(WirelessAdbAutoEnabler.shouldApplyOnLaunch(true, false, current))
        }
    }

    @Test
    fun launchSkipsWhenFeatureIsDisabled() {
        for (permission in listOf(true, false)) {
            for (current in listOf(true, false, null)) {
                assertFalse(WirelessAdbAutoEnabler.shouldApplyOnLaunch(false, permission, current))
            }
        }
    }

    @Test
    fun launchAppliesWhenCurrentStateIsUnknown() {
        assertTrue(WirelessAdbAutoEnabler.shouldApplyOnLaunch(true, true, null))
    }

    @Test
    fun writesTheSystemSettingKeyThePlatformWatches() {
        // 这是 system_server 里 AdbService 监听的全局设置键，不能改
        assertEquals("adb_wifi_enabled", WirelessAdbAutoEnabler.SETTING_KEY)
    }

    @Test
    fun grantCommandTargetsThisAppAndPermission() {
        val cmd = WirelessAdbAutoEnabler.GRANT_COMMAND
        assertTrue("必须用 pm grant，实际=$cmd", cmd.contains("pm grant"))
        assertTrue("必须指向本应用包名", cmd.contains("com.demo.zt"))
        assertTrue(
            "必须授予 WRITE_SECURE_SETTINGS",
            cmd.contains("android.permission.WRITE_SECURE_SETTINGS"),
        )
    }

    @Test
    fun resultCarriesActionableMessage() {
        // 缺权限时的提示必须把命令带出来，否则用户不知道下一步做什么
        val r = WirelessAdbAutoEnabler.Result(
            ok = false,
            message = "缺少 WRITE_SECURE_SETTINGS 权限。请用一次 ADB 命令授权：\n" +
                WirelessAdbAutoEnabler.GRANT_COMMAND,
        )
        assertFalse(r.ok)
        assertTrue(r.message.contains("pm grant"))
    }

    @Test
    fun grantCommandIsCopyPasteable() {
        // 不能带换行/引号，用户要直接粘进终端
        val cmd = WirelessAdbAutoEnabler.GRANT_COMMAND
        assertFalse("不应含换行", cmd.contains("\n"))
        assertFalse("不应含双引号", cmd.contains("\""))
        assertTrue(cmd.startsWith("adb shell "))
    }

    @Test
    fun enablingWritesOneAndVerifiesReadback() {
        var written: Int? = null
        var reads = 0
        val result = WirelessAdbAutoEnabler.applySetting(
            enable = true,
            writeSetting = { written = it; true },
            readSetting = { reads++; true },
        )
        assertEquals(1, written)
        assertEquals(1, reads)
        assertTrue(result.ok)
        assertTrue(result.message.contains("已开启"))
        assertTrue(result.message.contains("adb_wifi_enabled=1"))
    }

    @Test
    fun enablingFailsWhenReadbackIsStillOff() {
        val result = WirelessAdbAutoEnabler.applySetting(true, { true }, { false })
        assertFalse(result.ok)
        assertTrue(result.message.contains("未生效"))
    }

    @Test
    fun enablingStillSucceedsWhenReadbackIsUnavailable() {
        val result = WirelessAdbAutoEnabler.applySetting(true, { true }, { null })
        assertTrue(result.ok)
        assertTrue(result.message.contains("adb_wifi_enabled=1"))
        assertTrue(result.message.contains("回读"))
    }

    @Test
    fun disablingWritesZeroAndSucceedsEvenIfAlreadyOff() {
        var written: Int? = null
        var reads = 0
        val result = WirelessAdbAutoEnabler.applySetting(
            enable = false,
            writeSetting = { written = it; true },
            readSetting = { reads++; false },
        )
        assertEquals(0, written)
        assertEquals(1, reads)
        assertTrue(result.ok)
        assertTrue(result.message.contains("已关闭"))
        assertTrue(result.message.contains("adb_wifi_enabled=0"))
    }

    @Test
    fun disablingOnlyRequiresSuccessfulWriteButKeepsReadback() {
        for (now in listOf(true, false, null)) {
            var reads = 0
            val result = WirelessAdbAutoEnabler.applySetting(
                enable = false,
                writeSetting = { assertEquals(0, it); true },
                readSetting = { reads++; now },
            )
            assertEquals(1, reads)
            assertTrue("关闭方向写入成功就应成功（回读=$now）", result.ok)
            assertTrue(result.message.contains("已关闭"))
            if (now == true) assertTrue(result.message.contains("回读仍为开启"))
        }
    }

    @Test
    fun failedWriteIsFailureInBothDirectionsAndSkipsReadback() {
        for (enable in listOf(true, false)) {
            var reads = 0
            val result = WirelessAdbAutoEnabler.applySetting(
                enable,
                { false },
                { reads++; false },
            )
            assertFalse(result.ok)
            assertEquals(0, reads)
            assertTrue(result.message.contains("写入"))
            assertTrue(result.message.contains("失败"))
        }
    }

    @Test
    fun writeExceptionsAreFailureInBothDirections() {
        for (enable in listOf(true, false)) {
            val result = WirelessAdbAutoEnabler.applySetting(
                enable,
                { throw SecurityException("权限被撤销") },
                { error("写入失败后不应回读") },
            )
            assertFalse(result.ok)
        }
    }

    @Test
    fun readExceptionsDoNotUndoSuccessfulWrites() {
        for (enable in listOf(true, false)) {
            val result = WirelessAdbAutoEnabler.applySetting(
                enable,
                { true },
                { error("回读不可用") },
            )
            assertTrue(result.ok)
            assertTrue(result.message.contains("回读"))
            if (!enable) assertTrue(result.message.contains("已关闭"))
        }
    }
}
