package com.demo.zt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.demo.zt.adb.AdbClient
import kotlin.concurrent.thread

/**
 * 开机自动提权的执行者。
 *
 * ### 为什么要有这个 Service
 * 原来 `BootReceiver` 直接 `startActivity()` 拉起界面再跑载荷。这条路在 Android 10+
 * 是**不通的**：后台启动 Activity 会被系统静默拒绝，用户看到的现象就是
 * 「无线调试自启成功了，但提权根本没跑」。
 *
 * 现在改为：开机广播 → 启动本前台服务 → 服务里等 ADB 起来 → 直接跑载荷。
 * 全程不需要界面，也不受后台启动 Activity 的限制。
 *
 * ### 与界面路径的关系
 * 服务跑完只发一条通知（成功/失败），不弹界面。
 * 「开机自动提权」开关与 30 次上限仍由 [AutoBoot] 控制。
 */
class AutoBootService : Service() {

    companion object {
        private const val TAG = "AutoBootService"
        private const val CHANNEL_ID = "boot_autostart"
        private const val NOTIFICATION_ID = 1001

        /** 等 ADB 就绪的总时长上限：无线调试开机后要几秒才起监听。 */
        private const val CONNECT_TIMEOUT_MS = 300_000L

        /** 每次重连间隔。 */
        private const val CONNECT_RETRY_INTERVAL_MS = 8_000L

        fun start(context: Context) {
            val intent = Intent(context, AutoBootService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.e(TAG, "启动 AutoBootService 失败", it) }
        }
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BootTrace.log(this, "AutoBootService 已启动")
        startForegroundSafely(buildNotification("开机自动提权：准备中", "正在等待 ADB 就绪…"))
        BootTrace.log(this, "前台通知已发布（解锁前也应有通知栏条目）")

        if (worker?.isAlive == true) return START_NOT_STICKY

        worker = thread(isDaemon = true) {
            log("开机广播触发，开始执行（计数 ${AutoBoot.runCount(this)}/${AutoBoot.MAX_RUNS}）")
            try {
                runPayloadOnBoot()
            } catch (e: Throwable) {
                Log.e(TAG, "开机自动提权异常", e)
                notify("开机自动提权失败", e.message ?: e::class.simpleName ?: "未知异常")
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runPayloadOnBoot() {
        val client = awaitClient()
        if (client == null) {
            notify(
                "开机自动提权失败",
                "等不到 ADB 连接（超过 ${CONNECT_TIMEOUT_MS / 1000} 秒）。" +
                    "请检查：Wi-Fi 是否连上、无线调试是否已开、是否在弹窗里选了「始终允许此网络」",
            )
            return
        }

        val decision = runCatching { PayloadResolver.decide(this) }.getOrNull()
        val variant = decision?.variant
        if (variant == null) {
            notify("开机自动提权失败", "无法判定要用的资源包")
            return
        }

        notify("开机自动提权：正在执行", "${variant.shortName}｜${decision.reason}")

        val result = runCatching {
            com.demo.zt.shell.PayloadRunner.run(this, client, variant) { line ->
                Log.i(TAG, line)
            }
        }.getOrElse {
            notify("开机自动提权失败", it.message ?: it::class.simpleName ?: "执行异常")
            return
        }

        if (result.ok) {
            // 开机场景更需要这步：屏幕可能还锁着，用户看不到任何反馈
            val post = PostEscalation.unlockAndOpenManager(this, client)
            Log.i(TAG, "收尾：${post.message}")
            notify("开机自动提权成功", "${variant.shortName}｜${result.reason}\n${post.message}")
        } else {
            notify("开机自动提权失败", "${variant.shortName}｜${result.reason}")
        }
    }

    /**
     * 先等解锁，解锁后再连 ADB。
     *
     * ### 为什么要单独等解锁
     * ADB 授权密钥在凭据加密存储（`/data/misc/adb/adb_keys`），解锁前 adbd
     * 拿不到，握手只能 AUTH 失败。以前这里直接开连，结果是：解锁前每一轮都
     * 白连，等用户解锁时 300 秒早就用完了，表现为「必须解锁后手动再点一次」。
     *
     * 现在把两段等待分开：
     *   - 等解锁：最多 10 分钟，1 秒一轮，解锁立刻继续
     *   - 等连接：解锁后 5 分钟，8 秒一轮
     *
     * 开机时 Wi-Fi 通常还没连上，此时 mDNS 什么也发现不到；
     * 所以网络就绪之后才开始连，否则前面几轮纯属浪费。
     */
    private fun awaitClient(): AdbClient? {
        // ── 第零段：解锁前先**真试一次** ──
        //     不预设"解锁前一定不行"。能连上就直接跑，连不上就把失败原因记下来。
        //     这样"解锁前到底卡在哪"有实测依据，而不是靠推断。
        if (!com.demo.zt.UnlockWait.isUnlocked(this)) {
            BootTrace.log(this, "解锁前试连一次（未解锁）")
            val pre = runCatching { AdbAutoConnector.connect(this) }
            val preOk = pre.getOrDefault(false)
            BootTrace.log(this, "解锁前试连结果：${if (preOk) "成功" else "失败"}${pre.exceptionOrNull()?.let { "（${it.message}）" } ?: ""}")
            if (preOk) {
                AdbHolder.client?.let {
                    log("解锁前已连上 ADB，直接执行")
                    BootTrace.log(this, "解锁前连上 ADB，跳过等解锁")
                    return it
                }
            }
        }

        // ── 第一段：等解锁 ──
        val unlockDeadline = System.currentTimeMillis() + com.demo.zt.UnlockWait.UNLOCK_WAIT_MS
        var announcedLock = false
        while (true) {
            AdbHolder.client?.let { return it }
            val unlocked = com.demo.zt.UnlockWait.isUnlocked(this)
            when (com.demo.zt.UnlockWait.decide(
                now = System.currentTimeMillis(),
                unlockDeadline = unlockDeadline,
                unlocked = unlocked,
            )) {
                com.demo.zt.UnlockWait.Decision.PROCEED -> {
                    if (announcedLock) {
                        log("设备已解锁，继续执行")
                        BootTrace.log(this, "检测到已解锁，开始连接")
                    }
                    break
                }

                com.demo.zt.UnlockWait.Decision.TIMEOUT -> {
                    log("等解锁超时（${com.demo.zt.UnlockWait.UNLOCK_WAIT_MS / 1000} 秒），放弃本次自动执行")
                    BootTrace.log(this, "等解锁超时，放弃")
                    updateProgress("开机自动提权未执行", "等了 ${com.demo.zt.UnlockWait.UNLOCK_WAIT_MS / 1000} 秒仍未解锁")
                    return null
                }

                com.demo.zt.UnlockWait.Decision.WAIT_UNLOCK -> {
                    if (!announcedLock) {
                        log(com.demo.zt.UnlockWait.WAITING_MESSAGE)
                        BootTrace.log(this, "进入等解锁循环")
                        updateProgress("等待解锁", "解锁后会自动继续，无需再点任何东西")
                        announcedLock = true
                    }
                    // 解锁瞬间无线调试开关可能被系统重置，顺手补一次（幂等）
                    runCatching { com.demo.zt.WirelessAdbAutoEnabler.applyNow(this, enable = true) }
                    runCatching { Thread.sleep(com.demo.zt.UnlockWait.UNLOCK_POLL_MS) }
                }
            }
        }

        // ── 第二段：连 ADB ──
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var round = 0
        var announcedNetworkWait = false

        while (System.currentTimeMillis() < deadline) {
            AdbHolder.client?.let { return it }

            if (!isNetworkReady()) {
                if (!announcedNetworkWait) {
                    log("网络未就绪，等待 Wi-Fi/数据连接…")
                    updateProgress("等待网络就绪", "开机后 Wi-Fi 连接需要时间")
                    announcedNetworkWait = true
                }
                runCatching { Thread.sleep(3_000L) }
                continue
            }
            if (announcedNetworkWait) {
                Log.i(TAG, "网络已就绪，开始尝试连接 ADB")
                announcedNetworkWait = false
            }

            round++
            val ok = runCatching { AdbAutoConnector.connect(this) }.getOrDefault(false)
            if (ok) AdbHolder.client?.let { return it }

            updateProgress("等待 ADB 就绪", "第 $round 轮未连上，继续重试…")
            Log.i(TAG, "第 $round 轮未连上，等待 ${CONNECT_RETRY_INTERVAL_MS / 1000}s 重试")
            runCatching { Thread.sleep(CONNECT_RETRY_INTERVAL_MS) }
        }
        return AdbHolder.client
    }

    /** 是否有可用的网络（Wi-Fi 或数据）。用于判断 mDNS 值不值得试。 */
    private fun isNetworkReady(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** 更新前台通知，让用户看得到卡在哪一步。 */
    private fun updateProgress(title: String, text: String) {
        runCatching { startForegroundSafely(buildNotification(title, text)) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "开机自启", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun buildNotification(title: String, text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.e(TAG, "startForeground 失败", it) }
    }

    /**
     * 记录一条进度。
     *
     * 同时写 logcat 和 AppLog：**AppLog 才是用户"复制日志"能拿到的那份**。
     * 之前只写 logcat，界面日志里看不到开机自动提权的任何痕迹，
     * 用户会以为"什么都没做它自己跑了一次"。
     */
    private fun log(line: String) {
        Log.i(TAG, line)
        runCatching { AppLog.add("[自动提权] $line") }
    }

    private fun notify(title: String, text: String) {
        log("$title：$text")
        runCatching {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
                .setSmallIcon(R.drawable.ic_stellar)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
        }.onFailure { Log.e(TAG, "发送通知失败", it) }
    }
}
