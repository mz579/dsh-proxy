package com.dshproxy.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 本地 HTTP 代理（原生 ServerSocket 实现，不依赖 NanoHTTPD）。
 *
 * 为什么弃用 NanoHTTPD：
 *  1) 它的 IHTTPSession 只有 parseBody() 一条读 body 的路径；
 *  2) parseBody 不支持 Transfer-Encoding: chunked（会读到空 body）；
 *  3) 它不处理 Expect: 100-continue —— 客户端若等 100 就是死锁；
 *  4) parseBody 会对 body 做 .trim()，还可能按 US-ASCII 解码。
 *
 * 这里自己实现 HTTP/1.1：完整支持 content-length / chunked，逐字节透传。
 */
class ProxyServer(
    private val port: Int,
    private val cfgProvider: () -> ProxyConfig,
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * 工作线程池：有界，防止思考型模型慢响应时线程无限增长。
     * 队列满就拒绝，由 accept 循环回 503 并关连接（不阻塞 accept）。
     */
    private val worker = java.util.concurrent.ThreadPoolExecutor(
        4, 16, 30, TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(128),
        { r -> Thread(r, "proxy-worker").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )

    companion object {
        /** 逐跳头 + 由我们自己重写的头，一律不透传。 */
        private val HOP = setOf(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "upgrade", "host", "content-length",
            "accept-encoding", "transfer-encoding", "expect",
        )
        /**
         * OkHttp 默认 maxRequestsPerHost=5：思考型模型响应慢时，同时挂起的请求
         * 超过 5 个，后续请求会**阻塞在 dispatcher 队列**，表现为「代理突然不回」。
         * 这里把并发上限调高，并显式建 client 实例（不每次 newBuilder 复制）。
         */
        private val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 64
        }

        private val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(20, TimeUnit.SECONDS)
            // SSE 长思考：读超时给足 300s，避免思考型模型「想很久」被提前掐断。
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private const val MAX_HEADER = 64 * 1024
        // DSH 的请求实测约 60KB；64MB 上限在手机上可能 OOM，收敛到 16MB。
        private const val MAX_BODY = 16L * 1024 * 1024
    }

    fun startServer() {
        if (running) return
        val ss = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        running = true
        acceptThread = Thread({
            ProxyLog.add("代理已启动 127.0.0.1:$port")
            while (running) {
                var socket: Socket? = null
                try {
                    socket = ss.accept()
                    val s = socket
                    try {
                        worker.execute { handleSocket(s) }
                    } catch (rej: java.util.concurrent.RejectedExecutionException) {
                        // 线程池满：回 503 并关闭，绝不阻塞 accept
                        ProxyLog.add("并发已满，拒绝一个连接")
                        try {
                            s.getOutputStream().write(
                                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.ISO_8859_1)
                            )
                        } catch (_: Exception) {}
                        try { s.close() } catch (_: Exception) {}
                    }
                } catch (e: Throwable) {
                    // 注意：必须 catch Throwable。之前只 catch Exception，
                    // 一旦线程池无界导致 OutOfMemoryError，accept 线程会直接死掉，
                    // 表现为「监听还在、队列积压、连 healthz 都不回」。
                    try { socket?.close() } catch (_: Exception) {}
                    if (running) ProxyLog.add("accept 异常：${e.message}")
                }
            }
        }, "proxy-accept").apply { isDaemon = true; start() }
    }

    fun stopServer() {
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { acceptThread?.join(800) } catch (_: Exception) {}
        acceptThread = null
        // 关键：不关线程池，反复启停会泄漏 worker 线程（每次 startProxy 都 new 一个 ProxyServer）。
        try { worker.shutdownNow() } catch (_: Exception) {}
        if (wasRunning) ProxyLog.add("代理已停止")
    }

    // ---------------------------------------------------------------- 连接处理

    private fun handleSocket(socket: Socket) {
        try {
            // 只限制「读请求」阶段：30 秒还没把请求头发完，就是死连接，直接释放线程。
            // 之前设 600s，半开连接会白占 worker 线程 10 分钟，是线程耗尽的真凶。
            // 响应阶段（含 SSE 长流）不受 soTimeout 影响，上游慢不在此列。
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()

            val req = readRequest(input, output) ?: return
            writeResponse(output, req)
        } catch (_: SocketTimeoutException) {
            // 空闲/半开连接超时，正常
        } catch (e: Throwable) {
            ProxyLog.add("连接处理异常：${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private class HttpReq(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val httpVersion: String,
    )

    /** 读一个完整请求（含 body）；流结束返回 null。 */
    private fun readRequest(input: BufferedInputStream, output: OutputStream): HttpReq? {
        val headerBytes = readHeaderBlock(input) ?: return null
        val text = String(headerBytes, Charsets.ISO_8859_1)
        // 兼容 CRLF 与 LF 两种换行
        val lines = text.split("\r\n", "\n")
        if (lines.isEmpty() || lines[0].isBlank()) return null

        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val httpVersion = if (requestLine.size >= 3) requestLine[2] else "HTTP/1.0"

        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase()
            val v = line.substring(idx + 1).trim()
            headers[k] = if (headers.containsKey(k)) headers[k] + ", " + v else v
        }

        // 客户端带 Expect: 100-continue 时会先等一个 100 再发 body；
        // 不回就会互等死锁。这里立即回 100 Continue。
        if (headers["expect"]?.contains("100-continue", true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        var body = ByteArray(0)
        val te = headers["transfer-encoding"]?.lowercase() ?: ""
        if (te.contains("chunked")) {
            body = readChunked(input)
        } else {
            val cl = headers["content-length"]?.toLongOrNull() ?: 0L
            if (cl > 0) {
                if (cl > MAX_BODY) {
                    writeSimple(output, 413, "Payload Too Large", "application/json",
                        """{"error":{"message":"request body too large"}}""".toByteArray())
                    return null
                }
                body = readExactly(input, cl.toInt())
            }
        }
        return HttpReq(method, target, headers, body, httpVersion)
    }

    /**
     * 读到请求头结束为止（\r\n\r\n 或 \n\n），返回 header 块字节。
     * 同时兼容只用 LF 的客户端（少量 HTTP 客户端会这么发，只认 CRLF 会一直读到超限）。
     */
    private fun readHeaderBlock(input: BufferedInputStream): ByteArray? {
        val buf = ByteArrayOutputStream(1024)
        var crlf = 0      // 连续 \r\n\r\n 的进度
        var lf = 0        // 连续 \n\n 的进度
        while (buf.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toByteArray()
            buf.write(b)
            // CRLF 匹配
            crlf = when {
                (crlf == 0 || crlf == 2) && b == 13 -> crlf + 1
                (crlf == 1 || crlf == 3) && b == 10 -> crlf + 1
                else -> if (b == 13) 1 else 0
            }
            // LF-only 匹配（\n\n）
            lf = if (b == 10) lf + 1 else 0
            if (crlf == 4 || lf == 2) return buf.toByteArray()
        }
        throw IllegalStateException("header too large")
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    /** 解 chunked 编码。 */
    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val hex = sizeLine.trim().substringBefore(';').trim()
            val size = try {
                hex.toInt(16)
            } catch (_: Exception) {
                break
            }
            // 防御：负数或超大 chunk 直接拒绝，避免 ByteArray 分配爆内存
            if (size < 0 || size > MAX_BODY) throw IllegalStateException("bad chunk size: $size")
            if (size == 0) {
                while (true) {
                    val t = readLine(input) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            out.write(readExactly(input, size))
            readLine(input)
            if (out.size() > MAX_BODY) throw IllegalStateException("chunked body too large")
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == 10) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    // ---------------------------------------------------------------- 转发与写回

    private fun writeResponse(output: OutputStream, req: HttpReq) {
        val c = cfgProvider()
        val base = c.baseUrl.trimEnd('/')

        // 兼容 absolute-form request-target（个别客户端会发 "POST http://h/p HTTP/1.1"）
        val originTarget = if (req.target.startsWith("http://") || req.target.startsWith("https://")) {
            try { java.net.URI(req.target).let { it.rawPath + (it.rawQuery?.let { q -> "?$q" } ?: "") } }
            catch (_: Exception) { req.target }
        } else req.target

        if (originTarget == "/healthz" || originTarget == "/healthz/") {
            writeSimple(output, 200, "OK", "application/json; charset=utf-8", """{"ok":true}""".toByteArray())
            return
        }

        val rest0 = if (originTarget.startsWith("/v1")) originTarget.substring(3) else originTarget
        val target = base + rest0

        val te = req.headers["transfer-encoding"] ?: "-"
        ProxyLog.add("→ ${req.method} ${originTarget} body=${req.body.size}B te=$te")

        val jsonType = "application/json".toMediaType()
        // OkHttp 要求 POST/PUT/PATCH/DELETE 必须带 body 对象（哪怕是空体）。
        // 只有 GET/HEAD 可以传 null；否则会抛 "method POST must have a request body"。
        val needsBody = req.method !in setOf("GET", "HEAD")
        val rb = if (!needsBody) null else req.body.toRequestBody(jsonType)

        val builder = try {
            Request.Builder().url(target).method(req.method, rb)
        } catch (e: Exception) {
            writeSimple(output, 400, "Bad Request", "application/json",
                """{"error":{"message":"bad target: ${esc(e.message)}"}}""".toByteArray())
            return
        }

        val inboundHasAuth = req.headers.keys.any { it.equals("authorization", true) }
        for ((k, v) in req.headers) {
            if (k.lowercase() in HOP) continue
            try { builder.header(k, v) } catch (_: Exception) {}
        }
        builder.header("Accept-Encoding", "identity")
        if (c.apiKey.isNotBlank() && !inboundHasAuth) {
            builder.header("Authorization", "Bearer ${c.apiKey}")
        }

        // 直接用共享 client：newBuilder() 每次都会复制，且旧的 builder 版本
        // 在慢响应场景下容易让人误以为代理卡死。readTimeout 放在共享 client 上。
        val resp = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            ProxyLog.add("上游请求失败：${e.message}")
            writeSimple(output, 502, "Bad Gateway", "application/json",
                """{"error":{"message":"upstream failed: ${esc(e.message)}"}}""".toByteArray())
            return
        }

        resp.use { r ->
            val ct = r.header("Content-Type") ?: ""
            ProxyLog.add("← ${r.code} ${originTarget} ct=${ct.take(30)}")
            if (ct.contains("text/event-stream", true)) {
                val body = r.body
                if (body == null) {
                    writeSimple(output, 502, "Bad Gateway", "application/json",
                        """{"error":{"message":"upstream returned empty SSE body"}}""".toByteArray())
                    return
                }
                writeSse(output, body)
                return
            }
            val bodyBytes = r.body?.bytes() ?: ByteArray(0)
            val outBody = if (r.code >= 400) bodyBytes else JsonCleaner.clean(bodyBytes)
            writeSimple(output, r.code, reasonOf(r.code), sanitizeHeaderValue(ct.ifBlank { "application/json" }), outBody)
        }
    }

    /** 去掉 header 值里的 CR/LF，防止上游返回的 Content-Type 注入响应头。 */
    private fun sanitizeHeaderValue(v: String): String =
        v.replace("\r", "").replace("\n", "").take(200)

    /** 流式写 SSE：chunked 编码，逐事件 flush。 */
    private fun writeSse(output: OutputStream, body: okhttp3.ResponseBody) {
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: text/event-stream\r\n")
        head.append("Cache-Control: no-cache\r\n")
        head.append("X-Accel-Buffering: no\r\n")
        head.append("Transfer-Encoding: chunked\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val clean = SseFixInputStream(body.byteStream(), stripUsage = true)
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = clean.read(buf, 0, buf.size)
                if (n < 0) break
                if (n == 0) continue
                output.write((Integer.toHexString(n) + "\r\n").toByteArray(Charsets.ISO_8859_1))
                output.write(buf, 0, n)
                output.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
        } catch (e: Exception) {
            ProxyLog.add("SSE 写出中断：${e.message}")
        }
        try {
            output.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun writeSimple(output: OutputStream, code: Int, reason: String, contentType: String, body: ByteArray) {
        val head = StringBuilder()
        head.append("HTTP/1.1 $code $reason\r\n")
        head.append("Content-Type: $contentType\r\n")
        head.append("Content-Length: ${body.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 402 -> "Payment Required"
        403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
        406 -> "Not Acceptable"; 408 -> "Request Timeout"; 409 -> "Conflict"
        410 -> "Gone"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
        418 -> "I'm a teapot"; 422 -> "Unprocessable Entity"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"
        503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        529 -> "Site is overloaded"
        // 未知状态码：用通用 reason，保证状态行合法（不能一律写 OK）
        else -> "Status"
    }

    private fun esc(s: String?): String =
        (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
