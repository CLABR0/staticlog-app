package com.demo.zt

import android.content.Context
import android.util.Log
import com.demo.zt.adb.AdbClient

/**
 * ADB 连接的**唯一状态源**与单飞入口。
 *
 * ### 为什么需要它
 * 之前 `AdbAutoConnector.connect()` 可以被多处并发调用，而且调用方
 * （主界面启动、从配对页返回、设置页授权前）各自判断 `AdbHolder.client`，
 * 于是出现两种症状：
 *   - 后台连接还没跑完，界面就报「尚未连接 ADB」（竞态）
 *   - 同一时刻开了好几条连接尝试（浪费、状态互相覆盖）
 *
 * 现在统一走这里：
 *   - **单飞**：并发调用共享同一次尝试，不会重复连
 *   - **可等待**：调用方可以「等这次连接结束」而不是立刻下结论
 *   - **有状态**：界面能显示「连接中 / 已连接(端口) / 未连接(原因)」
 */
object AdbConnectionManager {

    private const val TAG = "AdbConn"

    enum class State { IDLE, CONNECTING, CONNECTED, FAILED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var connectedPort: Int = -1
        private set

    /** 最近一次失败原因，供界面直接展示。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 进行中的连接尝试；并发调用共用同一个 latch。 */
    private val lock = Any()
    private var inFlight: InFlight? = null

    private class InFlight {
        val done = java.util.concurrent.CountDownLatch(1)
        @Volatile var ok = false
    }

    /** 供界面显示的一句话状态。 */
    fun describe(): String = when (state) {
        State.CONNECTED -> "已连接（端口 $connectedPort）"
        State.CONNECTING -> "连接中…"
        State.FAILED -> "未连接：${lastError ?: "未知原因"}"
        State.IDLE -> "未连接"
    }

    fun isConnected(): Boolean = AdbHolder.client != null

    /**
     * 建立连接（单飞 + 重试）。阻塞调用，必须在后台线程。
     *
     * @param timeoutMs 整体预算；内部按 [RETRY_INTERVAL_MS] 重试
     * @return 是否成功
     */
    fun connectBlocking(context: Context, timeoutMs: Long = 30_000L): Boolean {
        AdbHolder.client?.let {
            state = State.CONNECTED
            connectedPort = AdbHolder.connectPort
            return true
        }

        val mine: InFlight
        val owner: Boolean
        synchronized(lock) {
            val existing = inFlight
            if (existing != null) {
                mine = existing
                owner = false
            } else {
                mine = InFlight()
                inFlight = mine
                owner = true
            }
        }

        if (!owner) {
            // 已有尝试在进行：等它，别重复连
            val waited = runCatching { mine.done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            Log.i(TAG, "等待中的连接结束：waited=$waited ok=${mine.ok}")
            return AdbHolder.client != null
        }

        state = State.CONNECTING
        var ok = false
        val deadline = System.currentTimeMillis() + timeoutMs
        val logs = mutableListOf<String>()
        var round = 0

        try {
            while (System.currentTimeMillis() < deadline && !ok) {
                round++
                logs.clear()
                ok = runCatching {
                    AdbAutoConnector.connect(context) { line ->
                        logs += line
                        Log.i(TAG, "第 $round 轮：$line")
                    }
                }.getOrDefault(false)

                if (ok && AdbHolder.client != null) break

                if (!ok && System.currentTimeMillis() < deadline) {
                    runCatching { Thread.sleep(RETRY_INTERVAL_MS) }
                }
            }
        } finally {
            if (ok && AdbHolder.client != null) {
                state = State.CONNECTED
                connectedPort = AdbHolder.connectPort
                lastError = null
            } else {
                state = State.FAILED
                lastError = logs.lastOrNull()?.removePrefix("[自动连接] ") ?: "连接超时（$round 轮）"
            }
            mine.ok = ok
            synchronized(lock) { if (inFlight === mine) inFlight = null }
            mine.done.countDown()
        }

        Log.i(TAG, "connectBlocking 结束：ok=$ok state=$state")
        return ok
    }

    /**
     * 取连接：有就直接返回；没有就发起一次并等待。
     * 阻塞调用，必须在后台线程。
     */
    fun clientOrConnect(context: Context, timeoutMs: Long = 30_000L): AdbClient? {
        AdbHolder.client?.let { return it }
        connectBlocking(context, timeoutMs)
        return AdbHolder.client
    }

    /** 断开并复位状态。 */
    fun reset() {
        AdbHolder.clear()
        state = State.IDLE
        connectedPort = -1
        lastError = null
    }

    private const val RETRY_INTERVAL_MS = 2_000L
}
