package co.edu.uniautonoma.medidormovimiento

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import co.edu.uniautonoma.medidormovimiento.databinding.ActivityHistoryBinding
import java.io.File

class HistoryActivity : UpNavActivity() {

    private val binding by lazy { ActivityHistoryBinding.inflate(layoutInflater) }
    private lateinit var store: MeasurementStore
    private var entries: List<MeasurementStore.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        store = MeasurementStore(this)

        // Al exportar se envía un informe PDF (datos técnicos, interpretación y gráficas) junto con los CSV.
        binding.list.setOnItemClickListener { _, _, position, _ ->
            val fileName = entries[position].fileName
            val csv = File(store.dir, fileName)
            if (!csv.exists()) {
                toast(getString(R.string.file_missing, csv.name))
                return@setOnItemClickListener
            }
            buildAndShare {
                val sm = store.load(fileName) ?: return@buildAndShare null
                val pdf = File(store.reportsDir, fileName.removeSuffix(".csv").replace("medicion_", "informe_") + ".pdf")
                ReportPdf(this).buildSingle(sm, pdf)
                store.shareReport(pdf, listOf(csv), getString(R.string.export_one, pdf.name))
            }
        }

        binding.btnExportAll.setOnClickListener {
            if (entries.isEmpty()) {
                toast(getString(R.string.hist_none))
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

        binding.btnDeleteAll.setOnClickListener {
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
    private fun buildAndShare(build: () -> Intent?) {
        toast(getString(R.string.report_generating))
        Thread {
            val intent = try {
                build()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (intent == null) toast(getString(R.string.report_error), long = true) else startActivity(intent)
            }
        }.start()
    }

    private fun load() {
        entries = store.list()
        binding.txtCount.text =
            if (entries.isEmpty()) getString(R.string.hist_none) else getString(R.string.hist_count, entries.size)
        val rows = entries.map { getString(R.string.hist_row, it.label, it.date, it.peak, it.rms, it.duration, it.axis) }
        binding.list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
