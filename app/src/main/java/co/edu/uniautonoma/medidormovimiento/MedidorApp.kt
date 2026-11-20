package co.edu.uniautonoma.medidormovimiento

import android.app.Application

class MedidorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.applyTheme(this)
    }
}
