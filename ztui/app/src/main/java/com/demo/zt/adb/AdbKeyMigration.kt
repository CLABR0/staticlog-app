package com.demo.zt.adb

import android.content.Context
import android.os.UserManager
import com.demo.zt.BootTrace

/**
 * 把旧版 CE 中的 ADB 私钥密文原样复制到 DE；保留 CE 原件，不重新生成或加密。
 * CE 只有首次解锁后才可读，锁屏时跳过，等解锁广播或用户打开 App 再补迁移。
 */
object AdbKeyMigration {

    private const val PREFS_NAME = "adb_keys"
    private const val KEY_NAME = "adbkey"

    /** 只抽象迁移需要的操作，让 JVM 单测直接使用内存实现，不触发 Android API。 */
    internal interface PrefsBridge {
        fun isUnlocked(): Boolean
        fun ceGet(key: String): String?
        fun deGet(key: String): String?
        fun dePut(key: String, value: String): Boolean
    }

    fun migrate(context: Context): Boolean = runCatching {
        migrate(AndroidPrefsBridge(context)) { message -> trace(context, message) }
    }.getOrElse {
        trace(context, "ADB 私钥迁移异常：${it.message}")
        false
    }

    /**
     * 日志由调用方注入：这里不直接依赖 BootTrace，测试时可传空实现。
     * 不解码 Base64、不解密，避免复制过程改变已获 adbd 授权的那把密钥。
     */
    internal fun migrate(bridge: PrefsBridge, log: (String) -> Unit): Boolean = runCatching {
        if (!bridge.isUnlocked()) {
            log("ADB 私钥迁移：未解锁，跳过")
            return@runCatching false
        }
        // 已有值就不覆盖，也不刷日志；更不能用 CE 里的旧值回滚 DE 的新值。
        if (bridge.deGet(KEY_NAME) != null) return@runCatching true
        val ciphertext = bridge.ceGet(KEY_NAME) ?: return@runCatching false

        val committed = bridge.dePut(KEY_NAME, ciphertext)
        // commit 失败也可能已经更新内存，因此必须同时核对提交结果与读回值。
        val readBack = bridge.deGet(KEY_NAME)
        when {
            !committed -> {
                log("ADB 私钥迁移失败：同步写入失败")
                false
            }
            readBack != ciphertext -> {
                log("ADB 私钥迁移失败：读回不一致")
                false
            }
            else -> {
                log("ADB 私钥已复制到 DE（锁屏可读）")
                true
            }
        }
    }.getOrElse {
        // 连日志回调抛异常也要兜住，迁移失败不应中断开机流程。
        runCatching { log("ADB 私钥迁移异常：${it.message}") }
        false
    }

    fun hasDeviceProtectedKey(context: Context): Boolean = runCatching {
        hasDeviceProtectedKey(AndroidPrefsBridge(context)) { message -> trace(context, message) }
    }.getOrElse {
        trace(context, "ADB 私钥 DE 检查异常：${it.message}")
        false
    }

    /** 这里只检查非空密文；能否解密由禁止生成模式的 AdbKey 构造过程最终确认。 */
    internal fun hasDeviceProtectedKey(bridge: PrefsBridge, log: (String) -> Unit = {}): Boolean = runCatching {
        !bridge.deGet(KEY_NAME).isNullOrEmpty()
    }.getOrElse {
        runCatching { log("ADB 私钥 DE 检查异常：${it.message}") }
        false
    }

    private class AndroidPrefsBridge(private val context: Context) : PrefsBridge {
        // CE 侧：用 application context 的默认存储（就是 CE）。
        // 不用 createCredentialProtectedStorageContext()：把 DE context 传进来时
        // 那个方法才有意义，而这里的入参本来就是默认 context。
        private val ce by lazy {
            (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        private val de by lazy {
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun isUnlocked(): Boolean =
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

        override fun ceGet(key: String): String? = ce.getString(key, null)

        override fun deGet(key: String): String? = de.getString(key, null)

        override fun dePut(key: String, value: String): Boolean =
            de.edit().putString(key, value).commit()
    }

    private fun trace(context: Context, message: String) {
        runCatching { BootTrace.log(context, message) }
    }
}
