package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 开机轨迹 + 「解锁前先真试一次」的守卫。
 *
 * 用户的现象：**解锁之后才看到通知**，所以要能回答"解锁前到底走到哪一步"。
 * 这件事不能靠推断，得靠落盘记录。
 */
class BootTraceTest {

    private val root = File("src/main/java/com/demo/zt")

    private fun src(name: String) = File(root, name).readText()

    @Test
    fun lineFormatShowsElapsedAndUnlockState() {
        val line = BootTrace.format(
            step = "AutoBootService 已启动",
            wallMs = 1_760_000_000_000L,
            elapsedMs = 43_500L,
            unlocked = false,
        )
        assertTrue("要有墙钟时间", line.contains(":"))
        assertTrue("要有开机第几秒", line.contains("[+43s"))
        assertTrue("要标明未解锁", line.contains("未解锁"))
        assertTrue("要有步骤文字", line.contains("AutoBootService 已启动"))

        val unlockedLine = BootTrace.format("继续", 1_760_000_000_000L, 61_000L, unlocked = true)
        assertTrue(unlockedLine.contains("[+61s 已解锁]"))
    }

    @Test
    fun traceLivesInDeviceProtectedStorage() {
        val s = src("BootTrace.kt")
        assertTrue(
            "轨迹必须写 DE 存储，解锁前才写得进去",
            s.contains("createDeviceProtectedStorageContext()"),
        )
        assertTrue("要有大小上限，避免无限增长", s.contains("MAX_BYTES"))
        assertTrue("写日志失败不能影响提权", s.contains("runCatching"))
    }

    @Test
    fun receiverRecordsWhichBroadcastArrived() {
        val s = src("BootReceiver.kt")
        assertTrue("必须记录收到哪条广播", s.contains("收到广播："))
        assertTrue("必须记录跳过原因", s.contains("本次开机已启动过，跳过"))
        assertTrue("必须记录开关状态", s.contains("开关未开或已达上限，跳过"))
    }

    @Test
    fun serviceRecordsNotificationAndPreUnlockAttempt() {
        val s = src("AutoBootService.kt")
        assertTrue("服务启动要留痕", s.contains("AutoBootService 已启动"))
        assertTrue("通知发布要留痕（用户说解锁后才看到通知）", s.contains("前台通知已发布"))
        assertTrue("解锁前必须真试一次", s.contains("解锁前试连一次"))
        assertTrue("试连结果要写明成功/失败", s.contains("解锁前试连结果"))
        assertTrue("连上就不能再等解锁", s.contains("解锁前已连上 ADB，直接执行"))
    }

    @Test
    fun preUnlockAttemptHappensBeforeUnlockWait() {
        val s = src("AutoBootService.kt")
        val pre = s.indexOf("解锁前试连一次")
        val wait = s.indexOf("进入等解锁循环")
        assertTrue("试连必须在等解锁之前", pre > 0 && pre < wait)
    }

    @Test
    fun mainActivityMergesTraceIntoLog() {
        val s = File("src/main/java/com/demo/zt/MainActivity.kt").readText()
        assertTrue("启动时要并入开机轨迹", s.contains("BootTrace.read(this)"))
        assertTrue("要有可读的分隔行", s.contains("开机轨迹（落盘"))
        assertEquals(
            "轨迹行要带前缀，便于和实时日志区分",
            true,
            s.contains("[开机]"),
        )
    }
}
