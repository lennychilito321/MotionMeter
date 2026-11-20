package co.edu.uniautonoma.medidormovimiento

import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import co.edu.uniautonoma.medidormovimiento.databinding.ActivityMainBinding
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.LegendEntry
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    private enum class Mode { IDLE, CALIBRATING, ARMED, RESULT }

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val explainCharts by lazy { with(binding) { listOf(chartAccel, chartVel, chartDisp, chartAxes) } }
    private lateinit var sensorManager: SensorManager
    private lateinit var store: MeasurementStore
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

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 50) // ~20 cuadros por segundo
        }
    }

    private fun colorOf(id: Int) = ContextCompat.getColor(this, id)
    private fun openScreen(cls: Class<*>) = startActivity(Intent(this, cls))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        store = MeasurementStore(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        usingLinear = linear != null
        sensor = linear ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        with(binding) {
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
            explainCharts.forEach { styleExplainChart(it) }
            // La gráfica por eje tiene valores negativos: el eje vertical no parte de 0 y se marca la línea del cero
            chartAxes.axisLeft.apply {
                resetAxisMinimum()
                setDrawZeroLine(true)
                zeroLineColor = colorOf(R.color.ink)
            }

            seekK.onChange { k = (it + 2).toDouble(); updateThresholdText() } // k de 2 a 10
            k = (seekK.progress + 2).toDouble()
            updateThresholdText()

            filterOn = AppSettings.filterNoise(this@MainActivity)
            switchFilter.isChecked = filterOn
            switchFilter.setOnCheckedChangeListener { _, checked ->
                filterOn = checked
                AppSettings.setFilterNoise(this@MainActivity, checked)
                noiseFilter.reset()
            }

            windowIdx = AppSettings.windowIndex(this@MainActivity)
            seekWindow.progress = windowIdx
            updateWindowText()
            seekWindow.onChange {
                windowIdx = it
                AppSettings.setWindowIndex(this@MainActivity, it)
                updateWindowText()
            }

            btnTech.setOnClickListener { showTech = !showTech; updateResultVisibility() }
            btnSettings.setOnClickListener { openScreen(SettingsActivity::class.java) }
            btnCalibrate.setOnClickListener { startCalibration() }
            btnMeasure.setOnClickListener { if (mode == Mode.ARMED) stopMeasuring() else startMeasuring() }
            btnSave.setOnClickListener { saveMeasurement() }
            btnHelp.setOnClickListener { openScreen(HelpActivity::class.java) }
            btnHistory.setOnClickListener { openScreen(HistoryActivity::class.java) }
        }
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
            Mode.CALIBRATING -> calibrator?.let { if (it.add(raw)) finishCalibration(it) }
            Mode.ARMED -> detector?.add(corrected)?.let { onMeasurement(it) }
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
        binding.btnSave.isEnabled = false
        calibrator = Calibrator(2_000_000_000L)
        mode = Mode.CALIBRATING
        binding.txtResult.text = getString(R.string.calibrating_msg)
    }

    private fun finishCalibration(c: Calibrator) {
        val cal = c.result()
        calibration = cal
        calibrator = null
        mode = Mode.IDLE
        live.clear()
        updateThresholdText()
        binding.txtResult.text = getString(
            R.string.calib_done,
            cal.offsetX, cal.offsetY, cal.offsetZ, cal.noiseRms, cal.sampleRateHz, cal.sampleCount,
        )
        if (!cal.looksStill) toast(getString(R.string.noise_warning), long = true)
    }

    private fun startMeasuring() {
        val cal = calibration
        if (cal == null) {
            toast(getString(R.string.calibrate_first))
            return
        }
        binding.btnSave.isEnabled = false
        measuredFiltered = filterOn
        measuredWindowS = AppSettings.WINDOW_OPTIONS[windowIdx]
        noiseFilter.reset()
        detector = EventDetector(
            cal.threshold(k),
            fixedWindowNs = if (measuredWindowS > 0) measuredWindowS * 1_000_000_000L else null,
        )
        mode = Mode.ARMED
        chartNeedsEvent = false
        binding.txtChartCaption.text = getString(R.string.chart_caption_live)
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
        binding.btnSave.isEnabled = true
        showResult(m)
    }

    private fun showResult(m: Measurement) {
        binding.txtResult.text = ReportText.technical(this, m, measuredFiltered, measuredWindowS)
        binding.txtExplain.text = ReportText.explanation(this, m, measuredFiltered)
        showTech = false
        updateResultVisibility()
        drawExplainCharts(m)
    }

    // ---------- Estilo y series de las gráficas ----------

    /** Estilo común de todas las gráficas: solo lectura, colores del tema y ejes visibles. */
    private fun styleChart(c: LineChart) {
        val ink = colorOf(R.color.ink)
        c.description.isEnabled = false
        c.setTouchEnabled(false) // evita pelear con el scroll; la gráfica es solo de lectura
        c.axisRight.isEnabled = false
        c.legend.isEnabled = true
        c.legend.textColor = ink
        c.setNoDataText(getString(R.string.chart_no_data))
        c.setNoDataTextColor(ink)
        c.xAxis.position = XAxis.XAxisPosition.BOTTOM
        c.xAxis.setDrawGridLines(false)
        c.xAxis.textColor = ink
        c.xAxis.axisLineColor = ink
        c.axisLeft.textColor = ink
        c.axisLeft.axisLineColor = ink
        c.axisLeft.gridColor = colorOf(R.color.line)
    }

    private fun setupChart() {
        styleChart(binding.chart)
        binding.chart.axisLeft.apply {
            setDrawZeroLine(true)
            zeroLineColor = colorOf(R.color.ink)
        }
    }

    private fun styleExplainChart(c: LineChart) {
        styleChart(c) // la leyenda explica cada curva y marca (se arma en drawExplainCharts)
        c.legend.isWordWrapEnabled = true
        c.legend.textSize = 11f
        c.axisLeft.axisMinimum = 0f
    }

    private fun lineSet(entries: List<Entry>, color: Int, width: Float, label: String = "", filled: Boolean = false) =
        LineDataSet(entries, label).apply {
            this.color = color
            lineWidth = width
            setDrawCircles(false)
            setDrawValues(false)
            isHighlightEnabled = false
            if (filled) {
                setDrawFilled(true)
                fillColor = color
                fillAlpha = 50
            }
        }

    /** Un solo punto con una etiqueta (por ejemplo el pico o el máximo). */
    private fun markerSet(e: Entry, text: String, color: Int) = LineDataSet(listOf(e), "").apply {
        this.color = Color.TRANSPARENT
        setDrawCircles(true)
        setCircleColor(color)
        circleRadius = 6f
        setDrawCircleHole(false)
        setDrawValues(true)
        valueTextColor = colorOf(R.color.ink)
        valueTextSize = 11f
        valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float) = text
        }
        isHighlightEnabled = false
    }

    private fun limitLine(value: Float, label: String, color: Int, dash: Float) = LimitLine(value, label).apply {
        lineColor = color
        lineWidth = 1f
        enableDashedLine(dash, 6f, 0f)
        textSize = 10f
        textColor = colorOf(R.color.ink)
        labelPosition = LimitLine.LimitLabelPosition.RIGHT_TOP
    }

    private fun entry(label: String, color: Int, form: Legend.LegendForm = Legend.LegendForm.LINE) =
        LegendEntry(label, form, 10f, 2f, null, color)

    // ---------- Gráficas que explican el resultado ----------

    private fun drawExplainCharts(m: Measurement) = with(binding) {
        scaleView.setPeak(m.peak)

        val n = m.samples.size
        val stride = maxOf(1, n / 600) // máximo ~600 puntos por gráfica
        fun tOf(i: Int) = ((m.samples[i].tNs - m.startNs) / 1e9).toFloat()
        fun vOf(i: Int) = m.kinematics[i].let { sqrt(it.vx * it.vx + it.vy * it.vy + it.vz * it.vz).toFloat() }
        fun dOf(i: Int) = m.kinematics[i].let { (sqrt(it.dx * it.dx + it.dy * it.dy + it.dz * it.dz) * 1000).toFloat() } // mm
        val idx = (0 until n step stride).toList()
        val accent = colorOf(R.color.accent)
        val muted = colorOf(R.color.muted)
        val red = colorOf(R.color.axis_x)
        val green = colorOf(R.color.axis_y)
        val blue = colorOf(R.color.axis_z)

        // 1) Aceleración |a| con umbral, inicio/fin y pico
        chartAccel.data = LineData(
            lineSet(idx.map { Entry(tOf(it), m.samples[it].mag.toFloat()) }, blue, 2f, filled = true),
            markerSet(Entry(m.peakTimeS.toFloat(), m.peak.toFloat()), getString(R.string.gr_peak_label, m.peak), accent),
        )
        chartAccel.axisLeft.removeAllLimitLines()
        chartAccel.axisLeft.addLimitLine(limitLine(m.threshold.toFloat(), getString(R.string.gr_threshold), accent, 10f))
        chartAccel.xAxis.removeAllLimitLines()
        chartAccel.xAxis.addLimitLine(limitLine(0f, getString(R.string.gr_start), muted, 6f))
        chartAccel.xAxis.addLimitLine(limitLine(m.durationS.toFloat(), getString(R.string.gr_end), muted, 6f))

        // 2) Velocidad estimada |v|
        val vMaxI = (0 until n).maxByOrNull { vOf(it) } ?: 0
        chartVel.data = LineData(
            lineSet(idx.map { Entry(tOf(it), vOf(it)) }, green, 2f, filled = true),
            markerSet(Entry(tOf(vMaxI), vOf(vMaxI)), getString(R.string.gr_max_label, m.maxVelocity), accent),
        )

        // 3) Desplazamiento estimado |d| en mm
        val dMaxI = (0 until n).maxByOrNull { dOf(it) } ?: 0
        chartDisp.data = LineData(
            lineSet(idx.map { Entry(tOf(it), dOf(it)) }, accent, 2f, filled = true),
            markerSet(Entry(tOf(dMaxI), dOf(dMaxI)), getString(R.string.gr_max_label, m.maxDisplacement * 1000), muted),
        )

        // 4) Aceleración de cada eje del celular (x, y, z), con signo
        fun axisSet(sel: (Sample) -> Double, color: Int) =
            lineSet(idx.map { Entry(tOf(it), sel(m.samples[it]).toFloat()) }, color, 1.8f)
        chartAxes.data = LineData(axisSet({ it.x }, red), axisSet({ it.y }, green), axisSet({ it.z }, blue))

        // Leyendas: qué significa cada color y cada marca
        val dot = Legend.LegendForm.CIRCLE
        chartAccel.legend.setCustom(
            listOf(
                entry(getString(R.string.gr_a_legend), blue),
                entry(getString(R.string.gr_threshold), accent),
                entry(getString(R.string.gr_start) + " / " + getString(R.string.gr_end), muted),
                entry(getString(R.string.gr_peak_label, m.peak), accent, dot),
            ),
        )
        chartVel.legend.setCustom(
            listOf(entry(getString(R.string.gr_v_legend), green), entry(getString(R.string.gr_max_label, m.maxVelocity), accent, dot)),
        )
        chartDisp.legend.setCustom(
            listOf(entry(getString(R.string.gr_d_legend), accent), entry(getString(R.string.gr_max_label, m.maxDisplacement * 1000), muted, dot)),
        )
        chartAxes.legend.setCustom(
            listOf(entry(getString(R.string.gr_ax_x), red), entry(getString(R.string.gr_ax_y), green), entry(getString(R.string.gr_ax_z), blue)),
        )
        for (c in explainCharts) {
            c.notifyDataSetChanged()
            c.invalidate()
        }
    }

    // ---------- Interfaz ----------

    private fun updateResultVisibility() = with(binding) {
        val inResult = mode == Mode.RESULT
        cardExplain.isVisible = inResult
        btnTech.isVisible = inResult
        btnTech.text = getString(if (showTech) R.string.btn_tech_hide else R.string.btn_tech_show)
        txtResult.isVisible = !inResult || showTech
    }

    private fun updateWindowText() {
        val s = AppSettings.WINDOW_OPTIONS[windowIdx]
        binding.txtWindow.text = getString(
            R.string.window_label,
            if (s == 0) getString(R.string.window_auto) else getString(R.string.window_fixed, s),
        )
    }

    private fun updateThresholdText() {
        val cal = calibration
        binding.txtThreshold.text =
            if (cal == null) getString(R.string.thr_uncal, k) else getString(R.string.thr_cal, k, cal.threshold(k))
    }

    private fun saveMeasurement() {
        val m = lastMeasurement ?: return
        val cal = calibration ?: return
        val type = if (usingLinear) "lineal" else "acelerometro_pasoalto"
        val file = store.save(m, cal, binding.editLabel.text.toString(), type, measuredFiltered)
        binding.btnSave.isEnabled = false
        toast(getString(R.string.saved, file.name))
    }

    private fun refreshUi() = with(binding) {
        // Estado, opciones bloqueadas, botones, gráfica y lectura en vivo
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

        if (mode != Mode.RESULT) {
            drawLive()
        } else if (chartNeedsEvent) {
            lastMeasurement?.let { drawEvent(it) }
            chartNeedsEvent = false
        }
        latest?.let { txtLive.text = getString(R.string.live_line, it.x, it.y, it.z, it.mag, rateHz) }
    }

    private fun plot(samples: List<Sample>, t0: Long) {
        if (samples.isEmpty()) return
        fun entries(sel: (Sample) -> Double) = samples.map { Entry(((it.tNs - t0) / 1e9).toFloat(), sel(it).toFloat()) }
        val chart = binding.chart
        chart.data = LineData(
            lineSet(entries { it.x }, colorOf(R.color.axis_x), 1f, "x"),
            lineSet(entries { it.y }, colorOf(R.color.axis_y), 1f, "y"),
            lineSet(entries { it.z }, colorOf(R.color.axis_z), 1f, "z"),
            lineSet(entries { it.mag }, colorOf(R.color.axis_mag), 1.8f, "|a|"),
        )

        chart.axisLeft.removeAllLimitLines()
        val thr = detector?.threshold ?: lastMeasurement?.takeIf { mode == Mode.RESULT }?.threshold
            ?: calibration?.threshold(k)
        if (thr != null) {
            chart.axisLeft.addLimitLine(limitLine(thr.toFloat(), getString(R.string.limit_threshold), colorOf(R.color.accent), 10f))
        }
        chart.invalidate()
    }

    private fun drawLive() {
        if (live.isEmpty()) return
        plot(live.toList(), live.last().tNs - 5_000_000_000L)
    }

    private fun drawEvent(m: Measurement) {
        // Máximo ~800 puntos para que la gráfica no se ponga lenta
        val step = maxOf(1, m.samples.size / 800)
        plot(m.samples.filterIndexed { i, _ -> i % step == 0 }, m.startNs)
        binding.txtChartCaption.text = getString(R.string.chart_caption_event)
    }
}

/** Escucha solo el cambio de valor de una SeekBar. */
private fun SeekBar.onChange(block: (Int) -> Unit) = setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = block(progress)
    override fun onStartTrackingTouch(bar: SeekBar?) {}
    override fun onStopTrackingTouch(bar: SeekBar?) {}
})
