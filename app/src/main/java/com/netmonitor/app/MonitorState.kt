package com.netmonitor.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

enum class Status { IDLE, OK, WARN, FAIL }

data class LogLine(val time: String, val msg: String, val type: String)

/** Process-wide state shared between the foreground service and the UI. */
object MonitorState {
    @Volatile var running = false
    @Volatile var status = Status.IDLE
    @Volatile var fails = 0
    @Volatile var latency = -1
    @Volatile var downSince = 0L
    @Volatile var disconnected = false
    @Volatile var disconnectCount = 0
    @Volatile var totalTimeouts = 0

    private val lines = mutableListOf<LogLine>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun log(msg: String, type: String = "ok") {
        synchronized(lines) {
            lines.add(0, LogLine(fmt.format(Date()), msg, type))
            while (lines.size > 200) lines.removeAt(lines.size - 1)
        }
        publish()
    }

    fun snapshot(): List<LogLine> = synchronized(lines) { lines.toList() }

    fun clearLog() {
        synchronized(lines) { lines.clear() }
        publish()
    }

    fun reset() {
        running = false
        status = Status.IDLE
        fails = 0
        latency = -1
        downSince = 0L
        disconnected = false
        publish()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun publish() {
        main.post { listeners.forEach { it() } }
    }
}
