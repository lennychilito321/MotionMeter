package co.edu.uniautonoma.medidormovimiento

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Muestra un mensaje breve (o largo si [long] es true). */
fun Context.toast(msg: String, long: Boolean = false) =
    Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** Pantalla con flecha «atrás» en la barra superior: volver cierra la pantalla. */
abstract class UpNavActivity : AppCompatActivity() {
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
