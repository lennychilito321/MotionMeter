package co.edu.uniautonoma.medidormovimiento

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

/** Configuración: apariencia (claro/oscuro/sistema) e idioma (español/inglés/sistema). */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val groupTheme = findViewById<RadioGroup>(R.id.groupTheme)
        val groupLang = findViewById<RadioGroup>(R.id.groupLang)

        groupTheme.check(
            when (AppSettings.theme(this)) {
                AppSettings.THEME_LIGHT -> R.id.themeLight
                AppSettings.THEME_DARK -> R.id.themeDark
                else -> R.id.themeSystem
            },
        )
        groupLang.check(
            when (AppSettings.language()) {
                AppSettings.LANG_ES -> R.id.langEs
                AppSettings.LANG_EN -> R.id.langEn
                else -> R.id.langSystem
            },
        )

        // Los listeners se ponen después de marcar el estado inicial. Al cambiar tema o idioma,
        // Android recrea la pantalla y ya aparece con la nueva configuración.
        groupTheme.setOnCheckedChangeListener { _, id ->
            AppSettings.setTheme(
                this,
                when (id) {
                    R.id.themeLight -> AppSettings.THEME_LIGHT
                    R.id.themeDark -> AppSettings.THEME_DARK
                    else -> AppSettings.THEME_SYSTEM
                },
            )
        }
        groupLang.setOnCheckedChangeListener { _, id ->
            AppSettings.setLanguage(
                when (id) {
                    R.id.langEs -> AppSettings.LANG_ES
                    R.id.langEn -> AppSettings.LANG_EN
                    else -> AppSettings.LANG_SYSTEM
                },
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
