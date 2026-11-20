package co.edu.uniautonoma.medidormovimiento

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.ContextCompat
import co.edu.uniautonoma.medidormovimiento.databinding.ActivityHelpBinding

/** Guía detallada: cómo colocar el celular, cómo medir y qué significa cada dato. */
class HelpActivity : UpNavActivity() {

    private val binding by lazy { ActivityHelpBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.help_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        heading(R.string.help_h1)
        body(R.string.help_placement)

        heading(R.string.help_h2)
        body(R.string.help_steps)

        heading(R.string.help_h3)
        term(R.string.t_peak, R.string.help_peak)
        term(R.string.t_rms, R.string.help_rms)
        term(R.string.t_duration, R.string.help_duration)
        term(R.string.t_axis, R.string.help_axis)
        term(R.string.t_disp, R.string.help_disp)
        term(R.string.t_noise, R.string.help_noise)
        term(R.string.t_k, R.string.help_k)
        term(R.string.t_filter, R.string.help_filter)
        term(R.string.t_window, R.string.help_window)

        heading(R.string.help_h4)
        body(R.string.help_interpret)

        heading(R.string.help_h5)
        body(R.string.help_problems)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Agrega un texto a la guía con el color, tamaño y espacios indicados. */
    private fun addText(res: Int, colorRes: Int, sp: Float, bold: Boolean = false, top: Int = 0, bottom: Int = 0, spacing: Boolean = false) {
        binding.helpContent.addView(TextView(this).apply {
            text = getString(res)
            setTextColor(ContextCompat.getColor(context, colorRes))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (spacing) setLineSpacing(0f, 1.15f)
            setPadding(0, dp(top), 0, dp(bottom))
        })
    }

    private fun heading(res: Int) = addText(res, R.color.title, 20f, bold = true, top = 22, bottom = 6)
    private fun body(res: Int) = addText(res, R.color.ink, 15f, spacing = true)

    private fun term(nameRes: Int, textRes: Int) {
        addText(nameRes, R.color.ink, 16f, bold = true, top = 12, bottom = 2)
        body(textRes)
    }
}
