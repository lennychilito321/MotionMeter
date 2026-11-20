package co.edu.uniautonoma.medidormovimiento

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Preferencias de apariencia e idioma.
 * - Tema: se guarda aquí y se aplica al iniciar la app (MedidorApp).
 * - Idioma: lo guarda AppCompat (AppLocalesMetadataHolderService en el manifiesto).
 */
object AppSettings {
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val LANG_SYSTEM = ""
    const val LANG_ES = "es"
    const val LANG_EN = "en"

    private const val PREFS = "settings"
    private const val KEY_THEME = "theme"

    fun theme(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(context)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    fun setTheme(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, value).apply()
        applyTheme(context)
    }

    /** Duraciones elegibles en la pantalla de medición: 0 = automática, el resto en segundos. */
    val WINDOW_OPTIONS = intArrayOf(0, 1, 2, 3, 5, 10, 15, 20, 30, 45, 60)

    fun filterNoise(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("filter_noise", true)

    fun setFilterNoise(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("filter_noise", value).apply()
    }

    fun windowIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("window_idx", 0)
            .coerceIn(0, WINDOW_OPTIONS.size - 1)

    fun setWindowIndex(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("window_idx", value).apply()
    }

    /** "" = según el sistema, "es" o "en". */
    fun language(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return if (locales.isEmpty) LANG_SYSTEM else locales[0]?.language ?: LANG_SYSTEM
    }

    fun setLanguage(value: String) {
        AppCompatDelegate.setApplicationLocales(
            if (value.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(value),
        )
    }
}
