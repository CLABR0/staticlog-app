package com.demo.zt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：关于页必须有作者信息，且点击能打开酷安主页。
 *
 * 作者与主页 URL 是易被误改的固定信息，用源码断言钉住。
 */
class AboutAuthorTest {

    private fun about(): String {
        val candidates = listOf(
            File("src/main/java/com/demo/zt/AboutScreen.kt"),
            File("app/src/main/java/com/demo/zt/AboutScreen.kt"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 AboutScreen.kt（cwd=${File(".").absolutePath}）")
    }

    @Test
    fun authorNameIsExact() {
        val s = about()
        assertTrue(
            "作者名必须是 Mcjdifif",
            s.contains("AUTHOR_NAME = \"Mcjdifif\""),
        )
    }

    @Test
    fun coolapkUrlIsExact() {
        val s = about()
        assertTrue(
            "酷安主页链接必须完整正确",
            s.contains("\"https://www.coolapk.com/u/33270757\""),
        )
    }

    @Test
    fun authorRowOpensTheLink() {
        val s = about()
        val idx = s.indexOf("AUTHOR_COOLAPK_URL")
        assertTrue("必须引用作者链接常量", idx >= 0)
        assertTrue(
            "点击必须调用 openUri 打开链接",
            s.contains("uriHandler.openUri(AUTHOR_COOLAPK_URL)"),
        )
    }

    @Test
    fun authorSectionIsVisibleInAboutPage() {
        val s = about()
        assertTrue("关于页要有「作者」分组标题", s.contains("SmallTitle(text = \"作者\")"))
        assertTrue("作者信息用 ArrowPreference 展示（与开源库条目风格一致）",
            s.contains("title = AUTHOR_NAME"))
    }
}
