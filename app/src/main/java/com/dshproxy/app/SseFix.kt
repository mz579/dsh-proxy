package com.dshproxy.app

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 把上游 SSE 流转成"清洗后"的字节流：删掉顶层 "usage": null。
 * 逐事件处理，保留 [DONE] 与非 data 行。
 */
class SseFixInputStream(src: InputStream, private val stripUsage: Boolean = true) : InputStream() {

    private val reader = BufferedReader(InputStreamReader(src, Charsets.UTF_8))
    private val pending = ArrayList<String>()
    private var outBuf: ByteArray = ByteArray(0)
    private var outPos = 0
    private var eof = false

    override fun read(): Int {
        if (!ensure()) return -1
        return outBuf[outPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!ensure()) return -1
        val n = minOf(len, outBuf.size - outPos)
        System.arraycopy(outBuf, outPos, b, off, n)
        outPos += n
        return n
    }

    private fun ensure(): Boolean {
        while (outPos >= outBuf.size) {
            if (eof) return false
            val line = reader.readLine()
            when {
                line == null -> {
                    eof = true
                    emit()
                }
                line.isEmpty() -> emit()
                else -> pending.add(line)
            }
        }
        return true
    }

    private fun emit() {
        outPos = 0
        val lines = ArrayList(pending)
        pending.clear()
        if (lines.isEmpty()) {
            // 空行 = 事件分隔符，必须原样回一个空行。
            outBuf = "\n".toByteArray(Charsets.UTF_8)
            return
        }
        val dataVals = ArrayList<String>()
        val others = ArrayList<String>()
        for (l in lines) {
            if (l.startsWith("data:")) {
                var v = l.substring(5)
                if (v.startsWith(" ")) v = v.substring(1)
                dataVals.add(v)
            } else if (l == "data") {
                dataVals.add("")
            } else {
                others.add(l)
            }
        }
        if (dataVals.isEmpty()) {
            outBuf = (lines.joinToString("\n") + "\n\n").toByteArray(Charsets.UTF_8)
            return
        }
        val payload = dataVals.joinToString("\n")
        var newPayload = payload
        var changed = false
        if (stripUsage && payload.trim() != "[DONE]") {
            try {
                val obj = JSONObject(payload)
                if (obj.has("usage") && obj.isNull("usage")) {
                    obj.remove("usage")
                    newPayload = obj.toString()
                    changed = true
                }
            } catch (_: Exception) {
                // 非 JSON 对象，原样透传
            }
        }
        outBuf = if (!changed) {
            (lines.joinToString("\n") + "\n\n").toByteArray(Charsets.UTF_8)
        } else {
            val sb = StringBuilder()
            for (o in others) sb.append(o).append('\n')
            sb.append("data: ").append(newPayload).append("\n\n")
            sb.toString().toByteArray(Charsets.UTF_8)
        }
    }
}

object JsonCleaner {
    fun clean(raw: ByteArray): ByteArray {
        return try {
            val text = String(raw, Charsets.UTF_8)
            val obj = JSONObject(text)
            if (obj.has("usage") && obj.isNull("usage")) {
                obj.remove("usage")
                obj.toString().toByteArray(Charsets.UTF_8)
            } else {
                raw
            }
        } catch (_: Exception) {
            raw
        }
    }
}
