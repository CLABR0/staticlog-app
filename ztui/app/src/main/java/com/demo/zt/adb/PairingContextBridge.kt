package com.demo.zt.adb

/**
 * 只给自动化测试用的 SPAKE2 桥。
 *
 * `PairingContext` 是 private 的，测试无法直接取到；这里把它的
 * 创建/取消息/加密/解密暴露成 internal 方法，用于验证原生链路与配对密码学。
 * 生产代码不走这里（AdbPairingClient 自己直接用 PairingContext）。
 */
internal object PairingContextBridge {

    fun create(password: ByteArray): Any? = PairingContext.create(password)

    fun message(ctx: Any): ByteArray? {
        val c = ctx as? PairingContext ?: return null
        return runCatching { c.msg }.getOrNull()
    }

    fun destroy(ctx: Any) {
        (ctx as? PairingContext)?.destroy()
    }
}
