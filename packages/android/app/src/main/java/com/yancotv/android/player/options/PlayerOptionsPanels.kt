package com.yancotv.android.player.options

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.R
import com.yancotv.android.player.ExternalPlayer
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.player.SleepTimerOption
import com.yancotv.android.player.SleepTimerState
import com.yancotv.android.player.subtitles.MoviehashCalculator
import com.yancotv.android.player.subtitles.MoviehashUnavailable
import com.yancotv.android.player.subtitles.OpenSubtitlesClient
import com.yancotv.android.player.subtitles.SubtitleResult
import com.yancotv.android.player.subtitles.buildSubtitleQuery
import com.yancotv.android.player.subtitles.subtitleMimeFor
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ResizeMode
import com.yancotv.android.recording.RecordingService
import com.yancotv.android.ui.focus.PlacedFocusAnchor
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.focus.rememberPlacedFocusAnchor
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.handoff.toHandoffItem
import com.yancotv.shared.playback.Playable
import com.yancotv.shared.playback.toPlayable
import com.yancotv.shared.recording.RecordInput
import com.yancotv.shared.recording.RecordingFormat
import com.yancotv.shared.recording.RecordingStatus
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.types.ContentType
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * MK.options.redesign — host for the per-category floating panels.
 *
 * Renders the active panel's content in a compact right-anchored card
 * that sits over the popup. BACK closes the panel and returns focus to
 * the popup (PlayerActivity drives that side via the state holder).
 *
 * Visually: small rounded card, ~380dp wide, height-fits-content with
 * a soft cap. No tab strip, no kicker, no hex chassis. Just the
 * controls for one category.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerOptionsPanelHost(
    state: PlayerOptionsState,
    controller: PlaybackController,
    prefs: AppPreferences,
    onPickSubtitleFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active by state.activePanel.collectAsState()
    val palette = LocalYancoPalette.current
    // Picking an option in a panel returns the user to the popup (one
    // level up) so they can change another category without re-opening
    // MENU. BACK in the panel does the same. Outside-tap on the scrim
    // (touch only) is the only path that fully dismisses.
    val onPickOption: () -> Unit = remember(state) { { state.closePanel() } }

    BoxWithConstraints(
        modifier =
        Modifier
            .fillMaxSize()
            .pointerInput(active) {
                // Outside-tap (touch only) closes everything — popup
                // and panel both. Distinct from BACK / option-pick,
                // which only pop one level.
                if (active != null) detectTapGestures { onDismiss() }
            },
        contentAlignment = Alignment.BottomEnd,
    ) {
        // MB-306 — the cap has to come from the actual viewport, not a
        // constant. `PANEL_MAX_HEIGHT` was a flat 480dp while the panel sits
        // 96dp off the bottom edge; on the Fire TV's 540dp-tall viewport
        // that put its top at -36dp, so a long track list (BluRay rips
        // routinely carry 10+ subtitle tracks) scrolled its first rows off
        // the top of the screen with no way to reach them. Same shape as the
        // MB-300 settings-rail bug: a dp figure that reads fine on a phone
        // and overflows on TV.
        val panelMaxHeight =
            (maxHeight - PANEL_BOTTOM_INSET.dp - PANEL_TOP_MARGIN.dp)
                .coerceIn(PANEL_MIN_HEIGHT.dp, PANEL_MAX_HEIGHT.dp)
        AnimatedVisibility(
            visible = active != null,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 3 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 3 }),
        ) {
            val current = active
            Column(
                modifier =
                Modifier
                    .padding(end = 32.dp, bottom = PANEL_BOTTOM_INSET.dp)
                    .width(PANEL_WIDTH.dp)
                    .heightIn(max = panelMaxHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xEE0A1410))
                    .border(1.dp, palette.Accent, RoundedCornerShape(12.dp)),
            ) {
                if (current != null) {
                    PanelHeader(label = labelFor(current))
                    Column(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (current) {
                            PlayerOptionCategory.AUDIO ->
                                AudioPanelContent(controller, prefs, onPickOption)
                            PlayerOptionCategory.SUBTITLES ->
                                SubtitlesPanelContent(controller, prefs, onPickSubtitleFile, state, onPickOption)
                            PlayerOptionCategory.ASPECT ->
                                AspectPanelContent(prefs, onPickOption)
                            PlayerOptionCategory.SPEED ->
                                SpeedPanelContent(controller, prefs, onPickOption)
                            PlayerOptionCategory.SLEEP ->
                                SleepPanelContent(controller, onPickOption)
                            PlayerOptionCategory.RECORD ->
                                RecordPanelContent(controller, onPickOption)
                            PlayerOptionCategory.FAVORITES ->
                                FavoritesPanelContent(controller, onPickOption)
                            PlayerOptionCategory.EXTERNAL ->
                                ExternalPanelContent(controller, onPickOption)
                            PlayerOptionCategory.PLAY_ON_TV ->
                                // Cast/handoff are terminal — fully dismiss the
                                // whole options UI (onDismiss), not one panel
                                // level (onPickOption), or the root menu pops
                                // back up and strands the user (MB-233).
                                PlayOnTvPanelContent(controller, onDismiss)
                            PlayerOptionCategory.SUBTITLE_SEARCH ->
                                SubtitleSearchPanelContent(controller, onPickOption)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(label: String) {
    val palette = LocalYancoPalette.current
    Text(
        text = label.uppercase(Locale.ROOT),
        color = palette.Accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun labelFor(c: PlayerOptionCategory): String = when (c) {
    PlayerOptionCategory.AUDIO -> stringResource(R.string.po_audio)
    PlayerOptionCategory.SUBTITLES -> stringResource(R.string.cf_subtitles)
    PlayerOptionCategory.ASPECT -> stringResource(R.string.po_aspect)
    PlayerOptionCategory.SPEED -> stringResource(R.string.po_speed)
    PlayerOptionCategory.SLEEP -> stringResource(R.string.po_sleep)
    PlayerOptionCategory.RECORD -> stringResource(R.string.po_record)
    PlayerOptionCategory.FAVORITES -> stringResource(R.string.cat_favorites)
    PlayerOptionCategory.EXTERNAL -> stringResource(R.string.po_external)
    PlayerOptionCategory.PLAY_ON_TV -> stringResource(R.string.po_play_on_tv)
    PlayerOptionCategory.SUBTITLE_SEARCH -> stringResource(R.string.po_search_subs)
}

// ───── Audio ─────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun AudioPanelContent(controller: PlaybackController, prefs: AppPreferences, onPickOption: () -> Unit) {
    val tracks = rememberAudioTracks(controller.player)
    val scope = rememberCoroutineScope()
    val firstRowAnchor = rememberPlacedFocusAnchor()
    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) firstRowAnchor.awaitAndRequest()
    }
    if (tracks.isEmpty()) {
        EmptyLine(stringResource(R.string.po_no_audio))
        return
    }
    tracks.forEachIndexed { idx, t ->
        OptionRow(
            label = t.displayName,
            selected = t.selected,
            focusAnchor = if (idx == 0) firstRowAnchor else null,
            onPick = {
                applyAudioTrack(controller.player, t)
                t.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    scope.launch { prefs.setAudioLanguage(lang) }
                }
                onPickOption()
            },
        )
    }
}

private data class AudioTrack(val group: Tracks.Group, val trackIndex: Int, val language: String?, val displayName: String, val selected: Boolean)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberAudioTracks(player: Player): List<AudioTrack> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fallback: (Int) -> String = remember(ctx) { { n -> ctx.getString(R.string.po_track_n, n) } }
    var t by remember { mutableStateOf(readAudioTracks(player, fallback)) }
    DisposableEffect(player) {
        val l =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    t = readAudioTracks(player, fallback)
                }
            }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return t
}

/**
 * MK.31.12 — [fallbackName] names a track that reports neither a label nor a
 * language. It is a parameter rather than a `stringResource` call because this
 * runs from `remember {}` and from an `onTracksChanged` listener, neither of
 * which is composable scope.
 *
 * The default is English on purpose: the public `cycleAudioTrack` /
 * `cycleTextTrack` entry points are called from PlayerActivity's key handler
 * and have no Context to resolve with, and threading one through them for a
 * fallback that only appears on an unnamed, language-less track is not worth
 * the API churn. The panels — where the user actually reads a track list —
 * pass the localized version.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun readAudioTracks(player: Player, fallbackName: (Int) -> String = { "Track $it" }): List<AudioTrack> {
    val out = mutableListOf<AudioTrack>()
    for (group in player.currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val fmt = group.getTrackFormat(i)
            val lang = fmt.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            val codec = fmt.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
            val name =
                fmt.label?.takeIf { it.isNotBlank() }
                    ?: lang?.let { Locale(it).getDisplayLanguage(Locale.getDefault()) }
                    ?: codec
                    ?: fallbackName(out.size + 1)
            out +=
                AudioTrack(
                    group = group,
                    trackIndex = i,
                    language = lang,
                    displayName = name,
                    selected = group.isTrackSelected(i),
                )
        }
    }
    return out
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun applyAudioTrack(player: Player, track: AudioTrack) {
    val params = player.trackSelectionParameters.buildUpon()
    track.language?.takeIf { it.isNotBlank() }?.let { lang ->
        params.setPreferredAudioLanguage(lang)
        params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
    } ?: run {
        params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        params.addOverride(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
    }
    player.trackSelectionParameters = params.build()
}

// ───── Subtitles ─────

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SubtitlesPanelContent(
    controller: PlaybackController,
    prefs: AppPreferences,
    onPickExternal: () -> Unit,
    state: PlayerOptionsState,
    onPickOption: () -> Unit,
) {
    val tracks = rememberTextTracks(controller.player)
    val disabled = rememberTextDisabled(controller.player)
    val external by controller.externalSubtitle.collectAsState()
    val scope = rememberCoroutineScope()
    val firstRowAnchor = rememberPlacedFocusAnchor()
    val searchRowAnchor = rememberPlacedFocusAnchor()
    // bringIntoView lets us scroll the search row into view after focusing
    // it. Without this the row gets focus but stays below the scroll
    // viewport when there are many subtitle tracks above (BluRay rips
    // routinely have 10+) — focus is technically there, but the user
    // sees no selector because the focused row is offscreen.
    val searchRowBringIntoView = remember { BringIntoViewRequester() }

    // When the panel re-enters after a sub-panel closes, focus the row
    // that opened the sub-panel — not the default first row. Currently
    // SUBTITLE_SEARCH is the only sub-panel; structured this way so
    // future sub-rows drop in with one extra branch.
    //
    // Snapshot the hint at first composition (NOT via collectAsState) so
    // any subsequent recomposition — e.g. tracks flow emits after the
    // ExoPlayer applyExternalSubtitle landed — can't observe a stale or
    // already-consumed value mid-effect.
    val initialReturning = remember { state.returningFromSubPanel.value }
    LaunchedEffect(Unit) {
        when (initialReturning) {
            PlayerOptionCategory.SUBTITLE_SEARCH -> {
                searchRowAnchor.awaitAndRequest()
                runCatching { searchRowBringIntoView.bringIntoView() }
            }
            else -> firstRowAnchor.awaitAndRequest()
        }
        state.consumeSubPanelReturn()
    }

    // MB-306 — a side-loaded subtitle is NOT in `currentTracks` until
    // ExoPlayer has fetched and parsed the sidecar, which for a remote
    // provider URL is a network round-trip after prepare(). Deriving the
    // selection from tracks alone therefore reported "Off" for a subtitle
    // that was on its way, and re-opening the menu a moment later showed it
    // correctly — exactly the reported symptom. `external` closes that gap:
    // while it is set and no track has surfaced yet, the panel shows the
    // pending subtitle as the selection instead of claiming Off.
    val externalPending = external != null && tracks.none { it.selected }
    val offSelected = disabled || (tracks.none { it.selected } && !externalPending)
    OptionRow(
        label = stringResource(R.string.ps_off),
        selected = offSelected,
        focusAnchor = firstRowAnchor,
        onPick = {
            val params =
                controller.player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            controller.player.trackSelectionParameters = params
            // Drop the side-loaded track too. Without this, "Off" disabled
            // the renderer but left the subtitle attached to the MediaItem,
            // so the next load (next episode, resume, zap back) silently
            // brought it back.
            controller.clearExternalSubtitle()
            scope.launch { prefs.setSubtitleLanguage("") }
            onPickOption()
        },
    )
    // Local val: `external` is a delegated property, so it can't smart-cast.
    val pendingExternal = external?.takeIf { externalPending }
    if (pendingExternal != null) {
        OptionRow(
            label = pendingExternal.label,
            detail = stringResource(R.string.po_loading),
            selected = true,
            onPick = onPickOption,
        )
    }
    tracks.forEach { t ->
        OptionRow(
            label = t.displayName,
            selected = t.selected,
            onPick = {
                applyTextTrack(controller.player, t)
                t.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    scope.launch { prefs.setSubtitleLanguage(lang) }
                }
                onPickOption()
            },
        )
    }
    OptionRow(
        label = stringResource(R.string.po_load_external),
        selected = false,
        onPick = {
            onPickExternal()
            onPickOption()
        },
    )
    Box(modifier = Modifier.bringIntoViewRequester(searchRowBringIntoView)) {
        OptionRow(
            label = stringResource(R.string.po_search_online),
            selected = false,
            focusAnchor = searchRowAnchor,
            onPick = { state.openPanel(PlayerOptionCategory.SUBTITLE_SEARCH) },
        )
    }
}

private data class TextTrack(val group: Tracks.Group, val trackIndex: Int, val language: String?, val displayName: String, val selected: Boolean)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberTextTracks(player: Player): List<TextTrack> {
    // Keyed on `player`: MK.9.4's FFmpeg fallback releases the ExoPlayer and
    // stands a new one up. Unkeyed, the seed value survived that swap and the
    // panel showed the OLD player's tracks until the new one happened to emit
    // onTracksChanged — which it may already have done before this composed.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fallback: (Int) -> String = remember(ctx) { { n -> ctx.getString(R.string.po_track_n, n) } }
    var t by remember(player) { mutableStateOf(readTextTracks(player, fallback)) }
    DisposableEffect(player) {
        val l =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    t = readTextTracks(player, fallback)
                }
            }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return t
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun rememberTextDisabled(player: Player): Boolean {
    // Keyed on `player` for the same reason as rememberTextTracks.
    var d by remember(player) {
        mutableStateOf(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
    }
    DisposableEffect(player) {
        val l =
            object : Player.Listener {
                override fun onTrackSelectionParametersChanged(p: androidx.media3.common.TrackSelectionParameters) {
                    d = p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                }
            }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return d
}

/** See [readAudioTracks] for why [fallbackName] is a parameter. */
@androidx.annotation.OptIn(UnstableApi::class)
private fun readTextTracks(player: Player, fallbackName: (Int) -> String = { "Track $it" }): List<TextTrack> {
    val out = mutableListOf<TextTrack>()
    for (group in player.currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val fmt = group.getTrackFormat(i)
            val lang = fmt.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            val name =
                fmt.label?.takeIf { it.isNotBlank() }
                    ?: lang?.let { Locale(it).getDisplayLanguage(Locale.getDefault()) }
                    ?: fallbackName(out.size + 1)
            out +=
                TextTrack(
                    group = group,
                    trackIndex = i,
                    language = lang,
                    displayName = name,
                    selected = group.isTrackSelected(i),
                )
        }
    }
    return out
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun applyTextTrack(player: Player, track: TextTrack) {
    val params = player.trackSelectionParameters.buildUpon()
    params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    track.language?.takeIf { it.isNotBlank() }?.let { lang ->
        params.setPreferredTextLanguage(lang)
        params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
    } ?: run {
        params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        params.addOverride(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
    }
    player.trackSelectionParameters = params.build()
}

// ───── Subtitle Search ─────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SubtitleSearchPanelContent(controller: PlaybackController, onPickOption: () -> Unit) {
    // MK.31.12 — the search coroutine below assigns error text, and a coroutine
    // is not composable scope, so those resolve through a Context.
    val subCtx = androidx.compose.ui.platform.LocalContext.current
    val client: OpenSubtitlesClient = org.koin.compose.koinInject()
    val http: OkHttpClient = org.koin.compose.koinInject()
    val scope = rememberCoroutineScope()
    val firstRowAnchor = rememberPlacedFocusAnchor()

    var results by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var searching by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<Int?>(null) }

    val item by controller.currentItem.collectAsState()
    val episode by controller.currentEpisode.collectAsState()
    val q = remember(item, episode) { buildSubtitleQuery(item, episode) }

    LaunchedEffect(q?.query, q?.season, q?.episode, q?.type, item?.streamUrl) {
        val bundle = q
        if (bundle == null || bundle.query.isBlank()) {
            searching = false
            error = subCtx.getString(R.string.po_no_title)
            return@LaunchedEffect
        }
        searching = true
        error = null
        try {
            val found =
                withContext(Dispatchers.IO) {
                    // Try to compute moviehash from the actual stream bytes —
                    // gives OpenSubtitles a file-level match that ignores the
                    // M3U title entirely. Skip for live + HLS (no fixed-size
                    // file). On any failure (provider doesn't honor HEAD/Range,
                    // file too small, network hiccup), we silently fall back
                    // to title-only search.
                    var hash: String? = null
                    var byteSize: Long? = null
                    val streamUrl = item?.streamUrl
                    val isLive = item?.type == ContentType.LIVE
                    val isHls = streamUrl?.contains(".m3u8", ignoreCase = true) == true
                    if (!isLive && !isHls && !streamUrl.isNullOrBlank()) {
                        try {
                            val r = MoviehashCalculator.compute(http, streamUrl)
                            hash = r.hash
                            byteSize = r.byteSize
                        } catch (e: MoviehashUnavailable) {
                            // expected on segmented / non-Range-supporting providers
                        } catch (e: Exception) {
                            // network error — title fallback handles it
                        }
                    }
                    client.search(
                        query = bundle.query,
                        season = bundle.season,
                        episode = bundle.episode,
                        languages = "en",
                        type = bundle.type,
                        moviehash = hash,
                        moviebytesize = byteSize,
                    )
                }
            results = found
            if (found.isEmpty()) error = subCtx.getString(R.string.po_no_subs_found)
        } catch (e: Exception) {
            error = e.message ?: subCtx.getString(R.string.po_search_failed)
        }
        searching = false
    }

    // Re-request focus whenever the visible row set changes — we move
    // through three states (loading → error OR results), and each
    // composes a different first row. Without this LaunchedEffect keying
    // on all three, focus would only land once results arrive; during
    // the loading window the panel would have no focused row at all.
    LaunchedEffect(searching, error, results) {
        firstRowAnchor.awaitAndRequest()
    }

    if (searching) {
        OptionRow(
            label = stringResource(R.string.po_searching_q, q?.query.orEmpty()),
            selected = false,
            focusAnchor = firstRowAnchor,
            onPick = {},
        )
        return
    }
    error?.let {
        OptionRow(
            label = it,
            selected = false,
            focusAnchor = firstRowAnchor,
            onPick = {},
        )
        return
    }

    results.forEachIndexed { idx, sub ->
        val langDisplay = Locale(sub.language).displayLanguage.replaceFirstChar { it.uppercase() }
        val label = buildString {
            append(langDisplay)
            if (sub.hearingImpaired) append(" [CC]")
            if (sub.aiTranslated) append(" [AI]")
        }
        val detail = sub.release.take(40).ifBlank { sub.fileName.take(40) }
        val busy = downloading == sub.fileId
        OptionRow(
            // Release info moved to the second line — it was concatenated
            // into the label with an em dash, which pushed the language (the
            // only part most picks turn on) off the end of a 380dp panel.
            label = label,
            detail = if (busy) stringResource(R.string.po_downloading) else detail,
            selected = false,
            focusAnchor = if (idx == 0) firstRowAnchor else null,
            onPick = {
                if (downloading != null) return@OptionRow
                downloading = sub.fileId
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            client.download(sub.fileId)
                        }
                        val uri = android.net.Uri.fromFile(result.file)
                        val mime = subtitleMimeFor(result.file.name)
                        controller.applyExternalSubtitle(uri, mime, label)
                        onPickOption()
                    } catch (e: Exception) {
                        error = e.message ?: subCtx.getString(R.string.po_download_failed)
                        downloading = null
                    }
                }
            },
        )
    }
}

// Subtitle query helpers (buildSubtitleQuery, stripReleaseNoise,
// appendYearIfMissing, SUBTITLE_PREFIX_NOISE, SUBTITLE_RELEASE_NOISE,
// SubtitleQueryBundle) live in
// com/yancotv/android/player/subtitles/SubtitleQueryBuilder.kt with
// `internal` visibility so the unit tests can pin the regex set without
// pulling Compose / Koin into the test classpath.

// ───── Aspect ─────

@Composable
private fun AspectPanelContent(prefs: AppPreferences, onPickOption: () -> Unit) {
    val state by prefs.playbackFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val firstRowAnchor = rememberPlacedFocusAnchor()
    LaunchedEffect(Unit) { firstRowAnchor.awaitAndRequest() }

    ResizeMode.values().forEachIndexed { idx, mode ->
        OptionRow(
            label = stringResource(mode.labelRes),
            selected = state.resizeMode == mode,
            focusAnchor = if (idx == 0) firstRowAnchor else null,
            onPick = {
                scope.launch { prefs.setResizeMode(mode) }
                onPickOption()
            },
        )
    }
}

/** Cycle helper exposed for the popup-row LEFT/RIGHT gesture. */
suspend fun cycleAspect(prefs: AppPreferences, forward: Boolean) {
    val current = prefs.playbackFlow.value.resizeMode
    val all = ResizeMode.values()
    val idx = all.indexOf(current).coerceAtLeast(0)
    val next = if (forward) (idx + 1) % all.size else (idx - 1 + all.size) % all.size
    prefs.setResizeMode(all[next])
}

// ───── Speed ─────

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SpeedPanelContent(controller: PlaybackController, prefs: AppPreferences, onPickOption: () -> Unit) {
    val state by prefs.playbackFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val firstRowAnchor = rememberPlacedFocusAnchor()
    LaunchedEffect(Unit) { firstRowAnchor.awaitAndRequest() }

    SPEED_PRESETS.forEachIndexed { idx, speed ->
        val selected = kotlin.math.abs(state.speed - speed) < 0.01f
        OptionRow(
            label = formatSpeed(speed),
            selected = selected,
            focusAnchor = if (idx == 0) firstRowAnchor else null,
            onPick = {
                applySpeed(controller, prefs, speed, scope)
                onPickOption()
            },
        )
    }
}

private fun formatSpeed(speed: Float): String = if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"

@androidx.annotation.OptIn(UnstableApi::class)
private fun applySpeed(controller: PlaybackController, prefs: AppPreferences, speed: Float, scope: kotlinx.coroutines.CoroutineScope) {
    controller.player.setPlaybackSpeed(speed)
    scope.launch { prefs.setSpeed(speed) }
}

/** Cycle helper for the popup-row LEFT/RIGHT gesture on Speed. */
@androidx.annotation.OptIn(UnstableApi::class)
suspend fun cycleSpeed(controller: PlaybackController, prefs: AppPreferences, forward: Boolean) {
    val current = prefs.playbackFlow.value.speed
    val idx =
        SPEED_PRESETS.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
            .takeIf { it >= 0 } ?: SPEED_PRESETS.indexOf(1.0f)
    val next =
        if (forward) {
            (idx + 1) % SPEED_PRESETS.size
        } else {
            (idx - 1 + SPEED_PRESETS.size) % SPEED_PRESETS.size
        }
    val target = SPEED_PRESETS[next]
    controller.player.setPlaybackSpeed(target)
    prefs.setSpeed(target)
}

// ───── Sleep ─────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SleepPanelContent(controller: PlaybackController, onPickOption: () -> Unit) {
    val sleep by controller.sleepTimer.collectAsState()
    val firstRowAnchor = rememberPlacedFocusAnchor()
    LaunchedEffect(Unit) { firstRowAnchor.awaitAndRequest() }

    val isActive = sleep is SleepTimerState.Active
    val activeOption = (sleep as? SleepTimerState.Active)?.option

    OptionRow(
        label = stringResource(R.string.ps_off),
        selected = !isActive,
        focusAnchor = firstRowAnchor,
        onPick = {
            controller.cancelSleepTimer()
            onPickOption()
        },
    )
    SleepTimerOption.values().forEach { opt ->
        if (opt == SleepTimerOption.END_OF_PROGRAM) return@forEach // EOP needs EPG lookup; defer
        OptionRow(
            label = sleepLabel(opt),
            selected = activeOption == opt,
            onPick = {
                controller.setSleepTimer(opt)
                onPickOption()
            },
        )
    }
}

// MK.31.12 — @Composable; only caller is the sleep-timer option row. The
// minute values go through one format string rather than four literals, so
// Arabic gets its plural agreement from one place.
@Composable
private fun sleepLabel(opt: SleepTimerOption): String = when (opt) {
    SleepTimerOption.MIN_15 -> pluralStringResource(R.plurals.po_minutes, 15, 15)
    SleepTimerOption.MIN_30 -> pluralStringResource(R.plurals.po_minutes, 30, 30)
    SleepTimerOption.MIN_45 -> pluralStringResource(R.plurals.po_minutes, 45, 45)
    SleepTimerOption.MIN_60 -> pluralStringResource(R.plurals.po_minutes, 60, 60)
    SleepTimerOption.END_OF_PROGRAM -> stringResource(R.string.po_end_of_programme)
}

// ───── Audio / Subs cycle helpers ─────

/**
 * Picks the next supported audio track and applies it. Wraps; if no
 * selectable tracks, no-op.
 *
 * 2026-04-27 — also persists the selected track's language via
 * [AppPreferences.setAudioLanguage]. Without that, the popup row's
 * `currentValue` (read from `prefs.playbackFlow`) stayed stale after a
 * LEFT/RIGHT cycle: track did switch on the player, but the popup
 * label still showed the old language code until the next reopen.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun cycleAudioTrack(controller: PlaybackController, forward: Boolean, scope: kotlinx.coroutines.CoroutineScope? = null, prefs: AppPreferences? = null) {
    val tracks = readAudioTracks(controller.player)
    if (tracks.isEmpty()) return
    val currentIdx = tracks.indexOfFirst { it.selected }.coerceAtLeast(0)
    val nextIdx =
        if (forward) {
            (currentIdx + 1) % tracks.size
        } else {
            (currentIdx - 1 + tracks.size) % tracks.size
        }
    val nextTrack = tracks[nextIdx]
    applyAudioTrack(controller.player, nextTrack)
    if (scope != null && prefs != null) {
        val lang = nextTrack.language?.takeIf { it.isNotBlank() }.orEmpty()
        scope.launch { prefs.setAudioLanguage(lang) }
    }
}

/**
 * Cycle subtitle selection: walks through tracks, then OFF, then back
 * to first track.
 *
 * 2026-04-27 — also persists the selected track's language (or empty
 * for Off) via [AppPreferences.setSubtitleLanguage] so the popup row's
 * `currentValue` reflects the cycle without reopening.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun cycleTextTrack(controller: PlaybackController, forward: Boolean, scope: kotlinx.coroutines.CoroutineScope? = null, prefs: AppPreferences? = null) {
    val player = controller.player
    val tracks = readTextTracks(player)
    if (tracks.isEmpty()) {
        // No tracks → nothing to cycle (Off is the only state).
        return
    }
    val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val states = tracks.size + 1 // tracks + Off
    val currentIdx =
        when {
            disabled -> tracks.size // "Off" is last
            else -> tracks.indexOfFirst { it.selected }.coerceAtLeast(0)
        }
    val nextIdx =
        if (forward) {
            (currentIdx + 1) % states
        } else {
            (currentIdx - 1 + states) % states
        }
    val newLang: String
    if (nextIdx == tracks.size) {
        // Off
        val params =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        player.trackSelectionParameters = params
        newLang = ""
    } else {
        applyTextTrack(player, tracks[nextIdx])
        newLang = tracks[nextIdx].language?.takeIf { it.isNotBlank() }.orEmpty()
    }
    if (scope != null && prefs != null) {
        scope.launch { prefs.setSubtitleLanguage(newLang) }
    }
}

// ───── Shared ─────

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onPick: () -> Unit,
    focusAnchor: PlacedFocusAnchor? = null,
    /** Optional second line — state ("Loading…") or provenance ("From provider"). */
    detail: String? = null,
) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        when {
            focused -> palette.BackgroundHover
            selected -> palette.AccentMuted.copy(alpha = 0.25f)
            else -> Color.Transparent
        }
    val border = if (focused) palette.Accent else Color.Transparent
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .let { m -> if (focusAnchor != null) m.placedFocus(focusAnchor) else m }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter,
                    Key.Spacebar,
                    -> {
                        onPick()
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { onPick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) palette.Accent else palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    color = palette.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text(
                text = "✓",
                color = palette.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        color = LocalYancoPalette.current.TextMuted,
        fontSize = 14.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

// ───── Record (slice 2b) ─────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun RecordPanelContent(controller: PlaybackController, onPickOption: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recordings: RecordingsRepository =
        org.koin.compose.koinInject()
    // Stage 5.6 — first-time recording disclaimer gate. Wraps the
    // Record click so the user sees the legal acknowledgement once
    // (then never again across the whole app, by SharedPreferences).
    val disclaimerGate = com.yancotv.android.recording.rememberRecordingDisclaimerGate()
    val currentItem by controller.currentItem.collectAsState()
    val inflight by remember { recordings.allFlow() }.collectAsState(initial = emptyList())
    val active =
        inflight.firstOrNull {
            it.contentId == currentItem?.id && it.status == RecordingStatus.RECORDING
        }
    // MB-205 root cause (2026-04-27 logcat): the inflight flow starts
    // empty, so on first frame `active` is null and the "Record this
    // channel" row composes + receives focus. The flow then emits the
    // real list and Compose swaps in "Stop recording". The two rows
    // are different OptionRow call sites (separate identities), so
    // focus doesn't transfer.
    //
    // Fix: ONE anchor per branch. Each branch's anchor lives only while
    // its row is mounted; the LaunchedEffect picks the right one based
    // on `active != null` and waits for that row's own onPlaced. No
    // reset()-vs-markPlaced race that broke the prior attempt.
    val recordAnchor = rememberPlacedFocusAnchor()
    val stopAnchor = rememberPlacedFocusAnchor()
    LaunchedEffect(currentItem?.id, active != null) {
        if (currentItem == null) return@LaunchedEffect
        if (active != null) {
            stopAnchor.awaitAndRequest()
        } else {
            recordAnchor.awaitAndRequest()
        }
    }
    val item = currentItem
    if (item == null) {
        EmptyLine(stringResource(R.string.po_nothing_record))
        return
    }
    val displayTitle = item.cleanTitle?.takeIf { it.isNotBlank() } ?: item.title

    if (active != null) {
        OptionRow(
            label = stringResource(R.string.po_stop_recording),
            selected = true,
            focusAnchor = stopAnchor,
            onPick = {
                // Diagnostic log so a "stop didn't stop" report can be
                // confirmed via `adb logcat -s YancoRecsPanel`.
                android.util.Log.i(
                    "YancoRecsPanel",
                    "stop tap: recordId=${active.id} contentId=${active.contentId} status=${active.status}",
                )
                RecordingService.stop(context, active.id)
                onPickOption()
            },
        )
        return
    }

    val format = detectRecordingFormat(item.streamUrl)
    OptionRow(
        label = stringResource(R.string.po_record_channel),
        selected = false,
        focusAnchor = recordAnchor,
        onPick = {
            disclaimerGate {
                RecordingService.start(
                    context = context,
                    input =
                    RecordInput(
                        recordId = "rec-${System.currentTimeMillis()}-${item.id.take(8)}",
                        sourceUrl = item.streamUrl,
                        title = displayTitle,
                        format = format,
                        contentId = item.id,
                    ),
                )
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.po_recording_started),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                onPickOption()
            }
        },
    )
}

private fun detectRecordingFormat(streamUrl: String): RecordingFormat {
    val withoutQuery = streamUrl.substringBefore('?').substringBefore('#')
    return if (withoutQuery.endsWith(".m3u8", ignoreCase = true)) {
        RecordingFormat.HLS
    } else {
        RecordingFormat.MPEG_TS
    }
}

// ───── Favorites (slice 2b) ─────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FavoritesPanelContent(controller: PlaybackController, onPickOption: () -> Unit) {
    val favorites: FavoritesRepository = org.koin.compose.koinInject()
    val scope = rememberCoroutineScope()
    val currentItem by controller.currentItem.collectAsState()
    val currentEpisode by controller.currentEpisode.collectAsState()
    val firstRowAnchor = rememberPlacedFocusAnchor()

    // Episodes don't have content rows — favorite the series. Live /
    // movie use the item's own id. Mirrors the legacy panel's contract.
    val favoriteId = currentEpisode?.seriesId ?: currentItem?.id

    val isFav by remember(favoriteId) {
        if (favoriteId != null) favorites.isFavoriteFlow(favoriteId) else flowOf(false)
    }.collectAsState(initial = false)

    LaunchedEffect(favoriteId) {
        if (favoriteId != null) firstRowAnchor.awaitAndRequest()
    }

    if (favoriteId == null) {
        EmptyLine(stringResource(R.string.po_nothing_favorite))
        return
    }

    OptionRow(
        label = if (isFav) stringResource(R.string.po_remove_favorite) else stringResource(R.string.po_add_favorite),
        selected = isFav,
        focusAnchor = firstRowAnchor,
        onPick = {
            scope.launch(Dispatchers.IO) { runCatching { favorites.toggle(favoriteId) } }
            onPickOption()
        },
    )
}

// ───── External player (slice 2b) ─────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ExternalPanelContent(controller: PlaybackController, onPickOption: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val currentItem by controller.currentItem.collectAsState()
    val installed = remember { ExternalPlayer.installed(context) }
    val firstRowAnchor = rememberPlacedFocusAnchor()

    val streamUrl = currentItem?.streamUrl?.takeIf { it.isNotBlank() }
    LaunchedEffect(streamUrl) {
        if (streamUrl != null) firstRowAnchor.awaitAndRequest()
    }
    if (streamUrl == null) {
        EmptyLine(stringResource(R.string.po_nothing_handoff))
        return
    }

    val isLive = currentItem?.type == com.yancotv.shared.types.ContentType.LIVE
    val positionMs =
        if (isLive) null else controller.player.currentPosition.takeIf { it > 0L }

    installed.forEachIndexed { idx, app ->
        OptionRow(
            label = app.displayName,
            selected = false,
            focusAnchor = if (idx == 0) firstRowAnchor else null,
            onPick = {
                controller.player.pause()
                ExternalPlayer.launch(
                    context = context,
                    streamUrl = streamUrl,
                    positionMs = positionMs,
                    app = app,
                )
                onPickOption()
            },
        )
    }
    OptionRow(
        label = stringResource(R.string.po_choose_player),
        selected = false,
        focusAnchor = if (installed.isEmpty()) firstRowAnchor else null,
        onPick = {
            controller.player.pause()
            ExternalPlayer.launch(
                context = context,
                streamUrl = streamUrl,
                positionMs = positionMs,
                app = null,
            )
            onPickOption()
        },
    )
}

// ───── Play on TV (MK.26.A.3 — LAN companion-handoff sender) ─────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayOnTvPanelContent(controller: PlaybackController, onDismissAll: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs: AppPreferences = org.koin.compose.koinInject()
    val handoff: com.yancotv.shared.handoff.HandoffClient = org.koin.compose.koinInject()
    val sources: com.yancotv.shared.sources.SourceRepository = org.koin.compose.koinInject()
    val castController: com.yancotv.android.cast.CastController = org.koin.compose.koinInject()
    val logger: com.yancotv.shared.logger.Logger = org.koin.compose.koinInject()

    val currentItem by controller.currentItem.collectAsState()
    val currentEpisode by controller.currentEpisode.collectAsState()
    val pairedHost by prefs.pairedTvHostFlow.collectAsState()
    val firstRowAnchor = rememberPlacedFocusAnchor()

    // Browse for YancoTV receivers on the LAN while the panel is open. All
    // hooks must run unconditionally (before any early return) per Compose.
    val discovery = remember { com.yancotv.android.handoff.HandoffDiscovery(context, logger) }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    val discovered by discovery.devices.collectAsState()

    // MB-397 — warm Google Cast discovery in parallel with the LAN browse, so
    // the system picker behind the Chromecast row opens with routes already
    // found instead of scanning from zero. Gated on Play Services internally;
    // main thread by CAF requirement (LaunchedEffect runs on Main).
    LaunchedEffect(Unit) { castController.warmDiscovery() }

    // Episode-first: currentItem is a synthesized MOVIE-typed view of an
    // episode, so toPlayable() on it would mis-tag the handoff kind.
    val playable: Playable? = currentEpisode ?: currentItem?.toPlayable()

    LaunchedEffect(playable?.id) {
        if (playable != null) firstRowAnchor.awaitAndRequest()
    }
    if (playable == null) {
        EmptyLine(stringResource(R.string.po_nothing_send_tv))
        return
    }

    // player.currentPosition is main-thread-only — snapshot here, before IO.
    val resumeSeconds =
        if (playable.isLive) 0L else controller.player.currentPosition.coerceAtLeast(0L) / 1000L
    val handoffItem = playable.toHandoffItem()

    // Auto-discovered TVs first, then the manually-paired host if it isn't
    // already one of them.
    val targets =
        buildList {
            discovered.forEach { add(TvTarget(it.name, it.host, it.port)) }
            val manual = pairedHost
            if (manual != null && none { it.host == manual }) {
                add(TvTarget(context.getString(R.string.po_paired, manual), manual, com.yancotv.android.handoff.HandoffServer.DEFAULT_PORT))
            }
        }

    fun send(host: String, port: Int) {
        // The send runs on the panel's composition scope, which stays alive
        // while the options popup is up — so do the POST first and dismiss the
        // UI only once it completes (dismissing clears activePanel, which
        // removes this panel and cancels the scope). That fixes the stuck menu
        // (MB-233): the menu auto-clears after the send instead of lingering
        // until system BACK, and on success the sender player closes (the
        // stream is on the TV) rather than leaving a paused frame behind it.
        scope.launch(Dispatchers.IO) {
            val code = prefs.readHandoffPairingCode()
            if (code.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast
                        .makeText(
                            context,
                            context.getString(R.string.po_enter_pairing_code),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    onDismissAll()
                }
                return@launch
            }
            // Resolve this source's provider headers (MK.26.A.4) so a gated
            // stream plays on the TV, whose source ids differ from this phone's.
            val src = runCatching { sources.getById(handoffItem.sourceId) }.getOrNull()
            val ua =
                src?.userAgent?.takeIf { it.isNotBlank() }
                    ?: prefs.networkFlow.value.userAgentOverride?.takeIf { it.isNotBlank() }
            val referer = src?.referer?.takeIf { it.isNotBlank() }
            val command =
                com.yancotv.shared.handoff.HandoffPlayCommand(
                    pairingToken = code,
                    item = handoffItem.copy(userAgent = ua, referer = referer),
                    resumePositionSeconds = resumeSeconds,
                )
            val result = handoff.play(host, port, command)
            val message =
                when (result) {
                    is com.yancotv.shared.handoff.HandoffSendResult.Accepted ->
                        context.getString(R.string.po_playing_on_tv)
                    is com.yancotv.shared.handoff.HandoffSendResult.Rejected ->
                        context.getString(R.string.po_tv_refused, result.reason)
                    is com.yancotv.shared.handoff.HandoffSendResult.Unreachable ->
                        context.getString(R.string.po_tv_unreachable, host, result.message)
                }
            withContext(Dispatchers.Main) {
                android.widget.Toast
                    .makeText(context, message, android.widget.Toast.LENGTH_LONG)
                    .show()
                onDismissAll()
                if (result is com.yancotv.shared.handoff.HandoffSendResult.Accepted) {
                    (context as? android.app.Activity)?.finish()
                }
            }
        }
    }

    val castAvailable = castController.isAvailable()

    if (targets.isEmpty() && !castAvailable) {
        EmptyLine(
            stringResource(R.string.po_searching_tv),
        )
        return
    }

    targets.forEachIndexed { idx, target ->
        OptionRow(
            label = target.label,
            selected = false,
            focusAnchor = if (idx == 0) firstRowAnchor else null,
            onPick = { send(target.host, target.port) },
        )
    }

    // MK.26 Track B — cast to a bare Chromecast (a TV without YancoTV). Only
    // when Google Play Services is present, so it's absent on Fire OS. The
    // default receiver plays the VOD subset; failures surface on the TV.
    if (castAvailable) {
        OptionRow(
            label = stringResource(R.string.po_cast_chromecast),
            selected = false,
            focusAnchor = if (targets.isEmpty()) firstRowAnchor else null,
            onPick = {
                // Fully dismiss the options UI BEFORE the system cast chooser
                // so it can't lurk behind / after the chooser (MB-233).
                onDismissAll()
                castController.showDevicePicker(context)
            },
        )
    }
}

private data class TvTarget(val label: String, val host: String, val port: Int)

private const val PANEL_WIDTH = 380

/** Gap between the panel's bottom edge and the bottom of the screen — clears the transport bar. */
private const val PANEL_BOTTOM_INSET = 96

/** Breathing room above the panel so it never runs to the very top edge. */
private const val PANEL_TOP_MARGIN = 24

/** Ceiling on tall viewports (phone landscape / tablet), where the derived height would be excessive. */
private const val PANEL_MAX_HEIGHT = 480

/** Floor, so a very short viewport still yields a scrollable panel rather than a sliver. */
private const val PANEL_MIN_HEIGHT = 160
