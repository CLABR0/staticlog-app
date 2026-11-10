package com.demo.zt.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移行为的 JVM 单测：使用内存 PrefsBridge，不构造 Context / UserManager，
 * 日志也由 lambda 替代，因此不触发 BootTrace 或 Android 运行时。
 */
class AdbKeyMigrationTest {

    @Test
    fun existingDeviceProtectedKeyIsNotOverwritten() {
        val bridge = FakePrefsBridge(ceCiphertext = "old-ce", deCiphertext = "existing-de")
        val logs = mutableListOf<String>()

        assertTrue(AdbKeyMigration.migrate(bridge) { logs.add(it) })
        assertEquals("existing-de", bridge.de[KEY_NAME])
        assertEquals("已有 DE 时不必访问 CE", 0, bridge.ceReads)
        assertEquals(0, bridge.deWrites)
        assertTrue("幂等快路径不刷开机日志", logs.isEmpty())
    }

    @Test
    fun lockedUserSkipsWithoutAccessingPreferences() {
        val bridge = FakePrefsBridge(unlocked = false, ceCiphertext = "ce-key")
        val logs = mutableListOf<String>()

        assertFalse(AdbKeyMigration.migrate(bridge) { logs.add(it) })
        assertEquals(0, bridge.ceReads)
        assertEquals(0, bridge.deReads)
        assertEquals(0, bridge.deWrites)
        assertTrue(bridge.de.isEmpty())
        assertEquals(listOf("ADB 私钥迁移：未解锁，跳过"), logs)
    }

    @Test
    fun credentialProtectedCiphertextIsCopiedByteForByteAndOnlyOnce() {
        // 包括空白、换行、填充符：不能解码重编码，也不能 trim。
        val ciphertext = "  AQID+/==\r\n"
        val bridge = FakePrefsBridge(ceCiphertext = ciphertext)

        assertTrue(AdbKeyMigration.migrate(bridge) {})
        assertEquals(ciphertext, bridge.de[KEY_NAME])
        assertArrayEquals(
            ciphertext.toByteArray(Charsets.UTF_8),
            bridge.de.getValue(KEY_NAME).toByteArray(Charsets.UTF_8),
        )
        assertEquals("复制不删除 CE 原件", ciphertext, bridge.ce[KEY_NAME])
        assertEquals("提交后还要读回", 2, bridge.deReads)
        assertTrue(AdbKeyMigration.migrate(bridge) {})
        assertEquals("重复迁移不再次写入", 1, bridge.deWrites)
    }

    @Test
    fun absentCredentialProtectedKeyReturnsFalse() {
        val bridge = FakePrefsBridge()

        assertFalse(AdbKeyMigration.migrate(bridge) {})
        assertEquals(0, bridge.deWrites)
        assertTrue(bridge.de.isEmpty())
    }

    @Test
    fun hasDeviceProtectedKeyReturnsTrueForStoredCiphertextEvenWhenLocked() {
        val bridge = FakePrefsBridge(unlocked = false, deCiphertext = "AQIDBA==")

        assertTrue(AdbKeyMigration.hasDeviceProtectedKey(bridge))
        assertEquals(0, bridge.ceReads)
        assertEquals(0, bridge.deWrites)
    }

    @Test
    fun hasDeviceProtectedKeyReturnsFalseWhenOnlyCredentialProtectedKeyExists() {
        val bridge = FakePrefsBridge(ceCiphertext = "ce-only")

        assertFalse(AdbKeyMigration.hasDeviceProtectedKey(bridge))
        assertEquals(0, bridge.ceReads)
    }

    @Test
    fun hasDeviceProtectedKeyReturnsFalseForEmptyCiphertext() {
        assertFalse(AdbKeyMigration.hasDeviceProtectedKey(FakePrefsBridge(deCiphertext = "")))
    }

    @Test
    fun successfulCopyIsLogged() {
        val logs = mutableListOf<String>()

        assertTrue(AdbKeyMigration.migrate(FakePrefsBridge(ceCiphertext = "ce-key")) { logs.add(it) })
        assertEquals(listOf("ADB 私钥已复制到 DE（锁屏可读）"), logs)
    }

    @Test
    fun failedCommitIsNotMistakenForSuccessEvenIfMemoryWasUpdated() {
        val bridge = FakePrefsBridge(ceCiphertext = "ce-key").apply { commitSucceeds = false }
        val logs = mutableListOf<String>()

        assertFalse(AdbKeyMigration.migrate(bridge) { logs.add(it) })
        assertEquals("模拟 SharedPreferences 先更新内存再提交失败", "ce-key", bridge.de[KEY_NAME])
        assertEquals(2, bridge.deReads)
        assertEquals(listOf("ADB 私钥迁移失败：同步写入失败"), logs)
    }

    @Test
    fun inconsistentReadBackReturnsFalseAndIsLogged() {
        val bridge = FakePrefsBridge(ceCiphertext = "ce-key").apply { readBackOverride = "different" }
        val logs = mutableListOf<String>()

        assertFalse(AdbKeyMigration.migrate(bridge) { logs.add(it) })
        assertEquals(listOf("ADB 私钥迁移失败：读回不一致"), logs)
    }

    @Test
    fun preferenceExceptionIsLoggedAndDoesNotEscapeMigration() {
        val bridge = FakePrefsBridge().apply { failDeRead = true }
        val logs = mutableListOf<String>()

        assertFalse(AdbKeyMigration.migrate(bridge) { logs.add(it) })
        assertTrue(logs.single().startsWith("ADB 私钥迁移异常："))
        assertEquals(0, bridge.deWrites)
    }

    @Test
    fun preferenceExceptionMakesDeviceProtectedCheckReturnFalse() {
        val bridge = FakePrefsBridge().apply { failDeRead = true }
        val logs = mutableListOf<String>()

        assertFalse(AdbKeyMigration.hasDeviceProtectedKey(bridge) { logs.add(it) })
        assertTrue(logs.single().startsWith("ADB 私钥 DE 检查异常："))
    }

    @Test
    fun loggingExceptionDoesNotEscapeMigration() {
        val bridge = FakePrefsBridge(unlocked = false)

        assertFalse(AdbKeyMigration.migrate(bridge) { error("fake log failure") })
        assertEquals(0, bridge.deWrites)
    }

    /** 同步提交结果与内存读回值可分别控制，覆盖 commit 失败也能读到值的情况。 */
    private class FakePrefsBridge(
        var unlocked: Boolean = true,
        ceCiphertext: String? = null,
        deCiphertext: String? = null,
    ) : AdbKeyMigration.PrefsBridge {
        val ce = mutableMapOf<String, String>()
        val de = mutableMapOf<String, String>()
        var ceReads = 0
        var deReads = 0
        var deWrites = 0
        var commitSucceeds = true
        var readBackOverride: String? = null
        var failDeRead = false

        init {
            ceCiphertext?.let { ce[KEY_NAME] = it }
            deCiphertext?.let { de[KEY_NAME] = it }
        }

        override fun isUnlocked(): Boolean = unlocked

        override fun ceGet(key: String): String? {
            ceReads++
            return ce[key]
        }

        override fun deGet(key: String): String? {
            deReads++
            check(!failDeRead) { "fake DE read failure" }
            return de[key]
        }

        override fun dePut(key: String, value: String): Boolean {
            deWrites++
            de[key] = readBackOverride ?: value
            return commitSucceeds
        }
    }

    private companion object {
        const val KEY_NAME = "adbkey"
    }
}
