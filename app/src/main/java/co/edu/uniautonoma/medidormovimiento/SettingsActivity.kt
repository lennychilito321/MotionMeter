package co.edu.uniautonoma.medidormovimiento

import android.os.Bundle
import co.edu.uniautonoma.medidormovimiento.databinding.ActivitySettingsBinding

/** Configuración: apariencia (claro/oscuro/sistema) e idioma (español/inglés/sistema). */
class SettingsActivity : UpNavActivity() {

    private val binding by lazy { ActivitySettingsBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        with(binding) {
            groupTheme.check(
                when (AppSettings.theme(this@SettingsActivity)) {
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
                    this@SettingsActivity,
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
    }
}
