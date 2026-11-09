package com.demo.zt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 橘子7 载荷替换的**字节级**守卫（2026-11-04 换成 A17 SukiSU 包）。
 *
 * 目的不是"验证载荷能跑"（那只能真机验），而是保证：
 *   1. 7-12g / 7-16g 用的确实是被替换后的新包，不是旧包
 *   2. 6-12g / 6-16g 一个字节都没动
 *   3. 新包的特征能被区分：zt_prep.zte 由 6,222,474 B 变小到 4,892,198 B
 */
class Orange7PayloadSwapTest {

    private val assets = File("src/main/assets/variants")

    private fun sha256(path: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(path.readBytes())
            .joinToString("") { "%02X".format(it) }

    private data class Expect(val size: Long, val shaPrefix: String)

    private val expected = mapOf(
        // 橘子6：本次未替换，哈希必须保持原样
        "6-12g/zt_fanout.zte" to Expect(1020312, "AFE54537558677F7"),
        "6-12g/zt_run" to Expect(603608, "92D382EB4D0FFB08"),
        "6-12g/zt_su" to Expect(603608, "8C2CDADFDDC985A6"),
        "6-12g/zt_su.zte" to Expect(721904, "A96A0B6A7E0C1275"),
        "6-16g/zt_fanout.zte" to Expect(1020112, "50FFE8F0D84E18C0"),
        "6-16g/zt_run" to Expect(603608, "376D8FA69D1EB6B0"),
        "6-16g/zt_su" to Expect(603608, "8CC31CC4FC36A3F5"),
        "6-16g/zt_su.zte" to Expect(721904, "D2DD5702B5E27DCC"),
        // 橘子7：替换后的 A17 SukiSU 包
        "7-12g/zt_fanout.zte" to Expect(1022744, "86B71256799586F7"),
        "7-12g/zt_prep.zte" to Expect(4892198, "778E71FA4DD388C1"),
        "7-12g/zt_run" to Expect(603608, "DB086DF35AA6D621"),
        "7-16g/zt_fanout.zte" to Expect(1022512, "61417B091D5A831A"),
        "7-16g/zt_prep.zte" to Expect(4892198, "2595E68E65B3ADE2"),
        "7-16g/zt_run" to Expect(603608, "510FF897946D3D64"),
    )

    @Test
    fun everyPayloadMatchesExpectedBytes() {
        for ((rel, exp) in expected) {
            val f = File(assets, rel)
            assertTrue("缺文件：$rel", f.isFile)
            assertEquals("$rel 字节数不符（是不是又被换回旧包了？）", exp.size, f.length())
            assertTrue(
                "$rel 哈希不符：实际 ${sha256(f).take(16)}",
                sha256(f).startsWith(exp.shaPrefix),
            )
        }
    }

    @Test
    fun orange7PrepIsTheNewSmallerBuild() {
        val new12 = File(assets, "7-12g/zt_prep.zte")
        val new16 = File(assets, "7-16g/zt_prep.zte")
        // 旧包是 6,222,474 B；新包 4,892,198 B
        assertEquals(4_892_198L, new12.length())
        assertEquals(4_892_198L, new16.length())
        assertNotEquals(6_222_474L, new12.length())
    }

    @Test
    fun orange7VariantsStillListThreeFiles() {
        assertEquals(
            listOf("zt_run", "zt_fanout.zte", "zt_prep.zte"),
            Variant.ORANGE7_12G.files,
        )
        assertEquals(
            listOf("zt_run", "zt_fanout.zte", "zt_prep.zte"),
            Variant.ORANGE7_16G.files,
        )
        for (name in Variant.ORANGE7_12G.files) {
            assertTrue("7-12g 缺 $name", File(assets, "7-12g/$name").isFile)
            assertTrue("7-16g 缺 $name", File(assets, "7-16g/$name").isFile)
        }
    }

    @Test
    fun orange6StillHasItsOwnFourFiles() {
        for (dir in listOf("6-12g", "6-16g")) {
            val files = File(assets, dir).listFiles()!!.map { it.name }.sorted()
            assertEquals(
                "$dir 文件集合应与橘子6 官方包一致（不含 zt_prep.zte）",
                listOf("zt_fanout.zte", "zt_run", "zt_su", "zt_su.zte"),
                files,
            )
        }
    }

    @Test
    fun noStrayFilesInVariantDirs() {
        val allowed = mapOf(
            "6-12g" to setOf("zt_fanout.zte", "zt_run", "zt_su", "zt_su.zte"),
            "6-16g" to setOf("zt_fanout.zte", "zt_run", "zt_su", "zt_su.zte"),
            "7-12g" to setOf("zt_fanout.zte", "zt_prep.zte", "zt_run"),
            "7-16g" to setOf("zt_fanout.zte", "zt_prep.zte", "zt_run"),
        )
        for (dir in assets.listFiles()!!.filter { it.isDirectory }) {
            val want = allowed[dir.name] ?: continue
            val got = dir.listFiles()!!.map { it.name }.toSet()
            assertEquals("$dir 有多余或缺失文件", want, got)
        }
    }
}
