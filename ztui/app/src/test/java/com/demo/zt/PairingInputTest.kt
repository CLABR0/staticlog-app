package com.demo.zt

import com.demo.zt.adb.PairingInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对通知输入框的解析：`配对码` 或 `配对码@端口`。
 *
 * 手填端口是「mDNS 给了过期端口」时的逃生通道，必须可靠。
 */
class PairingInputTest {

    @Test
    fun plainCodeHasNoManualPort() {
        val p = PairingInput.parse("123456")
        assertEquals("123456", p.code)
        assertNull(p.manualPort)
        assertTrue(p.codeValid)
        assertNull(PairingInput.errorFor(p))
    }

    @Test
    fun codeWithPort() {
        val p = PairingInput.parse("123456@42313")
        assertEquals("123456", p.code)
        assertEquals(42313, p.manualPort)
        assertTrue(p.codeValid)
        assertNull(PairingInput.errorFor(p))
    }

    @Test
    fun trimsWhitespace() {
        val p = PairingInput.parse("  123456 @ 42313  ")
        assertEquals("123456", p.code)
        assertEquals(42313, p.manualPort)
        assertTrue(p.codeValid)
    }

    @Test
    fun portWithColonPrefixIsAccepted() {
        // 用户可能把「IP:端口」的后半截直接粘进来
        val p = PairingInput.parse("123456@:42313")
        assertEquals(42313, p.manualPort)
    }

    @Test
    fun invalidCodeIsRejected() {
        for (bad in listOf("", "12345", "1234567", "12345a", "abcdef")) {
            val p = PairingInput.parse(bad)
            assertFalse("应判为非法：$bad", p.codeValid)
            assertNotNull("应给出提示：$bad", PairingInput.errorFor(p))
        }
    }

    @Test
    fun outOfRangePortFallsBackToAuto() {
        // 端口越界时当作没填，走 mDNS 发现的端口
        for (bad in listOf("0", "65536", "99999", "9999999")) {
            val p = PairingInput.parse("123456@$bad")
            assertNull("越界端口应忽略：$bad", p.manualPort)
        }
    }

    @Test
    fun emptyPortAfterAtIsIgnored() {
        val p = PairingInput.parse("123456@")
        assertEquals("123456", p.code)
        assertNull(p.manualPort)
        assertTrue(p.codeValid)
    }

    @Test
    fun nullAndBlankAreSafe() {
        assertFalse(PairingInput.parse(null).codeValid)
        assertFalse(PairingInput.parse("   ").codeValid)
        assertNotNull(PairingInput.errorFor(PairingInput.parse(null)))
    }
}
