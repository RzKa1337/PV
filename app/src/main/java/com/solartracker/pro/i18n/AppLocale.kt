package com.solartracker.pro.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.solartracker.pro.data.AppLanguage
import com.solartracker.pro.ui.Format
import java.util.Locale

/**
 * Per-app UI language without AppCompat: the chosen language is applied by wrapping the Activity's base
 * context (and the widget's context) in a configuration with that locale.
 *
 * The source of truth is [com.solartracker.pro.data.AppSettings.language] in DataStore. Because the
 * locale must be known synchronously in `attachBaseContext`, the last applied choice is mirrored here in
 * a tiny SharedPreferences file; MainActivity recreates itself whenever the stored setting differs.
 */
object AppLocale {
    private const val PREFS = "app_locale"
    private const val KEY = "language"

    /** Language currently used for texts produced outside Compose (view models, workers). */
    @Volatile
    var current: AppLanguage = AppLanguage.POLISH
        private set

    /** True when the effective UI language (after resolving [AppLanguage.SYSTEM]) is English. */
    @Volatile
    var isEnglish: Boolean = false
        private set

    fun stored(context: Context): AppLanguage = runCatching {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        AppLanguage.entries.firstOrNull { it.name == name }
    }.getOrNull() ?: AppLanguage.POLISH

    fun store(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.name).commit()
    }

    /** Resolves [language] against the device locale: the app ships Polish (default) and English texts. */
    fun resolve(language: AppLanguage, deviceLocale: Locale): Locale = when (language) {
        AppLanguage.POLISH -> POLISH
        AppLanguage.ENGLISH -> ENGLISH
        AppLanguage.SYSTEM -> if (deviceLocale.language == ENGLISH.language) ENGLISH else POLISH
    }

    /** Makes [language] the current one for formatting and non-Compose texts. */
    fun apply(language: AppLanguage, deviceLocale: Locale): Locale {
        val locale = resolve(language, deviceLocale)
        current = language
        isEnglish = locale.language == ENGLISH.language
        Format.locale = locale
        return locale
    }

    /** [base] with resources in the stored language (falls back to Polish). */
    fun wrap(base: Context, language: AppLanguage = stored(base)): Context {
        val deviceLocale = base.resources.configuration.locales[0] ?: Locale.getDefault()
        val locale = apply(language, deviceLocale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Picks the text for the current language; for strings built outside Compose (view models, workers). */
    fun pick(pl: String, en: String): String = if (isEnglish) en else pl

    val POLISH: Locale = Locale.forLanguageTag("pl-PL")
    val ENGLISH: Locale = Locale.forLanguageTag("en-GB")
}

/** Shorthand for [AppLocale.pick]. */
fun tr(pl: String, en: String): String = AppLocale.pick(pl, en)
