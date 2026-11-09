package com.demo.zt.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.demo.zt.R

internal object NecessaryPermissions {
    private const val QUERY_ALL_PACKAGES_OP = "android:query_all_packages"

    /**
     * 「读取应用列表」是否可用。
     *
     * 重要前提：QUERY_ALL_PACKAGES 是 **normal 保护级别**权限（实测 prot=normal），
     * 安装即授予，**系统设置里没有可切换的开关**。所以用户点「重新检查」跳到
     * 应用详情页找不到这个开关是正常现象，不是未授权。
     *
     * 本函数只回答一件事：现在能否真的枚举到应用列表。判据只用
     * getInstalledApplications() 的实际结果——不依赖 /data/app 目录
     * （该目录在 Android 12+ 常因 SELinux 不可读，返回 null，不能当成「被拒绝」）。
     */
    fun hasAppListAccess(context: Context): Boolean {
        // Android 11 之前没有包可见性限制
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true

        // appops 显式拒绝才判失败；default/allow 都放行
        val appOpsResult = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(
                    QUERY_ALL_PACKAGES_OP,
                    Process.myUid(),
                    context.packageName,
                )
        }.getOrNull()
        if (appOpsResult == AppOpsManager.MODE_IGNORED ||
            appOpsResult == AppOpsManager.MODE_ERRORED
        ) return false

        // 唯一硬判据：实际能枚举到多少应用
        val count = runCatching {
            context.packageManager.getInstalledApplications(0).size
        }.getOrNull() ?: return false

        // 被限制时系统通常只暴露自己 + 极少数包；放宽到 >1 即认为可用，
        // 避免在真机上因个别 ROM 只返回少量包而误判成未授权
        return count > 1
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = context
            .getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() != false
        return permissionGranted && notificationsEnabled
    }

    /** 电池优化白名单：不加白名单，息屏后开机自启与后台执行很容易被杀。 */
    fun hasBatteryWhitelist(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * 厂商「自启动」是否已放行。
     *
     * **无法可靠查询**：vivo / iQOO 的自启动白名单没有公开 API，
     * 只能靠「开机后真的被拉起过」间接证明。所以这里返回 null 表示未知，
     * 由界面引导用户自己去打开，而不是假装检测到了。
     */
    fun hasAutostartEnabled(context: Context): Boolean? = runCatching {
        // 能读到厂商白名单才有判断依据；读不到就是未知
        val allowed = context.getSharedPreferences("staticlog_boot", Context.MODE_PRIVATE)
            .getBoolean("boot_received", false)
        if (allowed) true else null
    }.getOrNull()

    fun allGranted(context: Context): Boolean =
        hasAppListAccess(context) && hasNotificationAccess(context)
}

@Composable
internal fun NecessaryPermissionsScreen(
    onGranted: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var appListGranted by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
    var batteryGranted by remember { mutableStateOf(false) }
    var autostartUnknown by remember { mutableStateOf(true) }
    var checking by remember { mutableStateOf(true) }
    var showAppListNotice by remember { mutableStateOf(false) }

    fun refresh() {
        appListGranted = NecessaryPermissions.hasAppListAccess(context)
        notificationGranted = NecessaryPermissions.hasNotificationAccess(context)
        batteryGranted = NecessaryPermissions.hasBatteryWhitelist(context)
        autostartUnknown = NecessaryPermissions.hasAutostartEnabled(context) != true
        checking = false
    }

    fun openNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            },
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refresh()
        if (NecessaryPermissions.allGranted(context)) onGranted()
    }

    LaunchedEffect(Unit) {
        refresh()
        if (NecessaryPermissions.allGranted(context)) onGranted()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NecessaryBrandHeader()
            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
                    ) {
                        Text(
                            text = stringResource(R.string.necessary_permissions_badge),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.necessary_permissions_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.necessary_permissions_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(34.dp), strokeWidth = 3.dp)
                        Text(
                            text = stringResource(R.string.necessary_permissions_checking),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        PermissionStatusRow(
                            label = stringResource(R.string.necessary_permission_app_list),
                            granted = appListGranted,
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.necessary_permission_notifications),
                            granted = notificationGranted,
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.necessary_permission_battery),
                            granted = batteryGranted,
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.necessary_permission_autostart),
                            granted = !autostartUnknown,
                        )
                        if (!appListGranted) {
                            Text(
                                text = stringResource(R.string.necessary_permission_app_list_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            if (showAppListNotice) {
                                Text(
                                    text = stringResource(R.string.necessary_permission_app_list_recheck),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.primary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(17.dp),
                            onClick = onExit,
                        ) {
                            Text(
                                stringResource(R.string.necessary_permissions_skip),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Button(
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            enabled = !checking,
                            shape = RoundedCornerShape(17.dp),
                            onClick = {
                                val appListReady = NecessaryPermissions.hasAppListAccess(context)
                                val notificationReady = NecessaryPermissions.hasNotificationAccess(context)
                                appListGranted = appListReady
                                notificationGranted = notificationReady
                                checking = false
                                when {
                                    !notificationReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    !notificationReady -> openNotificationSettings()
                                    // 电池优化白名单：不加白名单，息屏/重启后很容易被厂商杀后台
                                    !NecessaryPermissions.hasBatteryWhitelist(context) ->
                                        AutostartHelper.requestIgnoreBatteryOptimization(context)
                                    // 厂商自启动没有可申请的 API，只能把人带到正确页面
                                    NecessaryPermissions.hasAutostartEnabled(context) != true ->
                                        AutostartHelper.open(context)
                                    // 应用列表是 normal 权限，设置里没有开关；
                                    // 不再把用户扔进应用详情页，只如实提示并允许跳过
                                    !appListReady -> showAppListNotice = true
                                    else -> onGranted()
                                }
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.necessary_permissions_retry),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.necessary_permissions_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NecessaryBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(40.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        SharedDeviceInfoButton(compact = true)
    }
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Text(
                    text = stringResource(
                        if (granted) R.string.necessary_permission_granted
                        else R.string.necessary_permission_missing,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) colors.primary else colors.error,
                )
            }
            Surface(
                shape = CircleShape,
                color = if (granted) colors.primaryContainer else colors.errorContainer,
            ) {
                Text(
                    text = if (granted) "✓" else "!",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (granted) colors.onPrimaryContainer else colors.onErrorContainer,
                )
            }
        }
    }
}
