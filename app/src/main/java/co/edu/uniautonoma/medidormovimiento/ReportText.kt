package co.edu.uniautonoma.medidormovimiento

import android.content.Context

/** Textos del resultado de una medición; los comparten la pantalla de medición y el informe PDF. */
object ReportText {

    /** Explicación en lenguaje sencillo, sin hablar de ejes x/y (eso queda en los datos técnicos). */
    fun explanation(context: Context, m: Measurement, filtered: Boolean?): String {
        val i = MeasurementInterpreter.interpret(m)
        val parts = ArrayList<String>()
        parts.add(
            context.getString(
                when (i.intensity) {
                    Intensity.VERY_WEAK -> R.string.ex_int_1
                    Intensity.WEAK -> R.string.ex_int_2
                    Intensity.MODERATE -> R.string.ex_int_3
                    Intensity.STRONG -> R.string.ex_int_4
                    Intensity.VERY_STRONG -> R.string.ex_int_5
                },
                m.peak,
            ),
        )
        parts.add(
            context.getString(
                when (i.durationKind) {
                    DurationKind.INSTANT -> R.string.ex_dur_1
                    DurationKind.SHORT -> R.string.ex_dur_2
                    DurationKind.SUSTAINED -> R.string.ex_dur_3
                    DurationKind.LONG -> R.string.ex_dur_4
                },
                m.durationS,
            ),
        )
        parts.add(
            context.getString(
                when (i.shape) {
                    MotionShape.IMPACT -> R.string.ex_shape_1
                    MotionShape.MIXED -> R.string.ex_shape_2
                    MotionShape.VIBRATION -> R.string.ex_shape_3
                },
            ),
        )
        parts.add(
            context.getString(
                when (i.direction) {
                    Direction.VERTICAL -> R.string.ex_dir_1
                    Direction.HORIZONTAL -> R.string.ex_dir_2
                    Direction.MIXED -> R.string.ex_dir_3
                },
            ),
        )
        parts.add(
            context.getString(
                if (i.displacementTrusted) R.string.ex_disp_ok else R.string.ex_disp_bad,
                i.displacementMm,
            ),
        )
        parts.add(context.getString(if (i.reliable) R.string.ex_reliable else R.string.ex_unreliable))
        if (m.fixedWindow && m.windowS - m.durationS < 0.15) parts.add(context.getString(R.string.ex_cut))
        if (m.truncated) parts.add(context.getString(R.string.r_truncated).trim())
        if (filtered != null) {
            parts.add(context.getString(if (filtered) R.string.ex_mode_filtered else R.string.ex_mode_raw))
        }
        return parts.joinToString("\n\n")
    }

    /** Bloque de datos técnicos en texto monoespaciado (pantalla de medición). */
    fun technical(context: Context, m: Measurement, filtered: Boolean, windowS: Int): String {
        val sb = StringBuilder()
        sb.append(context.getString(R.string.r_peak, m.peak, m.peakTimeS))
        sb.append(context.getString(R.string.r_rms, m.rms))
        sb.append(context.getString(R.string.r_dur, m.durationS))
        sb.append(context.getString(R.string.r_axis, m.dominantAxis.uppercaseChar().toString()))
        sb.append(context.getString(R.string.r_rms_axes, m.rmsX, m.rmsY, m.rmsZ))
        sb.append(context.getString(R.string.r_peak_axes, m.peakX, m.peakY, m.peakZ))
        sb.append(context.getString(R.string.r_vel, m.maxVelocity))
        sb.append(context.getString(R.string.r_disp_max, m.maxDisplacement * 100))
        sb.append(context.getString(R.string.r_disp_final, m.finalDisplacement * 100))
        sb.append(context.getString(R.string.r_thr, m.threshold))
        sb.append(context.getString(R.string.r_filter, context.getString(if (filtered) R.string.yes else R.string.no)))
        sb.append(
            context.getString(
                R.string.r_window,
                if (m.fixedWindow) context.getString(R.string.window_fixed, windowS) else context.getString(R.string.window_auto),
            ),
        )
        if (m.truncated) sb.append(context.getString(R.string.r_truncated))
        sb.append(context.getString(R.string.r_footnote))
        return sb.toString()
    }
}
