package com.demo.zt

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 以下组件逐字移植自 GhostLock-App / app / src / main / kotlin / com / ghostlock / app / ui / GhostlockUI.kt
 * 仅把状态来源换成我们自己的字段，视觉与交互保持原样。
 */

/** 原版 ActivationStatusCard（GhostlockUI.kt L913-1022）。 */
@Composable
fun ActivationStatusCard(
    supported: Boolean,
    summaryRes: Int,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非空时优先于 summaryRes，用于展示判定失败的具体原因。 */
    summaryText: String? = null,
) {
    val title = if (supported) R.string.kernel_supported else R.string.kernel_unsupported
    val icon = if (supported) Icons.Rounded.CheckCircleOutline else Icons.Rounded.RemoveCircleOutline
    val backgroundColor = when {
        supported -> when {
            MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
            isSystemInDarkTheme() -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }
        else -> MiuixTheme.colorScheme.errorContainer
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = backgroundColor),
        onClick = onCardClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (MiuixTheme.isDynamicColor) {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                        else -> MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                    }
                } else {
                    when (icon) {
                        Icons.Rounded.CheckCircleOutline -> Color(0xFF36D167)
                        else -> Color(0xFFF5A623)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 120.dp, bottom = 14.dp),
            ) {
                Text(
                    text = stringResource(title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = summaryText ?: stringResource(summaryRes),
                    fontSize = 15.sp,
                )
            }
        }
    }
}

/** 原版 DeviceInfoCard + DeviceInfoItem（GhostlockUI.kt L1023-1090）。 */
@Composable
fun DeviceInfoCard(
    deviceName: String,
    socName: String,
    kernelRelease: String,
    memoryText: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DeviceInfoItem(title = stringResource(R.string.device_label), value = deviceName)
                DeviceInfoItem(title = stringResource(R.string.soc_label), value = socName)
                DeviceInfoItem(title = stringResource(R.string.kernel_label), value = kernelRelease)
                // 内存规格：自动选 16G / 12G 载荷的依据，显示出来才能核验
                if (memoryText != null) {
                    DeviceInfoItem(title = stringResource(R.string.memory_label), value = memoryText)
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoItem(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
    }
}

/** 原版 RunButton（GhostlockUI.kt L1119-1147）。 */
@Composable
fun RunButton(
    running: Boolean,
    labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TextButton(
            text = stringResource(if (running) R.string.action_running else labelRes),
            enabled = !running,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 原版 LogPanel（GhostlockUI.kt L1149-1185）。 */
@Composable
fun LogPanel(lines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.trimEnd('\r', '\n'),
                        color = Color(0xFFD1D5DB),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/** 载荷版本选择：直接用原版的 OverlaySpinnerPreference（即「CPU 核心组」那一行）。 */
@Composable
fun VariantSpinner(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    /** 「自动」时显示判定依据；手动选择时可为 null。 */
    summary: String? = null,
) {
    OverlaySpinnerPreference(
        title = stringResource(R.string.variant_label),
        items = labels.map { DropdownItem(icon = null, title = it) },
        selectedIndex = selectedIndex,
        summary = summary,
        showValue = true,
        onSelectedIndexChange = onSelected,
    )
}

/** 底部入口行：原版 ArrowPreference。 */
@Composable
fun EntryRow(titleRes: Int, summaryRes: Int, onClick: () -> Unit) {
    ArrowPreference(
        title = stringResource(titleRes),
        summary = stringResource(summaryRes),
        onClick = onClick,
    )
}

/** 开关行：原版 SwitchPreference。 */
@Composable
fun ToggleRow(titleRes: Int, summaryRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChange,
        title = stringResource(titleRes),
        summary = stringResource(summaryRes),
    )
}