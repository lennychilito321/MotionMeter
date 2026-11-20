package co.edu.uniautonoma.medidormovimiento

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import java.util.Random

/**
 * Pruebas con señales sintéticas cuya física conocemos de forma exacta.
 *
 * Pulso de prueba sobre el eje x durante un periodo T:  a(t) = A·sin(ωt)
 *   v(t) = (A/ω)(1 − cos ωt)        -> v máx = 2A/ω
 *   x(t) = (A/ω)(t − sin(ωt)/ω)     -> x(T)  = A·T/ω
 */
class MotionAnalyzerTest {

    private val fs = 400.0                 // Hz
    private val dtNs = (1e9 / fs).toLong()
    private val noise = 0.01               // m/s² (desviación por eje)
    private val offset = doubleArrayOf(0.03, -0.02, 0.05)
    private val rnd = Random(42)

    private fun gauss() = rnd.nextGaussian() * noise

    /** Genera reposo + pulso senoidal en x + reposo, con offset y ruido como un sensor real. */
    private fun signal(a: Double, f: Double, restS: Double = 1.0): List<Sample> {
        val out = ArrayList<Sample>()
        val w = 2 * PI * f
        val pulseS = 1 / f
        val total = restS + pulseS + restS
        var t = 0.0
        var tNs = 1_000_000_000L
        while (t < total) {
            val ax = if (t >= restS && t < restS + pulseS) a * sin(w * (t - restS)) else 0.0
            out.add(Sample(tNs, ax + offset[0] + gauss(), offset[1] + gauss(), offset[2] + gauss()))
            t += 1 / fs
            tNs += dtNs
        }
        return out
    }

    private fun calibrate(): Calibration {
        val c = Calibrator()
        var tNs = 0L
        while (true) {
            val done = c.add(Sample(tNs, offset[0] + gauss(), offset[1] + gauss(), offset[2] + gauss()))
            if (done) break
            tNs += dtNs
        }
        return c.result()
    }

    @Test
    fun calibracionEstimaOffsetYRuido() {
        val cal = calibrate()
        assertEquals(0.03, cal.offsetX, 0.003)
        assertEquals(-0.02, cal.offsetY, 0.003)
        assertEquals(0.05, cal.offsetZ, 0.003)
        // ruido de magnitud ≈ sqrt(3)·σ
        assertEquals(Math.sqrt(3.0) * noise, cal.noiseRms, 0.003)
        assertEquals(fs, cal.sampleRateHz, 1.0)
        assertTrue(cal.looksStill)
    }

    @Test
    fun reposoNoGeneraEventos() {
        val cal = calibrate()
        val det = EventDetector(cal.threshold(5.0))
        var tNs = 0L
        repeat((fs * 10).toInt()) {
            val s = Sample(tNs, offset[0] + gauss(), offset[1] + gauss(), offset[2] + gauss())
            assertNull(det.add(cal.apply(s)))
            tNs += dtNs
        }
    }

    @Test
    fun pulsoSenoidalDaLosValoresTeoricos() {
        val a = 1.0
        val f = 2.0
        val w = 2 * PI * f
        val cal = calibrate()
        val det = EventDetector(cal.threshold(5.0))
        var m: Measurement? = null
        for (s in signal(a, f)) {
            val r = det.add(cal.apply(s))
            if (r != null) m = r
        }
        assertNotNull(m)
        m!!
        assertEquals('x', m.dominantAxis)
        assertEquals(a, m.peak, 0.05)
        assertEquals(1 / (4 * f), m.peakTimeS, 0.03)          // el pico está a T/4 (la cima es plana: el ruido lo mueve ±20 ms)
        assertEquals(a / Math.sqrt(2.0), m.rms, 0.05)          // RMS de una senoide
        assertTrue("duración ${m.durationS}", m.durationS > 0.85 / f && m.durationS <= 1.0 / f)
        assertEquals(2 * a / w, m.maxVelocity, 0.02)           // 0,159 m/s
        assertEquals(a * (1 / f) / w, m.finalDisplacement, 0.006) // 0,0398 m
        assertTrue(!m.truncated)
    }

    @Test
    fun filtroDeGravedadQuitaLaComponenteConstante() {
        val gf = GravityFilter()
        var last = Sample(0, 0.0, 0.0, 0.0)
        var tNs = 0L
        repeat((fs * 5).toInt()) {
            last = gf.filter(tNs, 0.0, 0.0, 9.81)
            tNs += dtNs
        }
        assertEquals(0.0, last.mag, 1e-3)
    }

    @Test
    fun csvTieneUnaFilaPorLectura() {
        val cal = calibrate()
        val det = EventDetector(cal.threshold(5.0))
        var m: Measurement? = null
        for (s in signal(1.0, 2.0)) det.add(cal.apply(s))?.let { m = it }
        val csv = CsvFormat.samplesCsv(m!!)
        assertEquals(m!!.samples.size + 1, csv.trim().lines().size)
        val row = CsvFormat.summaryRow("2026-10-21 10:00:00", "a.csv", "fuerte, x", "lineal", cal, m!!)
        assertEquals(CsvFormat.SUMMARY_HEADER.size, row.split(",").size)
    }

    @Test
    fun ventanaFijaMideExactamenteEseTiempo() {
        val cal = calibrate()
        val det = EventDetector(cal.threshold(5.0), fixedWindowNs = 2_000_000_000L)
        var m: Measurement? = null
        for (s in signal(1.0, 2.0, restS = 2.5)) {
            val r = det.add(cal.apply(s))
            if (r != null) { m = r; break }
        }
        assertNotNull(m)
        m!!
        assertTrue(m.fixedWindow)
        assertEquals(2.0, m.windowS, 0.01)
    }

    @Test
    fun filtroDeRuidoDejaElReposoEnCero() {
        val cal = calibrate()
        val nf = NoiseFilter()
        for (s in signal(0.0, 2.0, restS = 2.0)) {
            val o = nf.filter(cal.apply(s), cal.noiseRms)
            assertEquals(0.0, o.mag, 1e-12)
        }
    }

    @Test
    fun interpretaUnEmpujonModeradoHorizontal() {
        val cal = calibrate()
        val det = EventDetector(cal.threshold(5.0))
        var m: Measurement? = null
        for (s in signal(1.0, 2.0)) det.add(cal.apply(s))?.let { m = it }
        val i = MeasurementInterpreter.interpret(m!!)
        assertEquals(Intensity.MODERATE, i.intensity)
        assertEquals(Direction.HORIZONTAL, i.direction)
        assertTrue(i.reliable)
    }
}
