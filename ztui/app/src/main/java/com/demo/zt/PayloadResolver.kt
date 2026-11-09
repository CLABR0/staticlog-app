package com.demo.zt

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/** 自动选择的最终结果，带上可展示的判定依据。 */
data class PayloadDecision(
    val variant: Variant,
    val reason: String,
    /** 单行摘要，给界面用；完整理由见 [reason]。 */
    val summary: String,
    /** false 表示没有可靠依据（兜底或内存读不到），需要人工确认。 */
    val confirmed: Boolean,
)

/**
 * 「自动」载荷选择的落地实现。
 *
 * 判定优先级：历史成功记录 → 已验证指纹规则 → 较新代数兜底。
 * 内存只决定 16G/12G，与代数无关。
 */
object PayloadResolver {

    /**
     * 已验证映射表：完整 `Build.FINGERPRINT` → 代数。
     *
     * **默认空表**。只有经过实机验证「这个构建确实该用第 N 代」才可加入；
     * 不要因为机型、SDK 相似就填，那会把兜底伪装成识别结果。
     */
    private val verifiedRules: List<Pair<String, FlowType>> = emptyList()

    fun decide(context: Context): PayloadDecision {
        val history = PayloadHistory(context)

        val plan = PayloadPolicy.decide(
            probes = PayloadProbes(
                readSuccessFamily = { history.readSuccessFamily() },
                readFingerprint = { Build.FINGERPRINT },
                readTotalMemGiB = { DeviceInfo.totalMemGb },
                readFailureFamily = { history.readFailureFamily() },
                readSoftwareMajor = { SoftwareVersion.read()?.major },
            ),
            rules = verifiedRules,
        )

        return PayloadDecision(
            variant = plan.variant,
            reason = plan.reason,
            summary = plan.summary,
            confirmed = plan.confirmed,
        )
    }

    /**
     * 成功与失败的代数记录。
     *
     * 「自愈」两段：
     *   - 成功过的那一代 → 下次优先用它（confirmed）
     *   - 上一次失败的那一代 → 下次优先**避开**，改试另一代（未 confirmed）
     *
     * 注意：规避只影响**下一次自动选择**，不会在本次执行里偷偷换代码重跑 ——
     * 那会在内核上留下两次尝试的副作用。
     */
    class PayloadHistory(context: Context) {

        private val preferences: SharedPreferences? = runCatching {
            context.applicationContext
                ?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        }.getOrNull()

        /** 原始存储值，仅供诊断。 */
        val lastSuccessFamily: String?
            get() = runCatching { preferences?.getString(KEY_FAMILY, null) }.getOrNull()

        val lastSuccessAt: Long
            get() = runCatching { preferences?.getLong(KEY_TIME, 0L) ?: 0L }
                .getOrDefault(0L)
                .coerceAtLeast(0L)

        /** 上一次失败那一代的原始存储值。 */
        val lastFailureFamily: String?
            get() = runCatching { preferences?.getString(KEY_FAIL_FAMILY, null) }.getOrNull()

        /** 只返回合法的 "V6"/"V7"，脏值当无记录。 */
        fun readSuccessFamily(): String? = when (val v = lastSuccessFamily) {
            "V6", "V7" -> v
            else -> null
        }

        /** 只返回合法的 "V6"/"V7"，脏值当无记录。 */
        fun readFailureFamily(): String? = when (val v = lastFailureFamily) {
            "V6", "V7" -> v
            else -> null
        }

        /**
         * 执行成功后调用（工作线程）。
         * 用 commit() 以拿到真实落盘结果；写入失败不抛异常，也不改判执行结果。
         * 成功时清掉失败记录——已经验证过这一代可用，不必再规避。
         */
        fun recordSuccess(family: FlowType): Boolean = runCatching {
            val prefs = preferences ?: return@runCatching false
            prefs.edit()
                .putString(KEY_FAMILY, family.name)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .remove(KEY_FAIL_FAMILY)
                .remove(KEY_FAIL_TIME)
                .commit()
        }.getOrDefault(false)

        /** 整链失败时调用（工作线程）。写入失败不影响本次结果。 */
        fun recordFailure(family: FlowType): Boolean = runCatching {
            val prefs = preferences ?: return@runCatching false
            prefs.edit()
                .putString(KEY_FAIL_FAMILY, family.name)
                .putLong(KEY_FAIL_TIME, System.currentTimeMillis())
                .commit()
        }.getOrDefault(false)

        fun clear(): Boolean = runCatching {
            preferences?.edit()?.clear()?.commit() ?: false
        }.getOrDefault(false)

        private companion object {
            const val PREFERENCES_NAME = "payload_history"
            const val KEY_FAMILY = "lastSuccessFamily"
            const val KEY_TIME = "lastSuccessAt"
            const val KEY_FAIL_FAMILY = "lastFailureFamily"
            const val KEY_FAIL_TIME = "lastFailureAt"
        }
    }
}
