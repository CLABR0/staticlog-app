package com.demo.zt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.demo.zt.adb.AdbKey
import com.demo.zt.adb.PreferenceAdbKeyStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 配对原生库验证。
 *
 * 修复前：APK 内没有 libadb.so，AdbPairingClient 类初始化即 UnsatisfiedLinkError，
 * 真机上点「配对」直接崩，五步配对永远走不完。
 *
 * 本测试证明：libadb.so 已打进 APK、JNI_OnLoad 注册成功、
 * SPAKE2 上下文可创建（即配对密码学链路可用）。
 */
@RunWith(AndroidJUnit4::class)
class PairingNativeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeLibraryLoads() {
        // 触发 AdbPairingClient 的 companion init -> System.loadLibrary("adb")
        val loaded = com.demo.zt.adb.AdbPairingClient.available()
        assertTrue("libadb.so 应可加载（JNI_OnLoad 注册成功）", loaded)
    }

    @Test
    fun adbKeyGeneratesPublicKey() {
        val prefs = context.getSharedPreferences("adb_keys_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val key = AdbKey(PreferenceAdbKeyStore(prefs), "rootkit")
        val text = key.adbPublicKeyText
        assertTrue("adb 公钥应为 base64 + 名称形式，实际=${text.take(40)}", text.length > 100)
        assertTrue("公钥应带密钥名后缀", text.endsWith("rootkit"))
        assertNotNull(key.adbPublicKey)
    }
    /**
     * 真正走一遍 SPAKE2：创建上下文 -> 取公钥消息 -> 用自己的公钥消息喂回去
     * （自配对在密码学上不可完成，这里只验证 native 侧不崩且返回长度合理）。
     */
    @Test
    fun spake2ContextCreates() {
        val ctx = com.demo.zt.adb.PairingContextBridge.create("123456".toByteArray())
        assertNotNull("SPAKE2 上下文应可创建（说明 boringssl spake25519 链路正常）", ctx)
        val msg = com.demo.zt.adb.PairingContextBridge.message(ctx!!)
        assertTrue("SPAKE2 公钥消息应有合理长度，实际=${msg?.size}", (msg?.size ?: 0) > 16)
        com.demo.zt.adb.PairingContextBridge.destroy(ctx)
    }
}
