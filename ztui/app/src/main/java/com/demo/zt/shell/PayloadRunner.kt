package com.demo.zt.shell

import android.content.Context
import com.demo.zt.FlowType
import com.demo.zt.Variant
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.runShell

/**
 * 把选中变体的载荷推到 /data/local/tmp 并执行。
 *
 * 流程对照官方 `zt_oneclick.sh`：
 *   1. 内核门禁：`uname -r` 必须包含 6.6.127，否则拒绝（官方脚本同样在此退出）
 *   2. 清理历史残留：`rm -f /data/local/tmp/zt* k* *.log`
 *   3. 用 adb sync 协议推送载荷（等价 `adb push`，不受 shell 长度限制）
 *   4. chmod 755/644
 *   5. 橘子6：写 zt_cmds.txt 后 `./zt_run --run < zt_cmds.txt`
 *      橘子7：带环境变量直接 `./zt_run --run`
 *   6. 解析输出里的成功标志
 */
object PayloadRunner {

    const val TMP = "/data/local/tmp"

    /** 单轮执行超时，官方脚本用 480 秒。 */
    private const val RUN_TIMEOUT_SECONDS = 480

    data class Result(
        val ok: Boolean,
        val reason: String,
        val marker: String?,
        val log: List<String>,
    )

    private const val MARK_READY = "ROOT_SHELL_READY"
    private const val MARK_HOLD = "ROOT_CHILD_HOLD_PASS"
    private const val MARK_ABORT = "IQBIND_ABORT"
    private const val MARK_INSMOD = "KHONE_INSMOD_RC=0"

    private fun executable(name: String) = name == "zt_run" || name == "zt_su"

    /** 读 assets 里某个变体的全部载荷。 */
    private fun loadAssets(context: Context, variant: Variant): List<Pair<String, ByteArray>> =
        variant.files.map { name ->
            name to context.assets.open("variants/${variant.assetKey}/$name").use { it.readBytes() }
        }

    /**
     * 完整执行一次。阻塞调用，请放在后台线程。
     * @param onLog 逐行回调，用于界面日志
     */
    fun run(
        context: Context,
        client: AdbClient,
        variant: Variant,
        onLog: (String) -> Unit = {},
    ): Result {
        val log = mutableListOf<String>()
        fun emit(line: String) {
            log += line
            onLog(line)
        }

        val v = variant.resolve()

        // 1. 内核门禁
        //    shell 读取可能失败（连接失效等），此时**不能**当成内核不匹配，
        //    否则会给出误导性的拒绝理由。回退到本机读取（界面用的就是它）。
        val shellRead = runCatching { client.runShell("uname -r").trim() }
        emit(
            if (shellRead.isSuccess) "[门禁] shell 读取内核：${shellRead.getOrNull()}"
            else "[门禁] shell 读取内核失败：${shellRead.exceptionOrNull()?.message}"
        )

        val kernel = shellRead.getOrNull()?.takeIf { it.isNotBlank() }
            ?: com.demo.zt.DeviceInfo.kernelRelease
        emit("[门禁] 采用内核：$kernel")

        val verdict = com.demo.zt.KernelGate.evaluate(kernel)
        emit("[门禁] 判定：${verdict.reason}")

        if (!verdict.supported) {
            return Result(
                false,
                "内核不匹配：${verdict.reason}；已拒绝，未写入任何文件",
                null,
                log,
            )
        }

        // 1b. root 管理器门禁：橘子6 要 KernelSU 系，橘子7 要 SukiSU Ultra。
        //     必须在推文件之前判——缺管理器时推了也没用，还会在 /data/local/tmp 留垃圾。
        val probe = com.demo.zt.RootManagerGate.probe(runShell = { cmd -> client.runShell(cmd) })
        emit("[管理器门禁] ${probe.summary()}")
        val gate = com.demo.zt.RootManagerGate.evaluate(v.flow, probe.raw)
        emit("[管理器门禁] ${gate.message}")
        if (!gate.ok) {
            return Result(false, gate.message, null, log)
        }
        if (!gate.verified) {
            // 读不到列表时不拦，但要留痕：载荷本身还会再验一次 root，
            // 这里如实标成"未确认"，而不是假装已经确认。
            emit("[管理器门禁] ⚠ 未能确认管理器，按已安装继续（结果见下方复核）")
        }

        // 2. 清理历史残留
        runCatching { client.runShell("cd $TMP 2>/dev/null && rm -f zt* k* *.log 2>/dev/null; true") }

        // 3. 推送载荷
        val assets = loadAssets(context, v)
        for ((name, bytes) in assets) {
            emit("[推送] $name (${bytes.size} 字节)")
            client.pushFile("$TMP/$name", bytes, if (executable(name)) "755" else "644")
        }

        // 4. 权限兜底
        client.runShell("chmod 755 $TMP/zt_run $TMP/zt_su 2>/dev/null; chmod 644 $TMP/*.zte 2>/dev/null; true")

        // 5. 组装并执行
        val command = when (v.flow) {
            FlowType.V6 -> {
                val cmds = buildString {
                    append("$TMP/zt_su --daemon\n")
                    append("sleep 6\n")
                    append("id\n")
                    append("echo $MARK_READY\n")
                    append("sleep 3600\n")
                }
                client.pushFile("$TMP/zt_cmds.txt", cmds.toByteArray(), "644")
                "cd $TMP && timeout $RUN_TIMEOUT_SECONDS ./zt_run --run < zt_cmds.txt 2>&1"
            }

            FlowType.V7 ->
                "cd $TMP && ZT_PIPE_DRAIN=80 ZT_PIPE_RECLAIM=40 KHDUMP=1 " +
                    "NEO11_DOUBLE_SPRAY=1 KHONESHOT=1 timeout $RUN_TIMEOUT_SECONDS ./zt_run --run 2>&1"
        }

        emit("[执行] ${v.shortName}：$command")
        val out = runCatching { client.runShell(command) }.getOrElse {
            markFailure(context, v) { line -> emit(line) }
            return Result(false, "执行失败：${it.message}", null, log)
        }
        out.lineSequence().filter { it.isNotBlank() }.forEach { emit(it.trimEnd()) }

        // 6. 判定
        if (out.contains(MARK_ABORT)) {
            markFailure(context, v) { line -> emit(line) }
            return Result(false, "启动器执行失败（IQBIND_ABORT）", MARK_ABORT, log)
        }
        val marker = listOf(MARK_READY, MARK_HOLD, MARK_INSMOD, "KSU_MODULE_LOADED=1")
            .firstOrNull { out.contains(it) }
        if (marker == null) {
            markFailure(context, v) { line -> emit(line) }
            return Result(false, "未出现成功标志（ROOT_SHELL_READY / ROOT_CHILD_HOLD_PASS / KHONE_INSMOD_RC=0）", null, log)
        }
        emit("[判定] 命中标志：$marker")

        // 7. root 复核：能读到 uid=0 才算真的通
        val verify = runCatching {
            client.runShell("echo 'id;exit' | $TMP/zt_su 2>/dev/null | grep -m1 'uid=0'")
        }.getOrDefault("")
        return if (verify.contains("uid=0")) {
            emit("[复核] $verify")
            emit("[完成] root 通道已建立：$TMP/zt_su")
            markSuccess(context, v) { line -> emit(line) }
            Result(true, "提权成功", marker, log)
        } else {
            val alt = runCatching { client.runShell("su -c id 2>/dev/null") }.getOrDefault("")
            if (alt.contains("uid=0")) {
                emit("[复核] su -c id → $alt")
                markSuccess(context, v) { line -> emit(line) }
                Result(true, "提权成功（系统 su 可用）", marker, log)
            } else {
                emit("[复核] zt_su 未返回 uid=0")
                markFailure(context, v) { line -> emit(line) }
                Result(false, "载荷已返回成功标志，但 root 未复核通过", marker, log)
            }
        }
    }

    /**
     * 记录本次成功的代数，供「自动」下次优先复用。
     *
     * 只记录**实际执行成功**的代数；写盘失败不影响本次结果。
     */
    /**
     * 记录本次失败的代数，供「自动」下次改试另一代。
     *
     * 只记录，不在本次自动重跑 —— 避免在设备上留下两次尝试的副作用。
     */
    private fun markFailure(
        context: Context,
        variant: Variant,
        emit: (String) -> Unit,
    ) {
        val saved = runCatching {
            com.demo.zt.PayloadResolver.PayloadHistory(context)
                .recordFailure(variant.flow)
        }.getOrDefault(false)
        if (saved) {
            emit("[记录] ${variant.flow.name} 本次失败，下次自动改试另一代")
        }
    }

    private fun markSuccess(
        context: Context,
        variant: Variant,
        emit: (String) -> Unit,
    ) {
        val saved = runCatching {
            com.demo.zt.PayloadResolver.PayloadHistory(context)
                .recordSuccess(variant.flow)
        }.getOrDefault(false)
        if (saved) {
            emit("[记录] 已记住本机可用代数：${variant.flow.name}（下次自动优先）")
        } else {
            emit("[记录] 成功历史写入失败，不影响本次结果")
        }
    }
}
