package com.demo.zt.shell

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.demo.zt.Variant
import kotlin.concurrent.thread

/**
 * 原版 PrivilegeEscalator 的外部 API 保持不变（stage / result / 四个入口方法），
 * 内部改为执行我们内置的四套 zt 载荷。
 *
 * 关键修正：载荷必须先推送到 /data/local/tmp 再执行。之前的实现直接把
 * `payload.command` 丢给 shell，文件从未上机，真机上必然 `No such file or directory`。
 */
class PrivilegeEscalator(
    private val context: Context,
    private val shell: StellarShellController,
) : AutoCloseable {

    var stage by mutableStateOf(EscalationStage.Idle)
        private set

    var result by mutableStateOf<EscalationResult>(EscalationResult.Idle)
        private set

    private fun log(text: String, isError: Boolean = false) {
        shell.appendExternal(text, isError)
    }

    /** 选择内置载荷后开始提权。 */
    fun startWithBundledPayload(payload: BundledPayload) {
        if (stage !in setOf(EscalationStage.Idle, EscalationStage.Done)) return

        val variant = Variant.byAssetKey(payload.library)
        if (variant == null) {
            result = EscalationResult.Failure(stage, "未知载荷：${payload.library}")
            return
        }
        val client = shell.clientOrNull()
        if (client == null) {
            result = EscalationResult.Failure(stage, "ADB 通道未建立，请先完成配对")
            return
        }

        thread(isDaemon = true) {
            try {
                stage = EscalationStage.CheckingPermission
                log("[提权] 载荷：${payload.displayName}（${variant.assetKey}）")

                stage = EscalationStage.InstallingResources
                log("[提权] 推送载荷到 /data/local/tmp ...")

                stage = EscalationStage.ExecutingPayload
                val run = PayloadRunner.run(context, client, variant) { line ->
                    shell.appendExternal(line, line.startsWith("[门禁]") && !line.contains("6.6.127"))
                }

                stage = EscalationStage.CheckingRoot
                result = if (run.ok) {
                    EscalationResult.RootConfirmed(
                        run.log.takeLast(6).joinToString("\n"),
                        "/data/local/tmp/zt_su"
                    )
                } else {
                    EscalationResult.Failure(stage, run.reason)
                }
                stage = EscalationStage.Done
            } catch (e: Throwable) {
                result = EscalationResult.Failure(stage, e.message ?: "失败")
                stage = EscalationStage.Done
            }
        }
    }

    /** 选择本地文件后开始提权（此处按同一流程处理）。 */
    fun startWithLocalPayload(uri: Uri, command: String) {
        val name = uri.lastPathSegment ?: "local"
        startWithBundledPayload(
            BundledPayload(
                library = name,
                vendor = "local",
                displayName = name,
                models = emptyList(),
                kernelVersion = null,
                kernelUnknown = true,
                buildId = "",
                command = command,
                sha256 = "",
                size = 0L,
                source = "local",
            )
        )
    }

    /** 原版用于激活 KernelSU 越狱模式；这里改为 late-load libksud.so。 */
    fun activateJailbreak() {
        thread(isDaemon = true) {
            try {
                stage = EscalationStage.ActivatingJailbreak
                val script = "P=\$(find /data/app -name 'libksud.so' 2>/dev/null | head -n1)\n" +
                    "if [ -z \"\$P\" ]; then echo KSU_SKIP; exit 0; fi\n" +
                    "\"\$P\" late-load --allow-shell --package-name me.weishu.kernelsu 2>&1\n" +
                    "grep -q kernelsu /proc/modules && echo KSU_LOADED=1 || echo KSU_LOADED=0\n"
                val out = shell.runRaw(script)
                log(out.take(600))
                result = if (out.contains("KSU_LOADED=1") || out.contains("KSU_SKIP")) {
                    EscalationResult.JailbreakActivated(out.take(400))
                } else {
                    EscalationResult.JailbreakActivationFailure(out.take(400))
                }
                stage = EscalationStage.Done
            } catch (e: Throwable) {
                result = EscalationResult.JailbreakActivationFailure(e.message ?: "失败")
                stage = EscalationStage.Done
            }
        }
    }

    fun reset() {
        stage = EscalationStage.Idle
        result = EscalationResult.Idle
    }

    override fun close() = Unit
}
