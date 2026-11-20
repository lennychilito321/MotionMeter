package co.edu.uniautonoma.medidormovimiento

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import co.edu.uniautonoma.medidormovimiento.databinding.ActivityWelcomeBinding

/** Pantalla de inicio: resume para qué sirve la app y lleva a la guía, a medir o a la configuración. */
class WelcomeActivity : AppCompatActivity() {

    private val binding by lazy { ActivityWelcomeBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.btnSettings.setOnClickListener { openScreen(SettingsActivity::class.java) }
        binding.btnHowTo.setOnClickListener { openScreen(HelpActivity::class.java) }
        binding.btnStart.setOnClickListener { openScreen(MainActivity::class.java) }
    }

    private fun openScreen(cls: Class<*>) = startActivity(Intent(this, cls))
}
