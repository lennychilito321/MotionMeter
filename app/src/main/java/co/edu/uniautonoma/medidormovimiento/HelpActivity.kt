package co.edu.uniautonoma.medidormovimiento

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Guía detallada: cómo colocar el celular, cómo medir y qué significa cada dato. */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        title = getString(R.string.help_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val box = findViewById<LinearLayout>(R.id.helpContent)

        heading(box, R.string.help_h1)
        body(box, getString(R.string.help_placement))

        heading(box, R.string.help_h2)
        body(box, getString(R.string.help_steps))

        heading(box, R.string.help_h3)
        term(box, R.string.t_peak, R.string.help_peak)
        term(box, R.string.t_rms, R.string.help_rms)
        term(box, R.string.t_duration, R.string.help_duration)
        term(box, R.string.t_axis, R.string.help_axis)
        term(box, R.string.t_disp, R.string.help_disp)
        term(box, R.string.t_noise, R.string.help_noise)
        term(box, R.string.t_k, R.string.help_k)
        term(box, R.string.t_filter, R.string.help_filter)
        term(box, R.string.t_window, R.string.help_window)

        heading(box, R.string.help_h4)
        body(box, getString(R.string.help_interpret))

        heading(box, R.string.help_h5)
        body(box, getString(R.string.help_problems))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun heading(box: LinearLayout, res: Int) {
        box.addView(TextView(this).apply {
            text = getString(res)
            setTextColor(ContextCompat.getColor(context, R.color.title))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(22), 0, dp(6))
        })
    }

    private fun body(box: LinearLayout, text: String) {
        box.addView(TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
        })
    }

    private fun term(box: LinearLayout, nameRes: Int, textRes: Int) {
        box.addView(TextView(this).apply {
            text = getString(nameRes)
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(2))
        })
        body(box, getString(textRes))
    }
}
