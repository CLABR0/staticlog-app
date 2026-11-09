package com.demo.zt

import android.content.Context

/**
 * 开机自启的持久化状态。
 *
 * 规则：
 *   - 只有内核适配（版本 6.6.127）才允许开启
 *   - 每运行一次计数 +1
 *   - 计数超过 MAX_RUNS 自动关闭
 */
object AutoBoot {

    const val MAX_RUNS = 30
    private const val PREF = "auto_boot"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COUNT = "run_count"
    private const val KEY_SESSION_AT = "session_started_at"

    /** 同一次开机的判定窗口：30 分钟。 */
    private const val SESSION_WINDOW_MS = 30 * 60 * 1000L

    /**
     * 用**设备保护存储**（DE）而不是默认的凭据加密存储（CE）。
     *
     * 原因：`LOCKED_BOOT_COMPLETED` 在解锁之前发，那时 CE 存储还没解挂，
     * 默认 `getSharedPreferences` 读出来是空的 —— 开关看起来是"关的"，
     * 于是开机那一刻永远判成"未启用"，只能等解锁后重新开机才会跑。
     *
     * DE 存储在解锁前就能读，这才让"解锁前先把该做的判完"成立。
     */
    private fun prefs(context: Context) =
        context.applicationContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 把旧版本写在 CE 存储里的开关与计数搬到 DE。
     *
     * 升级用户的历史值必须保住：否则升级后第一次开机，开关会"自己变回关闭"。
     * 只在 DE 里还没有值时搬一次，搬完标记，避免把用户后来改的值覆盖回去。
     */
    fun migrateToDeviceProtected(context: Context) {
        runCatching {
            val app = context.applicationContext
            val de = app.createDeviceProtectedStorageContext()
            val dePrefs = de.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (dePrefs.contains(KEY_ENABLED)) return
            val cePrefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (!cePrefs.contains(KEY_ENABLED) && !cePrefs.contains(KEY_COUNT)) return
            dePrefs.edit()
                .putBoolean(KEY_ENABLED, cePrefs.getBoolean(KEY_ENABLED, false))
                .putInt(KEY_COUNT, cePrefs.getInt(KEY_COUNT, 0))
                .commit()
        }
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun runCount(context: Context): Int =
        prefs(context).getInt(KEY_COUNT, 0)

    /** 是否已达上限。 */
    fun exhausted(context: Context): Boolean = runCount(context) >= MAX_RUNS

    /**
     * 尝试开启。返回 false 表示内核不适配，拒绝开启。
     */
    fun enable(context: Context): Boolean {
        if (!KernelGate.check().supported) return false
        // commit() 而非 apply()：这个开关决定开机行为，异步写盘一旦进程被杀就会丢，
        // 表现是「关了但重启后照跑」。
        prefs(context).edit().putBoolean(KEY_ENABLED, true).commit()
        return true
    }

    fun disable(context: Context) {
        prefs(context).edit().putBoolean(KEY_ENABLED, false).commit()
    }

    fun resetCount(context: Context) {
        prefs(context).edit().putInt(KEY_COUNT, 0).commit()
    }

    /**
     * 开机触发时调用：若已开启且未超限，计数 +1 并返回 true 表示应当执行。
     * 达到上限时自动关闭并返回 false。
     */
    fun shouldRunOnBootAndConsume(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val count = runCount(context)
        if (count >= MAX_RUNS) {
            disable(context)
            return false
        }
        prefs(context).edit().putInt(KEY_COUNT, count + 1).apply()
        return true
    }

    /**
     * 本次开机是否**已经**起过服务。
     *
     * 为什么要这个：解锁前 `LOCKED_BOOT_COMPLETED` 已经起过一次服务，
     * 解锁后系统还会再发一次 `BOOT_COMPLETED`。没有这层判断的话：
     *   - 计数会被 +2（30 次上限提前耗尽）
     *   - 两个 AutoBootService 同时跑同一套载荷，`rm -f zt*` 互删文件
     *     → 表现为「偶发失败，重试又好」。
     *
     * 用 30 分钟窗口判定同一次开机：一次开机不可能相隔 30 分钟再发开机广播。
     * 时间取 `currentTimeMillis`（DE 存储里存，解锁前可写）。
     */
    fun beginSessionIfNeeded(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs(context).getLong(KEY_SESSION_AT, 0L)
        if (last > 0 && now - last in 0 until SESSION_WINDOW_MS) return false
        prefs(context).edit().putLong(KEY_SESSION_AT, now).commit()
        return true
    }
}