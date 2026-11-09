package com.demo.zt.shell

/**
 * 以下类型定义逐字取自
 * yuehong/assistant/src/main/java/roro/stellar/yuehong/shell/{PrivilegeEscalator,StellarShellController,BundledPayloadCatalog}.kt
 */

// ── StellarShellController.kt ──
enum class StellarStatus {
    Checking,
    Unavailable,
    NeedsPermission,
    Granted,
}

data class OutputLine(val text: String, val isError: Boolean = false)

data class ShellFileInstallResult(val exitCode: Int, val output: String)

// ── PrivilegeEscalator.kt ──

// 提权流程的各个阶段
enum class EscalationStage {
    Idle,
    CheckingPermission,
    ValidatingCommand,
    ImportingLocalResource,
    InstallingResources,
    ExecutingPayload,
    CheckingRoot,
    ActivatingJailbreak,
    Done,
}

// 提权结果
sealed interface EscalationResult {
    data object Idle : EscalationResult
    data class RootConfirmed(val output: String, val suPath: String) : EscalationResult
    data class JailbreakActivated(val output: String) : EscalationResult
    data class JailbreakActivationFailure(val reason: String) : EscalationResult
    data class Failure(val stage: EscalationStage, val reason: String) : EscalationResult
}

// ── BundledPayloadCatalog.kt ──
data class BundledPayload(
    val library: String,
    val vendor: String,
    val displayName: String,
    val models: List<String>,
    val kernelVersion: String?,
    val kernelUnknown: Boolean,
    val buildId: String,
    val command: String,
    val sha256: String,
    val size: Long,
    val source: String,
) {
    val label: String
        get() = buildString {
            append(displayName.ifBlank { library })
            if (!kernelVersion.isNullOrBlank()) append(" · 内核 ").append(kernelVersion)
            if (kernelUnknown) append(" · 内核未知")
            if (buildId.isNotBlank()) append(" · ").append(buildId)
            append("\n").append(library)
        }
}