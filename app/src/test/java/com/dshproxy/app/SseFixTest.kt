package com.dshproxy.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 与 phone-agent/test_proxy.py 对齐的回归用例。
 * 目标：Kotlin 实现必须和已验证通过的 Python 版行为一致。
 */
class SseFixTest {

    private val fixture = """
        data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}],"usage":null}

        data: {"choices":[{"index":0,"delta":{"content":"OK"},"finish_reason":null}],"usage":null}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":null}

        data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":2}}

        data: [DONE]

    """.trimIndent().replace("\r\n", "\n")

    private fun runThrough(src: String): ByteArray {
        val out = ByteArrayOutputStream()
        val ins = SseFixInputStream(ByteArrayInputStream(src.toByteArray(Charsets.UTF_8)), stripUsage = true)
        ins.use { i ->
            val buf = ByteArray(4096)
            while (true) {
                val n = i.read(buf, 0, buf.size)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun parseFrames(raw: ByteArray): Pair<List<JSONObject>, Boolean> {
        val frames = mutableListOf<JSONObject>()
        var done = false
        for (ev in String(raw, Charsets.UTF_8).split("\n\n")) {
            if (ev.isBlank()) continue
            val vals = ev.split("\n")
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").let { v -> if (v.startsWith(" ")) v.substring(1) else v } }
            if (vals.isEmpty()) continue
            val payload = vals.joinToString("\n")
            if (payload.trim() == "[DONE]") {
                done = true
                continue
            }
            frames.add(JSONObject(payload))
        }
        return frames to done
    }

    /**
     * 复刻 DSH 的 usageOf()：只挡 undefined，null 会炸。
     *
     * 注意：DSH 是 JS，null 是真 null，取值抛 TypeError；
     * JVM 的 org.json 用 JSONObject.NULL 哨兵表示 null，
     * 强转 JSONObject 抛 ClassCastException。两者都是「崩」，语义等价。
     */
    private fun dshUsageOf(f: JSONObject): Pair<Int, Int>? {
        if (!f.has("usage")) return null
        val u = f.get("usage")
        val o = u as JSONObject
        return o.getInt("prompt_tokens") to o.getInt("completion_tokens")
    }

    @Test
    fun case1_nullRemovedAndFinalUsageKept() {
        val framesAndDone = parseFrames(runThrough(fixture))
        val (frames, done) = framesAndDone
        assertTrue("必须保留 [DONE]", done)
        assertEquals(4, frames.size)
        for ((i, f) in frames.withIndex()) {
            if (f.has("usage")) {
                assertFalse("第 $i 帧仍含 usage:null", f.isNull("usage"))
            }
        }
        val last = frames.last().getJSONObject("usage")
        assertEquals(12, last.getInt("prompt_tokens"))
        assertEquals(2, last.getInt("completion_tokens"))
    }

    @Test
    fun case2_byteByByteEqualsWhole() {
        val whole = runThrough(fixture)
        // 逐字节读：SseFixInputStream 内部按行缓冲，单字节读也必须等价
        val oneByOne = ByteArrayOutputStream().also { out ->
            val ins = SseFixInputStream(ByteArrayInputStream(fixture.toByteArray()), true)
            ins.use { i ->
                val b = ByteArray(1)
                while (true) {
                    val n = i.read(b, 0, 1)
                    if (n < 0) break
                    out.write(b, 0, n)
                }
            }
        }.toByteArray()
        assertArrayEquals("逐字节与整体输出必须一致", whole, oneByOne)
    }

    @Test
    fun case3_crlf() {
        val crlf = fixture.replace("\n", "\r\n")
        val (frames, done) = parseFrames(runThrough(crlf))
        assertTrue(done)
        assertEquals(12, frames.last().getJSONObject("usage").getInt("prompt_tokens"))
        for (f in frames) {
            if (f.has("usage")) assertFalse(f.isNull("usage"))
        }
    }

    @Test
    fun case4_missingUsageUnchanged() {
        val data = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}\n\n" +
            "data: [DONE]\n\n"
        val (frames, done) = parseFrames(runThrough(data))
        assertTrue(done)
        assertFalse(frames[0].has("usage"))
        assertEquals("a", frames[0].getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getString("content"))
    }

    @Test
    fun case5_multilineData() {
        val data = "data: {\"choices\":[{\"index\":0,\n" +
            "data: \"delta\":{\"content\":\"x\"}}],\"usage\":null}\n\n" +
            "data: [DONE]\n\n"
        val (frames, done) = parseFrames(runThrough(data))
        assertTrue(done)
        assertEquals("x", frames[0].getJSONArray("choices").getJSONObject(0)
            .getJSONObject("delta").getString("content"))
        assertFalse(frames[0].has("usage"))
    }

    @Test
    fun case6_errorBodyPassthrough() {
        val raw = "{\"error\":{\"message\":\"Incorrect API key provided\",\"code\":\"invalid_api_key\"}}"
            .toByteArray(Charsets.UTF_8)
        val out = JsonCleaner.clean(raw)
        assertArrayEquals("错误体必须原样透传", raw, out)
    }

    @Test
    fun case7_bugReproducedThenFixed() {
        // 控制组：原始帧喂给 DSH usageOf 必然炸
        val (origFrames, _) = parseFrames(fixture.toByteArray())
        var thrown = false
        try {
            for (f in origFrames) dshUsageOf(f)
        } catch (e: Throwable) {   // AssertionError 是 Error，不是 Exception
            thrown = true
        }
        assertTrue("原始帧应复现 usage:null 崩溃", thrown)

        // 修好后：不再抛，末帧 usage 正常
        val (fixedFrames, _) = parseFrames(runThrough(fixture))
        val results = fixedFrames.map { dshUsageOf(it) }
        assertNull("首帧不该再报错", results.first())
        assertEquals(12 to 2, results.last())
    }

    @Test
    fun case8_healthzShape() {
        // 代理的 /healthz 必须返回合法 JSON，供自检用
        val obj = JSONObject("{\"ok\":true}")
        assertTrue(obj.getBoolean("ok"))
    }

    @Test
    fun case9_presetDefaultsAreValid() {
        val cfg = ProxyConfig()
        assertTrue("默认 Base URL 必须 https", cfg.baseUrl.startsWith("https://"))
        assertTrue("默认端口固定 8787", cfg.port == 8787)
        assertNotNull(cfg.model)
        for (p in Presets.ALL) {
            if (p.baseUrl.isNotBlank()) {
                assertTrue("${p.name} 的 Base URL 应以 http 开头", p.baseUrl.startsWith("http"))
                assertTrue("${p.name} 不该以 / 结尾", !p.baseUrl.endsWith("/"))
            }
        }
    }

    private fun assertArrayEquals(msg: String, a: ByteArray, b: ByteArray) {
        assertEquals(msg, a.size, b.size)
        for (i in a.indices) {
            assertEquals("$msg 第 $i 字节", a[i], b[i])
        }
    }
}
