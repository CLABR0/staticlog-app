package com.demo.zt

import android.content.Context
import android.os.UserManager

/**
 * 开机流程里的「等解锁」判定。
 *
 * ### 为什么开机提权必须等解锁
 * 这不是本项目的实现问题，是 Android 的平台约束：
 *
 * 1. **ADB 授权密钥在凭据加密（CE）存储里**：`/data/misc/adb/adb_keys`
 *    只有等用户第一次解锁、CE 存储被解挂后才可读。解锁前 adbd 拿不到密钥，
 *    握手只能以 `AUTH` 失败告终——连上了也认证不了。
 * 2. **无线调试（TLS 端口）在解锁后才真正可用**：`adb_wifi_enabled` 这个设置项
 *    可以提前写，但 adbd 的 TLS 服务要等 CE 可用。
 * 3. **`BOOT_COMPLETED` 本身就在解锁之后才发**：只有 `LOCKED_BOOT_COMPLETED`
 *    是解锁前发的，而收它要求 receiver 声明 `directBootAware`。
 *
 * 所以「必须解锁后才跑」在无 root 前提下**无法绕过**。能做的只有两件事：
 *   - 解锁前就把能做的事做完（写无线调试开关、判定要不要跑）
 *   - 解锁**那一刻立刻**开跑，而不是傻等到超时
 *
 * ### 本类只负责判定，不碰 Android API
 * 纯函数便于 JVM 单测；真正的 `UserManager.isUserUnlocked` 由调用方注入。
 */
object UnlockWait {

    /** 等解锁的最长时间：10 分钟。超过就不再等。 */
    const val UNLOCK_WAIT_MS = 600_000L

    /** 轮询解锁状态的间隔：1 秒。比 8 秒的连接重试细，解锁后能立刻开跑。 */
    const val UNLOCK_POLL_MS = 1_000L

    /** 解锁后留多少时间给 ADB 连接。 */
    const val CONNECT_AFTER_UNLOCK_MS = 300_000L

    enum class Decision {
        /** 已解锁，可以去连 ADB 了。 */
        PROCEED,

        /** 还没解锁，继续等。 */
        WAIT_UNLOCK,

        /** 等解锁超时，放弃。 */
        TIMEOUT,
    }

    /**
     * @param now 当前时间（毫秒）
     * @param unlockDeadline 等解锁的截止时间
     * @param unlocked 当前是否已解锁
     */
    fun decide(now: Long, unlockDeadline: Long, unlocked: Boolean): Decision = when {
        unlocked -> Decision.PROCEED
        now >= unlockDeadline -> Decision.TIMEOUT
        else -> Decision.WAIT_UNLOCK
    }

    /** 真实设备上的解锁状态。 */
    fun isUnlocked(context: Context): Boolean = runCatching {
        val um = context.getSystemService(UserManager::class.java) ?: return false
        um.isUserUnlocked
    }.getOrDefault(false)

    /** 给用户看的说明，避免"它怎么不动"的困惑。 */
    const val WAITING_MESSAGE =
        "设备还没解锁：ADB 密钥在加密存储里，解锁前无法完成认证。解锁后会自动继续，不用再点任何东西。"
}
