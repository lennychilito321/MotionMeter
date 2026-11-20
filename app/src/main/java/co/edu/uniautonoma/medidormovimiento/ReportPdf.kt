package co.edu.uniautonoma.medidormovimiento

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Genera el informe PDF que se envía al exportar: datos de la medición, explicación en lenguaje sencillo,
 * gráficas, datos técnicos y significado de cada dato. Dibuja directamente sobre el Canvas de PdfDocument
 * (hoja A4 vertical, siempre en colores claros para que se imprima bien).
 */
class ReportPdf(private val context: Context) {

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas = Canvas()
    private var y = 0f
    private var pageNo = 0

    private val pageW = 595
    private val pageH = 842
    private val margin = 40f
    private val contentW = pageW - 2 * margin

    private val ink = 0xFF1D2228.toInt()
    private val muted = 0xFF5F6B76.toInt()
    private val brand = 0xFF1F4E79.toInt()
    private val lineColor = 0xFFDDE3E8.toInt()
    private val accent = 0xFFE07A1F.toInt()
    private val blue = 0xFF2F6FD6.toInt()
    private val green = 0xFF2E9E5B.toInt()
    private val panel = 0xFFF1F4F7.toInt()

    private val loc: Locale = context.resources.configuration.locales[0]

    private fun s(id: Int): String = context.getString(id)
    private fun s(id: Int, vararg a: Any): String = context.getString(id, *a)
    private fun clean(t: String) = t.replace("⚠ ", "")
    private fun f(v: Double, d: Int): String = String.format(loc, "%.${d}f", v)

    private fun paint(size: Float, bold: Boolean = false, color: Int = ink) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ---------------- Páginas y texto ----------------

    private fun newPage() {
        finishPage()
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        page = p
        canvas = p.canvas
        y = margin
    }

    private fun finishPage() {
        val p = page ?: return
        canvas.drawText(s(R.string.rep_footer, pageNo), margin, pageH - 22f, paint(8.5f, color = muted))
        doc.finishPage(p)
        page = null
    }

    private fun ensure(h: Float) {
        if (page == null || y + h > pageH - margin - 12f) newPage()
    }

    private fun layout(text: String, p: TextPaint, width: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt()).setLineSpacing(0f, 1.1f).build()

    private fun text(t: String, p: TextPaint = paint(10.5f), after: Float = 6f) {
        val l = layout(t, p, contentW)
        ensure(l.height.toFloat().coerceAtMost(pageH - 2 * margin))
        canvas.save()
        canvas.translate(margin, y)
        l.draw(canvas)
        canvas.restore()
        y += l.height + after
    }

    private fun heading(t: String) {
        ensure(60f)
        y += 8f
        canvas.drawText(t, margin, y + 13f, paint(14f, true, brand))
        y += 19f
        val lp = Paint().apply { color = lineColor; strokeWidth = 1f }
        canvas.drawLine(margin, y, margin + contentW, y, lp)
        y += 8f
    }

    private fun kv(label: String, value: String) {
        val lw = 190f
        val vl = layout(value, paint(10.5f), contentW - lw)
        val h = max(16f, vl.height.toFloat() + 4f)
        ensure(h)
        canvas.drawText(label, margin, y + 12f, paint(10f, color = muted))
        canvas.save()
        canvas.translate(margin + lw, y + 1f)
        vl.draw(canvas)
        canvas.restore()
        y += h
        canvas.drawLine(margin, y, margin + contentW, y, Paint().apply { color = lineColor; strokeWidth = 0.5f })
    }

    private fun term(name: String, desc: String) {
        val nl = layout(name, paint(10.5f, true), contentW)
        val dl = layout(clean(desc), paint(10f), contentW)
        ensure(nl.height + dl.height + 10f)
        canvas.save(); canvas.translate(margin, y); nl.draw(canvas); canvas.restore()
        y += nl.height + 1f
        canvas.save(); canvas.translate(margin, y); dl.draw(canvas); canvas.restore()
        y += dl.height + 8f
    }

    // ---------------- Gráficas ----------------

    private fun fmtAxis(v: Float, max: Float): String = when {
        max >= 100f -> String.format(loc, "%.0f", v)
        max >= 10f -> String.format(loc, "%.1f", v)
        max >= 1f -> String.format(loc, "%.2f", v)
        else -> String.format(loc, "%.3f", v)
    }

    private fun downsample(xs: List<Float>, ys: List<Float>, maxPoints: Int = 600): Pair<List<Float>, List<Float>> {
        val stride = max(1, xs.size / maxPoints)
        val ox = ArrayList<Float>()
        val oy = ArrayList<Float>()
        var i = 0
        while (i < xs.size) { ox.add(xs[i]); oy.add(ys[i]); i += stride }
        return ox to oy
    }

    /** Una curva de una gráfica: valores, color y, si tiene nombre, entrada en la leyenda. */
    private class Series(val ys: List<Float>, val color: Int, val label: String? = null)

    /**
     * Gráfica de línea con una o varias curvas. Explica sus ejes: [yNote] y [xNote] van sobre la gráfica y debajo hay una
     * leyenda con el significado de cada color y marca. [hLines]: líneas horizontales (valor, etiqueta);
     * [vLines]: líneas verticales (x, etiqueta); [marker]: punto destacado (x, y, etiqueta);
     * [signed]: el eje vertical admite valores negativos (aceleración por eje).
     */
    private fun lineChart(
        title: String,
        caption: String,
        yNote: String,
        xNote: String,
        xsIn: List<Float>,
        series: List<Series>,
        hLines: List<Pair<Float, String>> = emptyList(),
        vLines: List<Pair<Float, String>> = emptyList(),
        marker: Triple<Float, Float, String>? = null,
        signed: Boolean = false,
    ) {
        val chartH = 160f
        val notePaint = paint(8.5f, color = muted)
        val yNoteL = layout(clean(yNote), notePaint, contentW)
        val xNoteL = layout(clean(xNote), notePaint, contentW)
        val capLayout = layout(clean(caption), paint(9f, color = muted), contentW)

        // Leyenda: (color, texto, estilo) con estilo 0 = línea, 1 = línea discontinua, 2 = punto
        val legend = ArrayList<Triple<Int, String, Int>>()
        for (sr in series) sr.label?.let { legend.add(Triple(sr.color, it, 0)) }
        for ((_, name) in hLines) legend.add(Triple(accent, name, 1))
        if (vLines.isNotEmpty()) legend.add(Triple(muted, vLines.joinToString(" / ") { it.second }, 1))
        marker?.let { legend.add(Triple(accent, it.third, 2)) }
        val lp = paint(8.5f)
        var legendRows = 1
        var rowX = 0f
        for ((_, txt, _) in legend) {
            val w = 18f + lp.measureText(txt) + 12f
            if (rowX + w > contentW && rowX > 0f) { legendRows++; rowX = 0f }
            rowX += w
        }
        val legendH = if (legend.isEmpty()) 0f else legendRows * 13f + 4f

        ensure(20f + yNoteL.height + xNoteL.height + chartH + legendH + capLayout.height + 24f)
        canvas.drawText(title, margin, y + 12f, paint(11.5f, true, ink))
        y += 18f
        canvas.save(); canvas.translate(margin, y); yNoteL.draw(canvas); canvas.restore()
        y += yNoteL.height + 2f
        canvas.save(); canvas.translate(margin, y); xNoteL.draw(canvas); canvas.restore()
        y += xNoteL.height + 4f

        val xsAll = xsIn
        val sampled = series.map { downsample(xsAll, it.ys) }
        val xs = sampled.first().first
        val l = margin + 44f
        val r = margin + contentW - 10f
        val t = y + 6f
        val b = y + chartH - 22f
        val xMin = xs.first()
        val xMax = if (xs.last() > xMin) xs.last() else xMin + 1f
        var yMax = sampled.maxOf { (_, ys) -> ys.maxOrNull() ?: 0f }
        var yMin = if (signed) sampled.minOf { (_, ys) -> ys.minOrNull() ?: 0f } else 0f
        for ((v, _) in hLines) yMax = max(yMax, v)
        marker?.let { yMax = max(yMax, it.second) }
        if (yMax <= 0f && yMin >= 0f) yMax = 1f
        yMax = if (yMax > 0f) yMax * 1.18f else 0f
        yMin = if (yMin < 0f) yMin * 1.18f else 0f
        val ySpan = yMax - yMin

        fun px(x: Float) = l + (x - xMin) / (xMax - xMin) * (r - l)
        fun py(v: Float) = b - (v - yMin) / ySpan * (b - t)

        val grid = Paint().apply { this.color = lineColor; strokeWidth = 0.6f }
        val lab = paint(8f, color = muted)
        for (i in 0..4) {
            val v = yMin + ySpan * i / 4f
            canvas.drawLine(l, py(v), r, py(v), grid)
            val txt = fmtAxis(v, max(abs(yMax), abs(yMin)))
            canvas.drawText(txt, l - 4f - lab.measureText(txt), py(v) + 3f, lab)
        }
        for (i in 0..5) {
            val xv = xMin + (xMax - xMin) * i / 5f
            val txt = String.format(loc, "%.1f", xv)
            canvas.drawText(txt, px(xv) - lab.measureText(txt) / 2, b + 11f, lab)
        }
        canvas.drawText("t (s)", r - lab.measureText("t (s)"), b + 21f, lab)

        // Curvas (con relleno si hay una sola)
        for ((si, sr) in series.withIndex()) {
            val ys = sampled[si].second
            val line = Path()
            for (i in xs.indices) {
                val x = px(xs[i]); val yy = py(ys[i])
                if (i == 0) line.moveTo(x, yy) else line.lineTo(x, yy)
            }
            if (series.size == 1) {
                val area = Path()
                val base = py(0f.coerceIn(yMin, yMax))
                area.moveTo(px(xs.first()), base)
                for (i in xs.indices) area.lineTo(px(xs[i]), py(ys[i]))
                area.lineTo(px(xs.last()), base); area.close()
                canvas.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = sr.color; alpha = 55; style = Paint.Style.FILL })
            }
            canvas.drawPath(line, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = sr.color; style = Paint.Style.STROKE; strokeWidth = 1.6f })
        }
        if (yMin < 0f) canvas.drawLine(l, py(0f), r, py(0f), Paint().apply { this.color = ink; strokeWidth = 0.8f })

        val dash = DashPathEffect(floatArrayOf(5f, 4f), 0f)
        for ((v, name) in hLines) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = accent; strokeWidth = 1f; pathEffect = dash }
            canvas.drawLine(l, py(v), r, py(v), p)
            canvas.drawText(name, r - lab.measureText(name) - 2f, py(v) - 3f, paint(8f, color = accent))
        }
        for ((x, name) in vLines) {
            if (x < xMin || x > xMax) continue
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = muted; strokeWidth = 1f; pathEffect = dash }
            canvas.drawLine(px(x), t, px(x), b, p)
            canvas.drawText(name, px(x) + 2f, t + 9f, lab)
        }
        marker?.let { (mx, my, name) ->
            canvas.drawCircle(px(mx), py(my), 3.6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = accent })
            val w = lab.measureText(name)
            val tx = (px(mx) - w / 2).coerceIn(l, r - w)
            canvas.drawText(name, tx, max(t + 8f, py(my) - 7f), paint(8.5f, true, ink))
        }
        val axis = Paint().apply { this.color = ink; strokeWidth = 1f }
        canvas.drawLine(l, t, l, b, axis)
        canvas.drawLine(l, b, r, b, axis)
        y += chartH

        // Leyenda
        if (legend.isNotEmpty()) {
            var lx = margin
            var ly = y + 2f
            for ((c, txt, kind) in legend) {
                val w = 18f + lp.measureText(txt) + 12f
                if (lx - margin + w > contentW && lx > margin) { lx = margin; ly += 13f }
                val sw = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = c; strokeWidth = 2f; if (kind == 1) pathEffect = dash }
                when (kind) {
                    2 -> canvas.drawCircle(lx + 7f, ly + 6f, 3.4f, sw.apply { this.style = Paint.Style.FILL })
                    else -> canvas.drawLine(lx, ly + 6f, lx + 14f, ly + 6f, sw)
                }
                canvas.drawText(txt, lx + 18f, ly + 9f, lp)
                lx += w
            }
            y += legendH
        }
        canvas.save(); canvas.translate(margin, y); capLayout.draw(canvas); canvas.restore()
        y += capLayout.height + 12f
    }

    /** Escala de intensidad con el marcador del pico (misma lógica que IntensityScaleView). */
    private fun scale(peak: Double) {
        ensure(70f)
        val edges = doubleArrayOf(0.02, 0.1, 0.5, 2.0, 5.0, 10.0)
        val colors = intArrayOf(0xFF4CAF50.toInt(), 0xFF9CCC65.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(), 0xFFE53935.toInt())
        val labels = intArrayOf(R.string.scale_1, R.string.scale_2, R.string.scale_3, R.string.scale_4, R.string.scale_5)
        val gap = 3f
        val segW = (contentW - gap * 4) / 5
        val barTop = y + 16f
        val barH = 16f
        val lp = paint(8.5f)
        for (i in 0..4) {
            val left = margin + i * (segW + gap)
            canvas.drawRoundRect(RectF(left, barTop, left + segW, barTop + barH), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[i] })
            val txt = s(labels[i])
            canvas.drawText(txt, left + segW / 2 - lp.measureText(txt) / 2, barTop + barH + 12f, lp)
        }
        val v = peak.coerceIn(edges.first(), edges.last() - 1e-9)
        var i = 0
        while (i < 4 && v >= edges[i + 1]) i++
        val frac = (ln(v) - ln(edges[i])) / (ln(edges[i + 1]) - ln(edges[i]))
        val x = margin + ((i + frac) / 5.0).toFloat() * contentW
        val tri = Path().apply { moveTo(x, barTop - 2f); lineTo(x - 5f, barTop - 12f); lineTo(x + 5f, barTop - 12f); close() }
        canvas.drawPath(tri, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        y += 16f + barH + 20f
        text(s(R.string.gr_scale_cap), paint(9f, color = muted), 8f)
    }

    private fun barChart(title: String, caption: String, values: List<Float>) {
        val chartH = 140f
        val capLayout = layout(caption, paint(9f, color = muted), contentW)
        ensure(20f + chartH + capLayout.height + 14f)
        canvas.drawText(title, margin, y + 12f, paint(11.5f, true, ink))
        y += 20f
        val l = margin + 44f
        val r = margin + contentW - 10f
        val t = y + 6f
        val b = y + chartH - 18f
        val yMax = (values.maxOrNull() ?: 1f).let { if (it <= 0f) 1f else it * 1.15f }
        val lab = paint(8f, color = muted)
        val grid = Paint().apply { color = lineColor; strokeWidth = 0.6f }
        for (i in 0..4) {
            val v = yMax * i / 4f
            val yy = b - v / yMax * (b - t)
            canvas.drawLine(l, yy, r, yy, grid)
            val txt = fmtAxis(v, yMax)
            canvas.drawText(txt, l - 4f - lab.measureText(txt), yy + 3f, lab)
        }
        val n = values.size
        val slot = (r - l) / n
        val barW = slot * 0.65f
        for ((i, v) in values.withIndex()) {
            val cx = l + slot * i + slot / 2
            val top = b - v / yMax * (b - t)
            canvas.drawRect(cx - barW / 2, top, cx + barW / 2, b, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blue })
            val txt = (i + 1).toString()
            canvas.drawText(txt, cx - lab.measureText(txt) / 2, b + 11f, lab)
        }
        canvas.drawLine(l, b, r, b, Paint().apply { color = ink; strokeWidth = 1f })
        y += chartH
        canvas.save(); canvas.translate(margin, y); capLayout.draw(canvas); canvas.restore()
        y += capLayout.height + 12f
    }

    // ---------------- Tabla de resumen ----------------

    private fun table(headers: List<String>, rows: List<List<String>>, fractions: List<Float>) {
        val rowH = 17f
        fun header() {
            ensure(rowH * 2)
            canvas.drawRect(margin, y, margin + contentW, y + rowH, Paint().apply { color = brand })
            var x = margin
            for ((i, h) in headers.withIndex()) {
                val w = contentW * fractions[i]
                val p = paint(8.5f, true, 0xFFFFFFFF.toInt())
                canvas.drawText(TextUtils.ellipsize(h, p, w - 6f, TextUtils.TruncateAt.END).toString(), x + 3f, y + 12f, p)
                x += w
            }
            y += rowH
        }
        header()
        for ((ri, row) in rows.withIndex()) {
            if (y + rowH > pageH - margin - 12f) { newPage(); header() }
            if (ri % 2 == 1) canvas.drawRect(margin, y, margin + contentW, y + rowH, Paint().apply { color = panel })
            var x = margin
            for ((i, c) in row.withIndex()) {
                val w = contentW * fractions[i]
                val p = paint(8.5f)
                canvas.drawText(TextUtils.ellipsize(c, p, w - 6f, TextUtils.TruncateAt.END).toString(), x + 3f, y + 12f, p)
                x += w
            }
            y += rowH
        }
        y += 10f
    }

    // ---------------- Contenido de una medición ----------------

    private fun addMeasurement(sm: MeasurementStore.SavedMeasurement, numbered: Int? = null) {
        val m = sm.m
        newPage()
        val title = if (numbered != null) "#$numbered · ${sm.label}" else s(R.string.rep_title)
        canvas.drawText(title, margin, y + 20f, paint(if (numbered != null) 17f else 22f, true, brand))
        y += 30f
        if (numbered == null) {
            canvas.drawText(sm.label, margin, y + 10f, paint(12f, color = muted))
            y += 22f
        } else {
            y += 4f
        }

        heading(s(R.string.rep_info))
        kv(s(R.string.rep_label), sm.label)
        kv(s(R.string.rep_date), sm.date)
        kv(s(R.string.rep_sensor), sm.sensor)
        kv(s(R.string.rep_rate), f(sm.rateHz, 0) + " Hz")
        kv(s(R.string.rep_noise), f(sm.noiseRms, 4) + " m/s²")
        kv(s(R.string.rep_thr), f(m.threshold, 4) + " m/s²")
        kv(
            s(R.string.rep_filter),
            when (sm.filtered) {
                true -> s(R.string.yes)
                false -> s(R.string.no)
                null -> "—"
            },
        )
        kv(
            s(R.string.rep_window),
            if (m.fixedWindow) s(R.string.window_fixed, Math.round(m.windowS).toInt()) else s(R.string.window_auto),
        )

        heading(s(R.string.explain_title))
        for (para in clean(ReportText.explanation(context, m, sm.filtered)).split("\n\n")) text(para, paint(10.5f), 5f)

        heading(s(R.string.rep_charts))
        text(s(R.string.gr_scale_title), paint(11.5f, true), 2f)
        scale(m.peak)

        val ts = m.samples.map { ((it.tNs - m.startNs) / 1e9).toFloat() }
        val xNote = s(R.string.gr_x_label)
        lineChart(
            s(R.string.gr_a_title), s(R.string.gr_a_cap), s(R.string.gr_a_ylabel), xNote, ts,
            listOf(Series(m.samples.map { it.mag.toFloat() }, blue, s(R.string.gr_a_legend))),
            hLines = listOf(m.threshold.toFloat() to s(R.string.gr_threshold)),
            vLines = listOf(0f to s(R.string.gr_start), m.durationS.toFloat() to s(R.string.gr_end)),
            marker = Triple(m.peakTimeS.toFloat(), m.peak.toFloat(), s(R.string.gr_peak_label, m.peak)),
        )
        val vs = m.kinematics.map { sqrt(it.vx * it.vx + it.vy * it.vy + it.vz * it.vz).toFloat() }
        val vMaxI = vs.indices.maxByOrNull { vs[it] } ?: 0
        lineChart(
            s(R.string.gr_v_title), s(R.string.gr_v_cap), s(R.string.gr_v_ylabel), xNote, ts,
            listOf(Series(vs, green, s(R.string.gr_v_legend))),
            marker = Triple(ts[vMaxI], vs[vMaxI], s(R.string.gr_max_label, m.maxVelocity)),
        )
        val ds = m.kinematics.map { (sqrt(it.dx * it.dx + it.dy * it.dy + it.dz * it.dz) * 1000).toFloat() }
        val dMaxI = ds.indices.maxByOrNull { ds[it] } ?: 0
        lineChart(
            s(R.string.gr_d_title), s(R.string.gr_d_cap), s(R.string.gr_d_ylabel), xNote, ts,
            listOf(Series(ds, accent, s(R.string.gr_d_legend))),
            marker = Triple(ts[dMaxI], ds[dMaxI], s(R.string.gr_max_label, m.maxDisplacement * 1000)),
        )
        lineChart(
            s(R.string.gr_ax_title), s(R.string.gr_ax_cap), s(R.string.gr_ax_ylabel), xNote, ts,
            listOf(
                Series(m.samples.map { it.x.toFloat() }, 0xFFD64545.toInt(), s(R.string.gr_ax_x)),
                Series(m.samples.map { it.y.toFloat() }, green, s(R.string.gr_ax_y)),
                Series(m.samples.map { it.z.toFloat() }, blue, s(R.string.gr_ax_z)),
            ),
            signed = true,
        )

        heading(s(R.string.rep_tech))
        kv(s(R.string.rt_peak), f(m.peak, 3) + " m/s²")
        kv(s(R.string.rt_peak_t), f(m.peakTimeS, 3) + " s")
        kv(s(R.string.rt_rms), f(m.rms, 3) + " m/s²")
        kv(s(R.string.rt_dur), f(m.durationS, 3) + " s")
        kv(s(R.string.rt_axis), m.dominantAxis.uppercaseChar().toString())
        kv(s(R.string.rt_rms_axes), "${f(m.rmsX, 3)} / ${f(m.rmsY, 3)} / ${f(m.rmsZ, 3)} m/s²")
        kv(s(R.string.rt_peak_axes), "${f(m.peakX, 3)} / ${f(m.peakY, 3)} / ${f(m.peakZ, 3)} m/s²")
        kv(s(R.string.rt_vmax), f(m.maxVelocity, 4) + " m/s *")
        kv(s(R.string.rt_dmax), f(m.maxDisplacement * 1000, 2) + " mm *")
        kv(s(R.string.rt_dfinal), f(m.finalDisplacement * 1000, 2) + " mm *")
        if (m.truncated) kv(s(R.string.rt_trunc), s(R.string.yes))
        text(clean(s(R.string.r_footnote)).trim(), paint(9f, color = muted), 4f)
        text(s(R.string.rep_csv_note), paint(9f, color = muted), 6f)

        heading(s(R.string.rep_glossary))
        term(s(R.string.t_peak), s(R.string.help_peak))
        term(s(R.string.t_rms), s(R.string.help_rms))
        term(s(R.string.t_duration), s(R.string.help_duration))
        term(s(R.string.t_axis), s(R.string.help_axis))
        term(s(R.string.t_disp), s(R.string.help_disp))
        term(s(R.string.t_noise), s(R.string.help_noise))
    }

    // ---------------- API pública ----------------

    private fun finish(out: File) {
        finishPage()
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
    }

    /** Informe de una sola medición. */
    fun buildSingle(sm: MeasurementStore.SavedMeasurement, out: File) {
        addMeasurement(sm)
        finish(out)
    }

    /** Informe con el resumen comparativo de todas las mediciones y una sección por medición. */
    fun buildAll(list: List<MeasurementStore.SavedMeasurement>, out: File) {
        newPage()
        canvas.drawText(s(R.string.rep_title_all), margin, y + 22f, paint(22f, true, brand))
        y += 32f
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        canvas.drawText(s(R.string.rep_count, list.size) + " · " + s(R.string.rep_generated, now), margin, y + 10f, paint(11f, color = muted))
        y += 24f

        heading(s(R.string.rep_summary))
        table(
            listOf("#", s(R.string.rep_label), s(R.string.rep_date), s(R.string.rep_col_peak), s(R.string.rep_col_rms), s(R.string.rep_col_dur), s(R.string.rep_col_disp)),
            list.mapIndexed { i, sm ->
                val m = sm.m
                listOf(
                    "${i + 1}", sm.label, sm.date, f(m.peak, 3), f(m.rms, 3), f(m.durationS, 2),
                    f(m.maxDisplacement * 1000, 1),
                )
            },
            listOf(0.06f, 0.24f, 0.22f, 0.12f, 0.12f, 0.11f, 0.13f),
        )

        val latest = list.takeLast(25)
        val offset = list.size - latest.size
        barChart(s(R.string.rep_bars), s(R.string.rep_bars_cap) + if (offset > 0) " " + s(R.string.rep_bars_last, latest.size) else "", latest.map { it.m.peak.toFloat() })

        list.forEachIndexed { i, sm -> addMeasurement(sm, i + 1) }
        finish(out)
    }
}
