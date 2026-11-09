package com.demo.zt

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 拔探针的运行状态。
 *
 * 放在 object 里而不是 `remember`：入口位于 LazyColumn 的 item 中，
 * 划出屏幕就会被销毁，局部 `remember` 随之丢失——表现是任务还在跑、
 * 界面却显示「没在跑」，回来再点还会并发起第二个任务。
 * 状态挂在进程级对象上，重建/滚出都不会丢。
 */
object ProbeRemoveState {
    /** 是否正在执行。 */
    val busy = mutableStateOf(false)

    /** 上一次的结论，null 表示没有未读结果。 */
    val result = mutableStateOf<String?>(null)
}

/**
 * 「拔除 KO 探针」入口。
 *
 * 位置就是原先「安全模式 / 禁用所有模块」开关那一格（主界面控制卡里、
 * 变体选择器与「本地 ADB」之间）。那个开关是死代码已删，这里换成真有动作的行。
 *
 * 交互刻意做了二次确认：这一步会改内核内存，误触不会造成不可逆后果
 * （重启即恢复），但也不该一键就跑。
 */
@Composable
fun ProbeRemoveEntry(log: (String) -> Unit) {
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    val busy by ProbeRemoveState.busy
    val resultMsg by ProbeRemoveState.result

    ArrowPreference(
        title = "拔除 KO 探针",
        summary = when {
            busy -> "正在加载模块并写入内核…"
            else -> "关掉 vivo vr.ko 的 sys_exit 探针（提权后点；重启自动恢复）"
        },
        onClick = {
            // 已经在跑就不再起第二个：这里既做门禁也做提示
            if (busy) {
                log("[探针] 正在执行中，请等待当前任务结束")
            } else {
                showConfirm = true
            }
        },
    )

    OverlayDialog(
        show = showConfirm,
        title = "拔除 KO 探针",
        onDismissRequest = { showConfirm = false },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "会加载一个内核模块，把 __tracepoint_sys_exit->funcs 置空，" +
                        "让 vivo vr.ko 不再在 sys_exit 上杀掉刚拿到 root 的进程。\n\n" +
                        "前提：已经提权（zt_su 可用）。\n" +
                        "影响：只改内核内存数据，重启后自动恢复，不需要卸载。\n" +
                        "失败时不会假装成功——内核给出的原因会原样写进日志。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "取消",
                        onClick = { showConfirm = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = "确认拔除",
                        onClick = {
                            showConfirm = false
                            ProbeRemoveState.busy.value = true
                            val main = Handler(Looper.getMainLooper())
                            val kernel = DeviceInfo.kernelRelease
                            val androidProp = DeviceInfo.androidReleaseNumber()
                            log("[探针] 开始：$kernel")
                            thread(isDaemon = true) {
                                val r = ProbeRemover.remove(
                                    context = context,
                                    client = AdbHolder.client,
                                    kernelRelease = kernel,
                                    androidProp = androidProp,
                                    log = log,
                                )
                                main.post {
                                    ProbeRemoveState.busy.value = false
                                    ProbeRemoveState.result.value = r.message
                                    Toast.makeText(
                                        context,
                                        if (r.ok) "探针已拔除" else "拔除失败，看日志",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        },
    )

    resultMsg?.let { msg ->
        OverlayDialog(
            show = true,
            title = "拔除 KO 探针",
            onDismissRequest = { ProbeRemoveState.result.value = null },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = msg, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(
                            text = "知道了",
                            onClick = { ProbeRemoveState.result.value = null },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
            },
        )
    }
}
