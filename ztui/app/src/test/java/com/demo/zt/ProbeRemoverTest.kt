package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拔除 KO 探针：模块选择、配置槽写入、结论判定。
 *
 * 这几个点是「抄月红」时最容易抄错的地方，所以逐条钉住：
 *   - KMI 表必须与上游 `select_ko_image` 完全一致（版本号 + 兜底规则）
 *   - 配置槽布局必须是 magic(8) / cfg1(8) / cfg2(8)，auto 时 cfg2=1
 *   - 失败原因必须能从内核原始输出里认出来，不能一律报「未知」
 */
class ProbeRemoverTest {

    // ── 内核串解析 ──

    @Test
    fun parsesAndroidAndKernelFromRealRelease() {
        // 真机内核串
        val p = ProbeRemover.parseKernel(
            "6.6.127-android15-8-g24b70dd1cb81-abogki543627741-4k",
        )
        assertNotNull(p)
        assertEquals(15, p!!.first)
        assertEquals(6, p.second)
        assertEquals(6, p.third)
    }

    @Test
    fun parsesReleaseWithoutAndroidToken() {
        val p = ProbeRemover.parseKernel("6.1.145-android14-11-gabc-4k")
        assertEquals(14, p!!.first)
        assertEquals(6, p.second)
        assertEquals(1, p.third)
    }

    @Test
    fun missingAndroidTokenIsNullSoCallerCanFallBackToProp() {
        val p = ProbeRemover.parseKernel("6.6.127-g24b70dd1cb81-abogki543627741-4k")
        assertNotNull(p)
        assertNull("内核串没有 androidNN 时必须交给属性兜底", p!!.first)
        assertEquals(6, p.second)
    }

    @Test
    fun garbageReleaseIsNull() {
        assertNull(ProbeRemover.parseKernel(""))
        assertNull(ProbeRemover.parseKernel("unknown"))
    }

    // ── 模块选择 ──

    @Test
    fun selectsExactKmiForThisDevice() {
        val k = ProbeRemover.selectKmi(15, 6, 6)
        assertNotNull(k)
        assertEquals("probe/dfroot-android15-6.6.ko", k!!.asset)
        assertEquals("android15-6.6", k.label)
    }

    @Test
    fun fallsBackToSameKernelVersionWhenAndroidDiffers() {
        // 上游行为：同 major.minor 但 android 对不上 → 取同版本的第一个
        val k = ProbeRemover.selectKmi(99, 5, 10)
        assertNotNull(k)
        assertEquals(12, k!!.android) // 表里 5.10 的第一个是 android12
    }

    @Test
    fun androidNullStillFallsBackToSameKernelVersion() {
        val k = ProbeRemover.selectKmi(null, 6, 12)
        assertEquals(16, k!!.android)
    }

    @Test
    fun unknownKernelVersionHasNoModule() {
        assertNull("5.4 没有对应模块，必须返回 null 而不是随便挑一个",
            ProbeRemover.selectKmi(15, 5, 4))
    }

    @Test
    fun tableCoversEveryBundledAsset() {
        // 表里有几项，assets/probe 下就该有几个 .ko，防止表与文件脱节
        assertEquals(8, ProbeRemover.TABLE.size)
        val names = ProbeRemover.TABLE.map { it.asset.substringAfterLast('/') }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.contains("dfroot-android15-6.6.ko"))
    }

    // ── 配置槽 ──

    /** 造一个最小可用的模块镜像：只有配置槽，前后各留一点字节。 */
    private fun fakeModule(): ByteArray {
        val head = ByteArray(16) { 0x7F }
        val tail = ByteArray(16) { 0x11 }
        return head + ProbeRemover.MAGIC + ByteArray(16) + tail
    }

    @Test
    fun patchWritesAutoModeIntoConfigSlot() {
        val patched = ProbeRemover.patchAutoMode(fakeModule())
        val slot = ProbeRemover.readSlot(patched)
        assertNotNull(slot)
        assertEquals("魔数必须原样保留", 0xDFC0FF16DFC0FF16UL.toLong(), slot!![0])
        assertEquals("auto 模式 cfg1 必须为 0", 0L, slot[1])
        assertEquals("auto 模式 cfg2 必须为 1", 1L, slot[2])
    }

    @Test
    fun patchDoesNotTouchBytesOutsideSlot() {
        val src = fakeModule()
        val patched = ProbeRemover.patchAutoMode(src)
        val at = src.indexOfFirst { it == ProbeRemover.MAGIC[0] }
        assertEquals("长度不能变", src.size, patched.size)
        for (i in src.indices) {
            if (i in at until at + 24) continue
            assertEquals("槽外字节被改了：$i", src[i], patched[i])
        }
    }

    @Test
    fun patchRefusesModuleWithoutMagic() {
        val broken = ByteArray(128) { 0x22 }
        val err = runCatching { ProbeRemover.patchAutoMode(broken) }.exceptionOrNull()
        assertNotNull("没有配置槽必须拒绝，不能盲写", err)
        assertTrue(err!!.message!!.contains("配置槽"))
    }

    @Test
    fun patchIsIdempotent() {
        val once = ProbeRemover.patchAutoMode(fakeModule())
        val twice = ProbeRemover.patchAutoMode(once)
        assertTrue(once.contentEquals(twice))
    }

    // ── 结论判定 ──

    @Test
    fun recognisesBtfAutoSuccess() {
        val r = ProbeRemover.interpret(
            "insmod_rc=0\n[ 12.3] dfroot: W1c vr sys_exit probe disarmed (funcs=NULL, off=72, btf)",
        )
        assertTrue(r.message, r.ok)
        assertTrue(r.message.contains("BTF"))
    }

    @Test
    fun recognisesManualOffsetSuccess() {
        val r = ProbeRemover.interpret(
            "insmod_rc=0\ndfroot: W1c vr sys_exit probe disarmed (funcs=NULL, off=64)",
        )
        assertTrue(r.ok)
    }

    @Test
    fun alreadyLoadedCountsAsDone() {
        val r = ProbeRemover.interpret("insmod: failed: File exists")
        assertTrue("重复加载说明探针已经处于拔除状态", r.ok)
        assertTrue(r.message.contains("重启后失效"))
    }

    @Test
    fun signatureRejectionIsReportedPrecisely() {
        val r = ProbeRemover.interpret("insmod: failed: module verification failed: signature and/or required key missing")
        assertFalse(r.ok)
        assertTrue("必须点明签名问题，不能糊成未知失败", r.message.contains("签名"))
    }

    @Test
    fun kmiMismatchIsReportedAsFormatError() {
        val r = ProbeRemover.interpret("insmod: failed: Exec format error")
        assertFalse(r.ok)
        assertTrue(r.message.contains("不匹配"))
    }

    @Test
    fun unresolvedOffsetMeansNotRemoved() {
        val r = ProbeRemover.interpret("insmod_rc=0\ndfroot: vr auto: funcs offset unresolved, abort")
        assertFalse("没解出偏移就是没拔掉", r.ok)
    }

    @Test
    fun unknownOutputIsNotTreatedAsSuccess() {
        val r = ProbeRemover.interpret("insmod_rc=0\n")
        assertFalse("认不出来就不能报成功", r.ok)
        assertTrue(r.message.contains("未能确认"))
    }

    @Test
    fun buildScriptUsesPushedPathAndPrintsRc() {
        val s = ProbeRemover.buildScript()
        assertTrue(s.contains("insmod ${ProbeRemover.REMOTE_KO}"))
        assertTrue("必须回读退出码", s.contains("insmod_rc"))
        assertTrue("必须带内核日志", s.contains("dmesg"))
    }

    // ── 真实模块文件 ──

    @Test
    fun everyBundledModuleHasUsableConfigSlot() {
        // 单测工作目录是 app/，assets 就在 src/main 下
        val dir = java.io.File("src/main/assets/probe")
        assertTrue("找不到内置模块目录：${dir.absolutePath}", dir.isDirectory)
        val kos = dir.listFiles { f -> f.name.endsWith(".ko") }.orEmpty()
        assertEquals("内置模块数量应与 KMI 表一致", ProbeRemover.TABLE.size, kos.size)

        for (ko in kos) {
            val patched = ProbeRemover.patchAutoMode(ko.readBytes())
            val slot = ProbeRemover.readSlot(patched)
            assertNotNull("${ko.name} 没有配置槽", slot)
            assertEquals("${ko.name} 魔数", 0xDFC0FF16DFC0FF16UL.toLong(), slot!![0])
            assertEquals("${ko.name} auto 标志", 1L, slot[2])
            assertEquals("${ko.name} 长度必须保持不变", ko.length(), patched.size.toLong())
        }
    }

    @Test
    fun bundledModuleNamesMatchTableExactly() {
        val expected = ProbeRemover.TABLE
            .map { it.asset.substringAfterLast('/') }
            .toSet()
        val actual = java.io.File("src/main/assets/probe")
            .listFiles { f -> f.name.endsWith(".ko") }
            .orEmpty()
            .map { it.name }
            .toSet()
        assertEquals("表与 assets 必须一一对应，否则会运行期才炸", expected, actual)
    }
}
