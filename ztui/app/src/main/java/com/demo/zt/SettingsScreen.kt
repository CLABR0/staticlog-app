package com.demo.zt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demo.zt.ui.AppSpacing
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置页。版式对齐 GhostLock 的 ControlPanel / About：
 * Miuix Scaffold + SmallTopAppBar + 分组 Card + ArrowPreference / SwitchPreference。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AutoBoot.isEnabled(context)) }
    var count by remember { mutableStateOf(AutoBoot.runCount(context)) }
    var showWarning by remember { mutableStateOf(false) }
    var showBlocked by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val supported = remember { KernelGate.check().supported }

    if (showAbout) { AboutScreen(onBack = { showAbout = false }); return }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(context.getString(R.string.autoboot_warn_title)) },
            text = { Text(context.getString(R.string.autoboot_warn_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showWarning = false
                    if (AutoBoot.enable(context)) enabled = true
                }) { Text(context.getString(R.string.autoboot_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) {
                    Text(context.getString(R.string.dlg_cancel))
                }
            }
        )
    }

    if (showBlocked) {
        AlertDialog(
            onDismissRequest = { showBlocked = false },
            title = { Text(context.getString(R.string.autoboot_blocked_title)) },
            text = { Text(context.getString(R.string.autoboot_blocked_body)) },
            confirmButton = {
                TextButton(onClick = { showBlocked = false }) {
                    Text(context.getString(R.string.ok))
                }
            }
        )
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(padding),
            contentPadding = PaddingValues(
                start = AppSpacing.screenHorizontalPadding,
                end = AppSpacing.screenHorizontalPadding,
                top = AppSpacing.topBarContentSpacing,
                bottom = AppSpacing.screenBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sectionGap)
        ) {
            item {
                MiuixText(
                    text = "高级功能",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
                )
            }
            item {
                Card {
                    Column {
                        SwitchPreference(
                            checked = enabled,
                            onCheckedChange = { want ->
                                if (want) {
                                    if (!supported) showBlocked = true else showWarning = true
                                } else {
                                    AutoBoot.disable(context)
                                    enabled = false
                                }
                            },
                            title = "开机自动提权",
                            summary = "开机后自动建立 uid 2000 并执行载荷"
                        )
                        ArrowPreference(
                            title = "运行次数",
                            summary = "$count / ${AutoBoot.MAX_RUNS}（超过自动关闭）",
                            onClick = { AutoBoot.resetCount(context); count = 0 }
                        )
                    }
                }
            }

            item {
                MiuixText(
                    text = "其他",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp)
                )
            }
            item {
                Card {
                    ArrowPreference(
                        title = "关于",
                        summary = "版本信息与所用开源库",
                        onClick = { showAbout = true }
                    )
                }
            }
        }
    }
}