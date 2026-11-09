package com.demo.zt

import android.content.Context
import com.demo.zt.adb.AdbClient
import com.demo.zt.adb.runShell
import android.util.Base64

/**
 * 拔除 KO 探针（vivo `vr.ko` 的 `__tracepoint_sys_exit` 探针）。
 *
 * ### 原理
 * vivo 的 `vr.ko` 在 `__tracepoint_sys_exit` 上挂了自己的回调：凡是**刚拿到 root
 * 凭据**的进程，在 exit 时会被它杀掉。这就是「提权成功但一执行就崩/被回收」的来源。
 *
 * 拔除方式（与 DirtyFrag VR / 月红 W1c 一致）：把
 * `__tracepoint_sys_exit->funcs` 置 NULL。纯数据写入，不挂钩子、不改代码，
 * 于是内核遍历 tracepoint 时读到空指针表，探针再也不会触发。
 *
 * ### 为什么需要内核模块
 * `funcs` 在 `.data`，用户态即使拿到 uid 0 也没有可用的内核写接口
 * （`/dev/kmem` 早已移除），只能由 ring 0 的模块完成这一次写入。
 *
 * ### 模块来源与参数
 * 预编译的 KMI 模块取自月红工程（DirtyFrag VR 上游），8 个 GKI 版本各一个。
 * 模块里有一个配置槽，布局与上游 `exp.c` 的扫描逻辑完全一致：
 *
 * ```
 * [0..7]   魔数 0xDFC0FF16DFC0FF16
 * [8..15]  cfg[1] = 手工偏移（auto 模式下为 0）
 * [16..23] cfg[2] = 1 表示 auto
 * ```
 *
 * auto 模式让模块自己在 ring 0 用运行内核的 BTF 解出 `funcs` 偏移，
 * 不依赖我们猜的数值——这正是上游认为「每台设备的地面真相」。
 *
 * ### 边界
 * - 必须已有 root（载荷建立的 `/data/local/tmp/zt_su`）
 * - 模块只改内存数据，**重启即恢复**，不需要也不提供卸载
 * - 模块加载失败时（签名强制、KMI 不符）如实报告内核给出的原因，不假装成功
 */
object ProbeRemover {

    /** 推送位置与远端脚本路径。 */
    const val REMOTE_KO = "/data/local/tmp/zt_dfroot.ko"
    const val REMOTE_SCRIPT = "/data/local/tmp/zt_probe.sh"

    /** 上游模块里的配置槽魔数（小端字节序）。 */
    val MAGIC = byteArrayOf(
        0x16, 0xFF.toByte(), 0xC0.toByte(), 0xDF.toByte(),
        0x16, 0xFF.toByte(), 0xC0.toByte(), 0xDF.toByte(),
    )

    /**
     * 一个 KMI 目标。
     *
     * `android` 取自内核串里的 `androidNN`（缺失时退到 `ro.vendor.build.version.release`），
     * `major`/`minor` 取自 `uname -r` 的前两段。
     */
    data class Kmi(val android: Int, val major: Int, val minor: Int) {
        val asset: String get() = "probe/dfroot-android$android-$major.$minor.ko"
        val label: String get() = "android$android-$major.$minor"
    }

    /** 与上游 `select_ko_image` 的表完全一致，顺序也保持一致。 */
    val TABLE: List<Kmi> = listOf(
        Kmi(12, 5, 10),
        Kmi(13, 5, 10),
        Kmi(13, 5, 15),
        Kmi(14, 5, 15),
        Kmi(14, 6, 1),
        Kmi(15, 6, 6),
        Kmi(16, 6, 12),
        Kmi(17, 6, 18),
    )

    data class Result(val ok: Boolean, val message: String)

    /**
     * 从内核串解析 `(android, major, minor)`。
     *
     * 例：`6.6.127-android15-8-g24b70dd1cb81-abogki543627741-4k` → `(15, 6, 6)`
     *
     * 内核串里没有 `androidNN` 时 android 返回 null，由调用方补 getprop。
     */
    fun parseKernel(release: String): Triple<Int?, Int, Int>? {
        val text = release.trim()
        if (text.isEmpty()) return null
        val ver = Regex("""^(\d+)\.(\d+)""").find(text) ?: return null
        val major = ver.groupValues[1].toIntOrNull() ?: return null
        val minor = ver.groupValues[2].toIntOrNull() ?: return null
        val android = Regex("""android(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        return Triple(android, major, minor)
    }

    /**
     * 选择模块，语义照抄上游：
     * 先找 `major.minor` 与 `android` 都相同的；找不到则退回**同 `major.minor` 的第一个**。
     */
    fun selectKmi(android: Int?, major: Int, minor: Int): Kmi? {
        val sameVer = TABLE.filter { it.major == major && it.minor == minor }
        if (sameVer.isEmpty()) return null
        if (android != null) {
            sameVer.firstOrNull { it.android == android }?.let { return it }
        }
        return sameVer.first()
    }

    /**
     * 把配置槽写成 auto 模式：`cfg[1] = 0`、`cfg[2] = 1`。
     *
     * 找不到魔数就抛异常——宁可不加载，也不加载一个探针没被配置的模块。
     */
    fun patchAutoMode(source: ByteArray): ByteArray {
        val out = source.copyOf()
        val at = indexOf(out, MAGIC)
        require(at >= 0) { "模块里没有 VR 配置槽（魔数缺失）" }
        require(at + 24 <= out.size) { "配置槽被截断" }
        // cfg[1] = 0（auto 模式不需要手工偏移）
        for (i in 0 until 8) out[at + 8 + i] = 0
        // cfg[2] = 1（auto）。小端：数值 1 落在最低字节，即 +16；
        // 上游是 memcpy(&automatic, 8)，等价于这三行。
        out[at + 16] = 1
        for (i in 1 until 8) out[at + 16 + i] = 0
        return out
    }

    /** 读回配置槽，供自检与单测断言。 */
    fun readSlot(bytes: ByteArray): LongArray? {
        val at = indexOf(bytes, MAGIC)
        if (at < 0 || at + 24 > bytes.size) return null
        fun q(off: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) {
                v = (v shl 8) or (bytes[off + i].toLong() and 0xFF)
            }
            return v
        }
        return longArrayOf(q(at), q(at + 8), q(at + 16))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || hay.size < needle.size) return -1
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) {
                if (hay[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** 交给 root 执行的脚本：加载模块并回读内核日志。 */
    fun buildScript(): String = buildString {
        appendLine("insmod $REMOTE_KO 2>&1")
        appendLine("echo \"insmod_rc=\$?\"")
        // 只取与模块相关的行，避免把整段 dmesg 灌进日志面板
        appendLine("dmesg | grep -i dfroot | tail -n 24 2>&1")
    }

    /** 从命令输出判断结论；纯函数，便于单测覆盖各种真实返回。 */
    fun interpret(output: String): Result {
        val text = output.trim()
        val disarmed = text.contains("probe disarmed", ignoreCase = true) ||
            text.contains("funcs already NULL", ignoreCase = true)
        return when {
            disarmed && text.contains("btf", ignoreCase = true) ->
                Result(true, "探针已拔除（BTF 自动定位偏移）")
            disarmed -> Result(true, "探针已拔除")
            text.contains("File exists", ignoreCase = true) ->
                Result(true, "模块已加载过，探针处于拔除状态（重启后失效）")
            text.contains("module verification failed", ignoreCase = true) ||
                text.contains("Key was rejected", ignoreCase = true) ->
                Result(false, "内核拒绝加载：模块签名校验未通过（需内核允许未签名模块）")
            text.contains("Exec format error", ignoreCase = true) ||
                text.contains("Invalid module format", ignoreCase = true) ->
                Result(false, "内核拒绝加载：模块与当前内核不匹配（KMI 不符）")
            text.contains("funcs offset unresolved", ignoreCase = true) ||
                text.contains("vr auto", ignoreCase = true) ->
                Result(false, "模块加载了，但没能从 BTF 解出 funcs 偏移，探针未拔除：\n$text")
            text.contains("Operation not permitted", ignoreCase = true) ->
                Result(false, "没有加载模块的权限，请先提权：\n$text")
            else -> Result(false, "未能确认探针被拔除，原始输出：\n$text")
        }
    }

    /**
     * 执行拔除。**阻塞**，必须在工作线程调用。
     *
     * @param client 已连接的 ADB 通道（root 命令走 `/data/local/tmp/zt_su`）
     * @param log 逐条上报进度，界面直接展示
     */
    fun remove(
        context: Context,
        client: AdbClient?,
        kernelRelease: String,
        androidProp: Int?,
        log: (String) -> Unit,
    ): Result {
        if (client == null) {
            return Result(false, "尚未连接 ADB：请先在「本地 ADB」里连上").also {
                log("[探针] ${it.message}")
            }
        }

        val parsed = parseKernel(kernelRelease)
            ?: return Result(false, "读不到内核版本（$kernelRelease），无法选择模块").also {
                log("[探针] ${it.message}")
            }
        val android = parsed.first ?: androidProp
        val kmi = selectKmi(android, parsed.second, parsed.third)
            ?: return Result(
                false,
                "当前内核 ${parsed.second}.${parsed.third} 不在支持的 KMI 列表内，" +
                    "没有可用模块；不做任何写入",
            ).also { log("[探针] ${it.message}") }

        log("[探针] 内核：$kernelRelease")
        log("[探针] 选用模块：${kmi.asset}")

        val raw = runCatching {
            context.assets.open(kmi.asset).use { it.readBytes() }
        }.getOrElse {
            return Result(false, "读取内置模块失败：${it.message}").also { m -> log("[探针] ${m.message}") }
        }

        val patched = runCatching { patchAutoMode(raw) }.getOrElse {
            return Result(false, "模块配置槽写入失败：${it.message}").also { m -> log("[探针] ${m.message}") }
        }
        val slot = readSlot(patched)
        log("[探针] 配置槽：magic=0x${slot?.get(0)?.toString(16)} cfg1=${slot?.get(1)} cfg2=${slot?.get(2)}")

        runCatching { client.pushFile(REMOTE_KO, patched, "644") }.getOrElse {
            return Result(false, "推送模块失败：${it.message}").also { m -> log("[探针] ${m.message}") }
        }
        log("[探针] 已推送 ${patched.size} 字节到 $REMOTE_KO")

        // root 是硬前提：没有 uid 0 就没有加载模块的可能
        val idOut = runCatching { client.runShell("echo id | /data/local/tmp/zt_su 2>&1") }
            .getOrElse {
                return Result(false, "检查 root 失败：${it.message}").also { m -> log("[探针] ${m.message}") }
            }
        if (!idOut.contains("uid=0")) {
            val why = "未取得 root（$idOut）——请先执行提权，再来拔探针"
            log("[探针] $why")
            return Result(false, why)
        }
        log("[探针] root 可用，开始加载模块")

        val script = buildScript()
        val encoded = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val command = "echo '$encoded' | base64 -d > $REMOTE_SCRIPT; " +
            "chmod 700 $REMOTE_SCRIPT; " +
            "echo 'sh $REMOTE_SCRIPT' | /data/local/tmp/zt_su 2>&1"

        val output = runCatching { client.runShell(command) }.getOrElse {
            return Result(false, "执行失败：${it::class.simpleName}: ${it.message}").also { m ->
                log("[探针] ${m.message}")
            }
        }
        output.lineSequence().filter { it.isNotBlank() }.forEach { log("[探针] $it") }

        val result = interpret(output)
        log("[探针] 结论：${result.message}")
        return result
    }
}
