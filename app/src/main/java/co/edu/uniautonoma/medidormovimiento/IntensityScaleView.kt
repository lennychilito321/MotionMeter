package co.edu.uniautonoma.medidormovimiento

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.ln

/**
 * Escala de intensidad en 5 tramos (casi nula, suave, moderada, fuerte, muy fuerte) con un marcador
 * que indica dónde cae el pico de la medición. La escala es logarítmica porque la intensidad
 * de un movimiento crece por órdenes de magnitud.
 */
class IntensityScaleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val edges = doubleArrayOf(0.02, 0.1, 0.5, 2.0, 5.0, 10.0) // m/s², límites de los 5 tramos
    private val colors = intArrayOf(0xFF4CAF50.toInt(), 0xFF9CCC65.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(), 0xFFE53935.toInt())
    private val labels = intArrayOf(R.string.scale_1, R.string.scale_2, R.string.scale_3, R.string.scale_4, R.string.scale_5)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink)
    }
    private val rect = RectF()
    private val path = Path()

    private var peak: Double? = null

    fun setPeak(value: Double?) {
        peak = value
        invalidate()
    }

    /** Posición 0..1 del pico dentro de la escala. */
    private fun position(p: Double): Float {
        val v = p.coerceIn(edges.first(), edges.last() - 1e-9)
        var i = 0
        while (i < colors.size - 1 && v >= edges[i + 1]) i++
        val frac = (ln(v) - ln(edges[i])) / (ln(edges[i + 1]) - ln(edges[i]))
        return ((i + frac) / colors.size).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val barTop = 22f * d
        val barH = 18f * d
        val gap = 2f * d
        val segW = (w - gap * (colors.size - 1)) / colors.size

        for (i in colors.indices) {
            val left = i * (segW + gap)
            rect.set(left, barTop, left + segW, barTop + barH)
            barPaint.color = colors[i]
            canvas.drawRoundRect(rect, 4f * d, 4f * d, barPaint)
            canvas.drawText(context.getString(labels[i]), left + segW / 2, barTop + barH + 16f * d, textPaint)
        }

        peak?.let {
            val x = position(it) * w
            path.reset()
            path.moveTo(x, barTop - 2f * d)
            path.lineTo(x - 7f * d, barTop - 14f * d)
            path.lineTo(x + 7f * d, barTop - 14f * d)
            path.close()
            canvas.drawPath(path, markerPaint)
        }
    }
}
