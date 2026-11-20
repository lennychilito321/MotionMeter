package co.edu.uniautonoma.medidormovimiento

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.util.Locale
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private enum class Mode { IDLE, CALIBRATING, ARMED, RESULT }

    private lateinit var sensorManager: SensorManager
    private var sensor: Sensor? = null
    private var usingLinear = true
    private val gravityFilter = GravityFilter()

    private var mode = Mode.IDLE
    private var calibration: Calibration? = null
    private var calibrator: Calibrator? = null
    private var detector: EventDetector? = null
    private var lastMeasurement: Measurement? = null
    private var k = 5.0

    // Opciones de medición
    private val noiseFilter = NoiseFilter()
    private var filterOn = true          // filtro de ruido activado
    private var windowIdx = 0            // índice en AppSettings.WINDOW_OPTIONS (0 = automática)
    private var measuredFiltered = true  // con qué opción se hizo la última medición
    private var measuredWindowS = 0      // ventana usada en la última medición (0 = automática)
    private var showTech = false

    // Señal para la gráfica en vivo (submuestreada a ~100 Hz, últimos 5 s)
    private val live = ArrayDeque<Sample>()
    private var lastChartNs = 0L
    private var latest: Sample? = null
    private var chartNeedsEvent = false

    // Frecuencia de muestreo real
    private var rateCount = 0
    private var rateWindowStartNs = 0L
    private var rateHz = 0.0

    private val es: Locale get() = resources.configuration.locales[0]
    private lateinit var store: MeasurementStore

    private lateinit var txtStatus: TextView
    private lateinit var txtSensor: TextView
    private lateinit var txtLive: TextView
    private lateinit var txtChartCaption: TextView
    private lateinit var txtThreshold: TextView
    private lateinit var txtResult: TextView
    private lateinit var editLabel: EditText
    private lateinit var btnCalibrate: Button
    private lateinit var btnMeasure: Button
    private lateinit var btnSave: Button
    private lateinit var btnHistory: Button
    private lateinit var seekK: SeekBar
    private lateinit var switchFilter: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var seekWindow: SeekBar
    private lateinit var txtWindow: TextView
    private lateinit var cardExplain: android.view.View
    private lateinit var txtExplain: TextView
    private lateinit var btnTech: Button
    private lateinit var chartAccel: LineChart
    private lateinit var chartVel: LineChart
    private lateinit var chartDisp: LineChart
    private lateinit var scaleView: IntensityScaleView
    private lateinit var chart: LineChart

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 50) // ~20 cuadros por segundo
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = MeasurementStore(this)

        txtStatus = findViewById(R.id.txtStatus)
        txtSensor = findViewById(R.id.txtSensor)
        txtLive = findViewById(R.id.txtLive)
        txtChartCaption = findViewById(R.id.txtChartCaption)
        txtThreshold = findViewById(R.id.txtThreshold)
        txtResult = findViewById(R.id.txtResult)
        editLabel = findViewById(R.id.editLabel)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnMeasure = findViewById(R.id.btnMeasure)
        btnSave = findViewById(R.id.btnSave)
        btnHistory = findViewById(R.id.btnHistory)
        seekK = findViewById(R.id.seekK)
        chart = findViewById(R.id.chart)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linear != null) {
            sensor = linear
            usingLinear = true
        } else {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            usingLinear = false
        }

        val s = sensor
        if (s == null) {
            txtStatus.text = getString(R.string.no_accel)
            btnCalibrate.isEnabled = false
            btnMeasure.isEnabled = false
        } else {
            val type = getString(if (usingLinear) R.string.sensor_type_linear else R.string.sensor_type_acc)
            txtSensor.text = getString(R.string.sensor_line, s.name, type)
        }

        setupChart()

        seekK.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                k = (progress + 2).toDouble() // rango 2 … 10
                updateThresholdText()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        k = (seekK.progress + 2).toDouble()
        updateThresholdText()

        switchFilter = findViewById(R.id.switchFilter)
        seekWindow = findViewById(R.id.seekWindow)
        txtWindow = findViewById(R.id.txtWindow)
        cardExplain = findViewById(R.id.cardExplain)
        txtExplain = findViewById(R.id.txtExplain)
        btnTech = findViewById(R.id.btnTech)
        chartAccel = findViewById(R.id.chartAccel)
        chartVel = findViewById(R.id.chartVel)
        chartDisp = findViewById(R.id.chartDisp)
        scaleView = findViewById(R.id.scaleView)
        listOf(chartAccel, chartVel, chartDisp).forEach { styleExplainChart(it) }

        filterOn = AppSettings.filterNoise(this)
        switchFilter.isChecked = filterOn
        switchFilter.setOnCheckedChangeListener { _, checked ->
            filterOn = checked
            AppSettings.setFilterNoise(this, checked)
            noiseFilter.reset()
        }

        windowIdx = AppSettings.windowIndex(this)
        seekWindow.progress = windowIdx
        updateWindowText()
        seekWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                windowIdx = progress
                AppSettings.setWindowIndex(this@MainActivity, progress)
                updateWindowText()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        btnTech.setOnClickListener {
            showTech = !showTech
            updateResultVisibility()
        }

        findViewById<android.widget.ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnCalibrate.setOnClickListener { startCalibration() }
        btnMeasure.setOnClickListener {
            if (mode == Mode.ARMED) stopMeasuring() else startMeasuring()
        }
        btnSave.setOnClickListener { saveMeasurement() }
        findViewById<Button>(R.id.btnHelp).setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }
        btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        ui.removeCallbacks(refresh)
        gravityFilter.reset()
        // Al volver habrá un hueco en las marcas de tiempo: se cancela lo que estuviera en curso.
        if (mode == Mode.CALIBRATING) {
            calibrator = null
            mode = Mode.IDLE
        } else if (mode == Mode.ARMED) {
            detector = null
            mode = Mode.IDLE
        }
        live.clear()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp
        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val raw = if (usingLinear) Sample(t, x, y, z) else gravityFilter.filter(t, x, y, z)
        val cal = calibration
        val calibrated = cal?.apply(raw) ?: raw
        // Filtro de ruido opcional (solo tiene sentido con calibración, que da el nivel de ruido)
        val corrected = if (filterOn && cal != null && mode != Mode.CALIBRATING) {
            noiseFilter.filter(calibrated, cal.noiseRms)
        } else {
            calibrated
        }
        latest = corrected
        countRate(t)

        when (mode) {
            Mode.CALIBRATING -> {
                val c = calibrator
                if (c != null && c.add(raw)) finishCalibration(c)
            }
            Mode.ARMED -> {
                val m = detector?.add(corrected)
                if (m != null) onMeasurement(m)
            }
            else -> {}
        }

        if (t - lastChartNs >= 10_000_000L) { // 100 Hz para la gráfica
            lastChartNs = t
            live.addLast(corrected)
            while (live.isNotEmpty() && t - live.first().tNs > 5_000_000_000L) live.removeFirst()
        }
    }

    private fun countRate(t: Long) {
        if (rateWindowStartNs == 0L) rateWindowStartNs = t
        rateCount++
        val span = t - rateWindowStartNs
        if (span >= 1_000_000_000L) {
            rateHz = rateCount * 1e9 / span
            rateCount = 0
            rateWindowStartNs = t
        }
    }

    // ---------- Calibración y medición ----------

    private fun startCalibration() {
        detector = null
        // Una medición anterior ya no se puede guardar: se guardaría con la calibración nueva (ruido y frecuencia distintos).
        btnSave.isEnabled = false
        calibrator = Calibrator(2_000_000_000L)
        mode = Mode.CALIBRATING
        txtResult.text = getString(R.string.calibrating_msg)
    }

    private fun finishCalibration(c: Calibrator) {
        val cal = c.result()
        calibration = cal
        calibrator = null
        mode = Mode.IDLE
        live.clear()
        updateThresholdText()
        txtResult.text = getString(
            R.string.calib_done,
            cal.offsetX, cal.offsetY, cal.offsetZ, cal.noiseRms, cal.sampleRateHz, cal.sampleCount,
        )
        if (!cal.looksStill) {
            Toast.makeText(
                this,
                getString(R.string.noise_warning),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun startMeasuring() {
        val cal = calibration
        if (cal == null) {
            Toast.makeText(this, getString(R.string.calibrate_first), Toast.LENGTH_SHORT).show()
            return
        }
        btnSave.isEnabled = false
        measuredFiltered = filterOn
        measuredWindowS = AppSettings.WINDOW_OPTIONS[windowIdx]
        noiseFilter.reset()
        detector = EventDetector(
            cal.threshold(k),
            fixedWindowNs = if (measuredWindowS > 0) measuredWindowS * 1_000_000_000L else null,
        )
        mode = Mode.ARMED
        chartNeedsEvent = false
        txtChartCaption.text = getString(R.string.chart_caption_live)
    }

    private fun stopMeasuring() {
        detector = null
        mode = Mode.IDLE
    }

    private fun onMeasurement(m: Measurement) {
        lastMeasurement = m
        detector = null
        mode = Mode.RESULT
        chartNeedsEvent = true
        btnSave.isEnabled = true
        showResult(m)
    }

    private fun showResult(m: Measurement) {
        txtResult.text = ReportText.technical(this, m, measuredFiltered, measuredWindowS)
        txtExplain.text = ReportText.explanation(this, m, measuredFiltered)
        showTech = false
        updateResultVisibility()
        drawExplainCharts(m)
    }

    // ---------- Gráficas que explican el resultado ----------

    private fun styleExplainChart(c: LineChart) {
        val ink = ContextCompat.getColor(this, R.color.ink)
        val grid = ContextCompat.getColor(this, R.color.line)
        c.description.isEnabled = false
        c.setTouchEnabled(false)
        c.axisRight.isEnabled = false
        c.legend.isEnabled = false
        c.setNoDataText(getString(R.string.chart_no_data))
        c.setNoDataTextColor(ink)
        c.xAxis.position = XAxis.XAxisPosition.BOTTOM
        c.xAxis.setDrawGridLines(false)
        c.xAxis.textColor = ink
        c.xAxis.axisLineColor = ink
        c.axisLeft.textColor = ink
        c.axisLeft.axisLineColor = ink
        c.axisLeft.gridColor = grid
        c.axisLeft.axisMinimum = 0f
    }

    private fun filledSet(entries: List<Entry>, color: Int): LineDataSet =
        LineDataSet(entries, "").apply {
            this.color = color
            lineWidth = 2f
            setDrawCircles(false)
            setDrawValues(false)
            isHighlightEnabled = false
            setDrawFilled(true)
            fillColor = color
            fillAlpha = 50
        }

    /** Un solo punto con una etiqueta (por ejemplo el pico o el máximo). */
    private fun markerSet(e: Entry, text: String, color: Int): LineDataSet =
        LineDataSet(listOf(e), "").apply {
            this.color = android.graphics.Color.TRANSPARENT
            setDrawCircles(true)
            setCircleColor(color)
            circleRadius = 6f
            setDrawCircleHole(false)
            setDrawValues(true)
            valueTextColor = ContextCompat.getColor(this@MainActivity, R.color.ink)
            valueTextSize = 11f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = text
            }
            isHighlightEnabled = false
        }

    private fun drawExplainCharts(m: Measurement) {
        scaleView.setPeak(m.peak)

        val n = m.samples.size
        val stride = maxOf(1, n / 600) // máximo ~600 puntos por gráfica
        fun tOf(i: Int) = ((m.samples[i].tNs - m.startNs) / 1e9).toFloat()
        fun vOf(i: Int): Float {
            val k = m.kinematics[i]
            return sqrt(k.vx * k.vx + k.vy * k.vy + k.vz * k.vz).toFloat()
        }
        fun dOf(i: Int): Float {
            val k = m.kinematics[i]
            return (sqrt(k.dx * k.dx + k.dy * k.dy + k.dz * k.dz) * 1000).toFloat() // mm
        }
        val idx = (0 until n step stride).toList()
        val accent = ContextCompat.getColor(this, R.color.accent)
        val muted = ContextCompat.getColor(this, R.color.muted)

        // 1) Aceleración |a| con umbral, inicio/fin y pico
        chartAccel.data = LineData(
            filledSet(idx.map { Entry(tOf(it), m.samples[it].mag.toFloat()) }, ContextCompat.getColor(this, R.color.axis_z)),
            markerSet(Entry(m.peakTimeS.toFloat(), m.peak.toFloat()), getString(R.string.gr_peak_label, m.peak), accent),
        )
        chartAccel.axisLeft.removeAllLimitLines()
        chartAccel.axisLeft.addLimitLine(LimitLine(m.threshold.toFloat(), getString(R.string.gr_threshold)).apply {
            lineColor = accent
            lineWidth = 1f
            enableDashedLine(10f, 6f, 0f)
            textSize = 10f
            textColor = ContextCompat.getColor(this@MainActivity, R.color.ink)
            labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
        })
        chartAccel.xAxis.removeAllLimitLines()
        for ((x, label) in listOf(0f to getString(R.string.gr_start), m.durationS.toFloat() to getString(R.string.gr_end))) {
            chartAccel.xAxis.addLimitLine(LimitLine(x, label).apply {
                lineColor = muted
                lineWidth = 1f
                enableDashedLine(6f, 6f, 0f)
                textSize = 10f
                textColor = ContextCompat.getColor(this@MainActivity, R.color.ink)
                labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
            })
        }

        // 2) Velocidad estimada |v|
        val vMaxI = (0 until n).maxByOrNull { vOf(it) } ?: 0
        chartVel.data = LineData(
            filledSet(idx.map { Entry(tOf(it), vOf(it)) }, ContextCompat.getColor(this, R.color.axis_y)),
            markerSet(Entry(tOf(vMaxI), vOf(vMaxI)), getString(R.string.gr_max_label, m.maxVelocity), accent),
        )

        // 3) Desplazamiento estimado |d| en mm
        val dMaxI = (0 until n).maxByOrNull { dOf(it) } ?: 0
        chartDisp.data = LineData(
            filledSet(idx.map { Entry(tOf(it), dOf(it)) }, accent),
            markerSet(Entry(tOf(dMaxI), dOf(dMaxI)), getString(R.string.gr_max_label, m.maxDisplacement * 1000), muted),
        )

        for (c in listOf(chartAccel, chartVel, chartDisp)) {
            c.notifyDataSetChanged()
            c.invalidate()
        }
    }

    private fun updateResultVisibility() {
        val inResult = mode == Mode.RESULT
        cardExplain.visibility = if (inResult) android.view.View.VISIBLE else android.view.View.GONE
        btnTech.visibility = if (inResult) android.view.View.VISIBLE else android.view.View.GONE
        btnTech.text = getString(if (showTech) R.string.btn_tech_hide else R.string.btn_tech_show)
        txtResult.visibility = if (inResult && !showTech) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun updateWindowText() {
        val s = AppSettings.WINDOW_OPTIONS[windowIdx]
        txtWindow.text = getString(
            R.string.window_label,
            if (s == 0) getString(R.string.window_auto) else getString(R.string.window_fixed, s),
        )
    }

    private fun saveMeasurement() {
        val m = lastMeasurement ?: return
        val cal = calibration ?: return
        val type = if (usingLinear) "lineal" else "acelerometro_pasoalto"
        val file = store.save(m, cal, editLabel.text.toString(), type, measuredFiltered)
        btnSave.isEnabled = false
        Toast.makeText(this, getString(R.string.saved, file.name), Toast.LENGTH_SHORT).show()
    }

    private fun updateThresholdText() {
        val cal = calibration
        txtThreshold.text = if (cal == null) {
            getString(R.string.thr_uncal, k)
        } else {
            getString(R.string.thr_cal, k, cal.threshold(k))
        }
    }

    // ---------- Interfaz ----------

    private fun refreshUi() {
        txtStatus.text = when (mode) {
            Mode.IDLE -> getString(if (calibration == null) R.string.status_uncal else R.string.status_cal)
            Mode.CALIBRATING -> getString(R.string.status_calibrating, ((calibrator?.progress ?: 0.0) * 100).roundToInt())
            Mode.ARMED -> {
                val d = detector
                when {
                    d == null || d.state != EventDetector.State.IN_EVENT -> getString(R.string.status_waiting)
                    measuredWindowS > 0 -> getString(R.string.status_recording_fixed, minOf(d.elapsedS, measuredWindowS.toDouble()), measuredWindowS)
                    else -> getString(R.string.status_recording)
                }
            }
            Mode.RESULT -> getString(R.string.status_done)
        }
        // Las opciones de medición no se cambian mientras se mide o se calibra
        val locked = mode == Mode.ARMED || mode == Mode.CALIBRATING
        seekK.isEnabled = !locked
        switchFilter.isEnabled = !locked
        seekWindow.isEnabled = !locked
        updateResultVisibility()
        btnMeasure.text = getString(if (mode == Mode.ARMED) R.string.btn_stop else R.string.btn_measure)
        btnCalibrate.isEnabled = sensor != null && mode != Mode.CALIBRATING && mode != Mode.ARMED

        latest?.let {
            txtLive.text = getString(
                R.string.live_line,
                it.x, it.y, it.z, it.mag, rateHz,
            )
        }

        if (mode == Mode.RESULT) {
            if (chartNeedsEvent) {
                lastMeasurement?.let { drawEvent(it) }
                chartNeedsEvent = false
            }
        } else {
            drawLive()
        }
    }

    private fun setupChart() {
        chart.description.isEnabled = false
        chart.setTouchEnabled(false) // evita pelear con el scroll; la gráfica es solo de lectura
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawGridLines(false)
        chart.axisLeft.setDrawZeroLine(true)
        chart.legend.isEnabled = true
        chart.setNoDataText(getString(R.string.chart_no_data))
        val textColor = ContextCompat.getColor(this, R.color.ink)
        val gridColor = ContextCompat.getColor(this, R.color.line)
        chart.setNoDataTextColor(textColor)
        chart.legend.textColor = textColor
        chart.xAxis.textColor = textColor
        chart.axisLeft.textColor = textColor
        chart.xAxis.axisLineColor = textColor
        chart.axisLeft.axisLineColor = textColor
        chart.axisLeft.gridColor = gridColor
        chart.axisLeft.zeroLineColor = textColor
    }

    private fun dataSet(entries: List<Entry>, label: String, colorRes: Int, width: Float): LineDataSet =
        LineDataSet(entries, label).apply {
            color = ContextCompat.getColor(this@MainActivity, colorRes)
            lineWidth = width
            setDrawCircles(false)
            setDrawValues(false)
            isHighlightEnabled = false
        }

    private fun plot(samples: List<Sample>, t0: Long) {
        if (samples.isEmpty()) return
        val ex = ArrayList<Entry>(samples.size)
        val ey = ArrayList<Entry>(samples.size)
        val ez = ArrayList<Entry>(samples.size)
        val em = ArrayList<Entry>(samples.size)
        for (s in samples) {
            val t = ((s.tNs - t0) / 1e9).toFloat()
            ex.add(Entry(t, s.x.toFloat()))
            ey.add(Entry(t, s.y.toFloat()))
            ez.add(Entry(t, s.z.toFloat()))
            em.add(Entry(t, s.mag.toFloat()))
        }
        chart.data = LineData(
            dataSet(ex, "x", R.color.axis_x, 1f),
            dataSet(ey, "y", R.color.axis_y, 1f),
            dataSet(ez, "z", R.color.axis_z, 1f),
            dataSet(em, "|a|", R.color.axis_mag, 1.8f),
        )

        val left = chart.axisLeft
        left.removeAllLimitLines()
        val thr = detector?.threshold ?: lastMeasurement?.takeIf { mode == Mode.RESULT }?.threshold
            ?: calibration?.threshold(k)
        if (thr != null) {
            left.addLimitLine(LimitLine(thr.toFloat(), getString(R.string.limit_threshold)).apply {
                textColor = ContextCompat.getColor(this@MainActivity, R.color.ink)
                lineColor = ContextCompat.getColor(this@MainActivity, R.color.accent)
                lineWidth = 1f
                enableDashedLine(10f, 6f, 0f)
                textSize = 10f
            })
        }
        chart.invalidate()
    }

    private fun drawLive() {
        if (live.isEmpty()) return
        val last = live.last().tNs
        plot(live.toList(), last - 5_000_000_000L)
    }

    private fun drawEvent(m: Measurement) {
        // Máximo ~800 puntos para que la gráfica no se ponga lenta
        val step = maxOf(1, m.samples.size / 800)
        val pts = m.samples.filterIndexed { i, _ -> i % step == 0 }
        plot(pts, m.startNs)
        txtChartCaption.text = getString(R.string.chart_caption_event)
    }
}
