package com.demo.zt

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 主界面日志面板的行数上限，与 [AppLog] 保持一致。 */
private const val LOG_MAX_LINES = 800

/**
 * 导航目的地，对应 GhostLock 原版的 `GhostlockScreen`。
 *
 * 原版用 Miuix 的 `NavDisplay` + `NavBackStack` 做页面栈，
 * 带滑动返回与圆角裁剪转场；这里照同一套实现。
 */
internal sealed interface StaticLogScreen : NavKey {
    data object Main : StaticLogScreen
    data object Starter : StaticLogScreen
    data object About : StaticLogScreen
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_RUN = "auto_run"

        fun createStarterIntent(
            context: android.content.Context,
            isRoot: Boolean = false,
            host: String = "127.0.0.1",
            port: Int = 0,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("starter_is_root", isRoot)
                .putExtra("starter_host", host)
                .putExtra("starter_port", port)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.demo.zt.shell.StellarSettings.init(this)
        AppContextHolder.init(this)
        CrashGuard.install(this)
        val autoRun = intent?.getBooleanExtra(EXTRA_AUTO_RUN, false) ?: false
        // 匿名使用统计：首次报 install，之后每次报 heartbeat；未配置服务器时内部直接返回
        runCatching { com.demo.zt.stats.UsageReporter.reportLaunch(this) }
        // 把开机轨迹并进界面日志：解锁前发生的事（AppLog 只存内存，会随进程丢失）
        runCatching {
            val trace = BootTrace.read(this)
            if (trace.isNotEmpty()) {
                AppLog.add("—— 开机轨迹（落盘，共 ${trace.size} 行）——")
                trace.takeLast(60).forEach { AppLog.add("[开机] $it") }
            }
        }
        setContent {
            MiuixTheme(
                colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Root(autoRunOnStart = autoRun)
            }
        }
    }
}

@Composable
private fun Root(autoRunOnStart: Boolean) {
    val context = LocalContext.current

    // ── UI 状态 ──
    var showMenu by remember { mutableStateOf(false) }
    var confirmMismatch by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    // 「本地 ADB」自检状态
    var checking by remember { mutableStateOf(false) }
    var selfCheck by remember { mutableStateOf<AdbSelfCheck.Result?>(null) }
    var logLines by remember { mutableStateOf(listOf<String>()) }
    val logLock = remember { Any() }

    fun appendLog(line: String) {
        // 必须设上限：之前每来一行就 `logLines = logLines + line`，
        // 跑一次载荷几百行，界面这份会一直长；AppLog 的 800 行上限管不到它。
        synchronized(logLock) {
            logLines = (logLines + line).takeLast(LOG_MAX_LINES)
        }
        // 同步进全局缓冲区，供设置页「导出日志」使用
        AppLog.add(line)
    }

    val verdict = remember { KernelGate.check() }
    val supported = verdict.supported

    // 首次运行引导（不属于导航栈，是前置 gate）
    val onboardingDone = remember {
        context.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
            .getBoolean("done", false)
    }
    var showPerms by remember { mutableStateOf(!onboardingDone) }

    fun finishOnboarding() {
        context.getSharedPreferences("onboarding", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("done", true).apply()
        showPerms = false
    }

    // 启动先补开无线调试，再按原有逻辑自动重连：配对过一次就不再需要配对码。
    LaunchedEffect(Unit) {
        val launchResult = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 先确保权限：配对后我们自己的 shell 通道就能 pm grant
                if (!SecureSettingsGrant.hasPermission(context)) {
                    SecureSettingsGrant.grant(context, AdbHolder.client)
                }
                val shouldApply = WirelessAdbAutoEnabler.shouldApplyOnLaunch(
                    enabled = WirelessAdbAutoEnabler.isEnabled(context),
                    hasPermission = WirelessAdbAutoEnabler.hasPermission(context),
                    current = WirelessAdbAutoEnabler.isWirelessDebuggingOn(context),
                )
                if (shouldApply) WirelessAdbAutoEnabler.applyNow(context, enable = true) else null
            }.getOrElse {
                WirelessAdbAutoEnabler.Result(false, "启动补写异常：${it::class.simpleName}: ${it.message}")
            }
        }
        // 设置读写全在后台；结果回主线程记日志，失败不弹窗，也不阻断重连。
        launchResult?.let { result ->
            appendLog("[无线调试启动自愈] ${if (result.ok) "成功" else "失败"}：${result.message}")
        }
        if (AdbHolder.client == null) {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { AdbAutoConnector.connect(context) }
            }
        }
    }

    // 上次崩溃过就摆到日志面板里，用户可直接截图/复制反馈
    LaunchedEffect(Unit) {
        val trace = CrashGuard.readLast(context)
        if (trace != null) {
            appendLog("[上次崩溃] 请把以下内容反馈给作者：")
            trace.lineSequence().take(40).forEach { line -> appendLog("  " + line) }
            CrashGuard.clear(context)
        }
    }

    // ── 导航栈（原版 NavDisplay）──
    val backStack = remember { navBackStackOf(StaticLogScreen.Main) }
    val swipeBack = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        NavSwipeDirection.RightToLeft
    } else {
        NavSwipeDirection.LeftToRight
    }

    fun navigateTo(screen: StaticLogScreen) {
        if (backStack.lastOrNull() != screen) backStack.add(screen)
    }

    /**
     * 点「执行」。
     *
     * **整个过程都在工作线程**：没连接时的自动连接是阻塞的
     * （`AdbAutoConnector.connect` 内部 `latch.await(15s)`），
     * 直接在 onClick 里调它会卡住主线程，超过 5 秒就是 ANR。
     * 之前就是这么写的——只在「本地 ADB」按钮上修过，这里漏了。
     */
    fun runPayload() {
        if (running) return                       // 防重复点击
        running = true

        // 变体判定要用界面状态，先在主线程取好再进后台
        val picked = Variant.ordered[selected]
        val decision = if (picked == Variant.AUTO) {
            PayloadResolver.decide(context)
        } else {
            PayloadDecision(picked, "手动选择：${picked.shortName}", "手动选择", true)
        }
        val variant = decision.variant
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        thread(isDaemon = true) {
            try {
                var client = AdbHolder.client
                if (client == null) {
                    appendLog("[连接] 还没有连接，正在自动连接…")
                    runCatching { AdbAutoConnector.connect(context) { line -> appendLog(line) } }
                        .onFailure { appendLog("[连接] 自动连接异常：${it::class.simpleName}: ${it.message}") }
                    client = AdbHolder.client
                }
                if (client == null) {
                    appendLog("[连接] 未能连接，已跳到配对页")
                    main.post {
                        running = false
                        navigateTo(StaticLogScreen.Starter)
                    }
                    return@thread
                }

                appendLog("[开始] 载荷 ${variant.shortName}｜${decision.reason}")
                val r = com.demo.zt.shell.PayloadRunner.run(context, client, variant) { line ->
                    appendLog(line)
                }
                appendLog(
                    if (r.ok) "[完成] ${r.reason}（标志 ${r.marker}）"
                    else "[失败] ${r.reason}"
                )
                if (r.ok) {
                    // 提权成功后解锁屏幕并打开 root 管理器（需要 root，走 zt_su）
                    val post = PostEscalation.unlockAndOpenManager(context, client)
                    appendLog("[收尾] ${post.message}")
                    // 匿名统计：只报"成功了一次"，不带任何设备标识之外的内容
                    com.demo.zt.stats.UsageReporter.report(context, com.demo.zt.stats.UsageReporter.EVENT_ESCALATE)
                }
            } catch (e: Throwable) {
                // 类型 + 信息都留下：只有 message 常常是空的，排查时等于没有
                appendLog("[异常] ${e::class.simpleName}: ${e.message}")
                android.util.Log.e("MainActivity", "执行载荷失败", e)
            } finally {
                main.post { running = false }
            }
        }
    }

    /**
     * 返回上一页。
     *
     * 注意：这里**不做任何自动执行**。配对页返回只代表「连上了」，
     * 是否跑载荷由用户点「执行」决定（或由开机自启在明确的条件下触发）。
     * 曾在这里挂过「配对回来就自动提权」，实测体验错误，已移除。
     */
    /** 需要连接时先取现成的，没有就主动连一次（单飞 + 重试，见 AdbConnectionManager）。 */
    fun ensureClient(): com.demo.zt.adb.AdbClient? =
        AdbConnectionManager.clientOrConnect(context)

    fun popBack() {
        if (backStack.size <= 1) return
        val leavingStarter = backStack.lastOrNull() == StaticLogScreen.Starter
        backStack.removeAt(backStack.lastIndex)

        // 配对页的连接是它自己内部用的、用完即关；这里补一次正式连接，
        // 否则回到主界面后 AdbHolder 仍为空，各处都会提示「尚未连接 ADB」。
        if (leavingStarter) {
            thread(isDaemon = true) {
                val ok = AdbConnectionManager.connectBlocking(context, 30_000L)
                appendLog(
                    if (ok) "[连接] 已建立 ADB 连接（端口 ${AdbConnectionManager.connectedPort}）"
                    else "[连接] 未能建立：${AdbConnectionManager.lastError ?: "未知原因"}"
                )
            }
        }
    }

    // 内核不支持时**不再**自动关闭「开机自动提权」。
    // 以前这里会 disable：只要有一次判定为不支持（曾经因为判据 bug 会误判），
    // 自启就被永久关掉，用户以为「开关开着但其实早被关了」。
    // 现在只记一条日志，让用户自己决定。
    if (!supported && autoRunOnStart) {
        LaunchedEffect(Unit) {
            android.util.Log.w("MainActivity", "开机触发时内核判定不支持：${verdict.reason}")
        }
    }

    if (!supported && confirmMismatch) {
        CountdownDialog(
            title = context.getString(R.string.dlg_kernel_title),
            message = context.getString(R.string.dlg_kernel_body),
            onConfirm = { confirmMismatch = false },
            onDismiss = { confirmMismatch = false },
        )
    }

    if (showPerms) {
        com.demo.zt.ui.GhostTheme {
            com.demo.zt.ui.NecessaryPermissionsScreen(
                onGranted = { finishOnboarding() },
                onExit = { finishOnboarding() },
            )
        }
        return
    }

    // 「自动」的判定依据要能在界面上看到，否则无法确认识别是否生效。
    // 先算判定，再据此生成标签 —— 两者必须同源，不能各算一次。
    val autoDecision = remember(selected) {
        if (Variant.ordered[selected] == Variant.AUTO) {
            runCatching { PayloadResolver.decide(context) }.getOrNull()
        } else null
    }

    val variants = remember(autoDecision) {
        Variant.ordered.map { it.displayLabel(autoDecision) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                effects = NavDisplayEffects(
                    cornerClipRadius = rememberNavSystemCornerRadius(),
                ),
                onBack = {
                    when {
                        showMenu -> showMenu = false
                        else -> popBack()
                    }
                },
            ) {
                entry<StaticLogScreen.Main>(swipeDismiss = NavSwipeDirection.None) {
                    MainScreen(
                        supported = supported,
                        unsupportedReason = if (supported) null else verdict.reason,
                        variants = variants,
                        variantSummary = autoDecision?.summary,
                        selected = selected,
                        onSelect = { selected = it },
                        running = running,
                        logLines = logLines,
                        menuExpanded = showMenu,
                        onToggleMenu = { showMenu = it },
                        onOpenAbout = { navigateTo(StaticLogScreen.About) },
                        onOpenLocalAdb = {
                            // checking 之前只写不读：既没有「进行中」提示，也挡不住连点。
                            // 这里既做门禁，又通过 adbChecking 渲染到界面上。
                            if (checking) {
                                appendLog("[本地 ADB] 自检还在进行，请稍候")
                            } else {
                            // 点击必须立刻有反馈：先写日志，再做耗时操作
                            checking = true
                            val main = android.os.Handler(android.os.Looper.getMainLooper())
                            val existing = AdbHolder.client

                            if (existing == null) {
                                // 没连接就马上进配对页，别让用户等
                                appendLog("[本地 ADB] 当前没有连接，正在进入配对页…")
                                checking = false
                                navigateTo(StaticLogScreen.Starter)
                                // 后台同时尝试自动重连；成功时日志会显示结果
                                thread(isDaemon = true) {
                                    val ok = AdbConnectionManager.connectBlocking(context, 20_000L)
                                    main.post {
                                        appendLog(
                                            if (ok) "[连接] 已自动连上（端口 ${AdbConnectionManager.connectedPort}）"
                                            else "[连接] 自动重连失败：${AdbConnectionManager.lastError ?: "未知原因"}"
                                        )
                                    }
                                }
                            } else {
                                thread(isDaemon = true) {
                                    val r = AdbSelfCheck.run(existing)
                                    main.post {
                                        selfCheck = r
                                        checking = false
                                        appendLog("[自检] ${r.message}")
                                        r.raw.takeIf { it.isNotBlank() }?.let { raw ->
                                            appendLog("[自检] $raw")
                                        }
                                    }
                                }
                            }
                            }
                        },
                        onRun = { if (!supported) confirmMismatch = true else runPayload() },
                        onLog = { appendLog(it) },
                        adbChecking = checking,
                    )
                }

                entry<StaticLogScreen.Starter>(swipeDismiss = swipeBack) {
                    com.demo.zt.ui.GhostTheme {
                        com.demo.zt.ui.StarterScreen(
                            isRoot = false,
                            host = "127.0.0.1",
                            port = 0,
                            onClose = { popBack() },
                        )
                    }
                }

                entry<StaticLogScreen.About>(swipeDismiss = swipeBack) {
                    AboutScreen(onBack = { popBack() })
                }
            }

        // 自检结果弹窗
        selfCheck?.let { result ->
            OverlayDialog(
                show = true,
                title = "ADB 自检",
                onDismissRequest = { selfCheck = null },
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = result.message,
                            fontSize = 15.sp,
                            color = if (result.channelOk) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.error
                            },
                        )
                        Text(
                            text = "期望 uid 2000（shell）",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (result.raw.isNotBlank()) {
                            Text(
                                text = "id → ${result.raw}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(
                                text = "关闭",
                                onClick = { selfCheck = null },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                },
            )
        }
        }
    }

    if (autoRunOnStart && supported) {
        LaunchedEffect(Unit) { runPayload() }
    }
}

/** 主界面。结构与 GhostLock 原版 MainScreen 一致：Scaffold + TopAppBar + LazyColumn。 */
@Composable
private fun MainScreen(
    supported: Boolean,
    /** 内核判定失败时的具体原因；支持时传 null。 */
    unsupportedReason: String?,
    variants: List<String>,
    variantSummary: String?,
    selected: Int,
    onSelect: (Int) -> Unit,
    running: Boolean,
    logLines: List<String>,
    menuExpanded: Boolean,
    onToggleMenu: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLocalAdb: () -> Unit,
    onRun: () -> Unit,
    /** 写一条日志到主界面日志面板 + 全局缓冲区。 */
    onLog: (String) -> Unit,
    /** 「本地 ADB」自检是否进行中；true 时入口给出可见反馈。 */
    adbChecking: Boolean = false,
) {
    val scrollBehavior = rememberScrollBehavior()

    Scaffold(topBar = {
        TitleBar(
            scrollBehavior = scrollBehavior,
            menuExpanded = menuExpanded,
            onToggleMenu = onToggleMenu,
            onOpenAbout = onOpenAbout,
        )
    }) { padding ->
        // 原版 MainContent：挂 scrollEndHaptic + overScrollVertical + nestedScroll，
        // 这是"手感像不像原版"的主要来源（滚动回弹、末端触感、顶栏折叠）。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                ActivationStatusCard(
                    supported = supported,
                    summaryRes = if (supported) R.string.kernel_profile_ready
                    else R.string.kernel_unsupported_summary,
                    onCardClick = { },
                    // 判定失败时显示具体原因（含内核原文），否则用户无法排查
                    summaryText = unsupportedReason,
                )
            }
            item(key = "device") {
                DeviceInfoCard(
                    deviceName = DeviceInfo.deviceName,
                    socName = DeviceInfo.socName,
                    kernelRelease = DeviceInfo.kernelRelease,
                    memoryText = DeviceInfo.memoryText,
                )
            }
            item(key = "controls") {
                Card {
                    Column {
                        VariantSpinner(variants, selected, onSelect, summary = variantSummary)
                        // 原先「安全模式 / 禁用所有模块」的位置：那个开关是死代码，已删；
                        // 现在换成一个真有动作的入口——加载内核模块拔掉 vivo vr.ko 的
                        // sys_exit 探针（提权后被它杀掉的就是"刚拿到 root 的进程"）。
                        ProbeRemoveEntry(log = onLog)
                        EntryRow(
                            titleRes = R.string.adb_label,
                            summaryRes = R.string.adb_summary,
                            onClick = onOpenLocalAdb,
                        )
                        if (adbChecking) {
                            Text(
                                text = "正在自检 ADB…",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                            )
                        }
                    }
                }
            }
            item(key = "run") {
                RunButton(
                    running = running,
                    labelRes = R.string.action_run,
                    onClick = onRun,
                )
            }
            if (logLines.isNotEmpty()) {
                item(key = "log") {
                    LogPanel(
                        lines = logLines,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp),
                    )
                }
            }
        }
    }
}

/**
 * 顶栏：单行布局 —— 左侧标题、右侧齿轮。
 *
 * Miuix 的 `SmallTopAppBar` 把 title 固定居中，且没有 titleAlign 参数。
 * 这里把标题放进左侧 `navigationIcon` 槽（该槽随内容左对齐），
 * title 传空串，从而得到"标题左对齐 + 右侧齿轮"的原版观感。
 */
@Composable
private fun TitleBar(
    scrollBehavior: ScrollBehavior,
    menuExpanded: Boolean,
    onToggleMenu: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
) {
    SmallTopAppBar(
        title = "",
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            Text(
                text = "StaticLog",
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackground,
            )
        },
        actions = {
            Box {
                IconButton(onClick = { onToggleMenu(true) }) {
                    Icon(
                        imageVector = MiuixIcons.Settings,
                        contentDescription = stringResource(R.string.menu_title),
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
                SettingsMenu(
                    expanded = menuExpanded,
                    onDismiss = { onToggleMenu(false) },
                    onOpenAbout = onOpenAbout,
                )
            }
        },
    )
}

/** 原版 MainScreen 的顶栏折叠行为。 */
@Composable
private fun rememberScrollBehavior(): ScrollBehavior {
    val state = rememberTopAppBarState()
    return MiuixScrollBehavior(state)
}

/** 内核不匹配时的 10 秒冷静期。用原版 OverlayDialog，不再用 Material3 AlertDialog。 */
@Composable
fun CountdownDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(KernelGate.HOLD_SECONDS) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
    }
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = message,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = ctx.getString(R.string.dlg_kernel_wait, remaining),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = ctx.getString(R.string.dlg_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = ctx.getString(R.string.dlg_continue),
                        onClick = onConfirm,
                        enabled = remaining <= 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}
