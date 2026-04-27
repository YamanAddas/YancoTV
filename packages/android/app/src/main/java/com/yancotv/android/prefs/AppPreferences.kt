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

    private val _hiddenGroups = MutableStateFlow(readHiddenGroups())
    val hiddenGroupsFlow: StateFlow<Set<String>> = _hiddenGroups.asStateFlow()

    private val _recording = MutableStateFlow(readRecording())
    val recordingFlow: StateFlow<RecordingPrefs> = _recording.asStateFlow()

    // MK.15.1 / 15.2 — EPG display window. `daysBack` / `daysForward`
    // bound the timeline range (catch-up + upcoming). `timelineMinutes`
    // is the visible-at-once strip width in the guide grid; the user
    // scrolls horizontally inside the larger range.
    private val _epg = MutableStateFlow(readEpg())
    val epgFlow: StateFlow<EpgPrefs> = _epg.asStateFlow()

    // Synchronous snapshot for bootstrapping — MainActivity/HomeScreen need
    // the "open app on" value on first composition before any flow has had
    // a chance to emit. Reads the settings table directly.
    fun generalSnapshot(): GeneralPrefs = readGeneral()

    // ───── Playback ─────

    suspend fun setResizeMode(mode: ResizeMode) =
        write(KEY_RESIZE, mode.key) {
            _playback.value = _playback.value.copy(resizeMode = mode)
        }

    suspend fun setAutoPlayNext(enabled: Boolean) =
        write(KEY_AUTOPLAY, if (enabled) "1" else "0") {
            _playback.value = _playback.value.copy(autoPlayNext = enabled)
        }

    suspend fun setAudioLanguage(lang: String) =
        write(KEY_AUDIO_LANG, lang) {
            _playback.value = _playback.value.copy(audioLanguage = lang)
        }

    suspend fun setSubtitleLanguage(lang: String) =
        write(KEY_SUBTITLE_LANG, lang) {
            _playback.value = _playback.value.copy(subtitleLanguage = lang)
        }

    // MK.12a.4 — Playback speed. Persisted as a plain float ("1.25") so a
    // future schema audit can read it in place. Only applied automatically
    // to VOD; live channels always reset to 1.0× at loadCurrent() time (the
    // user can still bump speed live, but zapping channels clears it).
    suspend fun setSpeed(speed: Float) =
        write(KEY_SPEED, speed.toString()) {
            _playback.value = _playback.value.copy(speed = speed)
        }

    // MK.17.3 — decoder fallback toggle. Stored as "1" / "0".
    suspend fun setDecoderFallback(enabled: Boolean) =
        write(KEY_DECODER_FALLBACK, if (enabled) "1" else "0") {
            _playback.value = _playback.value.copy(enableDecoderFallback = enabled)
        }

    // MK.17.4 — buffer profile preset.
    suspend fun setBufferProfile(profile: BufferProfile) =
        write(KEY_BUFFER_PROFILE, profile.key) {
            _playback.value = _playback.value.copy(bufferProfile = profile)
        }

    // ───── Network ─────

    suspend fun setUserAgent(ua: String) =
        write(KEY_USER_AGENT, ua) {
            _network.value = _network.value.copy(userAgentOverride = ua.takeIf { it.isNotBlank() })
        }

    suspend fun setConnectTimeout(sec: Int) =
        write(KEY_CONNECT_TIMEOUT, sec.toString()) {
            _network.value = _network.value.copy(connectTimeoutSec = sec)
        }

    suspend fun setReadTimeout(sec: Int) =
        write(KEY_READ_TIMEOUT, sec.toString()) {
            _network.value = _network.value.copy(readTimeoutSec = sec)
        }

    // ───── General ─────

    suspend fun setOpenOn(section: OpenOn) =
        write(KEY_OPEN_ON, section.key) {
            _general.value = _general.value.copy(openOn = section)
        }

    suspend fun setShowChannelNumbers(enabled: Boolean) =
        write(KEY_SHOW_NUMBERS, if (enabled) "1" else "0") {
            _general.value = _general.value.copy(showChannelNumbers = enabled)
        }

    suspend fun setSmartGrouping(enabled: Boolean) =
        write(KEY_SMART_GROUPING, if (enabled) "1" else "0") {
            _general.value = _general.value.copy(smartGrouping = enabled)
        }

    // ───── Recording (Stage 3.1 / MK.14.2-storage, MK.14.X audit revision) ─────
    //
    // Two interlocking prefs:
    //
    //   - [RecordingPrefs.storageMode] decides which destination strategy
    //     resolves the file. Default for fresh installs is
    //     [RecordingStorageMode.PUBLIC_MEDIA_STORE] — recordings land in
    //     `/storage/emulated/0/Movies/YancoTV/` via MediaStore (API 29+) or
    //     direct File writes (API ≤28 with WRITE_EXTERNAL_STORAGE).
    //   - [RecordingPrefs.folderUri] is the opaque persistable URI for
    //     `CUSTOM_SAF` mode — typically a SAF tree URI from the system
    //     picker. Ignored for the other modes.
    //
    // Migration: pre-revision installs only stored `folderUri`. If
    // present, we infer `storageMode = CUSTOM_SAF`. Absent → default
    // mode wins on first read. See [readRecording].

    suspend fun setRecordingStorageMode(mode: RecordingStorageMode) =
        write(KEY_RECORDING_STORAGE_MODE, mode.key) {
            _recording.value = _recording.value.copy(storageMode = mode)
        }

    // ───── EPG (MK.15.1 / 15.2) ─────

    suspend fun setEpgDaysBack(days: Int) =
        write(KEY_EPG_DAYS_BACK, days.coerceIn(0, 14).toString()) {
            _epg.value = _epg.value.copy(daysBack = days.coerceIn(0, 14))
        }

    suspend fun setEpgDaysForward(days: Int) =
        write(KEY_EPG_DAYS_FORWARD, days.coerceIn(1, 14).toString()) {
            _epg.value = _epg.value.copy(daysForward = days.coerceIn(1, 14))
        }

    suspend fun setEpgTimelineMinutes(minutes: Int) =
        write(KEY_EPG_TIMELINE_MIN, minutes.toString()) {
            _epg.value = _epg.value.copy(timelineMinutes = minutes)
        }

    suspend fun setRecordingFolderUri(uri: String?) =
        write(KEY_RECORDING_FOLDER_URI, uri.orEmpty()) {
            _recording.value =
                _recording.value.copy(folderUri = uri?.takeIf { it.isNotBlank() })
        }

    // ───── Hidden groups ─────
    //
    // Providers routinely push 400+ category groups, most of which a
    // personal viewer never uses. The hidden set filters them out of the
    // sidebar + channel list without touching the underlying rows, so a
    // future "show all" recovers the user's world without a re-sync.
    // Persisted as newline-joined names because category names can contain
    // commas/pipes but practically never newlines.

    suspend fun setGroupHidden(
        name: String,
        hidden: Boolean,
    ) {
        val next =
            _hiddenGroups.value.toMutableSet().apply {
                if (hidden) add(name) else remove(name)
            }
        writeHiddenGroups(next)
    }

    suspend fun clearHiddenGroups() = writeHiddenGroups(emptySet())

    private suspend fun writeHiddenGroups(next: Set<String>) {
        val value = next.joinToString("\n")
        withContext(Dispatchers.IO) {
            if (next.isEmpty()) {
                db.settingsQueries.delete(KEY_HIDDEN_GROUPS)
            } else {
                db.settingsQueries.upsert(KEY_HIDDEN_GROUPS, value)
            }
        }
        _hiddenGroups.value = next
    }

    // ───── internals ─────

    private fun readPlayback(): PlaybackPrefs =
        PlaybackPrefs(
            resizeMode = ResizeMode.fromKey(readString(KEY_RESIZE)),
            autoPlayNext = readString(KEY_AUTOPLAY) == "1",
            audioLanguage = readString(KEY_AUDIO_LANG).orEmpty(),
            subtitleLanguage = readString(KEY_SUBTITLE_LANG).orEmpty(),
            speed = readString(KEY_SPEED)?.toFloatOrNull() ?: 1.0f,
            enableDecoderFallback = readString(KEY_DECODER_FALLBACK) != "0",
            bufferProfile = BufferProfile.fromKey(readString(KEY_BUFFER_PROFILE)),
        )

    private fun readNetwork(): NetworkPrefs =
        NetworkPrefs(
            userAgentOverride = readString(KEY_USER_AGENT)?.takeIf { it.isNotBlank() },
            connectTimeoutSec = readString(KEY_CONNECT_TIMEOUT)?.toIntOrNull() ?: DEFAULT_CONNECT_TIMEOUT,
            readTimeoutSec = readString(KEY_READ_TIMEOUT)?.toIntOrNull() ?: DEFAULT_READ_TIMEOUT,
        )

    private fun readGeneral(): GeneralPrefs =
        GeneralPrefs(
            openOn = OpenOn.fromKey(readString(KEY_OPEN_ON)),
            showChannelNumbers = readString(KEY_SHOW_NUMBERS) == "1",
            smartGrouping = readString(KEY_SMART_GROUPING) == "1",
        )

    private fun readEpg(): EpgPrefs =
        EpgPrefs(
            daysBack =
                readString(KEY_EPG_DAYS_BACK)?.toIntOrNull()?.coerceIn(0, 14)
                    ?: EpgPrefs.DEFAULT_DAYS_BACK,
            daysForward =
                readString(KEY_EPG_DAYS_FORWARD)?.toIntOrNull()?.coerceIn(1, 14)
                    ?: EpgPrefs.DEFAULT_DAYS_FORWARD,
            timelineMinutes =
                readString(KEY_EPG_TIMELINE_MIN)?.toIntOrNull()
                    ?: EpgPrefs.DEFAULT_TIMELINE_MIN,
        )

    private fun readRecording(): RecordingPrefs {
        val folderUri = readString(KEY_RECORDING_FOLDER_URI)?.takeIf { it.isNotBlank() }
        val explicitMode = readString(KEY_RECORDING_STORAGE_MODE)
        val mode =
            when {
                explicitMode != null -> RecordingStorageMode.fromKey(explicitMode)
                // Pre-revision install with a SAF folder picked → CUSTOM_SAF.
                folderUri != null -> RecordingStorageMode.CUSTOM_SAF
                // Fresh install: default to the audit-recommended public path.
                else -> RecordingStorageMode.PUBLIC_MEDIA_STORE
            }
        return RecordingPrefs(storageMode = mode, folderUri = folderUri)
    }

    private fun readHiddenGroups(): Set<String> =
        readString(KEY_HIDDEN_GROUPS)
            ?.split('\n')
            ?.mapNotNull { it.takeIf(String::isNotBlank) }
            ?.toSet()
            ?: emptySet()

    private fun readString(key: String): String? = db.settingsQueries.get(key).executeAsOneOrNull()

    private suspend inline fun write(
        key: String,
        value: String,
        crossinline refresh: () -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            if (value.isBlank()) {
                db.settingsQueries.delete(key)
            } else {
                db.settingsQueries.upsert(key, value)
            }
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
        private const val KEY_HIDDEN_GROUPS = "pref_hidden_groups"
        private const val KEY_SMART_GROUPING = "pref_general_smart_grouping"
        private const val KEY_SPEED = "pref_playback_speed"
        private const val KEY_RECORDING_FOLDER_URI = "pref_recording_folder_uri"
        private const val KEY_RECORDING_STORAGE_MODE = "pref_recording_storage_mode"
        private const val KEY_EPG_DAYS_BACK = "pref_epg_days_back"
        private const val KEY_EPG_DAYS_FORWARD = "pref_epg_days_forward"
        private const val KEY_EPG_TIMELINE_MIN = "pref_epg_timeline_minutes"
        private const val KEY_DECODER_FALLBACK = "pref_playback_decoder_fallback"
        private const val KEY_BUFFER_PROFILE = "pref_playback_buffer_profile"
    }
}

/**
 * MK.15.1 / 15.2 — EPG display prefs.
 *
 *  - [daysBack] (0–14) bounds catch-up scroll backward from now.
 *  - [daysForward] (1–14) bounds upcoming scroll forward from now.
 *  - [timelineMinutes] is the visible-at-once strip in the guide grid;
 *    a longer span shows more programmes per screen at smaller width.
 *    Allowed: 30 / 60 / 90 / 120 / 180.
 */
data class EpgPrefs(
    val daysBack: Int = DEFAULT_DAYS_BACK,
    val daysForward: Int = DEFAULT_DAYS_FORWARD,
    val timelineMinutes: Int = DEFAULT_TIMELINE_MIN,
) {
    companion object {
        const val DEFAULT_DAYS_BACK = 1
        const val DEFAULT_DAYS_FORWARD = 2
        const val DEFAULT_TIMELINE_MIN = 60
        val TIMELINE_PRESETS = listOf(30, 60, 90, 120, 180)
    }
}

/**
 * Stage 3.1 / MK.14.2-storage (audit-revised) — recording-storage prefs.
 *
 * The [storageMode] field decides which resolver branch handles the
 * file allocation; [folderUri] is only meaningful when mode is
 * [RecordingStorageMode.CUSTOM_SAF].
 */
data class RecordingPrefs(
    val storageMode: RecordingStorageMode = RecordingStorageMode.PUBLIC_MEDIA_STORE,
    /** SAF tree URI string. Populated only when [storageMode] is
     *  [RecordingStorageMode.CUSTOM_SAF]; ignored otherwise. */
    val folderUri: String? = null,
)

/**
 * Where new recordings get written.
 *
 * - [PUBLIC_MEDIA_STORE] (default for fresh installs) — `Movies/YancoTV/`
 *   via MediaStore (API 29+) or direct File writes (API ≤28 with
 *   `WRITE_EXTERNAL_STORAGE`). Files survive uninstall; visible to any
 *   file manager / Gallery / Photos app.
 * - [APP_PRIVATE] — `getExternalFilesDir(Movies)/yanco-recordings/`. No
 *   permission needed on any API. Removed when YancoTV is uninstalled
 *   (Android contract for app-specific external dirs).
 * - [CUSTOM_SAF] — user picked a folder via the SAF system picker
 *   (`ACTION_OPEN_DOCUMENT_TREE`). Tree URI persisted via
 *   `takePersistableUriPermission`. Files survive uninstall but the
 *   YancoTV-side metadata (recordings table rows, persisted URI grant)
 *   does NOT carry across reinstall — Android attributes the orphaned
 *   files to "no installed app" until the user reimports.
 */
enum class RecordingStorageMode(val key: String) {
    PUBLIC_MEDIA_STORE("public_media_store"),
    APP_PRIVATE("app_private"),
    CUSTOM_SAF("custom_saf"),
    ;

    companion object {
        fun fromKey(key: String?): RecordingStorageMode =
            values().firstOrNull { it.key == key } ?: PUBLIC_MEDIA_STORE
    }
}

/** Default section to land on when the app opens. TiviMate defaults to last-used. */
enum class OpenOn(
    val key: String,
    val displayName: String,
) {
    HOME("home", "Home"),
    LIVE_TV("live_tv", "Live TV"),
    LAST_USED("last_used", "Last used"),
    ;

    companion object {
        fun fromKey(key: String?): OpenOn = values().firstOrNull { it.key == key } ?: HOME
    }
}

data class GeneralPrefs(
    val openOn: OpenOn = OpenOn.HOME,
    val showChannelNumbers: Boolean = false,
    /**
     * Smart grouping buckets the provider's flat category list into
     * meaningful sections (Sports, News, Movies, Kids, Music, Regions,
     * Other) via keyword matching. Visual only — still filters on the
     * exact group name when a sub-entry is selected.
     */
    val smartGrouping: Boolean = false,
)

enum class ResizeMode(
    val key: String,
    val displayName: String,
) {
    FIT("fit", "Fit"),
    FILL("fill", "Fill"),
    ZOOM("zoom", "Zoom"),
    // MK.12a.5 — forced aspect ratios. These override the stream's reported
    // aspect; useful when a provider tags the stream incorrectly (SD 4:3
    // content served in a 16:9 container, letterboxed 16:9 in a 4:3 frame).
    RATIO_16_9("16_9", "16:9"),
    RATIO_4_3("4_3", "4:3"),
    ;

    companion object {
        fun fromKey(key: String?): ResizeMode = values().firstOrNull { it.key == key } ?: FIT
    }
}

data class PlaybackPrefs(
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val autoPlayNext: Boolean = false,
    val audioLanguage: String = "",
    val subtitleLanguage: String = "",
    /**
     * Playback rate. Applied automatically on VOD loads; live channels
     * always reset to 1.0× at load time (live + speed-shift is a transient
     * override, not a persisted pref).
     */
    val speed: Float = 1.0f,
    /** MK.17.3 — when on (default), ExoPlayer falls back to a different
     *  decoder if the first one fails to initialise. Off makes hard
     *  failures more visible for debugging. */
    val enableDecoderFallback: Boolean = true,
    /** MK.17.4 — `LoadControl` profile. */
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
)

/** MK.17.4 — three preset profiles for `DefaultLoadControl`. Free
 *  sliders are deliberately avoided; pick a profile, ship it. */
enum class BufferProfile(
    val key: String,
    val displayName: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackMs: Int,
    val rebufferMs: Int,
    val backBufferMs: Int,
) {
    LOW_LATENCY("low_latency", "Low latency", 5_000, 5_000, 500, 1_000, 10_000),
    BALANCED("balanced", "Balanced", 15_000, 15_000, 1_000, 2_500, 30_000),
    STABLE("stable", "Stable", 30_000, 45_000, 2_000, 5_000, 60_000),
    ;

    companion object {
        fun fromKey(key: String?): BufferProfile =
            values().firstOrNull { it.key == key } ?: BALANCED
    }
}

/** MK.17.1a — known-good IPTV User-Agent strings. The default
 *  ("system") leaves the network layer to pick its own; everything
 *  else writes a verbatim UA into [NetworkPrefs.userAgentOverride]. */
enum class UserAgentPreset(
    val key: String,
    val displayName: String,
    val value: String?,
) {
    SYSTEM("system", "System default", null),
    VLC("vlc", "VLC", "VLC/3.0.20 LibVLC/3.0.20"),
    EXOPLAYER("exoplayer", "ExoPlayer", "ExoPlayerLib/2.19.1"),
    KODI("kodi", "Kodi", "Kodi/20.5 (Linux; Android 11) Mobile"),
    SMART_TV("smart_tv", "Smart TV", "Mozilla/5.0 (SMART-TV; Linux; Tizen 7.0)"),
    CHROME_ANDROID(
        "chrome_android",
        "Chrome Android",
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
    ),
    CUSTOM("custom", "Custom…", null),
    ;

    companion object {
        fun matchValue(value: String?): UserAgentPreset {
            if (value.isNullOrBlank()) return SYSTEM
            val hit = values().firstOrNull { it.value == value }
            return hit ?: CUSTOM
        }
    }
}

data class NetworkPrefs(
    val userAgentOverride: String? = null,
    val connectTimeoutSec: Int = AppPreferences.DEFAULT_CONNECT_TIMEOUT,
    val readTimeoutSec: Int = AppPreferences.DEFAULT_READ_TIMEOUT,
)
