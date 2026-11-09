package com.demo.zt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 把 GhostLock 的配色映射成 Material3 ColorScheme。
 *
 * 月虹移植过来的页面（权限引导 / ADB 终端）内部用的是 MaterialTheme.colorScheme，
 * 这里统一换成 GhostLock 的色板，视觉才能和主界面一致。
 *
 * 深色（取自 GhostLock-App colors.xml + 状态卡实测）：
 *   背景 #000000 / 卡片 #1C1C1E / 强调 #2563EB
 *   成功 #36D167 / 警示 #F5A623 / 错误 #F72727
 * 浅色：
 *   背景 #F7F8FA / 卡片 #FFFFFF / 强调 #2563EB
 */
private val GhostDark = darkColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3825),
    onPrimaryContainer = Color(0xFF36D167),
    secondary = Color(0xFF36D167),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1A3825),
    onSecondaryContainer = Color(0xFF36D167),
    tertiary = Color(0xFFF5A623),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3E2F1B),
    onTertiaryContainer = Color(0xFFF5A623),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xB3FFFFFF),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF3A3A3C),
    error = Color(0xFFF72727),
    onError = Color.White,
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFB4AB),
    scrim = Color(0xCC000000)
)

private val GhostLight = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFFAE4),
    onPrimaryContainer = Color(0xFF059669),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFFAE4),
    onSecondaryContainer = Color(0xFF059669),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF0DB),
    onTertiaryContainer = Color(0xFFD97706),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F5),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F3F5),
    surfaceContainerHighest = Color(0xFFE5E7EB),
    outline = Color(0xFFE5E7EB),
    outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEF2F2),
    onErrorContainer = Color(0xFFDC2626),
    scrim = Color(0x99000000)
)

/** 用 GhostLock 配色包裹 content，跟随系统深色/浅色。 */
@Composable
fun GhostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) GhostDark else GhostLight,
        content = content
    )
}