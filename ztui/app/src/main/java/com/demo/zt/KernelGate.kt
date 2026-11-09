package com.demo.zt

/**
 * 内核兼容性判定。
 *
 * 判据：**只看主版本 `6.6.127`**。
 *
 * 历史：早期还要求尾号 `7741`（且必须在 `abogki` 后面的数字里），
 * 但各版本内核串格式并不统一，尾号位置会变，导致版本对却把人挡在门外。
 * 按实际使用要求，尾号校验已整体删除：
 *
 *   `6.6.127-android15-8-g24b70dd1cb81-abogki543627741-4k`  ✓
 *   `6.6.127-android16-8-gabc-abogki7741-4k`                ✓
 *   `6.6.127-android16-8-gabc-4k`                           ✓（不再看尾号）
 *   `6.6.100-android16-8-gabc-4k`                           ✗ 版本不符
 */
object KernelGate {

    const val EXPECTED_PREFIX = "6.6.127"
    const val HOLD_SECONDS = 10

    data class Verdict(
        val release: String,
        val prefixOk: Boolean,
    ) {
        val supported: Boolean get() = prefixOk

        val reason: String
            get() = if (supported) {
                "内核匹配（$EXPECTED_PREFIX）"
            } else {
                "版本不匹配（期望 $EXPECTED_PREFIX；实际读取：$release）"
            }
    }

    fun check(): Verdict = evaluate(DeviceInfo.kernelRelease)

    /** 纯函数判定，便于单测覆盖各种真实的内核串格式。 */
    fun evaluate(release: String?): Verdict {
        val rel = release?.trim().orEmpty()
        return Verdict(
            release = rel,
            prefixOk = rel.contains(EXPECTED_PREFIX),
        )
    }
}
