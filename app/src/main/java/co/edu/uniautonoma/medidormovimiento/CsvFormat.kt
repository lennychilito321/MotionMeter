package co.edu.uniautonoma.medidormovimiento

import java.util.Locale

/** Formato de los archivos CSV (punto decimal y coma como separador, para Excel/Python). */
object CsvFormat {

    val SUMMARY_HEADER = listOf(
        "fecha", "archivo", "etiqueta", "sensor", "frecuencia_hz", "ruido_ms2", "umbral_ms2",
        "pico_ms2", "t_pico_s", "rms_ms2", "duracion_s", "eje_dominante",
        "rms_x_ms2", "rms_y_ms2", "rms_z_ms2", "pico_x_ms2", "pico_y_ms2", "pico_z_ms2",
        "v_max_ms", "d_max_m", "d_final_m", "truncada", "filtro_ruido", "ventana_s",
    )

    const val SAMPLES_HEADER = "t_s,ax_ms2,ay_ms2,az_ms2,a_mag_ms2,vx_ms,vy_ms,vz_ms,dx_m,dy_m,dz_m"

    private fun f(v: Double, decimals: Int = 5) = String.format(Locale.US, "%.${decimals}f", v)

    /** Quita comas, comillas y saltos de línea para que la etiqueta no rompa el CSV. */
    fun cleanLabel(label: String): String =
        label.replace(Regex("[,\"\\r\\n;]"), " ").trim().ifEmpty { "sin_etiqueta" }

    fun summaryRow(
        date: String,
        fileName: String,
        label: String,
        sensorType: String,
        cal: Calibration,
        m: Measurement,
        filterNoise: Boolean = false,
    ): String = listOf(
        date, fileName, cleanLabel(label), sensorType,
        f(cal.sampleRateHz, 1), f(cal.noiseRms), f(m.threshold),
        f(m.peak), f(m.peakTimeS, 4), f(m.rms), f(m.durationS, 4), m.dominantAxis.toString(),
        f(m.rmsX), f(m.rmsY), f(m.rmsZ), f(m.peakX), f(m.peakY), f(m.peakZ),
        f(m.maxVelocity), f(m.maxDisplacement, 6), f(m.finalDisplacement, 6),
        if (m.truncated) "si" else "no",
        if (filterNoise) "si" else "no",
        if (m.fixedWindow) f(m.windowS, 2) else "auto",
    ).joinToString(",")

    /** Una fila por lectura: tiempo relativo al inicio del movimiento (negativo = reposo previo). */
    fun samplesCsv(m: Measurement): String {
        val sb = StringBuilder(SAMPLES_HEADER).append('\n')
        for (i in m.samples.indices) {
            val s = m.samples[i]
            val k = m.kinematics[i]
            sb.append(f((s.tNs - m.startNs) / 1e9, 6)).append(',')
                .append(f(s.x)).append(',').append(f(s.y)).append(',').append(f(s.z)).append(',')
                .append(f(s.mag)).append(',')
                .append(f(k.vx, 6)).append(',').append(f(k.vy, 6)).append(',').append(f(k.vz, 6)).append(',')
                .append(f(k.dx, 7)).append(',').append(f(k.dy, 7)).append(',').append(f(k.dz, 7))
                .append('\n')
        }
        return sb.toString()
    }
}
