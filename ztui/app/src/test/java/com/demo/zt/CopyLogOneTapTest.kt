package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：调试区块必须「点一下直接复制」，且设置面板必须可滚动。
 *
 * 真实反馈：1) 调试点不动 —— 面板没有滚动，条目超出屏幕；
 *          2) 要求点击直接复制，不要多一步。
 */
class CopyLogOneTapTest {

    private fun settings(): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/SettingsMenu.kt"),
            File("app/src/main/java/com/demo/zt/SettingsMenu.kt"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 SettingsMenu.kt（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun settingsContentIsScrollable() {
        val s = settings()
        assertTrue("设置面板必须可滚动，否则底部条目点不到", s.contains("verticalScroll(rememberScrollState())"))
        assertTrue("滚动区域要有高度上限", s.contains("heightIn(max ="))
    }

    @Test
    fun debugRowCopiesOnSingleTap() {
        val s = settings()
        val idx = s.indexOf("复制当前日志")
        assertTrue("应有「复制当前日志」条目", idx >= 0)
        val block = s.substring(idx, minOf(s.length, idx + 1200))
        assertTrue("点击即复制", block.contains("copyToClipboard"))
        assertTrue("要有即时反馈", block.contains("Toast.makeText"))
    }

    @Test
    fun noExtraDialogForCopyResult() {
        val s = settings()
        val idx = s.indexOf("复制当前日志")
        val block = s.substring(idx, minOf(s.length, idx + 1200))
        assertFalse("复制结果不应再弹一个弹窗", block.contains("OverlayDialog"))
    }

    @Test
    fun debugBlockStillDebugOnly() {
        val s = settings()
        assertTrue("调试区块必须仅 debug 可见", s.contains("if (com.demo.zt.BuildConfig.DEBUG)"))
    }

    @Test
    fun copyIncludesContextHeader() {
        val s = settings()
        assertTrue("复制内容经 LogExporter 生成（含设备/版本上下文）", s.contains("LogExporter.buildText"))
    }
}
