package co.edu.uniautonoma.medidormovimiento

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class HistoryActivity : AppCompatActivity() {

    private lateinit var store: MeasurementStore
    private lateinit var list: ListView
    private lateinit var txtCount: TextView
    private var entries: List<MeasurementStore.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = MeasurementStore(this)
        list = findViewById(R.id.list)
        txtCount = findViewById(R.id.txtCount)

        // Al exportar se envía un informe PDF (datos técnicos, interpretación y gráficas) junto con los CSV.
        list.setOnItemClickListener { _, _, position, _ ->
            val fileName = entries[position].fileName
            val csv = File(store.dir, fileName)
            if (!csv.exists()) {
                Toast.makeText(this, getString(R.string.file_missing, csv.name), Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }
            buildAndShare {
                val sm = store.load(fileName) ?: return@buildAndShare null
                val pdf = File(store.reportsDir, fileName.removeSuffix(".csv").replace("medicion_", "informe_") + ".pdf")
                ReportPdf(this).buildSingle(sm, pdf)
                store.shareReport(pdf, listOf(csv), getString(R.string.export_one, pdf.name))
            }
        }

        findViewById<Button>(R.id.btnExportAll).setOnClickListener {
            if (entries.isEmpty()) {
                Toast.makeText(this, getString(R.string.hist_none), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val names = entries.map { it.fileName }.reversed() // de la más antigua a la más reciente
            buildAndShare {
                val all = names.mapNotNull { store.load(it) }
                if (all.isEmpty()) return@buildAndShare null
                val pdf = File(store.reportsDir, "informe_mediciones.pdf")
                ReportPdf(this).buildAll(all, pdf)
                store.shareReport(pdf, store.allFiles(), getString(R.string.export_all))
            }
        }

        findViewById<Button>(R.id.btnDeleteAll).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_msg)
                .setPositiveButton(R.string.delete_confirm) { _, _ ->
                    store.deleteAll()
                    load()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        load()
    }

    /** Genera el PDF en un hilo aparte (puede tardar unos segundos) y abre el selector de apps para enviarlo. */
    private fun buildAndShare(build: () -> android.content.Intent?) {
        Toast.makeText(this, getString(R.string.report_generating), Toast.LENGTH_SHORT).show()
        Thread {
            val intent = try {
                build()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (intent == null) {
                    Toast.makeText(this, getString(R.string.report_error), Toast.LENGTH_LONG).show()
                } else {
                    startActivity(intent)
                }
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        entries = store.list()
        txtCount.text = if (entries.isEmpty()) getString(R.string.hist_none)
        else getString(R.string.hist_count, entries.size)
        val rows = entries.map {
            getString(R.string.hist_row, it.label, it.date, it.peak, it.rms, it.duration, it.axis)
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
