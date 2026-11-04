package com.netmonitor.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

enum class Status { IDLE, OK, WARN, FAIL }

data class LogLine(val time: String, val msg: String, val type: String, val date: String = "")

data class Outage(
    val id: Long,
    val date: String,
    val startTime: String,
    var endTime: String,
    var durationSec: Long,
    var recoveryPing: String,
    var ongoing: Boolean
)

/**
 * Process-wide state shared between the foreground service and the UI.
 * Statistics, outage history and the event log are persisted (like localStorage in the
 * web version) and survive app / phone restarts.
 */
object MonitorState {
    // ---- live state (not persisted)
    @Volatile var running = false
    @Volatile var status = Status.IDLE
    @Volatile var fails = 0
    @Volatile var latency = -1
    @Volatile var downSince = 0L
    @Volatile var disconnected = false
    @Volatile var reconnectAt = 0L
    @Volatile var reconnectText = ""

    // ---- persisted statistics
    @Volatile var disconnectCount = 0
    @Volatile var totalTimeouts = 0
    @Volatile var totalDowntimeSec = 0L
    @Volatile var longestDowntimeSec = 0L
    @Volatile var lastDownSec = 0L
    @Volatile var lastDownStart = ""
    @Volatile var lastDownEnd = ""

    @Volatile var logVersion = 0
    @Volatile var historyVersion = 0

    private const val MAX_LOG = 250
    private const val MAX_OUTAGES = 200

    private val lock = Any()
    private val lines = ArrayList<LogLine>()
    private val outages = ArrayList<Outage>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var sp: SharedPreferences? = null
    @Volatile private var saveScheduled = false

    // ------------------------------------------------------------------ log

    fun log(msg: String, type: String = "ok") {
        synchronized(lock) {
            lines.add(0, LogLine(Fmt.time(), Fmt.toWestern(msg), type, Fmt.date()))
            while (lines.size > MAX_LOG) lines.removeAt(lines.size - 1)
            logVersion++
        }
        saveSoon()
        publish()
    }

    fun snapshot(): List<LogLine> = synchronized(lock) { ArrayList(lines) }

    fun outageSnapshot(): List<Outage> = synchronized(lock) { outages.map { it.copy() } }

    // -------------------------------------------------------------- outages

    /** Called when "disconnected" is declared. */
    fun openOutage(startTs: Long, initialDurSec: Long) {
        synchronized(lock) {
            lastDownStart = Fmt.time(startTs)
            lastDownEnd = ""
            lastDownSec = initialDurSec
            outages.add(
                0,
                Outage(System.currentTimeMillis(), Fmt.date(startTs), lastDownStart, "", initialDurSec, "--", true)
            )
            while (outages.size > MAX_OUTAGES) outages.removeAt(outages.size - 1)
            historyVersion++
        }
        saveSoon()
    }

    /** Called when the connection returns (or monitoring is stopped during an outage). */
    fun closeOutage(endTs: Long, durSec: Long, recoveryPing: String) {
        synchronized(lock) {
            val endStr = Fmt.time(endTs)
            outages.firstOrNull()?.let {
                if (it.ongoing) {
                    it.ongoing = false
                    it.endTime = endStr
                    it.durationSec = durSec
                    it.recoveryPing = recoveryPing
                }
            }
            lastDownSec = durSec
            lastDownEnd = endStr
            totalDowntimeSec += durSec
            if (durSec > longestDowntimeSec) longestDowntimeSec = durSec
            historyVersion++
        }
        saveSoon()
    }

    /** Monitoring stopped while the line was down: close the open row. */
    fun stopOngoing() {
        if (disconnected && downSince > 0) {
            val now = System.currentTimeMillis()
            closeOutage(now, maxOf(1L, Math.round((now - downSince) / 1000.0)), "--")
        }
    }

    fun clearAll() {
        synchronized(lock) {
            lines.clear()
            outages.clear()
            disconnectCount = 0
            totalTimeouts = 0
            totalDowntimeSec = 0L
            longestDowntimeSec = 0L
            lastDownSec = 0L
            lastDownStart = ""
            lastDownEnd = ""
            logVersion++
            historyVersion++
        }
        saveNow()
        publish()
    }

    /** Live values only (statistics / history are kept). */
    fun reset() {
        running = false
        status = Status.IDLE
        fails = 0
        latency = -1
        downSince = 0L
        disconnected = false
        publish()
    }

    // ---------------------------------------------------------- UI listeners

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun publish() {
        main.post { listeners.forEach { it() } }
    }

    // ----------------------------------------------------------- persistence

    fun init(ctx: Context) {
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("netmonitor_history", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        val p = sp ?: return
        try {
            p.getString("stats", null)?.let { j ->
                val o = JSONObject(j)
                disconnectCount = o.optInt("disconnectCount")
                totalTimeouts = o.optInt("totalTimeouts")
                totalDowntimeSec = o.optLong("totalDowntimeSec")
                longestDowntimeSec = o.optLong("longestDowntimeSec")
                lastDownSec = o.optLong("lastDownSec")
                lastDownStart = o.optString("lastDownStart")
                lastDownEnd = o.optString("lastDownEnd")
            }
            var hadOngoing = false
            p.getString("outages", null)?.let { j ->
                val a = JSONArray(j)
                synchronized(lock) {
                    outages.clear()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val wasOngoing = o.optBoolean("ongoing")
                        if (wasOngoing) hadOngoing = true
                        outages.add(
                            Outage(
                                o.optLong("id"),
                                o.optString("date"),
                                o.optString("startTime"),
                                if (wasOngoing) "أُغلق التطبيق" else o.optString("endTime"),
                                o.optLong("durationSec"),
                                o.optString("recoveryPing", "--"),
                                false
                            )
                        )
                    }
                }
            }
            if (hadOngoing && lastDownEnd.isEmpty()) lastDownEnd = "أُغلق التطبيق"
            p.getString("logs", null)?.let { j ->
                val a = JSONArray(j)
                synchronized(lock) {
                    lines.clear()
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        lines.add(
                            LogLine(o.optString("time"), o.optString("msg"), o.optString("type", "ok"), o.optString("date"))
                        )
                    }
                }
            }
            logVersion++
            historyVersion++
        } catch (_: Exception) { }
    }

    /** Debounced: at most one disk write every ~2 s even if logging every second. */
    private fun saveSoon() {
        if (saveScheduled) return
        saveScheduled = true
        main.postDelayed({
            saveScheduled = false
            saveNow()
        }, 2000)
    }

    fun saveNow() {
        val p = sp ?: return
        try {
            val stats = JSONObject()
                .put("disconnectCount", disconnectCount)
                .put("totalTimeouts", totalTimeouts)
                .put("totalDowntimeSec", totalDowntimeSec)
                .put("longestDowntimeSec", longestDowntimeSec)
                .put("lastDownSec", lastDownSec)
                .put("lastDownStart", lastDownStart)
                .put("lastDownEnd", lastDownEnd)
            val outArr = JSONArray()
            val logArr = JSONArray()
            val ds = downSince
            synchronized(lock) {
                for (o in outages) {
                    var dur = o.durationSec
                    if (o.ongoing && ds > 0) dur = maxOf(dur, (System.currentTimeMillis() - ds) / 1000)
                    outArr.put(
                        JSONObject().put("id", o.id).put("date", o.date).put("startTime", o.startTime)
                            .put("endTime", o.endTime).put("durationSec", dur)
                            .put("recoveryPing", o.recoveryPing).put("ongoing", o.ongoing)
                    )
                }
                for (l in lines) {
                    logArr.put(
                        JSONObject().put("time", l.time).put("msg", l.msg).put("type", l.type).put("date", l.date)
                    )
                }
            }
            p.edit()
                .putString("stats", stats.toString())
                .putString("outages", outArr.toString())
                .putString("logs", logArr.toString())
                .apply()
        } catch (_: Exception) { }
    }
}
