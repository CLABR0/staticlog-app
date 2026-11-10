package com.demo.zt.adb

import android.content.Context
import com.demo.zt.AppContextHolder
import com.demo.zt.BootTrace

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
 *
 * 私钥密文必须存 DE：重启后首次解锁前 CE 不可读，容易被误判为没有密钥。
 * 锁屏连接只允许读取已有密钥，不能现场生成，否则会覆盖已被 adbd 授权的密钥。
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
                PreferenceAdbKeyStore(ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
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
                PreferenceAdbKeyStore(context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
                KEY_NAME,
            )
            cached = key
            return key
        }
    }

    /** 锁屏专用：不复用允许生成的缓存路径，确保缺失或损坏时只放弃连接、不写新密钥。 */
    fun adbKeyForBootOrNull(context: Context): AdbKey? = runCatching {
        if (!AdbKeyMigration.hasDeviceProtectedKey(context)) {
            BootTrace.log(context, "锁屏态：DE 里没有 ADB 私钥，放弃本次连接")
            return@runCatching null
        }
        AdbKey(
            PreferenceAdbKeyStore(context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
            KEY_NAME,
            allowGenerate = false,
        )
    }.getOrElse {
        runCatching { BootTrace.log(context, "锁屏态：ADB 私钥读取失败，放弃本次连接：${it.message}") }
        null
    }
}
