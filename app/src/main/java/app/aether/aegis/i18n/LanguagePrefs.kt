package app.aether.aegis.i18n

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Tracks the user's explicit language choice and applies it via
 * AppCompatDelegate.setApplicationLocales, which:
 *   - On Android 13+ delegates to the platform LocaleManager so the
 *     choice survives reboots, shows up in Settings → System →
 *     Languages → Aegis, and is reflected in the system status bar.
 *   - On older Android (our minSdk 29 floor → 10/11/12) AppCompat
 *     applies it via a per-Activity configuration overlay so
 *     stringResource() lookups switch immediately.
 *
 * `picked` is the first-run gate. False = the user has never made an
 * explicit choice; the splash routes them to the LanguagePickerScreen
 * before onboarding. Becomes true the first time setLocale() is called.
 *
 * `tag` is the BCP-47 language tag they chose (or "system" if they
 * explicitly picked "Follow device language"). Empty = never picked.
 */
class LanguagePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    var picked: Boolean
        get() = prefs.getBoolean(KEY_PICKED, false)
        private set(v) { prefs.edit().putBoolean(KEY_PICKED, v).apply() }

    var tag: String
        get() = prefs.getString(KEY_TAG, "").orEmpty()
        private set(v) { prefs.edit().putString(KEY_TAG, v).apply() }

    /** Apply [tag] (BCP-47 like "de", "zh-CN", or "system" for the
     *  device default). Persists the choice and flips [picked] on. */
    fun setLocale(tag: String) {
        this.tag = tag
        this.picked = true
        val list = if (tag == "system" || tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
                   else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(list)
    }

    /** Restore the persisted choice on cold start. Called from
     *  AegisApp.onCreate so the FIRST composition already reflects
     *  the user's language without a flash of English. */
    fun applyOnBoot() {
        if (!picked) return
        val list = if (tag == "system" || tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
                   else LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(list)
    }

    companion object {
        private const val STORE = "aegis_language"
        private const val KEY_PICKED = "picked"
        private const val KEY_TAG = "tag"

        /** Supported languages — must match res/xml/locales_config.xml.
         *  First entry is the "follow system" sentinel. */
        val supported: List<LanguageOption> = listOf(
            LanguageOption("system", "Follow device language", "Auto"),
            LanguageOption("en", "English", "English"),
            LanguageOption("ar", "العربية", "Arabic"),
            LanguageOption("de", "Deutsch", "German"),
            LanguageOption("es", "Español", "Spanish"),
            LanguageOption("fr", "Français", "French"),
            LanguageOption("hi", "हिन्दी", "Hindi"),
            LanguageOption("it", "Italiano", "Italian"),
            LanguageOption("ja", "日本語", "Japanese"),
            LanguageOption("ko", "한국어", "Korean"),
            LanguageOption("nl", "Nederlands", "Dutch"),
            LanguageOption("pl", "Polski", "Polish"),
            LanguageOption("pt", "Português", "Portuguese"),
            LanguageOption("ru", "Русский", "Russian"),
            LanguageOption("sw", "Kiswahili", "Swahili"),
            LanguageOption("tr", "Türkçe", "Turkish"),
            LanguageOption("uk", "Українська", "Ukrainian"),
            LanguageOption("zh-CN", "中文", "Chinese"),
        )
    }
}

data class LanguageOption(
    val tag: String,
    /** Endonym — what speakers of the language call it. */
    val native: String,
    /** English name, shown as subtitle for disambiguation. */
    val english: String,
)
