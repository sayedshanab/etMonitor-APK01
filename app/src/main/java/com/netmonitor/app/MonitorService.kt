package com.netmonitor.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that checks internet reachability.
 *
 * Power strategy:
 *  - Checks are plain TCP connects to literal IPs (no DNS / TLS / HTTP).
 *  - Fast modes (<= 3 s): CPU is kept awake with a *timed* wake lock between checks.
 *  - Slow modes (15 s / 60 s): no wake lock is held while idle; an exact alarm wakes the
 *    CPU just long enough for one check.
 *  - Connectivity callbacks trigger an immediate check when the network changes, so slow
 *    modes still notice a lost link right away.
 *  - After a failed check the service retries every second until it recovers or the
 *    "disconnected" threshold is reached.
 */
class MonitorService : Service() {

    companion object {
        // Plain TCP connects to literal IPs: no DNS, no TLS, no HTTP -> very cheap per check.
        private val TARGETS = listOf("1.1.1.1" to 443, "8.8.8.8" to 443)

        private const val ACTION_ALARM = "com.netmonitor.app.PROBE_ALARM"
        private const val RETRY_MS = 1000L
        private const val DOWN_MAX_MS = 5000L
        private const val CONTINUOUS_MAX_MS = 3000L
        private const val WAKE_MARGIN_MS = 20_000L

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, MonitorService::class.java)
            )
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var alarms: AlarmManager
    private lateinit var alarmPi: PendingIntent
    private lateinit var wakeLock: PowerManager.WakeLock

    private var scheduler: ScheduledExecutorService? = null
    private var pending: ScheduledFuture<*>? = null
    private val probePool: ExecutorService = Executors.newCachedThreadPool()

    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastValidated: Boolean? = null
    private var receiverRegistered = false
    @Volatile private var active = false
    private var lastStatusText = ""

    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            poke()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Notifier.ensureChannels(this)
        alarms = getSystemService(AlarmManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netmonitor:probe").apply {
            setReferenceCounted(false)
        }
        alarmPi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_ALARM).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStatusText = "جاري البدء…"
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_FG, Notifier.status(this, lastStatusText),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // System restarted us (null intent) but the user had stopped monitoring.
        if (intent == null && !prefs.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.enabled = true
        startLoop()
        return START_STICKY
    }

    // ---------------------------------------------------------------- scheduling

    private fun startLoop() {
        stopLoop()
        active = true
        MonitorState.running = true
        MonitorState.log(
            "Started - فحص كل ${prefs.intervalMs / 1000} ثانية (خدمة خلفية)", "ok"
        )
        scheduler = Executors.newSingleThreadScheduledExecutor()
        ContextCompat.registerReceiver(
            this, alarmReceiver, IntentFilter(ACTION_ALARM), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        registerNetworkCallback()
        holdWake(WAKE_MARGIN_MS)
        schedule(0)
        MonitorState.publish()
    }

    private fun stopLoop() {
        active = false
        pending?.cancel(false)
        pending = null
        scheduler?.shutdownNow()
        scheduler = null
        cancelAlarm()
        if (receiverRegistered) {
            try { unregisterReceiver(alarmReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        netCb?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Exception) { }
        }
        netCb = null
        lastValidated = null
        releaseWake()
    }

    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = poke()
            override fun onLost(network: Network) {
                lastValidated = null
                poke()
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val v = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (lastValidated != v) {
                    lastValidated = v
                    poke()
                }
            }
        }
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Exception) { }
    }

    /** Run one check right now (alarm fired or the network just changed). */
    private fun poke() {
        if (!active) return
        holdWake(WAKE_MARGIN_MS)
        try {
            scheduler?.execute { cycle() }
        } catch (_: RejectedExecutionException) { }
    }

    private fun schedule(delayMs: Long) {
        try {
            pending?.cancel(false)
            pending = scheduler?.schedule({ cycle() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) { }
    }

    private fun cycle() {
        if (!active) return
        val t0 = SystemClock.elapsedRealtime()
        cancelAlarm()
        holdWake(WAKE_MARGIN_MS)
        try { tick() } catch (_: Throwable) { }
        if (!active || Thread.currentThread().isInterrupted) return
        scheduleNext(t0)
    }

    private fun scheduleNext(t0: Long) {
        val s = MonitorState
        val base = prefs.intervalMs.toLong()
        val period = when {
            s.disconnected -> minOf(base, DOWN_MAX_MS)
            s.fails > 0 -> minOf(base, RETRY_MS)
            else -> base
        }
        if (period <= CONTINUOUS_MAX_MS) {
            val delay = (period - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(0L)
            holdWake(delay + WAKE_MARGIN_MS)
            schedule(delay)
        } else {
            pending?.cancel(false)
            pending = null
            setAlarm(period)
            releaseWake()
        }
    }

    private fun setAlarm(delayMs: Long) {
        val at = SystemClock.elapsedRealtime() + delayMs
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmPi)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmPi)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmPi)
        }
    }

    private fun cancelAlarm() {
        alarms.cancel(alarmPi)
    }

    /** Timed wake lock: can never leak, and re-acquiring simply extends it. */
    private fun holdWake(ms: Long) {
        try { wakeLock.acquire(ms) } catch (_: Exception) { }
    }

    private fun releaseWake() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- the check

    /** Returns latency in ms on success, -1 on timeout/failure, -2 if interrupted. */
    private fun probe(timeoutMs: Int): Int {
        val tasks = TARGETS.map { (host, port) ->
            Callable<Int> {
                val t0 = System.nanoTime()
                Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
                ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
            }
        }
        return try {
            probePool.invokeAny(tasks, timeoutMs + 300L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            -2
        } catch (e: Exception) {
            -1
        }
    }

    private fun tick() {
        val threshold = prefs.threshold
        val timeout = prefs.intervalMs.coerceIn(1000, 2000)
        val lat = probe(timeout)
        if (lat == -2 || !active) return

        val s = MonitorState
        val now = System.currentTimeMillis()

        if (lat >= 0) {
            if (s.disconnected) {
                val wasDown = maxOf(1L, (now - s.downSince) / 1000)
                s.disconnected = false
                s.downSince = 0L
                s.fails = 0
                s.latency = lat
                s.status = Status.OK
                s.log("✅ BACK ONLINE after ${wasDown}s (${lat}ms) - الانترنت رجع", "ok")
                Notifier.reconnect(this, wasDown)
            } else {
                if (s.fails > 0) s.log("ℹ️ استقر الاتصال بعد فقد ${s.fails} باكت (${lat}ms)", "ok")
                s.fails = 0
                s.downSince = 0L
                s.latency = lat
                s.status = Status.OK
            }
        } else {
            s.fails++
            s.totalTimeouts++
            s.latency = -1
            if (s.fails == 1 && s.downSince == 0L) s.downSince = now
            s.log("⚠️ Request timed out. (${s.fails}/$threshold)", "warn")

            if (!s.disconnected) {
                if (s.fails >= threshold) {
                    s.disconnected = true
                    s.disconnectCount++
                    s.status = Status.FAIL
                    s.log("🚨 DISCONNECTED - الانترنت فصل رسمياً!", "fail")
                    Notifier.disconnect(this, threshold, prefs.repeatAlarm)
                } else {
                    s.status = Status.WARN
                }
            }
        }
        refreshStatusNotification()
        s.publish()
    }

    /** Only touches the notification when the text actually changes (saves power). */
    private fun refreshStatusNotification() {
        val s = MonitorState
        val text = when (s.status) {
            Status.OK -> "متصل ✅ — فحص كل ${prefs.intervalMs / 1000} ث"
            Status.WARN -> "⚠️ فقد باكت ${s.fails}/${prefs.threshold}"
            Status.FAIL -> "🚨 النت فاصل (${s.disconnectCount} مرات)"
            Status.IDLE -> "جاري البدء…"
        }
        if (text != lastStatusText) {
            lastStatusText = text
            getSystemService(NotificationManager::class.java)
                .notify(Notifier.ID_FG, Notifier.status(this, text))
        }
    }

    override fun onDestroy() {
        stopLoop()
        probePool.shutdownNow()
        MonitorState.reset()
        MonitorState.log("Stopped - تم إيقاف المراقبة", "warn")
        super.onDestroy()
    }
}
