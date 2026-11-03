package com.netmonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.text.TextUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var circle: TextView
    private lateinit var tvFails: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvCount: TextView
    private lateinit var banner: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnToggle: Button
    private lateinit var btnMute: Button
    private lateinit var btnBattery: Button
    private lateinit var spThreshold: Spinner
    private lateinit var spInterval: Spinner
    private lateinit var cbRepeat: CheckBox

    private val thresholds = listOf(2, 3, 5)
    private val intervals = listOf(1, 3, 15, 60)
    private var lastLogHtml = ""

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startMonitoring() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.ensureChannels(this)

        circle = findViewById(R.id.statusCircle)
        tvFails = findViewById(R.id.failCount)
        tvPing = findViewById(R.id.pingTime)
        tvDown = findViewById(R.id.downDuration)
        tvCount = findViewById(R.id.disconnectCount)
        banner = findViewById(R.id.disconnectBanner)
        tvLog = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        btnToggle = findViewById(R.id.toggleBtn)
        btnMute = findViewById(R.id.muteBtn)
        btnBattery = findViewById(R.id.batteryBtn)
        spThreshold = findViewById(R.id.thresholdSelect)
        spInterval = findViewById(R.id.intervalSelect)
        cbRepeat = findViewById(R.id.repeatCheck)

        setupSpinners()

        cbRepeat.isChecked = prefs.repeatAlarm
        cbRepeat.setOnCheckedChangeListener { _, checked -> prefs.repeatAlarm = checked }

        btnToggle.setOnClickListener {
            if (MonitorState.running) stopMonitoring() else requestAndStart()
        }
        btnMute.setOnClickListener { Notifier.mute(this) }
        findViewById<Button>(R.id.testDown).setOnClickListener {
            MonitorState.log("🚨 DISCONNECTED - الانترنت فصل (تجربة)", "fail")
            Notifier.disconnect(this, prefs.threshold, false)
        }
        findViewById<Button>(R.id.testUp).setOnClickListener {
            MonitorState.log("✅ BACK ONLINE - الانترنت رجع (تجربة)", "ok")
            Notifier.reconnect(this, 5)
        }
        findViewById<Button>(R.id.clearLog).setOnClickListener {
            MonitorState.clearLog()
            MonitorState.log("v2.3 جاهز - يسجل كل Request timed out لحظياً", "ok")
        }
        btnBattery.setOnClickListener { requestBatteryExemption() }

        if (MonitorState.snapshot().isEmpty()) {
            MonitorState.log("v2.3 جاهز - يسجل كل Request timed out لحظياً", "ok")
        }

        // Process was killed while monitoring was on: bring the service back.
        if (prefs.enabled && !MonitorState.running) {
            try { MonitorService.start(this) } catch (_: Exception) { }
        }
    }

    private fun setupSpinners() {
        spThreshold.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("2 ثواني (حساس جداً)", "3 ثواني (موصى به)", "5 ثواني")
        )
        spThreshold.setSelection(thresholds.indexOf(prefs.threshold).coerceAtLeast(0))
        spThreshold.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val value = thresholds[pos]
                if (value != prefs.threshold) {
                    prefs.threshold = value
                    render()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spInterval.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "كل 1 ثانية (دقيق، أعلى استهلاك)",
                "كل 3 ثواني (متوازن)",
                "كل 15 ثانية (موفر للطاقة)",
                "كل 60 ثانية (أقصى توفير)"
            )
        )
        spInterval.setSelection(intervals.indexOf(prefs.intervalMs / 1000).coerceAtLeast(0))
        spInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val ms = intervals[pos] * 1000
                if (ms != prefs.intervalMs) {
                    prefs.intervalMs = ms
                    // Restart the loop so the new interval applies immediately.
                    if (MonitorState.running) MonitorService.start(this@MainActivity)
                    render()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun requestAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        prefs.enabled = true
        MonitorState.running = true
        MonitorService.start(this)
        render()
        // One-time system prompt so Android doesn't freeze the service in Doze / battery saver.
        if (!isIgnoringBattery() && !prefs.batteryAsked) {
            prefs.batteryAsked = true
            requestBatteryExemption()
        }
    }

    private fun stopMonitoring() {
        prefs.enabled = false
        stopService(Intent(this, MonitorService::class.java))
        Notifier.mute(this)
        render()
    }

    private fun isIgnoringBattery(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun setCircle(bg: String, stroke: String, text: String) {
        val d = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(bg))
            setStroke(dp(4), Color.parseColor(stroke))
        }
        circle.background = d
        circle.setTextColor(Color.parseColor(stroke))
        circle.text = text
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        val s = MonitorState
        val thr = prefs.threshold

        when {
            !s.running -> setCircle("#0a3d1f", "#00ff88", "جاهز\nللبدء")
            s.status == Status.FAIL -> setCircle("#3d0a0a", "#ff3333", "فاصل!\n$thr/$thr")
            s.status == Status.WARN ->
                setCircle("#2a1a00", "#ffaa00", "فقد باكت\n${minOf(s.fails, thr)}/$thr")
            else -> setCircle("#0a3d1f", "#00ff88", "متصل\n${if (s.latency >= 0) "${s.latency}ms" else ""}")
        }

        tvFails.text = "${minOf(s.fails, thr)} / $thr"
        tvPing.text = when {
            s.latency >= 0 -> "${s.latency} ms"
            s.running && s.fails > 0 -> "Timeout"
            else -> "--"
        }
        tvCount.text = "${s.disconnectCount} (${s.totalTimeouts})"

        if (s.downSince > 0) {
            val d = (System.currentTimeMillis() - s.downSince) / 1000
            tvDown.text = if (d < 60) "$d ث" else "${d / 60} د ${d % 60} ث"
        } else {
            tvDown.text = "--"
        }

        banner.visibility = if (s.disconnected) View.VISIBLE else View.GONE
        btnMute.visibility = if (s.disconnected) View.VISIBLE else View.GONE

        if (s.running) {
            btnToggle.text = "⏸️ وقف"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ff3333"))
            btnToggle.setTextColor(Color.WHITE)
        } else {
            btnToggle.text = "▶️ ابدأ المراقبة"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00ff88"))
            btnToggle.setTextColor(Color.BLACK)
        }

        btnBattery.visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE

        val html = s.snapshot().joinToString("<br>") {
            val c = when (it.type) {
                "warn" -> "#ffaa00"
                "fail" -> "#ff5555"
                else -> "#00ff88"
            }
            "<font color='$c'>[${it.time}] ${TextUtils.htmlEncode(it.msg)}</font>"
        }
        if (html != lastLogHtml) {
            lastLogHtml = html
            tvLog.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            logScroll.scrollTo(0, 0)
        }
    }

    override fun onStart() {
        super.onStart()
        MonitorState.addListener(listener)
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        MonitorState.removeListener(listener)
        super.onStop()
    }
}
