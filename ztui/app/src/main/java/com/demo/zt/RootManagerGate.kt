package com.demo.zt

/**
 * root 管理器门禁。
 *
 * ### 为什么需要
 * 两种流程在收尾阶段依赖**已经装在手机上的管理器**：
 *
 * - **橘子6（V6）**：走 `libksud.so` late-load 激活 KernelSU。
 *   官方脚本在 `/data/app` 里找 `libksud.so`，只认
 *   `me.weishu.kernelsu`（KernelSU）或 `com.resukisu.resukisu`（ReSukiSU）。
 *   没有这两个包，late-load 没有目标，跑完也拿不到可授权的 root。
 *
 * - **橘子7（V7，本次换成的 SukiSU 包）**：成功后由 **SukiSU Ultra** 管理器授权。
 *   上游脚本明确写「打开 SukiSU Ultra 管理器即可正常授权 root」，
 *   管理器版本应为 `v4.3.0-e67b14e6@tadaki`。
 *
 * 所以按代次分别要求，缺了就直接拒绝执行，**不推文件、不 insmod**。
 *
 * ### 检测方式
 * 只用 `pm list packages`（无需 root，配对后就有 shell）。
 * 不猜 /proc/modules、不看文件路径——那些在"能跑之前"读不到，
 * 而且模块是否已加载不代表管理器存在。
 */
enum class RootManagerKind {
    /** KernelSU / ReSukiSU 系 */
    KSU,

    /** SukiSU Ultra */
    SUKISU,
}

object RootManagerGate {

    /** 可以满足橘子6 要求的包名（顺序即报错时的推荐顺序）。 */
    val KSU_PACKAGES = listOf(
        "me.weishu.kernelsu",      // KernelSU 官方
        "com.resukisu.resukisu",   // ReSukiSU（橘子6 脚本认的另一个）
        "com.rifsxd.ksunext",      // KernelSU-Next
    )

    /** 可以满足橘子7 要求的包名。 */
    val SUKISU_PACKAGES = listOf(
        "com.sukisu.ultra",        // SukiSU Ultra（新包对应的管理器）
        "com.resukisu.resukisu",   // ReSukiSU 也能授权内核里的 su
    )

    /** 该流程要求哪一类管理器。 */
    fun requirement(flow: FlowType): RootManagerKind = when (flow) {
        FlowType.V6 -> RootManagerKind.KSU
        FlowType.V7 -> RootManagerKind.SUKISU
    }

    fun displayName(kind: RootManagerKind): String = when (kind) {
        RootManagerKind.KSU -> "KernelSU（或 ReSukiSU / KernelSU-Next）"
        RootManagerKind.SUKISU -> "SukiSU Ultra"
    }

    private fun candidates(kind: RootManagerKind): List<String> = when (kind) {
        RootManagerKind.KSU -> KSU_PACKAGES
        RootManagerKind.SUKISU -> SUKISU_PACKAGES
    }

    /**
     * 从 `pm list packages` 输出里解析出已安装包集合。
     * 输出形如 `package:me.weishu.kernelsu`，一行一个。
     */
    fun parseInstalled(raw: String): Set<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    data class Verdict(
        val ok: Boolean,
        /** 找到的包，没找到时为 null。 */
        val foundPackage: String?,
        val kind: RootManagerKind,
        val message: String,
        /**
         * 是否**真的读到了**应用列表。
         *
         * false 表示探测失败（命令报错／输出为空）。此时 [ok] 仍为 true，
         * 因为拦下去会把本来能用的环境一起拦死；但结论要如实标成"未确认"。
         */
        val verified: Boolean = true,
    )

    /**
     * 探测命令，按顺序尝试。
     *
     * 单靠 `pm list packages` 不够：这个包装脚本在某些 ROM / 某些状态下会以
     * `Error: Could not access the package manager` 失败或干脆输出空，
     * 而 `cmd package list packages` 走的是同一条 binder 但绕开 `pm` 脚本。
     */
    val PROBE_COMMANDS = listOf(
        "pm list packages",
        "cmd package list packages",
        "pm list packages -3",
    )

    /**
     * 判定。
     *
     * @param installed 已安装包列表的原始输出；为 null 表示**读取失败**，
     *        这种要单独报「无法确认」而不是「没装」——两者处置完全不同。
     */
    fun evaluate(flow: FlowType, installed: String?): Verdict {
        val kind = requirement(flow)
        val wanted = displayName(kind)
        if (installed == null) {
            return Verdict(
                true,
                null,
                kind,
                "无法读取已安装应用列表，无法确认是否装了 $wanted；已放行但未确认",
                verified = false,
            )
        }
        val packages = parseInstalled(installed)
        if (packages.isEmpty()) {
            // 命令回了内容但没有一行 package: —— 同样是"没读到"，不是"没装"
            return Verdict(
                true,
                null,
                kind,
                "应用列表为空（命令未返回任何 package: 行），无法确认是否装了 $wanted；已放行但未确认",
                verified = false,
            )
        }
        val hit = candidates(kind).firstOrNull { it in packages }
        return if (hit != null) {
            Verdict(true, hit, kind, "已安装 $wanted（$hit）")
        } else {
            Verdict(
                false,
                null,
                kind,
                "缺少 ${displayName(kind)}：这套流程要靠它授权 root。" +
                    "请先安装该管理器再执行；已拒绝，未写入任何文件",
            )
        }
    }

    /** 探测结果。用于日志与诊断，不参与判定。 */
    data class Probe(
        val raw: String?,
        val command: String?,
        val bytes: Int,
        val attempts: Int,
        val error: String?,
    ) {
        val readable: Boolean get() = raw != null && parseInstalled(raw).isNotEmpty()

        /** 一行诊断，直接写进日志给用户看。 */
        fun summary(): String = buildString {
            append("探测命令=$command，尝试 $attempts 次，输出 ${bytes}B")
            if (!readable) append("（未读到应用列表${if (error != null) "：$error" else ""}）")
            val head = raw?.lineSequence()?.filter { it.isNotBlank() }?.take(3)?.joinToString(" / ")
            if (!head.isNullOrBlank()) append("；前几行：$head")
        }
    }

    /**
     * 真正去设备上读应用列表，按 [PROBE_COMMANDS] 依次兜底。
     *
     * **不抛异常**：任何一次失败都记进 [Probe]，最后交由 [evaluate] 判「未确认」。
     * 之前只跑一条 `pm list packages` 且异常即视为"读不到"，
     * 结果在 `pm` 抽风的机器上整条提权链路被拦死。
     */
    fun probe(runShell: (String) -> String, retriesPerCommand: Int = 2): Probe {
        var attempts = 0
        var lastError: String? = null
        for (cmd in PROBE_COMMANDS) {
            repeat(retriesPerCommand) {
                attempts++
                val out = runCatching { runShell(cmd) }
                val raw = out.getOrNull()
                if (raw != null && parseInstalled(raw).isNotEmpty()) {
                    return Probe(raw, cmd, raw.length, attempts, null)
                }
                if (raw != null && raw.isNotBlank()) {
                    // 有输出但一行 package: 都没有 —— 多半是 Error: ...
                    lastError = raw.trim().lineSequence().firstOrNull()
                }
                out.exceptionOrNull()?.let { lastError = it.message }
            }
        }
        return Probe(null, PROBE_COMMANDS.last(), 0, attempts, lastError)
    }
}
