package com.demo.zt.adb

import android.content.Context
import com.demo.zt.AppContextHolder

/**
 * 全局唯一的 ADB 密钥来源。
 *
 * **为什么必须有这个类**：
 * 之前代码里有三处各自 `AdbKey(...)`，用的 SharedPreferences 文件还不一样：
 *   - 配对服务 / 配对页连接 → `stellar_settings`
 *   - 主界面自动重连       → `adb_keys`
 * 两处拿到的是**完全不同的 RSA 密钥对**。
 * 配对流程把 A 的公钥授权给了设备，主界面却拿 B 去连 —— 设备不认，
 * 表现就是「输入配对码后并没有成功」。
 *
 * 现在所有路径统一从这里取同一把密钥，配对一次之后才能真的复用。
 */
internal object AdbKeyProvider {

    private const val PREFS_NAME = "adb_keys"
    private const val KEY_NAME = "staticlog"

    @Volatile
    private var cached: AdbKey? = null

    /** 取全局唯一的 ADB 密钥（首次调用时生成并持久化）。 */
    fun adbKey(): AdbKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ctx = AppContextHolder.context
                ?: error("AppContextHolder 未初始化，无法创建 ADB 密钥")
            val key = AdbKey(
                PreferenceAdbKeyStore(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
                KEY_NAME,
            )
            cached = key
            return key
        }
    }

    /** 用显式 context 取同一把密钥（早于 AppContextHolder 初始化的场合）。 */
    fun adbKey(context: Context): AdbKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val key = AdbKey(
                PreferenceAdbKeyStore(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
                KEY_NAME,
            )
            cached = key
            return key
        }
    }
}
