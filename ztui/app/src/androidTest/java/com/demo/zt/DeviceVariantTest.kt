package com.demo.zt

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备识别与载荷自动选择验证。
 *
 * 用户报告："自动识别荷载没有识别到我的设备"。
 * 根因：totalMemGb 只读 /proc/meminfo，Android 10+ 常被拦截 → 返回 0.0
 * → `>= 14.0` 判定失败 → 永远落到 12G 版本。
 */
@RunWith(AndroidJUnit4::class)
class DeviceVariantTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun totalMemIsReadable() {
        AppContextHolder.init(context)
        val gb = DeviceInfo.totalMemGb
        // 任何真实设备/模拟器都 > 0.5GB；为 0 说明读取完全失败
        assertTrue("totalMemGb 应 > 0（实际=$gb），为 0 说明内存读取失败", gb > 0.5)
    }

    @Test
    fun autoVariantMatchesMemory() {
        AppContextHolder.init(context)
        val gb = DeviceInfo.totalMemGb

        // 代理由版本号/规则/历史决定，这里只断言**规格**随内存走。
        // 阈值 13 GiB：16G 机型实报 13.97~14.41 GiB，12G 机型约 10.8 GiB。
        val expect16G = gb >= 13.0
        val actual = Variant.AUTO.resolve()
        val actualIs16G = actual == Variant.ORANGE6_16G || actual == Variant.ORANGE7_16G
        assertEquals(
            "内存 ${gb}GiB 的规格判定不符（实际 $actual）",
            expect16G,
            actualIs16G,
        )

        // resolve() 必须与 decide() 同源，不允许两处各算一次
        val decision = PayloadResolver.decide(context)
        assertEquals(
            "resolve() 与 decide() 必须给出同一个变体",
            decision.variant,
            actual,
        )

        // 自动项标签必须带上同一个变体
        assertTrue(
            "标签应含 ${decision.variant.shortName}，实际=${Variant.AUTO.displayLabel(decision)}",
            Variant.AUTO.displayLabel(decision).contains(decision.variant.shortName),
        )
    }

    @Test
    fun memorySourceIsReported() {
        AppContextHolder.init(context)
        // 触发读取
        val bytes = DeviceInfo.memoryBytes
        assertTrue("memoryBytes 应 > 0", bytes > 0)
        assertTrue(
            "应记录内存来源，实际=${DeviceInfo.memorySource}",
            DeviceInfo.memorySource == "ActivityManager" ||
                DeviceInfo.memorySource == "/proc/meminfo"
        )
        assertTrue("memoryText 不应为未知", DeviceInfo.memoryText != "未知")
    }

    @Test
    fun kernelAndDeviceInfoNonEmpty() {
        AppContextHolder.init(context)
        assertTrue("设备名不应为空", DeviceInfo.deviceName.isNotBlank())
        assertTrue("内核版本不应为空", DeviceInfo.kernelRelease.isNotBlank())
    }
}
