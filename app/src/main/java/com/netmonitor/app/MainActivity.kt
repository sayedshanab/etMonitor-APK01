package com.netmonitor.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** CreateDocument with an explicit MIME type (works on every androidx.activity version). */
@Suppress("DEPRECATION")
private class CreateDoc(private val mime: String) : ActivityResultContracts.CreateDocument() {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).setType(mime)
}

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
    private lateinit var tvLossSub: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvPingSub: TextView
    private lateinit var tvDown: TextView
    private lateinit var tvDownSub: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvLongestSub: TextView
    private lateinit var tvLastDur: TextView
    private lateinit var tvLastRange: TextView
    private lateinit var tvTotalDur: TextView
    private lateinit var banner: TextView
    private lateinit var recBanner: TextView
    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var outageScroll: ScrollView
    private lateinit var outageTable: TableLayout
    private lateinit var outageBadge: TextView
    private lateinit var logBadge: TextView
    private lateinit var volLabel: TextView
    private lateinit var volSeek: SeekBar
    private lateinit var btnToggle: Button
    private lateinit var btnMute: Button
    private lateinit var btnBattery: Button
    private lateinit var btnTestDown: Button
    private lateinit var btnTestUp: Button
    private lateinit var spThreshold: Spinner
    private lateinit var spInterval: Spinner
    private lateinit var cbRepeat: CheckBox

    private val thresholds = listOf(2, 3, 5)
    private val intervals = listOf(1, 3, 15, 60)

    private var lastLogVersion = -1
    private var lastHistoryVersion = -1
    private var ongoingDurTv: TextView? = null
    private var pendingExport: String? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startMonitoring() }
    private val createCsv = registerForActivityResult(CreateDoc("text/csv")) { writeExport(it) }
    private val createTxt = registerForActivityResult(CreateDoc("text/plain")) { writeExport(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        Notifier.ensureChannels(this)

        circle = findViewById(R.id.statusCircle)
        tvFails = findViewById(R.id.failCount)
        tvLossSub = findViewById(R.id.packetLossSub)
        tvPing = findViewById(R.id.pingTime)
        tvPingSub = findViewById(R.id.pingAvgSub)
        tvDown = findViewById(R.id.downDuration)
        tvDownSub = findViewById(R.id.currentDownSub)
        tvCount = findViewById(R.id.disconnectCount)
        tvLongestSub = findViewById(R.id.longestDownSub)
        tvLastDur = findViewById(R.id.lastDownDuration)
        tvLastRange = findViewById(R.id.lastDownTimeRange)
        tvTotalDur = findViewById(R.id.totalDownDuration)
        banner = findViewById(R.id.disconnectBanner)
        recBanner = findViewById(R.id.reconnectBanner)
        tvLog = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        outageScroll = findViewById(R.id.outageScroll)
        outageTable = findViewById(R.id.outageTable)
        outageBadge = findViewById(R.id.outageCountBadge)
        logBadge = findViewById(R.id.logCountBadge)
        volLabel = findViewById(R.id.volLabel)
        volSeek = findViewById(R.id.volumeSeek)
        btnToggle = findViewById(R.id.toggleBtn)
        btnMute = findViewById(R.id.muteBtn)
        btnBattery = findViewById(R.id.batteryBtn)
        btnTestDown = findViewById(R.id.testDown)
        btnTestUp = findViewById(R.id.testUp)
        spThreshold = findViewById(R.id.thresholdSelect)
        spInterval = findViewById(R.id.intervalSelect)
        cbRepeat = findViewById(R.id.repeatCheck)

        allowNestedScroll(logScroll)
        allowNestedScroll(outageScroll)
        setupSpinners()
        setupVolume()

        cbRepeat.isChecked = prefs.repeatAlarm
        cbRepeat.setOnCheckedChangeListener { _, checked -> prefs.repeatAlarm = checked }

        btnToggle.setOnClickListener {
            if (MonitorState.running) stopMonitoring() else requestAndStart()
        }
        btnMute.setOnClickListener { Notifier.mute(this) }

        btnTestDown.setOnClickListener {
            if (AlarmPlayer.playingRes == R.raw.disconnect) {
                AlarmPlayer.stop()
            } else {
                MonitorState.log("🚨 تجربة نغمة الفصل (القوة: ${prefs.volumePct}%)", "fail")
                AlarmPlayer.play(this, R.raw.disconnect, false)
            }
            render()
            handler.postDelayed({ render() }, 1700)
        }
        btnTestUp.setOnClickListener {
            if (AlarmPlayer.playingRes == R.raw.reconnect) {
                AlarmPlayer.stop()
            } else {
                MonitorState.log("✅ تجربة نغمة الرجوع (القوة: ${prefs.volumePct}%)", "ok")
                AlarmPlayer.play(this, R.raw.reconnect, false)
            }
            render()
            handler.postDelayed({ render() }, 1600)
        }

        findViewById<Button>(R.id.exportCsv).setOnClickListener {
            if (nothingToExport()) return@setOnClickListener
            pendingExport = Report.csv()
            createCsv.launch("ISP_Outage_Report_Mahmoud_Kamal_${Fmt.fileDate()}.csv")
        }
        findViewById<Button>(R.id.exportTxt).setOnClickListener {
            if (nothingToExport()) return@setOnClickListener
            pendingExport = Report.txt()
            createTxt.launch("ISP_Detailed_Report_Mahmoud_Kamal_${Fmt.fileDate()}.txt")
        }
        findViewById<Button>(R.id.clearHistory).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("هل أنت متأكد من مسح كافة سجلات الانقطاعات والإحصائيات المحفوظة؟")
                .setPositiveButton("نعم") { _, _ ->
                    MonitorState.clearAll()
                    MonitorState.log("🗑️ تم تصفير السجل المحفوظ وبدء جلسة إحصائيات جديدة.", "ok")
                    render()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
        btnBattery.setOnClickListener { requestBatteryExemption() }

        if (MonitorState.snapshot().isEmpty()) {
            MonitorState.log("v2.9 جاهز (By Mahmoud_Kamal) - فحص حقيقي من طرف لطرف، يعمل مع الـ VPN", "ok")
        }

        // Process was killed while monitoring was on: bring the service back.
        if (prefs.enabled && !MonitorState.running) {
            try { MonitorService.start(this) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------ setup

    @SuppressLint("ClickableViewAccessibility")
    private fun allowNestedScroll(sv: ScrollView) {
        sv.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun setupVolume() {
        volSeek.progress = prefs.volumePct
        updateVolLabel(prefs.volumePct)
        volSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                prefs.volumePct = progress
                updateVolLabel(progress)
                AlarmPlayer.setBoost(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateVolLabel(pct: Int) {
        volLabel.text = "$pct%"
        volLabel.setTextColor(Color.parseColor(if (pct > 100) "#ffaa00" else "#00ff88"))
    }

    private fun setupSpinners() {
        spThreshold.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("2 ثواني (حساس)", "3 ثواني (موصى به)", "5 ثواني")
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

    // ----------------------------------------------------------- actions

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

    private fun nothingToExport(): Boolean {
        if (MonitorState.outageSnapshot().isEmpty() && MonitorState.snapshot().isEmpty()) {
            MonitorState.log("ℹ️ لا توجد بيانات مسجلة لتصديرها بعد.", "warn")
            return true
        }
        return false
    }

    private fun writeExport(uri: Uri?) {
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            MonitorState.log("📊 تم حفظ التقرير بنجاح (أرقام غربية، يفتح في Excel / أي محرر نصوص).", "ok")
            Toast.makeText(this, "تم حفظ التقرير", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            MonitorState.log("⚠️ تعذر حفظ التقرير: ${e.message}", "warn")
        }
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

    // --------------------------------------------------------- rendering

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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

    private fun render() {
        val s = MonitorState
        val thr = prefs.threshold
        val run = s.running
        val now = System.currentTimeMillis()
        val liveDownSec = if (s.downSince > 0) maxOf(1L, (now - s.downSince) / 1000) else 0L

        when {
            !run -> setCircle("#181818", "#666666", "جاهز\nللبدء")
            s.status == Status.FAIL -> setCircle("#3d0a0a", "#ff3333", "فاصل!\n$thr/$thr")
            s.status == Status.WARN ->
                setCircle("#2a1a00", "#ffaa00", "فقد باكت\n${minOf(s.fails, thr)}/$thr")
            else -> setCircle("#0a3d1f", "#00ff88", "متصل\n${if (s.latency >= 0) "${s.latency}ms" else ""}")
        }

        tvFails.text = "${minOf(s.fails, thr)} / $thr"
        tvLossSub.text = "إجمالي فقد الباكت: ${s.totalTimeouts}"

        when {
            run && s.latency >= 0 -> {
                tvPing.text = "${s.latency} ms"
                tvPingSub.text = if (s.latency < 90) "حالة الخط: ممتاز" else "حالة الخط: مستقر"
            }
            run && s.fails > 0 -> {
                tvPing.text = "Timeout"
                tvPingSub.text = "لا توجد استجابة!"
            }
            run -> {
                tvPing.text = "--"
                tvPingSub.text = "جاري فحص الاتصال..."
            }
            else -> {
                tvPing.text = "--"
                tvPingSub.text = "حالة الخط: مستعد"
            }
        }

        when {
            run && liveDownSec > 0 -> {
                tvDown.text = Fmt.duration(liveDownSec)
                tvDownSub.text = "بدأ منذ ${Fmt.time(s.downSince)}"
            }
            run -> {
                tvDown.text = "--"
                tvDownSub.text = "الخط متصل الآن ✓"
            }
            else -> {
                tvDown.text = "--"
                tvDownSub.text = "المراقبة متوقفة"
            }
        }

        tvCount.text = "${s.disconnectCount}"
        tvLongestSub.text =
            if (s.longestDowntimeSec > 0) "أطول فصل: ${Fmt.duration(s.longestDowntimeSec)}" else "أطول فصل: --"

        if (s.disconnected && liveDownSec > 0) {
            tvLastDur.text = Fmt.duration(liveDownSec)
            tvLastRange.text = "بدأ ${s.lastDownStart} (جاري الآن...)"
        } else if (s.lastDownSec > 0 || s.lastDownStart.isNotEmpty()) {
            tvLastDur.text = Fmt.duration(s.lastDownSec)
            tvLastRange.text =
                if (s.lastDownEnd.isNotEmpty()) "${s.lastDownStart} ← ${s.lastDownEnd}"
                else "بدأ ${s.lastDownStart}"
        } else {
            tvLastDur.text = "--"
            tvLastRange.text = "لم يسجل انقطاع بعد"
        }
        tvTotalDur.text = Fmt.duration(s.totalDowntimeSec)

        banner.visibility = if (s.disconnected) View.VISIBLE else View.GONE
        btnMute.visibility = if (s.disconnected) View.VISIBLE else View.GONE
        if (!s.disconnected && s.reconnectAt > 0 && now - s.reconnectAt < 5000) {
            recBanner.text = s.reconnectText
            recBanner.visibility = View.VISIBLE
        } else {
            recBanner.visibility = View.GONE
        }

        if (run) {
            btnToggle.text = "⏸️ وقف المراقبة"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#ff3333"))
            btnToggle.setTextColor(Color.WHITE)
        } else {
            btnToggle.text = "▶️ ابدأ المراقبة"
            btnToggle.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00ff88"))
            btnToggle.setTextColor(Color.BLACK)
        }

        btnTestDown.text =
            if (AlarmPlayer.playingRes == R.raw.disconnect) "⏹️ إيقاف النغمة" else "🔊 جرب نغمة الفصل"
        btnTestUp.text =
            if (AlarmPlayer.playingRes == R.raw.reconnect) "⏹️ إيقاف النغمة" else "🔊 جرب نغمة الرجوع"

        btnBattery.visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE

        if (lastHistoryVersion != s.historyVersion) {
            lastHistoryVersion = s.historyVersion
            rebuildOutageTable()
        }
        if (s.disconnected && liveDownSec > 0) ongoingDurTv?.text = Fmt.duration(liveDownSec)

        if (lastLogVersion != s.logVersion) {
            lastLogVersion = s.logVersion
            val lines = s.snapshot()
            logBadge.text = "${lines.size} حدث مسجل"
            val html = lines.joinToString("<br>") {
                val c = when (it.type) {
                    "warn" -> "#ffaa00"
                    "fail" -> "#ff5555"
                    else -> "#00ff88"
                }
                "<font color='$c'>[${it.time}] ${TextUtils.htmlEncode(it.msg)}</font>"
            }
            tvLog.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            logScroll.scrollTo(0, 0)
        }
    }

    private fun cell(text: String, color: Int = Color.parseColor("#eeeeee"), bold: Boolean = false, span: Int = 1): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(3), dp(7), dp(3), dp(7))
            layoutParams = TableRow.LayoutParams(
                TableRow.LayoutParams.WRAP_CONTENT, TableRow.LayoutParams.WRAP_CONTENT
            ).also { if (span > 1) it.span = span }
        }

    private fun rebuildOutageTable() {
        val list = MonitorState.outageSnapshot()
        outageBadge.text = "${list.size} انقطاع مسجل"
        outageTable.removeAllViews()
        ongoingDurTv = null

        val head = TableRow(this).apply { setBackgroundColor(Color.parseColor("#222222")) }
        for (h in listOf("#", "التاريخ", "بداية الفصل", "عودة النت", "مدة الانقطاع", "البنج")) {
            head.addView(cell(h, Color.parseColor("#cccccc"), bold = true))
        }
        outageTable.addView(head)

        if (list.isEmpty()) {
            val r = TableRow(this)
            r.addView(cell("لا توجد انقطاعات مسجلة حتى الآن", Color.parseColor("#666666"), span = 6))
            outageTable.addView(r)
            return
        }

        val liveSec = if (MonitorState.downSince > 0)
            maxOf(1L, (System.currentTimeMillis() - MonitorState.downSince) / 1000) else 0L

        list.forEachIndexed { idx, o ->
            val r = TableRow(this)
            val num = list.size - idx
            if (o.ongoing) {
                r.setBackgroundColor(Color.parseColor("#3d0a0a"))
                val red = Color.parseColor("#ff8888")
                r.addView(cell("$num", red, true))
                r.addView(cell(o.date, red, true))
                r.addView(cell(o.startTime, red, true))
                r.addView(cell("🔴 جاري الآن...", red, true))
                val dur = cell(Fmt.duration(if (liveSec > 0) liveSec else o.durationSec), red, true)
                ongoingDurTv = dur
                r.addView(dur)
                r.addView(cell("--", red, true))
            } else {
                r.addView(cell("$num"))
                r.addView(cell(o.date))
                r.addView(cell(o.startTime))
                r.addView(cell(o.endTime))
                r.addView(cell(Fmt.duration(o.durationSec), Color.parseColor("#ffaa00"), true))
                r.addView(cell(o.recoveryPing, Color.parseColor("#00ff88")))
            }
            outageTable.addView(r)
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
