package com.demo.zt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 作者信息。点击打开酷安主页。 */
private const val AUTHOR_NAME = "Mcjdifif"
private const val AUTHOR_COOLAPK_URL = "https://www.coolapk.com/u/33270757"

/** 本项目仓库。 */
private const val PROJECT_GITHUB_URL = "https://github.com/zyyihao5-lang/staticlog"
private const val PROJECT_GITHUB_LABEL = "github.com/zyyihao5-lang/staticlog"

private data class Lib(val name: String, val role: String, val license: String, val url: String)

private val libs = listOf(
    Lib("GhostLock-App", "主界面 / 状态卡 / 信息卡 / 执行按钮 / 偏好行", "Apache-2.0", "https://github.com/YuKongA/ghostlock-app"),
    Lib("月虹提权助手", "无线调试配对五步引导 / 本地 ADB 终端页", "Apache-2.0", "https://github.com/qq2070006042-create/yuehong-privilege-assistant"),
    Lib("Stellar", "ADB 协议（CNXN/AUTH/OPEN/STLS/配对）", "Apache-2.0", "https://github.com/roro2239/Stellar"),
    Lib("libsu", "Root shell 调用", "Apache-2.0", "https://github.com/topjohnwu/libsu"),
    Lib("Miuix", "UI 组件库 Card / Preference / Scaffold", "Apache-2.0", "https://github.com/miuix-kotlin-multiplatform/miuix"),
    Lib("Jetpack Compose", "声明式 UI 框架", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
    Lib("BouncyCastle", "RSA / X.509（ADB 配对）", "MIT", "https://www.bouncycastle.org/"),
    Lib("Shizuku API", "权限声明与 Provider", "Apache-2.0", "https://github.com/RikkaApps/Shizuku-API")
)

/** 关于页。版式对齐 GhostLock 的 AboutScreen。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val uriHandler = LocalUriHandler.current

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "关于",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
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
            item(key = "hero") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(88.dp)
                            .background(
                                MiuixTheme.colorScheme.surface,
                                RoundedCornerShape(22.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "SL",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    MiuixText(
                        text = "StaticLog",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(4.dp))
                    MiuixText(
                        text = "${BuildConfig.VERSION_NAME} · 内置橘子6 / 橘子7 载荷",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            item(key = "author-title") {
                SmallTitle(text = "作者")
            }
            item(key = "author") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = AUTHOR_NAME,
                        summary = "酷安主页 · 点击打开",
                        onClick = { runCatching { uriHandler.openUri(AUTHOR_COOLAPK_URL) } },
                    )
                }
            }

            item(key = "project-title") {
                SmallTitle(text = "本项目")
            }
            item(key = "project") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = "GitHub 开源库",
                        summary = "$PROJECT_GITHUB_LABEL · 点击打开",
                        onClick = { runCatching { uriHandler.openUri(PROJECT_GITHUB_URL) } },
                    )
                }
            }

            item(key = "lib-title") {
                SmallTitle(text = "所用开源库")
            }

            items(libs.size) { index ->
                val lib = libs[index]
                // 原版 AboutLink 的写法：Card + ArrowPreference
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    ArrowPreference(
                        title = lib.name,
                        summary = "${lib.role} · ${lib.license}",
                        onClick = { runCatching { uriHandler.openUri(lib.url) } },
                    )
                }
            }

            item(key = "license") {
                SmallTitle(text = "许可说明")
            }
            item(key = "license-card") {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        MiuixText(
                            text = "以上组件均按其原始许可使用。界面与交互源自 GhostLock-App 与月虹提权助手，ADB 协议实现源自 Stellar。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        }
    }
}
