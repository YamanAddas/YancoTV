package com.yancotv.android.player.options

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ResizeMode
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.launch
import java.util.Locale

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
@UnstableApi
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

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(active) {
                    if (active != null) detectTapGestures { onDismiss() }
                },
        contentAlignment = Alignment.BottomEnd,
    ) {
        AnimatedVisibility(
            visible = active != null,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 3 }),
            exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 3 }),
        ) {
            val current = active
            Column(
                modifier =
                    Modifier
                        .padding(end = 32.dp, bottom = 96.dp)
                        .width(PANEL_WIDTH.dp)
                        .heightIn(max = PANEL_MAX_HEIGHT.dp)
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
                                AudioPanelContent(controller, prefs, onDismiss)
                            PlayerOptionCategory.SUBTITLES ->
                                SubtitlesPanelContent(controller, prefs, onPickSubtitleFile, onDismiss)
                            PlayerOptionCategory.ASPECT ->
                                AspectPanelContent(prefs, onDismiss)
                            else -> Unit
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
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private fun labelFor(c: PlayerOptionCategory): String =
    when (c) {
        PlayerOptionCategory.AUDIO -> "Audio"
        PlayerOptionCategory.SUBTITLES -> "Subtitles"
        PlayerOptionCategory.ASPECT -> "Aspect"
        PlayerOptionCategory.SPEED -> "Speed"
        PlayerOptionCategory.SLEEP -> "Sleep"
        PlayerOptionCategory.RECORD -> "Record"
        PlayerOptionCategory.FAVORITES -> "Favorites"
        PlayerOptionCategory.EXTERNAL -> "External player"
    }

// ───── Audio ─────

@UnstableApi
@Composable
private fun AudioPanelContent(
    controller: PlaybackController,
    prefs: AppPreferences,
    onDismiss: () -> Unit,
) {
    val tracks = rememberAudioTracks(controller.player)
    val scope = rememberCoroutineScope()
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(tracks) {
        if (tracks.isNotEmpty()) runCatching { firstRowFocus.requestFocus() }
    }
    if (tracks.isEmpty()) {
        EmptyLine("No audio tracks reported yet.")
        return
    }
    tracks.forEachIndexed { idx, t ->
        OptionRow(
            label = t.displayName,
            selected = t.selected,
            focusRequester = if (idx == 0) firstRowFocus else null,
            onPick = {
                applyAudioTrack(controller.player, t)
                t.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    scope.launch { prefs.setAudioLanguage(lang) }
                }
                onDismiss()
            },
        )
    }
}

private data class AudioTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val language: String?,
    val displayName: String,
    val selected: Boolean,
)

@UnstableApi
@Composable
private fun rememberAudioTracks(player: Player): List<AudioTrack> {
    var t by remember { mutableStateOf(readAudioTracks(player)) }
    DisposableEffect(player) {
        val l =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    t = readAudioTracks(player)
                }
            }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return t
}

@UnstableApi
private fun readAudioTracks(player: Player): List<AudioTrack> {
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
                    ?: "Track ${out.size + 1}"
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

@UnstableApi
private fun applyAudioTrack(
    player: Player,
    track: AudioTrack,
) {
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

@UnstableApi
@Composable
private fun SubtitlesPanelContent(
    controller: PlaybackController,
    prefs: AppPreferences,
    onPickExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tracks = rememberTextTracks(controller.player)
    val disabled = rememberTextDisabled(controller.player)
    val scope = rememberCoroutineScope()
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRowFocus.requestFocus() } }

    val offSelected = disabled || tracks.none { it.selected }
    OptionRow(
        label = "Off",
        selected = offSelected,
        focusRequester = firstRowFocus,
        onPick = {
            val params =
                controller.player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            controller.player.trackSelectionParameters = params
            scope.launch { prefs.setSubtitleLanguage("") }
            onDismiss()
        },
    )
    tracks.forEach { t ->
        OptionRow(
            label = t.displayName,
            selected = t.selected,
            onPick = {
                applyTextTrack(controller.player, t)
                t.language?.takeIf { it.isNotBlank() }?.let { lang ->
                    scope.launch { prefs.setSubtitleLanguage(lang) }
                }
                onDismiss()
            },
        )
    }
    OptionRow(
        label = "Load external file…",
        selected = false,
        onPick = {
            onPickExternal()
            onDismiss()
        },
    )
}

private data class TextTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val language: String?,
    val displayName: String,
    val selected: Boolean,
)

@UnstableApi
@Composable
private fun rememberTextTracks(player: Player): List<TextTrack> {
    var t by remember { mutableStateOf(readTextTracks(player)) }
    DisposableEffect(player) {
        val l =
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    t = readTextTracks(player)
                }
            }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    return t
}

@UnstableApi
@Composable
private fun rememberTextDisabled(player: Player): Boolean {
    var d by remember { mutableStateOf(player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) }
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

@UnstableApi
private fun readTextTracks(player: Player): List<TextTrack> {
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
                    ?: "Track ${out.size + 1}"
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

@UnstableApi
private fun applyTextTrack(
    player: Player,
    track: TextTrack,
) {
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

// ───── Aspect ─────

@Composable
private fun AspectPanelContent(
    prefs: AppPreferences,
    onDismiss: () -> Unit,
) {
    val state by prefs.playbackFlow.collectAsState()
    val scope = rememberCoroutineScope()
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRowFocus.requestFocus() } }

    ResizeMode.values().forEachIndexed { idx, mode ->
        OptionRow(
            label = mode.displayName,
            selected = state.resizeMode == mode,
            focusRequester = if (idx == 0) firstRowFocus else null,
            onPick = {
                scope.launch { prefs.setResizeMode(mode) }
                onDismiss()
            },
        )
    }
}

/** Cycle helper exposed for the popup-row LEFT/RIGHT gesture. */
suspend fun cycleAspect(
    prefs: AppPreferences,
    forward: Boolean,
) {
    val current = prefs.playbackFlow.value.resizeMode
    val all = ResizeMode.values()
    val idx = all.indexOf(current).coerceAtLeast(0)
    val next = if (forward) (idx + 1) % all.size else (idx - 1 + all.size) % all.size
    prefs.setResizeMode(all[next])
}

// ───── Shared ─────

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onPick: () -> Unit,
    focusRequester: FocusRequester? = null,
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
                .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null) { onPick() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) palette.Accent else palette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
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
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

private const val PANEL_WIDTH = 380
private const val PANEL_MAX_HEIGHT = 480
