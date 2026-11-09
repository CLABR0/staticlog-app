package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守卫：版本号必须真实生效且单调递增。
 *
 * 真实事故：出了十几个包，versionCode 一直是 1、versionName 一直是 "1.0"，
 * 界面还写死 "v1.0"，用户完全分不清手上装的是哪一版。
 *
 * 规则：编号 = 累计出包次数（V17 就是第 17 个 release 包）。
 */
class BuildVersionTest {

    private fun versionFile(): File {
        val candidates = listOf(
            File("../version.properties"),
            File("version.properties"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到 version.properties（cwd=${File(".").absolutePath}）")
    }

    private fun props(): Map<String, String> =
        versionFile().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { line ->
                val i = line.indexOf('=')
                line.substring(0, i).trim() to line.substring(i + 1).trim()
            }

    @Test
    fun versionNameUsesVPrefixAndNumber() {
        val name = props()["versionName"] ?: error("缺 versionName")
        assertTrue("versionName 应为 V+数字，实际=$name", Regex("""^V\d+$""").matches(name))
    }

    @Test
    fun versionCodeMatchesNameNumber() {
        val name = props()["versionName"] ?: error("缺 versionName")
        val code = props()["versionCode"]?.toIntOrNull() ?: error("缺 versionCode")
        val n = name.removePrefix("V").toInt()
        assertEquals("versionCode 必须等于 versionName 里的编号", n, code)
    }

    @Test
    fun versionCodeIsMonotonicVersusOriginalOne() {
        // 第一版是 1，之后每次出包都必须比它大，否则无法覆盖安装
        val code = props()["versionCode"]?.toIntOrNull() ?: error("缺 versionCode")
        assertTrue("versionCode 必须 > 1（首版），实际=$code", code > 1)
    }

    @Test
    fun buildConfigExposesTheSameVersion() {
        // 界面读的是 BuildConfig，两边必须一致，否则「关于页」会骗人
        val name = props()["versionName"] ?: error("缺 versionName")
        assertEquals("BuildConfig.VERSION_NAME 与 version.properties 不一致", name, BuildConfig.VERSION_NAME)
    }

    @Test
    fun uiDoesNotHardcodeVersion() {
        val about = File("src/main/java/com/demo/zt/AboutScreen.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/demo/zt/AboutScreen.kt")
        val src = about.readText()
        assertTrue("关于页必须读 BuildConfig.VERSION_NAME", src.contains("BuildConfig.VERSION_NAME"))
        assertTrue(
            "关于页不得再写死 v1.0",
            !src.contains("\"v1.0 ·"),
        )
    }
}
