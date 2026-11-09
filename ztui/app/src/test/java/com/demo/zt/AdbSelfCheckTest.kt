package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地 ADB」自检：判断当前身份是否为 uid 2000（shell）。
 *
 * 原来点「本地 ADB」会跳到月虹的终端页，现在改成只做自检，
 * 所以这段解析必须可靠——它是用户判断「ADB 通道是否真的拿到 shell」的唯一依据。
 */
class AdbSelfCheckTest {

    @Test
    fun parsesShellIdentity() {
        val out = "uid=2000(shell) gid=2000(shell) groups=2000(shell),3003(inet),9997(everybody)"
        assertEquals(2000, AdbSelfCheck.parseUid(out))
    }

    @Test
    fun parsesRootIdentity() {
        assertEquals(0, AdbSelfCheck.parseUid("uid=0(root) gid=0(root) groups=0(root)"))
    }

    @Test
    fun parsesPlainNumber() {
        // `id -u` 的纯数字输出
        assertEquals(2000, AdbSelfCheck.parseUid("2000"))
        assertEquals(0, AdbSelfCheck.parseUid("0"))
    }

    @Test
    fun handlesWhitespaceAndNewlines() {
        assertEquals(2000, AdbSelfCheck.parseUid("  uid=2000(shell)  "))
        assertEquals(2000, AdbSelfCheck.parseUid("uid=2000(shell)\n"))
    }

    @Test
    fun unparsableReturnsNull() {
        assertNull(AdbSelfCheck.parseUid(""))
        assertNull(AdbSelfCheck.parseUid("   "))
        assertNull(AdbSelfCheck.parseUid(null))
        assertNull(AdbSelfCheck.parseUid("error: closed"))
    }

    @Test
    fun shellUidPasses() {
        val r = AdbSelfCheck.evaluate("uid=2000(shell) gid=2000(shell) groups=2000(shell)")
        assertTrue("uid 2000 应判为通过", r.isShell)
        assertFalse(r.isRoot)
        assertTrue("通道应可用", r.channelOk)
        assertEquals(2000, r.uid)
        assertTrue("结论应提到 2000，实际=${r.message}", r.message.contains("2000"))
        assertTrue("结论应体现通过，实际=${r.message}", r.message.contains("通过"))
    }

    @Test
    fun rootIsAlsoUsableButNotShell() {
        val r = AdbSelfCheck.evaluate("uid=0(root) gid=0(root)")
        assertTrue(r.isRoot)
        assertFalse("root 不是 2000", r.isShell)
        assertTrue(r.channelOk)
        assertTrue(r.message.contains("root"))
    }

    @Test
    fun unexpectedUidIsFlagged() {
        val r = AdbSelfCheck.evaluate("uid=10123(u0_a123) gid=10123")
        assertFalse(r.isShell)
        assertFalse(r.isRoot)
        assertTrue("通道可用但身份异常", r.channelOk)
        assertTrue("应提示异常，实际=${r.message}", r.message.contains("异常"))
        assertTrue(r.message.contains("10123"))
    }

    @Test
    fun emptyOutputFails() {
        val r = AdbSelfCheck.evaluate("")
        assertNull(r.uid)
        assertFalse("解析不出版本应判失败", r.channelOk)
        assertTrue("应提示失败，实际=${r.message}", r.message.contains("失败"))
    }

    @Test
    fun nullClientFailsGracefully() {
        val r = AdbSelfCheck.run(null)
        assertNull(r.uid)
        assertFalse(r.channelOk)
        assertTrue("应提示先配对，实际=${r.message}", r.message.contains("配对"))
    }
}
