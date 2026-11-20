package co.edu.uniautonoma.medidormovimiento

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

/** Pantalla de inicio: resume para qué sirve la app y lleva a la guía, a medir o a la configuración. */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnHowTo).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}
