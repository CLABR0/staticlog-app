package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「开机必须解锁后才跑」的根因与修复守卫。
 *
 * 根因（平台约束，不是本项目 bug）：
 *   1. ADB 授权密钥在 `/data/misc/adb/adb_keys`（CE 存储），解锁前 adbd 读不到
 *   2. `BOOT_COMPLETED` 在解锁后才发，解锁前只有 `LOCKED_BOOT_COMPLETED`
 *   3. 收 `LOCKED_BOOT_COMPLETED` 要求 receiver 声明 `directBootAware`
 *   4. 用默认 SharedPreferences（CE）读开关，解锁前读出来是空
 *
 * 因此修复的三件事必须同时成立，缺一条就退回旧行为：
 *   - 清单里 receiver / service 有 directBootAware
 *   - 开关存 DE 存储，并带升级迁移
 *   - 服务先等解锁（poll 1s），解锁后才开始连 ADB
 */
class BootUnlockGateTest {

    private val root = File("src/main")

    private fun manifest() = File(root, "AndroidManifest.xml").readText()
    private fun source(rel: String) = File(root, "java/com/demo/zt/$rel").readText()

    @Test
    fun receiverIsDirectBootAware() {
        val m = manifest()
        val receiver = m.substringAfter("<receiver")
            .substringBefore("</receiver>")
        assertTrue(
            "BootReceiver 必须 directBootAware，否则收不到 LOCKED_BOOT_COMPLETED",
            receiver.contains("android:directBootAware=\"true\""),
        )
        assertTrue(
            "必须注册 LOCKED_BOOT_COMPLETED",
            receiver.contains("LOCKED_BOOT_COMPLETED"),
        )
    }

    @Test
    fun autoBootServiceIsDirectBootAware() {
        val m = manifest()
        val svc = m.substringAfter("android:name=\".AutoBootService\"")
            .substringBefore("</service>")
        assertTrue(
            "解锁前 startForegroundService 要求 service 也 directBootAware",
            svc.contains("android:directBootAware=\"true\""),
        )
    }

    @Test
    fun preferencesLiveInDeviceProtectedStorage() {
        val src = source("AutoBoot.kt")
        assertTrue(
            "开关必须存 DE 存储，否则解锁前读不到",
            src.contains("createDeviceProtectedStorageContext()"),
        )
        assertTrue("必须提供升级迁移", src.contains("fun migrateToDeviceProtected"))
        // 迁移只在 DE 没有值时做，不能覆盖用户后来改的值
        assertTrue("迁移前要先判 contains", src.contains("if (dePrefs.contains(KEY_ENABLED)) return"))
    }

    @Test
    fun receiverRunsMigrationBeforeReadingSwitch() {
        val src = source("BootReceiver.kt")
        val migrate = src.indexOf("migrateToDeviceProtected")
        val consume = src.indexOf("shouldRunOnBootAndConsume")
        assertTrue("必须先迁移", migrate > 0)
        assertTrue("迁移要在读开关之前", migrate < consume)
    }

    @Test
    fun serviceWaitsForUnlockBeforeConnecting() {
        val src = source("AutoBootService.kt")
        val unlock = src.indexOf("UnlockWait.isUnlocked")
        val connect = src.indexOf("AdbAutoConnector.connect")
        assertTrue("必须等解锁", unlock > 0)
        assertTrue("等解锁必须在连 ADB 之前", unlock < connect)
        assertTrue("等解锁要写日志说明原因", src.contains("UnlockWait.WAITING_MESSAGE"))
        assertTrue("超时要说明放弃原因", src.contains("等解锁超时"))
    }

    @Test
    fun unlockDecisionTable() {
        val d = UnlockWait.UNLOCK_WAIT_MS
        assertEquals(
            UnlockWait.Decision.PROCEED,
            UnlockWait.decide(now = 1000, unlockDeadline = d, unlocked = true),
        )
        assertEquals(
            UnlockWait.Decision.WAIT_UNLOCK,
            UnlockWait.decide(now = 1000, unlockDeadline = d, unlocked = false),
        )
        assertEquals(
            UnlockWait.Decision.TIMEOUT,
            UnlockWait.decide(now = d, unlockDeadline = d, unlocked = false),
        )
        assertEquals(
            "已解锁时即使过了截止时间也应放行",
            UnlockWait.Decision.PROCEED,
            UnlockWait.decide(now = d + 1, unlockDeadline = d, unlocked = true),
        )
    }

    @Test
    fun unlockPollIsFinerThanConnectRetry() {
        assertTrue(
            "解锁轮询必须比 8 秒的连接重试细，否则解锁后要干等",
            UnlockWait.UNLOCK_POLL_MS <= 2_000L,
        )
        assertTrue(
            "等解锁窗口要够长，否则用户慢慢解锁就超时了",
            UnlockWait.UNLOCK_WAIT_MS >= 300_000L,
        )
        assertTrue(
            "解锁后还要给 ADB 留连接时间",
            UnlockWait.CONNECT_AFTER_UNLOCK_MS >= 120_000L,
        )
    }

    @Test
    fun bootAndLockedBootDoNotStartTwoServices() {
        val src = source("BootReceiver.kt")
        val session = src.indexOf("beginSessionIfNeeded")
        val consume = src.indexOf("shouldRunOnBootAndConsume")
        assertTrue("必须有同一次开机的去重", session > 0)
        assertTrue("去重要在消费计数之前", session < consume)

        val boot = source("AutoBoot.kt")
        assertTrue("会话标记必须落盘", boot.contains("KEY_SESSION_AT"))
        assertTrue("会话窗口要够长，一次开机不会隔 30 分钟再发开机广播", boot.contains("SESSION_WINDOW_MS"))
    }

    @Test
    fun sessionGuardAllowsOneStartPerBoot() {
        // 纯时间窗口判定，逻辑与 AutoBoot.beginSessionIfNeeded 一致
        val window = 30 * 60 * 1000L
        fun shouldStart(last: Long, now: Long) = !(last > 0 && now - last in 0 until window)

        assertTrue("首次开机没有记录 → 应该开始", shouldStart(0L, 1_000_000L))
        assertFalse("同一分钟内的第二次广播 → 跳过", shouldStart(1_000_000L, 1_060_000L))
        assertTrue("超过 30 分钟 → 视为新一次开机", shouldStart(1_000_000L, 1_000_000L + window))
    }

    @Test
    fun waitingMessageExplainsWhyItIsNotBypassable() {
        val msg = UnlockWait.WAITING_MESSAGE
        assertTrue("要说明是加密存储导致", msg.contains("加密存储"))
        assertTrue("要告诉用户解锁后自动继续", msg.contains("自动继续"))
        assertFalse("不能承诺无需解锁", msg.contains("无需解锁即可"))
    }
}
