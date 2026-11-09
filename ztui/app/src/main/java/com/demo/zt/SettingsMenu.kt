package com.demo.zt

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 右上角小选项面板。
 *
 * 全部改用 GhostLock 原版那套 Miuix 组件：
 *   OverlayDialog（原版 GhostlockDialog 用的就是它）+ Card
 *   + SwitchPreference / ArrowPreference
 *
 * 之前是 Material3 的 DropdownMenu + AlertDialog + Switch，
 * 与主界面风格不一致（用户反馈"点进去的页面还是你自己做的"）。
 */
@Composable
fun SettingsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AutoBoot.isEnabled(context)) }
    var count by remember { mutableIntStateOf(AutoBoot.runCount(context)) }
    var showWarning by remember { mutableStateOf(false) }
    var showNeedWireless by remember { mutableStateOf(false) }
    var warnRemaining by remember { mutableIntStateOf(10) }
    var showBlocked by remember { mutableStateOf(false) }
    var showVersionDiag by remember { mutableStateOf(false) }
    var bootAdbMsg by remember { mutableStateOf<String?>(null) }
    var autoAdbEnabled by remember { mutableStateOf(WirelessAdbAutoEnabler.isEnabled(context)) }
    var debugMsg by remember { mutableStateOf<String?>(null) }
    var autoAdbMsg by remember { mutableStateOf<String?>(null) }
    var bootAdbBusy by remember { mutableStateOf(false) }
    var statsEnabled by remember { mutableStateOf(com.demo.zt.stats.UsageReporter.isEnabled(context)) }
    var persistentRoot by remember(expanded) { mutableStateOf<Boolean?>(null) }
    var persistentRootChecked by remember(expanded) { mutableStateOf(false) }
    var versionReadings by remember { mutableStateOf<List<SoftwareVersion.Reading>>(emptyList()) }
    var detectionDetail by remember { mutableStateOf("") }
    val versionSummary = remember {
        val v = SoftwareVersion.read()
        val fam = v?.family
        when {
            v == null -> "未读到版本号"
            fam == null -> "读到 ${v.major ?: "?"}（不认识）"
            else -> "${v.major} → ${fam.name}"
        }
    }
    val supported = remember { KernelGate.check().supported }

    // 每次打开面板重新探测；探测期间隐藏该行，避免展示过期结果或阻塞界面。
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching { BootAdbEnabler.hasPersistentRootServiceDir(AdbHolder.client) }.getOrNull()
        }
        // 回到主线程后再写 Compose 状态，确保触发重组。
        persistentRoot = result
        persistentRootChecked = true
    }

    OverlayDialog(
        show = expanded,
        title = "设置",
        onDismissRequest = onDismiss,
        content = {
            // 必须可滚动：条目多了以后底部会超出屏幕，导致「点不动」
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card {
                    Column {
                        SwitchPreference(
                            checked = enabled,
                            // 内核不匹配时**直接禁用**，不只是弹窗提示
                            enabled = supported,
                            onCheckedChange = { want ->
                                when {
                                    !want -> AutoBoot.disable(context)
                                    !supported -> showBlocked = true
                                    // 前置条件：必须先开启无线调试自启，否则开机没端口可连
                                    !WirelessAdbAutoEnabler.isEnabled(context) -> showNeedWireless = true
                                    else -> {
                                        warnRemaining = 10
                                        showWarning = true
                                    }
                                }
                                // 关键：状态以存储为准，切换后回读一次，
                                // 否则组件显示和实际存储会脱节，现象就是「关不掉」。
                                enabled = AutoBoot.isEnabled(context)
                                android.util.Log.i(
                                    "SettingsMenu",
                                    "开机自动提权 请求=" + want + " 实际=" + enabled,
                                )
                            },
                            title = "开机自动提权",
                            summary = when {
                                !supported -> "内核不匹配，此项已禁用"
                                !WirelessAdbAutoEnabler.isEnabled(context) ->
                                    "需先开启「重启后自动开无线调试」"
                                else -> "开机后自动跑一次提权流程（最多 30 次）"
                            },
                        )
                        SwitchPreference(
                            checked = autoAdbEnabled,
                            onCheckedChange = { want ->
                                if (want) {
                                    // 没有权限先给授权命令，不要假装打开了
                                    // 开关是「设置」，不该被连接状态卡住：
                                    // 先把它存下来，授权在连接可用时补（启动自愈也会补）。
                                    // 这样即使此刻没连上 ADB，用户也能打开它。
                                    WirelessAdbAutoEnabler.setEnabled(context, true)
                                    autoAdbEnabled = true
                                    autoAdbMsg = "已开启。正在连接 ADB 并申请所需权限…"

                                    if (!WirelessAdbAutoEnabler.hasPermission(context)) {
                                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                                        // 连接最多 30 秒，绝不能放主线程
                                        thread(isDaemon = true) {
                                            val client = AdbConnectionManager.clientOrConnect(context, 30_000L)
                                            val grantMsg: String
                                            val appliedMsg: String?
                                            if (client == null) {
                                                grantMsg = "暂时没有 ADB 连接，权限未授予。" +
                                                    "开关已保存，连接成功后会自动授予。"
                                                appliedMsg = null
                                            } else {
                                                val g = SecureSettingsGrant.grant(context, client)
                                                grantMsg = g.message
                                                appliedMsg = if (g.ok) {
                                                    WirelessAdbAutoEnabler.applyNow(context, enable = true).message
                                                } else {
                                                    null
                                                }
                                            }
                                            main.post {
                                                autoAdbMsg = buildString {
                                                    append(grantMsg)
                                                    if (appliedMsg != null) append("\n").append(appliedMsg)
                                                    if (client == null) {
                                                        append("\n\n也可用电脑执行一次：\n")
                                                        append(WirelessAdbAutoEnabler.GRANT_COMMAND)
                                                    }
                                                }
                                            }
                                        }
                                        return@SwitchPreference
                                    }
                                    val r = WirelessAdbAutoEnabler.applyNow(context, enable = true)
                                    autoAdbMsg = r.message
                                    if (r.ok) {
                                        WirelessAdbAutoEnabler.setEnabled(context, true)
                                    }
                                    autoAdbEnabled = WirelessAdbAutoEnabler.isEnabled(context)
                                } else {
                                    WirelessAdbAutoEnabler.setEnabled(context, false)
                                    val r = WirelessAdbAutoEnabler.applyNow(context, enable = false)
                                    autoAdbEnabled = WirelessAdbAutoEnabler.isEnabled(context)
                                    autoAdbMsg = r.message
                                }
                            },
                            title = "重启后自动开无线调试（免 root）",
                            summary = if (WirelessAdbAutoEnabler.hasPermission(context)) {
                                "一次性授权即可：开机自动把系统无线调试开关打开（会弹网络授权）"
                            } else {
                                "需一次性 ADB 授权（点开有命令），授权后开机自动打开无线调试"
                            },
                        )
                        // 明确没有持久 root 时完全隐藏；未知时保留入口并标明探测结论。
                        if (persistentRootChecked && persistentRoot != false) {
                            ArrowPreference(
                                title = "开机直接开网络 ADB 端口（需持久 root）",
                                summary = when {
                                    bootAdbBusy -> "正在检查持久 root 并尝试写入…"
                                    persistentRoot == null ->
                                        "无法确认本机是否有持久 root；仅在已安装 Magisk/KernelSU 时有效，" +
                                            "adbd 固定监听 5555，走明文"
                                    else ->
                                        "仅在设备已安装 Magisk/KernelSU 时有效；靠载荷临时 root 的话重启后不会执行。" +
                                            "adbd 固定监听 5555，走明文"
                                },
                                onClick = {
                                    if (bootAdbBusy) return@ArrowPreference
                                    bootAdbBusy = true
                                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                                    thread(isDaemon = true) {
                                        val r = BootAdbEnabler.install(AdbHolder.client)
                                        main.post {
                                            bootAdbBusy = false
                                            // 成功提示和前置检查的失败原因都原样展示。
                                            bootAdbMsg = r.message
                                        }
                                    }
                                },
                            )
                        }
                        ArrowPreference(
                            title = "运行次数",
                            summary = "超过 ${AutoBoot.MAX_RUNS} 次自动关闭 · 点击清零",
                            endActions = {
                                Text(
                                    text = "$count / ${AutoBoot.MAX_RUNS}",
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (count >= AutoBoot.MAX_RUNS)
                                        MiuixTheme.colorScheme.error
                                    else MiuixTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                AutoBoot.resetCount(context)
                                count = 0
                            },
                        )
                    }
                }
                Card {
                    ArrowPreference(
                        title = "版本诊断",
                        summary = versionSummary,
                        onClick = {
                            versionReadings = SoftwareVersion.probeAll()
                            detectionDetail = buildDetectionDetail()
                            showVersionDiag = true
                        },
                    )
                    ArrowPreference(
                        title = "关于",
                        summary = com.demo.zt.BuildConfig.VERSION_NAME,
                        onClick = {
                            onDismiss()
                            onOpenAbout()
                        },
                    )
                }
                if (com.demo.zt.stats.UsageReporter.isConfigured()) {
                    SmallTitle(text = "隐私")
                    Card {
                        SwitchPreference(
                            checked = statsEnabled,
                            onCheckedChange = { want ->
                                com.demo.zt.stats.UsageReporter.setEnabled(context, want)
                                // 以存储为准回读，避免组件状态与存储脱节
                                statsEnabled = com.demo.zt.stats.UsageReporter.isEnabled(context)
                            },
                            title = "匿名使用统计",
                            summary = if (statsEnabled) {
                                "仅上报版本、机型、Android 版本与内核版本，不采集个人数据"
                            } else {
                                "已关闭，不会向服务器发送任何请求"
                            },
                        )
                    }
                }
                if (com.demo.zt.BuildConfig.DEBUG) {
                    SmallTitle(text = "调试（仅 debug 包可见）")
                    Card {
                        ArrowPreference(
                            title = "复制当前日志",
                            summary = "点一下直接复制，粘贴发给我即可",
                            onClick = {
                                val text = LogExporter.buildText(context, AppLog.snapshot())
                                val ok = LogExporter.copyToClipboard(context, text)
                                val msg = if (ok) {
                                    "已复制 ${text.length} 字符"
                                } else {
                                    "复制失败，请重试"
                                }
                                debugMsg = msg
                                android.widget.Toast.makeText(
                                    context,
                                    msg,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                }

                // 连接状态用一行小字显示，不再占一整行条目
                Text(
                    text = if (AdbConnectionManager.isConnected()) {
                        "ADB " + AdbConnectionManager.describe()
                    } else {
                        "ADB 未连接"
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    // 警告 / 阻止 也用原版 OverlayDialog
    // 10 秒倒计时：给用户足够时间意识到「可能反复重启」
    if (showWarning) {
        LaunchedEffect(showWarning) {
            warnRemaining = 10
            while (warnRemaining > 0) {
                kotlinx.coroutines.delay(1000)
                warnRemaining--
            }
        }
    }

    OverlayDialog(
        show = showWarning,
        title = context.getString(R.string.autoboot_warn_title),
        onDismissRequest = { showWarning = false },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = context.getString(R.string.autoboot_warn_body),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                // 10 秒冷静期：必须等完才能确认，避免误触后设备反复重启
                Text(
                    text = if (warnRemaining > 0) "请等待 ${warnRemaining} 秒后可确认" else "现在可以确认",
                    fontSize = 14.sp,
                    color = if (warnRemaining > 0) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = context.getString(R.string.autoboot_confirm),
                        enabled = warnRemaining <= 0,
                        onClick = {
                            showWarning = false
                            AutoBoot.enable(context)
                            enabled = AutoBoot.isEnabled(context)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    // 前置条件未满足：没开无线调试自启，开机就没端口可连
    OverlayDialog(
        show = showNeedWireless,
        title = "还需要一步",
        onDismissRequest = { showNeedWireless = false },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "开机自动提权需要先有 ADB 连接。\n" +
                        "请先打开「重启后自动开无线调试（免 root）」并完成授权，" +
                        "否则开机时没有端口可连，提权会直接失败。",
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "知道了",
                        onClick = { showNeedWireless = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    OverlayDialog(
        show = showBlocked,
        title = context.getString(R.string.autoboot_blocked_title),
        onDismissRequest = { showBlocked = false },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = context.getString(R.string.autoboot_blocked_body),
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = context.getString(R.string.ok),
                        onClick = { showBlocked = false },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    // 自动恢复无线调试的提示（含授权命令）
    autoAdbMsg?.let { msg ->
        OverlayDialog(
            show = true,
            title = "重启后自动开无线调试（免 root）",
            onDismissRequest = { autoAdbMsg = null },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(text = msg, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        text = "注意：这是「自动恢复开关」，不是让连接跨重启不断开。" +
                            "部分厂商 ROM 可能拦截，需实机验证。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "关闭",
                            onClick = { autoAdbMsg = null },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            },
        )
    }

    // 开机脚本写入结果
    bootAdbMsg?.let { msg ->
        OverlayDialog(
            show = true,
            title = "开机直接开网络 ADB 端口（需持久 root）",
            onDismissRequest = { bootAdbMsg = null },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(text = msg, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        text = BootAdbEnabler.LIMITATION_NOTE,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "关闭",
                            onClick = { bootAdbMsg = null },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            },
        )
    }

    VersionDiagnosticsDialog(
        show = showVersionDiag,
        readings = versionReadings,
        detail = detectionDetail,
        onDismiss = { showVersionDiag = false },
    )
}

/** 判定链的完整说明，用于诊断弹窗底部。 */
private fun buildDetectionDetail(): String {
    val d = PayloadResolver.decide(AppContextHolder.context ?: return "上下文未初始化")
    return buildString {
        append("判定结果：").append(d.variant.shortName)
        append(if (d.confirmed) "（已确认）" else "（未确认）")
        append('\n').append("依据：").append(d.reason)
    }
}
/**
 * 版本诊断：把每个候选属性的**原始值**列出来。
 *
 * 真机上到底哪个属性装着「软件版本号」，用户看一眼屏幕就能确认，
 * 不需要接 logcat。判据是前两位 16 / 17。
 */
@Composable
private fun VersionDiagnosticsDialog(
    show: Boolean,
    readings: List<SoftwareVersion.Reading>,
    detail: String,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "版本诊断",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "判据：软件版本号前两位 16 = 第6代，17 = 第7代",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (readings.isEmpty()) {
                    Text(
                        text = "所有候选属性都为空——说明这台机器没开放这些属性。",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    readings.forEach { r ->
                        Text(
                            text = "${r.source}  =  ${r.raw}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (r.family != null) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    }
                }
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )
}
