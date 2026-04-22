package com.yancotv.android.prefs

import com.yancotv.shared.db.YancoDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * App-wide user preferences — backed by the shared [settings] table so
 * all reads go through the same key-value store as other persisted
 * state (EPG timestamps, global EPG URL, parental flags).
 *
 * Exposes reactive [StateFlow]s per preference group so the Settings
 * tabs can `collectAsState` and every consumer (PlaybackController,
 * KtorHttpClient) can subscribe without polling the DB.
 *
 * Reads are synchronous (SQLDelight is fast on flags), writes go
 * through an IO dispatcher so the Settings toggles don't block the
 * main thread while the Switch animates.
 */
class AppPreferences(
    private val db: YancoDb,
) {
    private val _playback = MutableStateFlow(readPlayback())
    val playbackFlow: StateFlow<PlaybackPrefs> = _playback.asStateFlow()

    private val _network = MutableStateFlow(readNetwork())
    val networkFlow: StateFlow<NetworkPrefs> = _network.asStateFlow()

    private val _general = MutableStateFlow(readGeneral())
    val generalFlow: StateFlow<GeneralPrefs> = _general.asStateFlow()

    // Synchronous snapshot for bootstrapping — MainActivity/HomeScreen need
    // the "open app on" value on first composition before any flow has had
    // a chance to emit. Reads the settings table directly.
    fun generalSnapshot(): GeneralPrefs = readGeneral()

    // ───── Playback ─────

    suspend fun setResizeMode(mode: ResizeMode) = write(KEY_RESIZE, mode.key) {
        _playback.value = _playback.value.copy(resizeMode = mode)
    }

    suspend fun setAutoPlayNext(enabled: Boolean) = write(KEY_AUTOPLAY, if (enabled) "1" else "0") {
        _playback.value = _playback.value.copy(autoPlayNext = enabled)
    }

    suspend fun setAudioLanguage(lang: String) = write(KEY_AUDIO_LANG, lang) {
        _playback.value = _playback.value.copy(audioLanguage = lang)
    }

    suspend fun setSubtitleLanguage(lang: String) = write(KEY_SUBTITLE_LANG, lang) {
        _playback.value = _playback.value.copy(subtitleLanguage = lang)
    }

    // ───── Network ─────

    suspend fun setUserAgent(ua: String) = write(KEY_USER_AGENT, ua) {
        _network.value = _network.value.copy(userAgentOverride = ua.takeIf { it.isNotBlank() })
    }

    suspend fun setConnectTimeout(sec: Int) = write(KEY_CONNECT_TIMEOUT, sec.toString()) {
        _network.value = _network.value.copy(connectTimeoutSec = sec)
    }

    suspend fun setReadTimeout(sec: Int) = write(KEY_READ_TIMEOUT, sec.toString()) {
        _network.value = _network.value.copy(readTimeoutSec = sec)
    }

    // ───── General ─────

    suspend fun setOpenOn(section: OpenOn) = write(KEY_OPEN_ON, section.key) {
        _general.value = _general.value.copy(openOn = section)
    }

    suspend fun setShowChannelNumbers(enabled: Boolean) = write(KEY_SHOW_NUMBERS, if (enabled) "1" else "0") {
        _general.value = _general.value.copy(showChannelNumbers = enabled)
    }

    // ───── internals ─────

    private fun readPlayback(): PlaybackPrefs = PlaybackPrefs(
        resizeMode = ResizeMode.fromKey(readString(KEY_RESIZE)),
        autoPlayNext = readString(KEY_AUTOPLAY) == "1",
        audioLanguage = readString(KEY_AUDIO_LANG).orEmpty(),
        subtitleLanguage = readString(KEY_SUBTITLE_LANG).orEmpty(),
    )

    private fun readNetwork(): NetworkPrefs = NetworkPrefs(
        userAgentOverride = readString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() },
        connectTimeoutSec = readString(KEY_CONNECT_TIMEOUT)?.toIntOrNull() ?: DEFAULT_CONNECT_TIMEOUT,
        readTimeoutSec = readString(KEY_READ_TIMEOUT)?.toIntOrNull() ?: DEFAULT_READ_TIMEOUT,
    )

    private fun readGeneral(): GeneralPrefs = GeneralPrefs(
        openOn = OpenOn.fromKey(readString(KEY_OPEN_ON)),
        showChannelNumbers = readString(KEY_SHOW_NUMBERS) == "1",
    )

    private fun readString(key: String): String? =
        db.settingsQueries.get(key).executeAsOneOrNull()

    private suspend inline fun write(key: String, value: String, crossinline refresh: () -> Unit) {
        withContext(Dispatchers.IO) {
            if (value.isBlank()) db.settingsQueries.delete(key)
            else db.settingsQueries.upsert(key, value)
        }
        refresh()
    }

    companion object {
        // Defaults chosen conservatively — match what KtorHttpClientFactory
        // uses today so a fresh install has parity with the pre-prefs build.
        const val DEFAULT_CONNECT_TIMEOUT = 15
        const val DEFAULT_READ_TIMEOUT = 90

        private const val KEY_RESIZE = "pref_playback_resize"
        private const val KEY_AUTOPLAY = "pref_playback_autoplay_next"
        private const val KEY_AUDIO_LANG = "pref_playback_audio_lang"
        private const val KEY_SUBTITLE_LANG = "pref_playback_subtitle_lang"
        private const val KEY_USER_AGENT = "pref_network_user_agent"
        private const val KEY_CONNECT_TIMEOUT = "pref_network_connect_timeout_sec"
        private const val KEY_READ_TIMEOUT = "pref_network_read_timeout_sec"
        private const val KEY_OPEN_ON = "pref_general_open_on"
        private const val KEY_SHOW_NUMBERS = "pref_general_show_channel_numbers"
    }
}

/** Default section to land on when the app opens. TiviMate defaults to last-used. */
enum class OpenOn(val key: String, val displayName: String) {
    HOME("home", "Home"),
    LIVE_TV("live_tv", "Live TV"),
    LAST_USED("last_used", "Last used");

    companion object {
        fun fromKey(key: String?): OpenOn =
            values().firstOrNull { it.key == key } ?: HOME
    }
}

data class GeneralPrefs(
    val openOn: OpenOn = OpenOn.HOME,
    val showChannelNumbers: Boolean = false,
)

enum class ResizeMode(val key: String, val displayName: String) {
    FIT("fit", "Fit"),
    FILL("fill", "Fill"),
    ZOOM("zoom", "Zoom");

    companion object {
        fun fromKey(key: String?): ResizeMode =
            values().firstOrNull { it.key == key } ?: FIT
    }
}

data class PlaybackPrefs(
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val autoPlayNext: Boolean = false,
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
)

data class NetworkPrefs(
    val userAgentOverride: String? = null,
    val connectTimeoutSec: Int = AppPreferences.DEFAULT_CONNECT_TIMEOUT,
    val readTimeoutSec: Int = AppPreferences.DEFAULT_READ_TIMEOUT,
)
