package co.edu.uniautonoma.medidormovimiento

/*
 * Convierte los números de una medición en categorías fáciles de explicar al usuario
 * ("suave", "golpe seco", "sobre todo vertical"...). Es Kotlin puro para poder probarlo con JUnit;
 * los textos los arma MainActivity con los recursos de cada idioma.
 */

enum class Intensity { VERY_WEAK, WEAK, MODERATE, STRONG, VERY_STRONG }
enum class DurationKind { INSTANT, SHORT, SUSTAINED, LONG }
enum class MotionShape { IMPACT, MIXED, VIBRATION }
enum class Direction { VERTICAL, HORIZONTAL, MIXED }

data class Interpretation(
    val intensity: Intensity,
    val durationKind: DurationKind,
    val shape: MotionShape,
    val direction: Direction,
    val peakG: Double,
    val displacementMm: Double,
    /** El desplazamiento solo es confiable en movimientos cortos. */
    val displacementTrusted: Boolean,
    /** false si el pico apenas superó el umbral: podría ser ruido o una vibración externa. */
    val reliable: Boolean,
)

object MeasurementInterpreter {
    const val G = 9.80665

    fun interpret(m: Measurement): Interpretation {
        val intensity = when {
            m.peak < 0.1 -> Intensity.VERY_WEAK
            m.peak < 0.5 -> Intensity.WEAK
            m.peak < 2.0 -> Intensity.MODERATE
            m.peak < 5.0 -> Intensity.STRONG
            else -> Intensity.VERY_STRONG
        }
        val durationKind = when {
            m.durationS < 0.3 -> DurationKind.INSTANT
            m.durationS < 1.5 -> DurationKind.SHORT
            m.durationS < 5.0 -> DurationKind.SUSTAINED
            else -> DurationKind.LONG
        }
        // Factor de cresta = pico / RMS: alto = un solo golpe; bajo = movimiento parejo (vibración).
        val crest = if (m.rms > 1e-9) m.peak / m.rms else 0.0
        val shape = when {
            crest >= 3.0 -> MotionShape.IMPACT
            crest < 2.0 -> MotionShape.VIBRATION
            else -> MotionShape.MIXED
        }
        val total = m.rmsX * m.rmsX + m.rmsY * m.rmsY + m.rmsZ * m.rmsZ
        val vertical = if (total > 1e-12) m.rmsZ * m.rmsZ / total else 0.0
        val direction = when {
            vertical > 0.65 -> Direction.VERTICAL
            vertical < 0.35 -> Direction.HORIZONTAL
            else -> Direction.MIXED
        }
        return Interpretation(
            intensity = intensity,
            durationKind = durationKind,
            shape = shape,
            direction = direction,
            peakG = m.peak / G,
            displacementMm = m.maxDisplacement * 1000,
            displacementTrusted = m.durationS <= 1.5,
            reliable = m.peak >= 2 * m.threshold,
        )
    }
}
