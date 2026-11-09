package com.demo.zt

import androidx.compose.runtime.mutableStateListOf

/**
 * 全局日志缓冲区。
 *
 * 主界面日志面板与设置页的「导出日志」共用同一份数据；
 * 之前靠参数层层传递，作用域一变就取不到，改用集中存放。
 */
object AppLog {

    private const val MAX_LINES = 800

    /** Compose 可观察的日志行。 */
    val lines = mutableStateListOf<String>()

    fun add(line: String) {
        lines.add(line)
        // 防止无限增长
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
    }

    fun snapshot(): List<String> = lines.toList()

    fun clear() = lines.clear()
}
