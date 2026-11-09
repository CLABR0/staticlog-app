package com.demo.zt

import com.demo.zt.stats.UsageReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 上报体的**实际内容**测试：纯函数，不依赖 Android。
 *
 * 用 org.json（Android 自带、单测里有实现）真解析一遍，
 * 这样"拼出来的字符串恰好是合法 JSON"这件事是被验证过的，而不是靠眼看。
 */
class UsagePayloadTest {

    private fun build(
        id: String = "aaaaaaaa-1111-2222-3333-444444444444",
        event: String = UsageReporter.EVENT_INSTALL,
        version: String = "V41",
        sdk: String = "36",
        model: String = "V2408A",
        kernel: String = "6.6.127-android15-8-g24b70dd1cb81",
    ) = JSONObject(
        UsageReporter.buildPayloadFrom(id, event, version, sdk, model, kernel)
    )

    @Test
    fun payloadIsValidJsonWithExactlySixFields() {
        val json = build()
        assertEquals(
            setOf("install_id", "event", "version", "sdk", "model", "kernel"),
            json.keys().asSequence().toSet(),
        )
        assertEquals("aaaaaaaa-1111-2222-3333-444444444444", json.getString("install_id"))
        assertEquals("install", json.getString("event"))
        assertEquals("V41", json.getString("version"))
        assertEquals("36", json.getString("sdk"))
        assertEquals("V2408A", json.getString("model"))
        assertEquals("6.6.127-android15-8-g24b70dd1cb81", json.getString("kernel"))
    }

    @Test
    fun threeEventNamesAllSerialize() {
        for (e in listOf(
            UsageReporter.EVENT_INSTALL,
            UsageReporter.EVENT_HEARTBEAT,
            UsageReporter.EVENT_ESCALATE,
        )) {
            assertEquals(e, build(event = e).getString("event"))
        }
    }

    @Test
    fun quotesAndBackslashesAreEscaped() {
        val nasty = """vivo "V2408A" \ 测试"""
        val json = build(model = nasty)
        assertEquals(nasty, json.getString("model"))
        assertFalse("不能出现未转义的裸引号", json.toString().contains("\"\"vivo"))
    }

    @Test
    fun newlinesAndControlCharsAreNeutralized() {
        val json = build(kernel = "6.6.127\n\r\t\u0001")
        // 换行/回车/制表被转义成 \n \r \t，控制字符变空格：仍然是合法 JSON
        assertTrue(json.getString("kernel").startsWith("6.6.127"))
        assertFalse("不能带原始控制字符", json.getString("kernel").any { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' })
    }

    @Test
    fun emptyFieldsStillProduceValidJson() {
        val json = build(model = "", kernel = "")
        assertEquals("", json.getString("model"))
        assertEquals("", json.getString("kernel"))
    }

    @Test
    fun noSensitiveFieldEverAppears() {
        val text = UsageReporter.buildPayloadFrom("id-1234-5678", "install", "V41", "36", "M", "K")
        for (bad in listOf("imei", "android_id", "mac", "phone", "serial", "account", "latitude")) {
            assertFalse("上报体里不能出现 $bad", text.contains(bad, ignoreCase = true))
        }
    }
}
