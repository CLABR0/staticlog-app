package com.demo.zt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关于页与本轮两项改动：
 *   1. 关于页要有 GitHub 仓库入口（并且是真实 URL）
 *   2. 开机自动提权必须把过程写进 AppLog —— 否则「复制日志」里看不到它跑过，
 *      用户只会看到界面上的其它日志，误以为"凭空提权了"
 */
class AboutAndAutoBootLogTest {

    private fun src(path: String): String =
        java.io.File("src/main/java/com/demo/zt/$path").readText()

    @Test
    fun aboutScreenHasGithubLink() {
        val about = src("AboutScreen.kt")
        assertTrue("必须有仓库常量", about.contains("PROJECT_GITHUB_URL"))
        assertTrue(
            "URL 必须指向真实仓库",
            about.contains("https://github.com/zyyihao5-lang/staticlog"),
        )
        assertTrue("必须有可点击的行", about.contains("\"GitHub 开源库\""))
        assertTrue("点击要真的打开浏览器", about.contains("uriHandler.openUri(PROJECT_GITHUB_URL)"))
        assertTrue("分组标题", about.contains("SmallTitle(text = \"本项目\")"))
    }

    @Test
    fun autoBootServiceWritesIntoAppLog() {
        val svc = src("AutoBootService.kt")
        assertTrue("必须有统一日志助手", svc.contains("private fun log(line: String)"))
        assertTrue(
            "进度必须进 AppLog（界面/导出日志读的是它）",
            svc.contains("AppLog.add("),
        )
        assertTrue(
            "通知内容也要留痕",
            svc.contains("log(\"\$title：\$text\")"),
        )
        assertTrue(
            "服务启动要有明确的触发标记",
            svc.contains("开机广播触发，开始执行"),
        )
        assertTrue(
            "重试与等待也要可见",
            svc.contains("网络未就绪，等待 Wi-Fi/数据连接…"),
        )
    }

    @Test
    fun autoBootLogLinesArePrefixed() {
        // 前缀便于用户在长日志里一眼认出是自动流程而不是自己点的
        assertTrue(src("AutoBootService.kt").contains("\"[自动提权] \$line\""))
    }

    @Test
    fun aboutScreenStillListsOpenSourceLibs() {
        val about = src("AboutScreen.kt")
        assertTrue(about.contains("所用开源库"))
        assertTrue("GhostLock 归属不能丢", about.contains("GhostLock-App"))
        assertTrue("月虹归属不能丢", about.contains("月虹提权助手"))
    }
}
