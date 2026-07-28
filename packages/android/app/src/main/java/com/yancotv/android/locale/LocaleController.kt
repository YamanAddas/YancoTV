package com.yancotv.android.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MK.31.1 — in-app language selection.
 *
 * ### Why this isn't just `AppCompatDelegate.setApplicationLocales`
 *
 * That call is the documented answer, and it is used here — but on its own it
 * does not work for this app. Its pre-API-33 backport applies the locale
 * through `AppCompatDelegate`, which only exists on AppCompat components.
 * [com.yancotv.android.MainActivity] is a `ComponentActivity`, and its theme
 * descends from `android:Theme.Material.NoActionBar` — making it an
 * `AppCompatActivity` would force a Theme.AppCompat migration across the whole
 * shell for an unrelated reason. Fire TV is API 28, i.e. exactly the case the
 * backport was meant to cover, so this is not a corner.
 *
 * So the locale is applied directly, via [wrap] from `attachBaseContext`. That
 * works with any Activity base class on any API level. The
 * `setApplicationLocales` call is kept alongside it so AppCompat-based screens
 * ([com.yancotv.android.player.PlayerActivity]) and, on API 33+, the system
 * per-app-language setting stay in agreement with our own state.
 *
 * ### Why SharedPreferences and not the settings table
 *
 * Every other preference lives in SQLDelight via
 * [com.yancotv.android.prefs.AppPreferences]. This one can't:
 * `attachBaseContext` runs before an Activity is usable and can precede a
 * ready Koin graph, so the read has to work with nothing but a Context. A
 * one-key SharedPreferences file has no initialisation order to get wrong.
 */
object LocaleController {
    private const val PREFS_NAME = "yanco_locale"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    private val _language = MutableStateFlow(AppLanguage.System)

    /** The selected language. Emits on [setLanguage] so UI can reflect it. */
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /**
     * Reads the persisted choice into [language] and pushes it to AppCompat.
     * Call from `Application.onCreate` before any Activity is created.
     */
    fun initialize(context: Context) {
        val stored = read(context)
        _language.value = stored
        applyToDelegates(context, stored)
    }

    /** The stored choice, read straight from disk. Safe at any point. */
    fun read(context: Context): AppLanguage = AppLanguage.of(
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, null),
    )

    /**
     * Persists [language] and applies it. Callers must then recreate the
     * visible Activity — a running Activity already resolved its resources,
     * so nothing on screen changes until it is rebuilt. Persisting before
     * applying means the recreated Activity's `attachBaseContext` reads the
     * new value.
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, language.tag)
            .commit() // commit, not apply: the recreate below must not race the write.
        _language.value = language
        applyToDelegates(context, language)
    }

    /**
     * Wraps [base] in a Context whose resources resolve in the selected
     * language. Call from every `Activity.attachBaseContext` — an Activity
     * that skips this keeps the device language while the rest of the app
     * switches.
     *
     * Returns [base] untouched for [AppLanguage.System] so platform locale
     * resolution (including the user's full preferred-language list, not just
     * their first choice) is left alone.
     */
    fun wrap(base: Context): Context {
        val locale = read(base).locale ?: return base
        val config = Configuration(base.resources.configuration)
        // setLocales, not setLocale: it also updates the configuration's
        // layout direction, which is what makes Arabic actually mirror.
        // Setting only `locale` leaves an LTR layoutDirection behind.
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * Whether [language] should render right-to-left. Kept here rather than
     * read off the live Configuration so callers can ask before a recreate.
     */
    fun isRtl(language: AppLanguage): Boolean = language.rtl

    private fun applyToDelegates(context: Context, language: AppLanguage) {
        val tags = language.tag
        // AppCompat screens (PlayerActivity) and its own persistence.
        AppCompatDelegate.setApplicationLocales(
            if (tags.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tags),
        )
        // API 33+ — mirror into the platform so the app's entry in system
        // Settings -> App languages shows the same choice the in-app picker
        // does. Guarded because the service is absent below Tiramisu.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList(Locale.forLanguageTag(tags))
            }
        }
    }
}
