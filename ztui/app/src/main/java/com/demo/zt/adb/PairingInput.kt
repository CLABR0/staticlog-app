package com.demo.zt.adb

/**
 * 解析用户在配对通知里输入的内容。
 *
 * 支持两种写法：
 *   `123456`            → 配对码，端口沿用 mDNS 发现结果
 *   `123456@42313`      → 配对码 + 手动指定端口
 *
 * 手动端口是「mDNS 给了过期端口」时的逃生通道：
 * 配对窗口上会显示 `IP:端口`，把端口填进去即可绕开自动发现。
 */
object PairingInput {

    /** 配对码固定 6 位。 */
    const val CODE_LENGTH = 6

    data class Parsed(
        /** 6 位配对码；不合法时为空串。 */
        val code: String,
        /** 手动指定的端口；没写或写错时为 null。 */
        val manualPort: Int?,
    ) {
        val codeValid: Boolean
            get() = code.length == CODE_LENGTH && code.all { it.isDigit() }
    }

    fun parse(raw: String?): Parsed {
        val text = raw?.trim().orEmpty()

        val code = text.substringBefore('@').trim()
        val portText = text.substringAfter('@', "")

        val manualPort = portText
            .filter { it.isDigit() }
            .takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }

        return Parsed(code, manualPort)
    }

    /**
     * 给用户看的错误提示；输入合法时返回 null。
     */
    fun errorFor(parsed: Parsed): String? = when {
        parsed.code.isEmpty() -> "请输入 6 位配对码（可写成 123456@端口 手动指定端口）"
        !parsed.codeValid -> "配对码应为 6 位数字（可写成 123456@端口 手动指定端口）"
        else -> null
    }
}
