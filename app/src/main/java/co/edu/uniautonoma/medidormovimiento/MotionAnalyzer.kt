package co.edu.uniautonoma.medidormovimiento

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
 * Lógica de medición, independiente de Android para poder probarla con JUnit.
 *
 * Flujo:  lectura del sensor -> (filtro de gravedad si hace falta) -> calibración (offset y ruido)
 *         -> detección de inicio/fin del movimiento -> cálculo de variables.
 */

/** Una lectura de aceleración en m/s². [tNs] es la marca de tiempo del sensor en nanosegundos. */
data class Sample(val tNs: Long, val x: Double, val y: Double, val z: Double) {
    val mag: Double get() = sqrt(x * x + y * y + z * z)
}

/**
 * Resultado de calibrar con el celular quieto.
 * - offset: promedio de cada eje en reposo (lo que el sensor marca cuando no debería marcar nada).
 * - noiseRms: valor RMS de la magnitud del residuo una vez quitado el offset (nivel de ruido).
 */
data class Calibration(
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val noiseRms: Double,
    val sampleRateHz: Double,
    val sampleCount: Int,
) {
    fun apply(s: Sample) = Sample(s.tNs, s.x - offsetX, s.y - offsetY, s.z - offsetZ)

    /** Umbral de detección = k veces el ruido, con un mínimo para que no sea absurdamente bajo. */
    fun threshold(k: Double): Double = max(k * noiseRms, MIN_THRESHOLD)

    /** Si el ruido es muy alto, probablemente la mesa se movió durante la calibración. */
    val looksStill: Boolean get() = noiseRms < STILL_LIMIT

    companion object {
        const val MIN_THRESHOLD = 0.02 // m/s²
        const val STILL_LIMIT = 0.15   // m/s²
    }
}

/** Acumula lecturas en reposo durante [durationNs] y calcula offset y ruido. */
class Calibrator(private val durationNs: Long = 2_000_000_000L) {
    private val samples = ArrayList<Sample>()

    /** Agrega una lectura. Devuelve true cuando ya se completó el tiempo de calibración. */
    fun add(s: Sample): Boolean {
        samples.add(s)
        return elapsedNs() >= durationNs && samples.size >= MIN_SAMPLES
    }

    val progress: Double
        get() = (elapsedNs().toDouble() / durationNs).coerceIn(0.0, 1.0)

    private fun elapsedNs(): Long =
        if (samples.size < 2) 0L else samples.last().tNs - samples.first().tNs

    fun result(): Calibration {
        require(samples.size >= 2) { "No hay suficientes lecturas para calibrar" }
        val n = samples.size
        val mx = samples.sumOf { it.x } / n
        val my = samples.sumOf { it.y } / n
        val mz = samples.sumOf { it.z } / n
        var sumSq = 0.0
        for (s in samples) {
            val dx = s.x - mx
            val dy = s.y - my
            val dz = s.z - mz
            sumSq += dx * dx + dy * dy + dz * dz
        }
        val noise = sqrt(sumSq / n)
        val spanS = elapsedNs() / 1e9
        val rate = if (spanS > 0) (n - 1) / spanS else 0.0
        return Calibration(mx, my, mz, noise, rate, n)
    }

    companion object {
        const val MIN_SAMPLES = 20
    }
}

/**
 * Filtro paso alto para cuando el celular no tiene TYPE_LINEAR_ACCELERATION:
 * estima la gravedad con un paso bajo y la resta de la lectura.
 * [tauS] es la constante de tiempo del filtro en segundos.
 */
class GravityFilter(private val tauS: Double = 0.5) {
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var lastNs = -1L

    fun filter(tNs: Long, x: Double, y: Double, z: Double): Sample {
        if (lastNs < 0) {
            gx = x; gy = y; gz = z
            lastNs = tNs
            return Sample(tNs, 0.0, 0.0, 0.0)
        }
        val dt = (tNs - lastNs) / 1e9
        lastNs = tNs
        val alpha = tauS / (tauS + dt)
        gx = alpha * gx + (1 - alpha) * x
        gy = alpha * gy + (1 - alpha) * y
        gz = alpha * gz + (1 - alpha) * z
        return Sample(tNs, x - gx, y - gy, z - gz)
    }

    fun reset() {
        lastNs = -1L
    }
}

/** Velocidad y desplazamiento estimados en un instante (m/s y m). */
data class Kinematics(
    val tNs: Long,
    val vx: Double, val vy: Double, val vz: Double,
    val dx: Double, val dy: Double, val dz: Double,
)

/** Resultado de una medición. Tiempos relativos al inicio del movimiento. */
data class Measurement(
    val samples: List<Sample>,          // lecturas del evento (incluye un poco de reposo antes y después)
    val kinematics: List<Kinematics>,   // integración, una por lectura
    val startNs: Long,                  // primera lectura que superó el umbral
    val endNs: Long,                    // última lectura que superó el umbral
    val threshold: Double,
    val peak: Double,                   // m/s²
    val peakTimeS: Double,              // s desde el inicio
    val rms: Double,                    // m/s²
    val durationS: Double,              // s
    val rmsX: Double, val rmsY: Double, val rmsZ: Double,
    val peakX: Double, val peakY: Double, val peakZ: Double,
    val dominantAxis: Char,
    val maxVelocity: Double,            // m/s
    val maxDisplacement: Double,        // m
    val finalDisplacement: Double,      // m
    val truncated: Boolean,             // true si se cortó por durar demasiado
    val windowS: Double = durationS,    // s que se registraron desde el inicio (ventana fija o automática)
    val fixedWindow: Boolean = false,   // true si el usuario fijó la duración de la medición
)

/**
 * Filtro de ruido opcional (se aplica después de calibrar):
 * 1) suavizado exponencial (paso bajo) con constante [tauS], que reduce el ruido de banda ancha;
 * 2) zona muerta: si la magnitud suavizada es menor que el nivel de ruido medido en la calibración,
 *    la lectura se toma como 0 (no se puede distinguir del ruido).
 */
class NoiseFilter(private val tauS: Double = 0.04) {
    private var last: Sample? = null

    fun filter(s: Sample, noiseRms: Double): Sample {
        // La primera lectura parte de 0 (no pasa sin filtrar): así una lectura de ruido suelta no se cuela.
        val p = last ?: Sample(s.tNs, 0.0, 0.0, 0.0)
        val dt = ((s.tNs - p.tNs) / 1e9).coerceAtLeast(0.0)
        val a = dt / (tauS + dt)
        val smooth = Sample(s.tNs, p.x + a * (s.x - p.x), p.y + a * (s.y - p.y), p.z + a * (s.z - p.z))
        last = smooth
        return if (smooth.mag < noiseRms) Sample(s.tNs, 0.0, 0.0, 0.0) else smooth
    }

    fun reset() {
        last = null
    }
}

/**
 * Detecta automáticamente el inicio y el fin del movimiento.
 * - Inicio: la magnitud supera el umbral.
 * - Fin: la magnitud se mantiene por debajo del umbral durante [quietNs].
 * Guarda [preTriggerNs] de reposo previo para que la integración arranque desde v = 0.
 *
 * Si [fixedWindowNs] no es null, la medición dura exactamente ese tiempo desde el inicio del movimiento
 * (aunque la mesa se quede quieta antes o siga moviéndose después). Si es null, termina sola al volver al reposo.
 */
class EventDetector(
    val threshold: Double,
    private val preTriggerNs: Long = 200_000_000L,
    private val quietNs: Long = 300_000_000L,
    private val maxEventNs: Long = 30_000_000_000L,
    private val fixedWindowNs: Long? = null,
) {
    enum class State { WAITING, IN_EVENT }

    var state = State.WAITING
        private set

    private val pre = ArrayDeque<Sample>()
    private val event = ArrayList<Sample>()
    private var startNs = 0L
    private var lastAboveNs = 0L
    private var lastNs = 0L

    /** Segundos transcurridos desde que empezó el movimiento (0 si todavía no empieza). */
    val elapsedS: Double get() = if (state == State.IN_EVENT) (lastNs - startNs) / 1e9 else 0.0

    /** Procesa una lectura (ya calibrada). Devuelve la medición cuando el movimiento termina. */
    fun add(s: Sample): Measurement? {
        val above = s.mag > threshold
        when (state) {
            State.WAITING -> {
                pre.addLast(s)
                while (pre.size > 1 && s.tNs - pre.first().tNs > preTriggerNs) pre.removeFirst()
                if (above) {
                    state = State.IN_EVENT
                    event.clear()
                    event.addAll(pre)
                    pre.clear()
                    startNs = s.tNs
                    lastAboveNs = s.tNs
                    lastNs = s.tNs
                }
            }
            State.IN_EVENT -> {
                event.add(s)
                lastNs = s.tNs
                if (above) lastAboveNs = s.tNs
                val fixed = fixedWindowNs
                if (fixed != null) {
                    if (s.tNs - startNs >= fixed) {
                        val m = MeasurementCalculator.compute(
                            event.toList(), startNs, lastAboveNs, threshold,
                            truncated = false, windowEndNs = s.tNs, fixedWindow = true,
                        )
                        reset()
                        return m
                    }
                } else {
                    val quiet = s.tNs - lastAboveNs >= quietNs
                    val tooLong = s.tNs - startNs >= maxEventNs
                    if (quiet || tooLong) {
                        val m = MeasurementCalculator.compute(
                            event.toList(), startNs, lastAboveNs, threshold, truncated = !quiet
                        )
                        reset()
                        return m
                    }
                }
            }
        }
        return null
    }

    fun reset() {
        state = State.WAITING
        pre.clear()
        event.clear()
    }
}

object MeasurementCalculator {

    fun compute(
        samples: List<Sample>,
        startNs: Long,
        endNs: Long,
        threshold: Double,
        truncated: Boolean = false,
        windowEndNs: Long = endNs,
        fixedWindow: Boolean = false,
    ): Measurement {
        require(samples.isNotEmpty()) { "Medición vacía" }
        val core = samples.filter { it.tNs in startNs..windowEndNs }.ifEmpty { samples }

        var peak = 0.0
        var peakNs = core.first().tNs
        var sumSq = 0.0
        var sx = 0.0; var sy = 0.0; var sz = 0.0
        var px = 0.0; var py = 0.0; var pz = 0.0
        for (s in core) {
            val m = s.mag
            if (m > peak) { peak = m; peakNs = s.tNs }
            sumSq += m * m
            sx += s.x * s.x; sy += s.y * s.y; sz += s.z * s.z
            px = max(px, abs(s.x)); py = max(py, abs(s.y)); pz = max(pz, abs(s.z))
        }
        val n = core.size
        val rmsX = sqrt(sx / n)
        val rmsY = sqrt(sy / n)
        val rmsZ = sqrt(sz / n)
        val dominant = when {
            rmsX >= rmsY && rmsX >= rmsZ -> 'x'
            rmsY >= rmsZ -> 'y'
            else -> 'z'
        }

        val kin = integrate(samples)
        var vMax = 0.0
        var dMax = 0.0
        for (k in kin) {
            vMax = max(vMax, sqrt(k.vx * k.vx + k.vy * k.vy + k.vz * k.vz))
            dMax = max(dMax, sqrt(k.dx * k.dx + k.dy * k.dy + k.dz * k.dz))
        }
        val last = kin.last()
        val dFinal = sqrt(last.dx * last.dx + last.dy * last.dy + last.dz * last.dz)

        return Measurement(
            samples = samples,
            kinematics = kin,
            startNs = startNs,
            endNs = endNs,
            threshold = threshold,
            peak = peak,
            peakTimeS = (peakNs - startNs) / 1e9,
            rms = sqrt(sumSq / n),
            durationS = (endNs - startNs) / 1e9,
            rmsX = rmsX, rmsY = rmsY, rmsZ = rmsZ,
            peakX = px, peakY = py, peakZ = pz,
            dominantAxis = dominant,
            maxVelocity = vMax,
            maxDisplacement = dMax,
            finalDisplacement = dFinal,
            truncated = truncated,
            windowS = (windowEndNs - startNs) / 1e9,
            fixedWindow = fixedWindow,
        )
    }

    /**
     * Integra la aceleración dos veces con la regla del trapecio.
     *
     * Como la mesa parte del reposo y termina en reposo, la velocidad final real es 0.
     * Cualquier velocidad final distinta de 0 es deriva (ruido u offset residual acumulado),
     * así que se resta una rampa lineal para que v(final) = 0 antes de integrar la posición.
     */
    fun integrate(samples: List<Sample>): List<Kinematics> {
        val n = samples.size
        val t0 = samples.first().tNs
        val tEnd = samples.last().tNs
        val span = (tEnd - t0).toDouble()

        val vx = DoubleArray(n); val vy = DoubleArray(n); val vz = DoubleArray(n)
        for (i in 1 until n) {
            val dt = (samples[i].tNs - samples[i - 1].tNs) / 1e9
            vx[i] = vx[i - 1] + (samples[i - 1].x + samples[i].x) / 2 * dt
            vy[i] = vy[i - 1] + (samples[i - 1].y + samples[i].y) / 2 * dt
            vz[i] = vz[i - 1] + (samples[i - 1].z + samples[i].z) / 2 * dt
        }
        if (span > 0) {
            val ex = vx[n - 1]; val ey = vy[n - 1]; val ez = vz[n - 1]
            for (i in 0 until n) {
                val f = (samples[i].tNs - t0) / span
                vx[i] -= ex * f; vy[i] -= ey * f; vz[i] -= ez * f
            }
        }

        val out = ArrayList<Kinematics>(n)
        var dx = 0.0; var dy = 0.0; var dz = 0.0
        out.add(Kinematics(samples[0].tNs, vx[0], vy[0], vz[0], 0.0, 0.0, 0.0))
        for (i in 1 until n) {
            val dt = (samples[i].tNs - samples[i - 1].tNs) / 1e9
            dx += (vx[i - 1] + vx[i]) / 2 * dt
            dy += (vy[i - 1] + vy[i]) / 2 * dt
            dz += (vz[i - 1] + vz[i]) / 2 * dt
            out.add(Kinematics(samples[i].tNs, vx[i], vy[i], vz[i], dx, dy, dz))
        }
        return out
    }
}
