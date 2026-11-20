package co.edu.uniautonoma.medidormovimiento

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guarda las mediciones en la memoria interna de la app:
 *   files/mediciones/resumen.csv          -> una fila por medición (variables calculadas)
 *   files/mediciones/medicion_*.csv       -> lecturas completas de cada medición
 */
class MeasurementStore(private val context: Context) {

    val dir: File = File(context.filesDir, "mediciones").apply { mkdirs() }
    val summaryFile: File get() = File(dir, "resumen.csv")

    data class Entry(
        val date: String,
        val fileName: String,
        val label: String,
        val peak: String,
        val rms: String,
        val duration: String,
        val axis: String,
        val dMax: String,
    )

    /** Si resumen.csv viene de una versión anterior (menos columnas), completa sus filas para que cuadren. */
    private fun ensureSummaryHeader() {
        val current = CsvFormat.SUMMARY_HEADER
        val lines = if (summaryFile.exists()) summaryFile.readLines().filter { it.isNotBlank() } else emptyList()
        if (lines.isEmpty()) {
            summaryFile.writeText(current.joinToString(",") + "\n")
            return
        }
        val old = lines.first().split(",")
        val missing = current.size - old.size
        if (missing > 0 && old == current.take(old.size)) {
            val pad = ",".repeat(missing)
            summaryFile.writeText(
                current.joinToString(",") + "\n" + lines.drop(1).joinToString("") { it + pad + "\n" },
            )
        }
    }

    fun save(
        m: Measurement,
        cal: Calibration,
        label: String,
        sensorType: String,
        filterNoise: Boolean,
    ): File {
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)
        val file = File(dir, "medicion_$stamp.csv")
        file.writeText(CsvFormat.samplesCsv(m))

        ensureSummaryHeader()
        summaryFile.appendText(CsvFormat.summaryRow(date, file.name, label, sensorType, cal, m, filterNoise) + "\n")
        return file
    }

    /** Lee resumen.csv, la medición más reciente primero. */
    fun list(): List<Entry> {
        if (!summaryFile.exists()) return emptyList()
        val lines = summaryFile.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = lines.first().split(",")
        fun idx(name: String) = header.indexOf(name)
        val iDate = idx("fecha"); val iFile = idx("archivo"); val iLabel = idx("etiqueta")
        val iPeak = idx("pico_ms2"); val iRms = idx("rms_ms2"); val iDur = idx("duracion_s")
        val iAxis = idx("eje_dominante"); val iD = idx("d_max_m")
        return lines.drop(1).mapNotNull { line ->
            val c = line.split(",")
            if (c.size < header.size) null
            else Entry(c[iDate], c[iFile], c[iLabel], c[iPeak], c[iRms], c[iDur], c[iAxis], c[iD])
        }.reversed()
    }

    fun allFiles(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        reportsDir.listFiles()?.forEach { it.delete() }
    }

    /** Medición guardada, reconstruida desde resumen.csv y su CSV de lecturas. */
    data class SavedMeasurement(
        val fileName: String,
        val label: String,
        val date: String,
        val sensor: String,
        val rateHz: Double,
        val noiseRms: Double,
        /** true/false según lo anotado en el resumen; null si la medición es de una versión anterior. */
        val filtered: Boolean?,
        val m: Measurement,
    )

    /** Carpeta de los informes PDF generados al exportar. */
    val reportsDir: File = File(context.filesDir, "informes").apply { mkdirs() }

    /** Reconstruye una medición guardada para generar su informe. Devuelve null si falta algún archivo. */
    fun load(fileName: String): SavedMeasurement? = try {
        loadOrThrow(fileName)
    } catch (e: Exception) {
        null
    }

    private fun loadOrThrow(fileName: String): SavedMeasurement? {
        if (!summaryFile.exists()) return null
        val lines = summaryFile.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return null
        val h = lines.first().split(",")
        val iFile = h.indexOf("archivo")
        val row = lines.drop(1).map { it.split(",") }
            .lastOrNull { it.size >= h.size && it[iFile] == fileName } ?: return null
        fun s(name: String): String = h.indexOf(name).let { if (it >= 0) row[it] else "" }
        fun d(name: String): Double = s(name).toDoubleOrNull() ?: 0.0

        val csv = File(dir, fileName)
        if (!csv.exists()) return null
        val samples = ArrayList<Sample>()
        val kin = ArrayList<Kinematics>()
        for (line in csv.readLines().drop(1)) {
            val c = line.split(",")
            if (c.size < 11) continue
            val t = (c[0].toDouble() * 1e9).toLong()
            samples.add(Sample(t, c[1].toDouble(), c[2].toDouble(), c[3].toDouble()))
            kin.add(
                Kinematics(
                    t, c[5].toDouble(), c[6].toDouble(), c[7].toDouble(),
                    c[8].toDouble(), c[9].toDouble(), c[10].toDouble(),
                ),
            )
        }
        if (samples.isEmpty()) return null

        val durationS = d("duracion_s")
        val window = s("ventana_s").toDoubleOrNull() // null = automática
        val m = Measurement(
            samples = samples,
            kinematics = kin,
            startNs = 0L,
            endNs = (durationS * 1e9).toLong(),
            threshold = d("umbral_ms2"),
            peak = d("pico_ms2"),
            peakTimeS = d("t_pico_s"),
            rms = d("rms_ms2"),
            durationS = durationS,
            rmsX = d("rms_x_ms2"), rmsY = d("rms_y_ms2"), rmsZ = d("rms_z_ms2"),
            peakX = d("pico_x_ms2"), peakY = d("pico_y_ms2"), peakZ = d("pico_z_ms2"),
            dominantAxis = s("eje_dominante").firstOrNull() ?: 'x',
            maxVelocity = d("v_max_ms"),
            maxDisplacement = d("d_max_m"),
            finalDisplacement = d("d_final_m"),
            truncated = s("truncada") == "si",
            windowS = window ?: durationS,
            fixedWindow = window != null,
        )
        val filtered = when (s("filtro_ruido")) {
            "si" -> true
            "no" -> false
            else -> null
        }
        return SavedMeasurement(
            fileName, s("etiqueta"), s("fecha"), s("sensor"),
            d("frecuencia_hz"), d("ruido_ms2"), filtered, m,
        )
    }

    private fun uri(file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    /** Comparte el informe PDF junto con los archivos CSV indicados. */
    fun shareReport(pdf: File, csvFiles: List<File>, chooserTitle: String): Intent {
        val files = listOf(pdf) + csvFiles
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(files.map { uri(it) }))
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle)
    }
}
