package com.demo.zt.shell

import com.demo.zt.AdbHolder

/** 原版 Stellar 门面，保留 StarterScreen 需要的 binder 监听 API。 */
object Stellar {

    fun interface OnBinderReceivedListener {
        fun onBinderReceived()
    }

    private val listeners = mutableListOf<OnBinderReceivedListener>()

    fun pingBinder(): Boolean = AdbHolder.client != null

    val isRemote: Boolean get() = false

    fun addBinderReceivedListener(listener: OnBinderReceivedListener) {
        listeners.add(listener)
    }

    fun addBinderReceivedListenerSticky(listener: OnBinderReceivedListener) {
        listeners.add(listener)
        if (pingBinder()) listener.onBinderReceived()
    }

    fun removeBinderReceivedListener(listener: OnBinderReceivedListener) {
        listeners.remove(listener)
    }

    /** 连接建立后由外部调用，通知所有监听者。 */
    fun dispatchBinderReceived() {
        listeners.toList().forEach { it.onBinderReceived() }
    }
}
