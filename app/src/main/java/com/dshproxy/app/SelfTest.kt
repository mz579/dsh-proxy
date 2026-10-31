package com.dshproxy.app

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** 自检：DNS → TCP → TLS → /models → 一次最小对话。 */
object SelfTest {

    fun run(cfg: ProxyConfig, log: (String) -> Unit) {
        val base = cfg.baseUrl.trimEnd('/')
        val host = try {
            URL(base).host
        } catch (e: Exception) {
            log("✗ Base URL 不合法：${e.message}")
            return
        }
        log("目标：$host")
        // 只做长度与空白的粗检，不打印 Key 任何片段（避免敏感信息进日志）
        if (cfg.apiKey.isNotBlank()) {
            val k = cfg.apiKey
            if (k.any { it.isWhitespace() }) {
                log("· 提示：Key 里含有空白字符，可能是复制时带入的，建议重新粘贴")
            }
            if (!k.startsWith("sk-")) {
                log("· 提示：Key 通常以 sk- 开头，当前不是，请核对")
            }
        }

        // 1. DNS
        val addrs = try {
            val all = InetAddress.getAllByName(host)
            log("✓ DNS：${all.joinToString { it.hostAddress ?: "?" }}")
            all
        } catch (e: Exception) {
            log("✗ DNS 失败：${e.message}")
            return
        }

        // 2. TCP（端口按 scheme 取，别写死 443）
        val https = base.startsWith("https")
        val port = if (https) 443 else 80
        try {
            Socket().use { it.connect(InetSocketAddress(addrs[0], port), 6000) }
            log("✓ TCP $port 可连接")
        } catch (e: Exception) {
            log("✗ TCP $port 失败：${e.message}")
            return
        }

        // 3. TLS（仅 https 才有意义）
        if (https) {
            try {
                val ctx = SSLContext.getDefault()
                val s = ctx.socketFactory.createSocket() as SSLSocket
                s.connect(InetSocketAddress(host, 443), 8000)
                s.startHandshake()
                log("✓ TLS：${s.session.protocol} ${s.session.cipherSuite}")
                s.close()
            } catch (e: Exception) {
                log("✗ TLS 失败：${e.message}")
            }
        } else {
            log("· 明文 http，跳过 TLS")
        }

        // 4. /models
        try {
            val (code, body) = httpText("$base/models", cfg.apiKey, "GET", null, readBody = true)
            log("✓ /models 返回 HTTP $code" + if (code == 401) "（Key 未带或无效）" else "")
            if (code == 401 && body.isNotBlank()) {
                log("  401 详情：${body.take(200)}")
            }
        } catch (e: Exception) {
            log("✗ /models 失败：${e.message}")
        }

        // 5. 最小对话（仅当有 Key）
        if (cfg.apiKey.isNotBlank()) {
            try {
                val payload = """{"model":"${cfg.model}","messages":[{"role":"user","content":"say OK"}],"max_tokens":8}"""
                val code = httpCode("$base/chat/completions", cfg.apiKey, "POST", payload)
                log("✓ 对话接口返回 HTTP $code")
            } catch (e: Exception) {
                log("✗ 对话接口失败：${e.message}")
            }
        } else {
            log("· 未在 App 内配置 Key，跳过直连对话测试")
        }

        // 6. 经本地代理端到端（最关键：这才是 DSH 真正走的路径）
        //    验证代理能转发，且流式里不再有 usage:null。
        try {
            val url = "http://127.0.0.1:${cfg.port}/v1/chat/completions"
            val payload = """{"model":"${cfg.model}","messages":[{"role":"user","content":"say OK"}],"stream":true,"max_tokens":8}"""
            val (code, sse) = httpText(url, cfg.apiKey, "POST", payload)
            if (code != 200) {
                log("✗ 经本地代理失败：HTTP $code")
            } else {
                val hasDone = sse.contains("[DONE]")
                val hasUsageNull = sse.contains("\"usage\": null") || sse.contains("\"usage\":null")
                val hasData = sse.contains("data:")
                log("✓ 经本地代理 HTTP 200（流式事件 ${if (hasData) "有" else "无"}，[DONE] ${if (hasDone) "有" else "无"}）")
                if (hasUsageNull) {
                    log("✗ 流中仍含 usage:null —— 清洗未生效！")
                } else {
                    log("✓ usage:null 已清除")
                }
            }
        } catch (e: Exception) {
            log("✗ 经本地代理测试失败：${e.message}")
        }
    }

    private fun httpCode(url: String, key: String, method: String, body: String?): Int {
        val (code, _) = httpText(url, key, method, body, readBody = false)
        return code
    }

    /** 发请求，返回 (状态码, 响应正文)。readBody=false 时只取状态码。 */
    private fun httpText(url: String, key: String, method: String, body: String?, readBody: Boolean = true): Pair<Int, String> {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 30000
        conn.requestMethod = method
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = conn.responseCode
            val text = if (readBody) {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } else ""
            code to text
        } finally {
            conn.disconnect()
        }
    }
}
