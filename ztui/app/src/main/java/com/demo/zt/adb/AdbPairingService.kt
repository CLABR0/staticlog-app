package com.demo.zt.adb

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.demo.zt.compat.BuildUtils.atLeast31
import com.demo.zt.compat.BuildUtils.atLeast34
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.demo.zt.R
import com.demo.zt.shell.StellarSettings
import kotlin.getValue
import androidx.core.content.edit

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val notificationChannel = "adb_pairing"
        const val alertNotificationChannel = "adb_pairing_alert"

        private const val tag = "AdbPairingService"

        private const val notificationId = 1
        private const val alertNotificationId = 2
        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val stopAndRetryRequestId = 4
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val stopAndRetryAction = "stop_and_retry"
        private const val replyAction = "reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "paring_code"

    /** 配对最多几轮（每轮之间重新发现端口）。 */
    private const val MAX_PAIRING_ROUNDS = 3

    /** 每轮重试前的退避基准（毫秒），按轮次递增。 */
    private const val PAIRING_RETRY_DELAY_MS = 1500L

    /** 重新发现端口的最长等待（毫秒）。 */
    private const val REDISCOVER_TIMEOUT_MS = 12_000L

        @Volatile
        private var isRunning = false

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(startAction)

        private fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(stopAction)

        private fun stopAndRetryIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(stopAndRetryAction)

        private fun replyIntent(context: Context, port: Int): Intent =
            Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
    }

    private var adbMdns: AdbMdns? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var discoveredPort: Int = -1

    /** mDNS 解析出的配对服务地址；为空时退回 127.0.0.1。 */
    @Volatile
    private var discoveredHost: String? = null

    /** 重新发现端口时，观察者拿到新端口后投递到这里。 */
    private val pendingPort =
        java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Int>?>(null)

    /**
     * 更新前台通知。
     *
     * 只调 `startForeground` 时，部分 ROM 的通知栏不会重新渲染，
     * 表现为「要手动点一下才刷新」。这里在 startForeground 之后再显式
     * `notify` 一次，强制刷新。
     */
    private fun pushNotification(notification: Notification) {
        try {
            if (atLeast34) {
                startForeground(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Throwable) {
            Log.w(tag, "startForeground 更新失败，改用 notify：${e.message}")
        }
        runCatching {
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)
        }.onFailure { Log.w(tag, "notify 刷新失败：${it.message}") }
    }

    /** 停掉当前发现再重新开始，用于「端口可能已过期」的场景。 */
    private fun restartSearch() {
        runCatching { stopSearch() }
        runCatching { startSearch() }
    }

    private val observer = Observer<Int> { port ->
        Log.i(tag, "配对服务端口: $port")
        if (port <= 0) {
            return@Observer
        }

        discoveredPort = port
        discoveredHost = adbMdns?.resolvedHost
        // 若正有一轮「等待重新发现端口」在跑，把新端口投递给它
        pendingPort.get()?.complete(port)
        Log.i(tag, "配对服务 host=${discoveredHost ?: "(未解析)"} port=$port")

        val notification = createInputNotification(port)
        try {
            pushNotification(notification)
            Log.i(tag, "已更新通知为输入配对码")
        } catch (e: Exception) {
            Log.e(tag, "更新通知失败", e)
            getSystemService(NotificationManager::class.java).notify(notificationId, notification)

            val alertNotification = Notification.Builder(this, alertNotificationChannel)
                .setSmallIcon(R.drawable.ic_stellar)
                .setContentTitle(getString(R.string.pairing_service_found))
                .setContentText(getString(R.string.enter_pairing_code))
                .addAction(replyNotificationAction(port))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(alertNotificationId, alertNotification)
        }
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        val notificationManager = getSystemService(NotificationManager::class.java)

        notificationManager.createNotificationChannel(
            NotificationChannel(
                notificationChannel,
                getString(R.string.wireless_debugging_pairing_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })

        notificationManager.createNotificationChannel(
            NotificationChannel(
                alertNotificationChannel,
                getString(R.string.pairing_alert_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                enableVibration(true)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {
                onStart()
            }
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1) {
                    onInput(code.toString(), port)
                } else {
                    onStart()
                }
            }
            stopAction -> {
                onStopSearch()
            }
            stopAndRetryAction -> {
                onStopAndRetry()
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        try {
            pushNotification(notification)
        } catch (e: Throwable) {
            Log.e(tag, "启动前台服务失败", e)

            if (atLeast31
                && e is ForegroundServiceStartNotAllowedException) {
                getSystemService(NotificationManager::class.java).notify(notificationId, notification)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(
            this,
            AdbMdns.TLS_PAIRING,
            observer,
            onMaxRefresh = { onSearchMaxRefresh() }
        ).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        try {
            adbMdns?.stop()
        } catch (e: Exception) {
            Log.e(tag, "停止搜索失败", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        retryHandler.removeCallbacksAndMessages(null)
        stopSearch()
        adbMdns?.destroy()
        adbMdns = null
        connectMdns?.destroy()
        connectMdns = null
    }

    private fun onStart(): Notification {
        if (isRunning && started) {
            Log.i(tag, "服务已在运行，忽略重复启动")
            return searchingNotification
        }
        isRunning = true
        startSearch()
        return searchingNotification
    }

    private fun onStopSearch(): Notification {
        stopSearch()
        return createManualInputNotification(discoveredPort)
    }

    private fun onStopAndRetry(): Notification {
        stopSearch()
        adbMdns?.destroy()
        adbMdns = null
        return onStart()
    }

    private fun onSearchMaxRefresh() {
        Log.i(tag, "搜索次数已达上限")
        stopSearch()
        val notification = createMaxRefreshNotification()
        try {
            pushNotification(notification)
            Log.i(tag, "更新前台通知失败")
        } catch (e: Exception) {
            Log.e(tag, "更新前台通知失败", e)
getSystemService(NotificationManager::class.java).notify(notificationId, notification)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun onInput(code: String, port: Int): Notification {
        if (port == -1) {
            return createManualInputNotification(-1)
        }

        // 允许用户手填端口：输入 `配对码@端口`。
        // 配对窗口每次重开端口都会变，mDNS 也可能给到过期端口，
        // 手填是最后的逃生通道。
        val parsedInput = PairingInput.parse(code)
        val pairCode = parsedInput.code
        val manualPort = parsedInput.manualPort

        PairingInput.errorFor(parsedInput)?.let { msg ->
            lastPairingError = msg
            handleResult(false)
            return workingNotification
        }

        GlobalScope.launch(Dispatchers.IO) {
            val key = try {
                AdbKeyProvider.adbKey(this@AdbPairingService)
            } catch (e: Throwable) {
                Log.e(tag, "获取 ADB 密钥失败", e)
                lastPairingError = "密钥初始化失败：${e.message}"
                handleResult(false)
                return@launch
            }

            val tried = mutableListOf<String>()
            var lastError: String? = null

            // 最多 3 轮；每轮之间重新发现配对端口（应对端口已过期）
            for (round in 1..MAX_PAIRING_ROUNDS) {
                val currentPort = manualPort ?: discoveredPort
                if (currentPort <= 0) {
                    lastError = "未发现配对端口，请重开无线调试的配对窗口"
                    break
                }

                for (host in hostCandidates()) {
                    val label = "$host:$currentPort"
                    Log.i(tag, "第 $round 轮尝试配对：$label")
                    val result = runCatching {
                        // 必须 use{}：start() 之后不 close 会漏掉流、Socket 和原生
                        // pairing context；同一项目里另一处已经是这种写法。
                        AdbPairingClient(host, currentPort, pairCode, key).use { it.start() }
                    }
                    if (result.getOrDefault(false)) {
                        lastPairingError = null
                        Log.i(tag, "配对成功（第 $round 轮，$label）")
                        handleResult(true)
                        return@launch
                    }
                    val e = result.exceptionOrNull()
                    if (e != null) lastError = "${e::class.simpleName}: ${e.message}"
                    tried += "$label → ${e?.let { it::class.simpleName } ?: "SPAKE2 未完成"}"
                    Log.w(tag, "配对未成功：$label", e)
                }

                if (manualPort != null) break   // 手填端口不重试发现
                if (round < MAX_PAIRING_ROUNDS) {
                    // 端口可能已过期：重新发起一次 mDNS 发现，拿到新端口再试
                    val fresh = rediscoverPort()
                    if (fresh != null && fresh != discoveredPort) {
                        Log.i(tag, "发现新配对端口：$fresh（旧 ${discoveredPort}）")
                        discoveredPort = fresh
                    } else {
                        Log.w(tag, "重新发现未拿到新端口，稍后重试")
                    }
                    kotlinx.coroutines.delay(PAIRING_RETRY_DELAY_MS * round)
                }
            }

            lastPairingError = buildString {
                append(lastError ?: "配对失败")
                if (tried.isNotEmpty()) {
                    append("\n已尝试：")
                    append(tried.take(4).joinToString("；"))
                    if (tried.size > 4) append(" 等 ${tried.size} 次")
                }
            }
            Log.e(tag, "配对失败：$tried")
            handleResult(false)
        }

        return workingNotification
    }

    /**
     * 地址候选顺序：mDNS 解析出的地址 → IPv4 回环 → IPv6 回环。
     *
     * 有些机型的 adbd 配对 listener 只绑在 wlan 地址，有些只绑 IPv6，
     * 三个都试一遍比赌一个更稳。
     */
    private fun hostCandidates(): List<String> = buildList {
        discoveredHost?.takeIf { it.isNotBlank() }?.let(::add)
        add("127.0.0.1")
        add("::1")
    }.distinct()

    /**
     * 重新发起一次 mDNS 发现并等待新端口。
     * 拿不到就返回 null（调用方沿用旧端口继续试）。
     */
    private suspend fun rediscoverPort(): Int? = withTimeoutOrNull(REDISCOVER_TIMEOUT_MS) {
        val deferred = CompletableDeferred<Int>()
        pendingPort.set(deferred)
        try {
            retryHandler.post { restartSearch() }
            deferred.await()
        } finally {
            pendingPort.compareAndSet(deferred, null)
        }
    }

    /** 最近一次配对失败原因，用于通知文案与 logcat。 */
    @Volatile
    private var lastPairingError: String? = null

    private var connectMdns: AdbMdns? = null

    private fun handleResult(success: Boolean) {
        retryHandler.post {
            if (success) {
                Log.i(tag, "配对成功，开始搜索连接服务")
                stopSearch()

                val successNotification = Notification.Builder(this, notificationChannel)
                    .setSmallIcon(R.drawable.ic_stellar)
                    .setContentTitle(getString(R.string.pairing_success))
                    .setContentText(getString(R.string.searching_connect_service))
                    .setOngoing(true)
                    .build()

                pushNotification(successNotification)

                searchConnectService()
            } else {
                val title = getString(R.string.pairing_failed_retrying)
                // 带上真实失败原因，方便判断是配对码错还是 TLS/端口问题
                val detail = lastPairingError
                val text = if (detail.isNullOrBlank()) {
                    getString(R.string.please_wait_auto_return)
                } else {
                    "$detail\n${getString(R.string.please_wait_auto_return)}"
                }

                Log.i(tag, "配对失败，正在重试；原因=$detail")

                val failureNotification = Notification.Builder(this, notificationChannel)
                    .setSmallIcon(R.drawable.ic_stellar)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .build()

                pushNotification(failureNotification)

                retryHandler.postDelayed({
                    val retryNotification = createManualInputNotification(discoveredPort)
                    pushNotification(retryNotification)
                }, 2000)
            }
        }
    }

    private fun searchConnectService() {
        val connectObserver = Observer<Int> { port ->
            Log.i(tag, "连接服务端口: $port")
            if (port <= 0) return@Observer

            connectMdns?.destroy()
            connectMdns = null

            onConnectServiceFound(port)
        }

        connectMdns = AdbMdns(
            this,
            AdbMdns.TLS_CONNECT,
            connectObserver,
            onMaxRefresh = {
                Log.w(tag, "搜索连接服务次数已达上限")
                onConnectServiceMaxRefresh()
            }
        ).apply { start() }
    }

    private fun onConnectServiceFound(port: Int) {
        retryHandler.post {
            Log.i(tag, "找到连接服务端口: $port")

            val preferences = StellarSettings.getPreferences()
            val tcpipPortEnabled = preferences.getBoolean(StellarSettings.TCPIP_PORT_ENABLED, true)
            val currentPort = preferences.getString(StellarSettings.TCPIP_PORT, "")

            if (tcpipPortEnabled && currentPort.isNullOrEmpty()) {
                preferences.edit {
                    putString(StellarSettings.TCPIP_PORT, port.toString())
                }
                Log.i(tag, "自动设置 TCP 端口: $port")
            }

            grantSecureSettingsPermission(port)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun grantSecureSettingsPermission(port: Int) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val key = AdbKeyProvider.adbKey(this@AdbPairingService)

                val maxWait = 5000L
                val interval = 200L
                var elapsed = 0L
                while (elapsed < maxWait) {
                    try {
                        java.net.Socket("127.0.0.1", port).close()
                        break
                    } catch (_: Exception) {
                        kotlinx.coroutines.delay(interval)
                        elapsed += interval
                    }
                }

                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    val command = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"
                    client.shellCommand(command) { output ->
                        Log.d(tag, "授权命令输出: ${String(output)}")
                    }
                }
                Log.i(tag, "WRITE_SECURE_SETTINGS 权限授权成功")
            } catch (e: Exception) {
                Log.e(tag, "自动授权 WRITE_SECURE_SETTINGS 失败", e)
            }

            retryHandler.post {
                navigateToStarter(port)
            }
        }
    }

    private fun navigateToStarter(port: Int) {
        val intent = com.demo.zt.MainActivity.createStarterIntent(
            this,
            isRoot = false,
            host = "127.0.0.1",
            port = port
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)

        stopForeground(STOP_FOREGROUND_REMOVE)
        isRunning = false
        stopSelf()
    }

    private fun onConnectServiceMaxRefresh() {
        retryHandler.post {
            Log.w(tag, "连接服务搜索次数已达上限，尝试使用系统端口")

            val systemPort = com.demo.zt.compat.EnvironmentUtils.getAdbTcpPort()
            if (systemPort in 1..65535) {
                grantSecureSettingsPermission(systemPort)
            } else {
                val notification = Notification.Builder(this, notificationChannel)
                    .setSmallIcon(R.drawable.ic_stellar)
                    .setContentTitle(getString(R.string.connect_service_not_found))
                    .setContentText(getString(R.string.please_open_app_manually))
                    .setAutoCancel(true)
                    .build()

                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).notify(notificationId, notification)
                isRunning = false
                stopSelf()
            }
        }
    }

    private val stopNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            if (atLeast31)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.stop_search),
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            retryRequestId,
            startIntent(this),
            if (atLeast31)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.retry),
            pendingIntent
        )
            .build()
    }

    private val stopAndRetryNotificationAction by lazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopAndRetryRequestId,
            stopAndRetryIntent(this),
            if (atLeast31)
                PendingIntent.FLAG_IMMUTABLE
            else
                0
        )

        Notification.Action.Builder(
            null,
            getString(R.string.cannot_find_pairing),
            pendingIntent
        )
            .build()
    }

    private val replyNotificationAction by lazy {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.pairing_code))
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, -1),
            if (atLeast31)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            getString(R.string.enter_pairing_code_action),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        val action = replyNotificationAction

        PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (atLeast31)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return action
    }

    private val searchingNotification by lazy {
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.searching_pairing_service))
            .addAction(stopNotificationAction)
            .addAction(stopAndRetryNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification =
        Notification.Builder(this, notificationChannel)
            .setContentTitle(getString(R.string.pairing_service_found))
            .setSmallIcon(R.drawable.ic_stellar)
            .addAction(replyNotificationAction(port))
            .build()

    private fun createMaxRefreshNotification(): Notification =
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.pairing_service_not_found))
            .setContentText(getString(R.string.ensure_wireless_debugging_open))
            .addAction(retryNotificationAction)
            .build()

    private val workingNotification by lazy {
        Notification.Builder(this, notificationChannel)
            .setContentTitle(getString(R.string.pairing_in_progress))
            .setSmallIcon(R.drawable.ic_stellar)
            .build()
    }

    private fun createManualInputNotification(port: Int): Notification =
        Notification.Builder(this, notificationChannel)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle(getString(R.string.search_stopped))
            .setContentText(if (port > 0) getString(R.string.enter_pairing_code) else getString(R.string.pairing_service_not_found_retry))
            .addAction(if (port > 0) replyNotificationAction(port) else retryNotificationAction)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}

