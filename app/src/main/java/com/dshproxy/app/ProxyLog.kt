package com.dshproxy.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 进程内环形日志，供界面显示与排障使用。 */
object ProxyLog {

    private const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    @Synchronized
    fun add(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        listeners.forEach { runCatching { it(line) } }
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun observe(l: (String) -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun unobserve(l: (String) -> Unit) {
        listeners.remove(l)
    }
}
