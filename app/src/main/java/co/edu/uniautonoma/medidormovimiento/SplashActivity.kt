package co.edu.uniautonoma.medidormovimiento

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/** Pantalla de carga con la imagen de fondo; pasa sola a la bienvenida. */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val goNext = Runnable {
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
    }

    override fun onStart() {
        super.onStart()
        handler.postDelayed(goNext, 1800)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(goNext)
    }
}
