package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ResizeMode
import com.yancotv.android.ui.theme.YancoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.koinInject
import java.util.Locale

/**
 * Player-options bottom sheet (MK.12a.1 scaffold + MK.12a.2 audio picker).
 *
 * Hosts the audio / subtitle / speed / aspect pickers from MK.12a.2–12a.5
 * and the sleep timer from MK.12b. Opened via KEYCODE_MENU on the remote;
 * dismissed by BACK. Playback is not paused while the sheet is up — the
 * video underneath keeps running.
 *
 * Sub-views (audio / subs / …) are rendered in-place by toggling
 * [SheetMode]. BACK returns to the top-level option list rather than
 * dismissing the whole sheet when a sub-view is active.
 */
/** Which panel the options sheet is currently showing. Top-level so
 *  [PlayerActivity] can hoist the state and route BACK from a sub-view
 *  back to [OPTIONS] before dismissing the sheet entirely. */
enum class SheetMode { OPTIONS, AUDIO, ASPECT, SPEED }

@UnstableApi
@Composable
fun PlayerOptionsSheet(
    mode: SheetMode,
    onModeChange: (SheetMode) -> Unit,
    onDismiss: () -> Unit,
    controller: PlaybackController = koinInject(),
    prefs: AppPreferences = koinInject(),
) {

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Translucent scrim — the stream underneath stays visible.
                .background(Color.Black.copy(alpha = 0.55f))
                // Any tap in the scrim (outside the sheet surface) dismisses.
                // Inside the column the sheet intercepts its own clicks.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(YancoPalette.BackgroundRaised)
                    // Swallow scrim-level clicks when they originate inside the sheet.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume */ }
                    .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (mode) {
                SheetMode.OPTIONS ->
                    OptionsView(
                        controller = controller,
                        prefs = prefs,
                        onOpenAudio = { onModeChange(SheetMode.AUDIO) },
                        onOpenAspect = { onModeChange(SheetMode.ASPECT) },
                        onOpenSpeed = { onModeChange(SheetMode.SPEED) },
                        onDismiss = onDismiss,
                    )
                SheetMode.AUDIO ->
                    AudioView(
                        controller = controller,
                        prefs = prefs,
                        onBack = { onModeChange(SheetMode.OPTIONS) },
                    )
                SheetMode.ASPECT ->
                    AspectView(
                        prefs = prefs,
                        onBack = { onModeChange(SheetMode.OPTIONS) },
                    )
                SheetMode.SPEED ->
                    SpeedView(
                        controller = controller,
                        prefs = prefs,
                        onBack = { onModeChange(SheetMode.OPTIONS) },
                    )
            }
        }
    }
}

@UnstableApi
@Composable
private fun OptionsView(
    controller: PlaybackController,
    prefs: AppPreferences,
    onOpenAudio: () -> Unit,
    onOpenAspect: () -> Unit,
    onOpenSpeed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { firstRowFocus.requestFocus() }
    }
    // Current audio language label — read live from the player's selected
    // audio track if any, else from the persisted pref, else "—".
    val audioTracks = rememberAudioTracks(controller.player)
    val playback by prefs.playbackFlow.collectAsState()
    val activeAudioLabel =
        audioTracks.firstOrNull { it.selected }?.displayName
            ?: playback.audioLanguage
                .takeIf { it.isNotBlank() }
                ?.let { languageDisplayName(it) }
            ?: "—"

    Text(
        text = "PLAYER OPTIONS",
        color = YancoPalette.Accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    OptionRow(
        label = "Audio track",
        value = activeAudioLabel,
        enabled = audioTracks.isNotEmpty(),
        focusRequester = firstRowFocus,
        onClick = {
            if (audioTracks.isNotEmpty()) onOpenAudio()
        },
    )
    OptionRow(
        label = "Subtitles",
        value = "Coming in MK.12a.3",
        enabled = false,
        onClick = onDismiss,
    )
    // Read the live player value rather than the pref — on LIVE channels
    // the pref still reflects the user's last VOD pick, but the player is
    // running at 1.0× (reset on channel zap). Showing what's actually
    // playing is less surprising than showing a stale pref value.
    val currentSpeed = currentPlayerSpeed(controller.player)
    OptionRow(
        label = "Playback speed",
        value = formatSpeed(currentSpeed),
        onClick = onOpenSpeed,
    )
    OptionRow(
        label = "Aspect ratio",
        value = playback.resizeMode.displayName,
        onClick = onOpenAspect,
    )
    OptionRow(
        label = "Sleep timer",
        value = "Coming in MK.12b.1",
        enabled = false,
        onClick = onDismiss,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "BACK to close",
        color = YancoPalette.TextMuted,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@UnstableApi
@Composable
private fun AudioView(
    controller: PlaybackController,
    prefs: AppPreferences,
    onBack: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    val audioTracks = rememberAudioTracks(controller.player)
    // Scope for the persist-to-prefs write. Keeps the write off the main
    // thread — the track selection itself applies synchronously on main.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    LaunchedEffect(audioTracks) {
        if (audioTracks.isNotEmpty()) runCatching { firstRowFocus.requestFocus() }
    }

    Text(
        text = "AUDIO TRACK",
        color = YancoPalette.Accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    if (audioTracks.isEmpty()) {
        Text(
            text = "No audio tracks reported yet. Try again once playback starts.",
            color = YancoPalette.TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        audioTracks.forEachIndexed { idx, track ->
            AudioTrackRow(
                track = track,
                autoFocus = idx == 0,
                focusRequester = if (idx == 0) firstRowFocus else null,
                onPick = {
                    applyAudioTrack(controller.player, track)
                    // Persist language so next channel zap defaults to it.
                    val lang = track.language
                    if (!lang.isNullOrBlank()) {
                        scope.launch { prefs.setAudioLanguage(lang) }
                    }
                    onBack()
                },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "BACK to options",
        color = YancoPalette.TextMuted,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Playback speed picker (MK.12a.4). Always writes through to the player,
 * but only persists the pick when the current item is VOD / Episode —
 * live channels get a transient speed-shift that clears on channel zap
 * (see PlaybackController.loadCurrent).
 */
@UnstableApi
@Composable
private fun SpeedView(
    controller: PlaybackController,
    prefs: AppPreferences,
    onBack: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // Observe the player's current speed so the ● marker stays in sync if
    // something else mutates playbackParameters (currently nothing does,
    // but rememberUpdatedState is cheap insurance).
    var currentSpeed by remember { mutableStateOf(currentPlayerSpeed(controller.player)) }
    DisposableEffect(controller.player) {
        val listener =
            object : Player.Listener {
                override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
                    currentSpeed = params.speed
                }
            }
        controller.player.addListener(listener)
        onDispose { controller.player.removeListener(listener) }
    }
    LaunchedEffect(Unit) {
        runCatching { firstRowFocus.requestFocus() }
    }

    Text(
        text = "PLAYBACK SPEED",
        color = YancoPalette.Accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    SPEED_PRESETS.forEachIndexed { idx, speed ->
        SpeedRow(
            speed = speed,
            selected = kotlin.math.abs(currentSpeed - speed) < 0.01f,
            focusRequester = if (idx == 0) firstRowFocus else null,
            onPick = {
                controller.player.setPlaybackSpeed(speed)
                // Only persist for non-live content — live sessions get a
                // transient speed-shift that doesn't pollute the saved VOD
                // preference. Controller.loadCurrent resets live to 1.0×.
                val item = controller.currentItem.value
                if (item != null && item.type != com.yancotv.shared.types.ContentType.LIVE) {
                    scope.launch { prefs.setSpeed(speed) }
                }
                onBack()
            },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "BACK to options",
        color = YancoPalette.TextMuted,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SpeedRow(
    speed: Float,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onPick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = formatSpeed(speed)
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) YancoPalette.BackgroundElevated else Color.Transparent)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onPick() }
            .semantics { contentDescription = label + if (selected) ", selected" else "" }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (selected) "●" else "○",
            color = if (selected) YancoPalette.Accent else YancoPalette.TextMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = label,
            color = YancoPalette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The presets shown in the sheet. 1.0× is the "normal speed" anchor. */
private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@UnstableApi
private fun currentPlayerSpeed(player: Player): Float = player.playbackParameters.speed

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100f).toInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) {
        "${rounded.toInt()}×"
    } else {
        // Strip trailing zero: 1.50 → 1.5×
        val s = String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
        "$s×"
    }
}

@Composable
private fun AspectView(
    prefs: AppPreferences,
    onBack: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    val playback by prefs.playbackFlow.collectAsState()
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    LaunchedEffect(Unit) {
        runCatching { firstRowFocus.requestFocus() }
    }

    Text(
        text = "ASPECT RATIO",
        color = YancoPalette.Accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    ResizeMode.values().forEachIndexed { idx, rm ->
        AspectRow(
            mode = rm,
            selected = playback.resizeMode == rm,
            focusRequester = if (idx == 0) firstRowFocus else null,
            onPick = {
                // Write through prefs — PlayerActivity's lifecycle collector
                // applies the new mode on the next emission. One source of
                // truth, no direct PlayerView mutation from the sheet.
                scope.launch { prefs.setResizeMode(rm) }
                onBack()
            },
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "BACK to options",
        color = YancoPalette.TextMuted,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun AspectRow(
    mode: ResizeMode,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onPick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) YancoPalette.BackgroundElevated else Color.Transparent)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onPick() }
            .semantics { contentDescription = mode.displayName + if (selected) ", selected" else "" }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (selected) "●" else "○",
            color = if (selected) YancoPalette.Accent else YancoPalette.TextMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = mode.displayName,
            color = YancoPalette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Flattened audio-track view. One entry per (TrackGroup, trackIndex) pair
 * so users can pick a specific codec variant if the stream offers both
 * e.g. English AC-3 and English AAC. Most IPTV streams only publish one
 * track per language and this collapses to the common case.
 */
private data class AudioTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val language: String?,
    val label: String?,
    val displayName: String,
    val selected: Boolean,
)

@UnstableApi
@Composable
private fun rememberAudioTracks(player: Player): List<AudioTrack> {
    var tracks by remember { mutableStateOf(readAudioTracks(player)) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onTracksChanged(t: Tracks) {
                    tracks = readAudioTracks(player)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return tracks
}

@UnstableApi
private fun readAudioTracks(player: Player): List<AudioTrack> {
    val groups = player.currentTracks.groups
    val out = mutableListOf<AudioTrack>()
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val fmt = group.getTrackFormat(i)
            val lang = fmt.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            val label = fmt.label?.takeIf { it.isNotBlank() }
            val codec = fmt.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT)
            val langName = lang?.let { languageDisplayName(it) }
            val base = label ?: langName ?: codec ?: "Track ${out.size + 1}"
            val suffix =
                buildList {
                    if (label != null && langName != null && langName != label) add(langName)
                    if (codec != null && base != codec) add(codec)
                }.joinToString(" · ")
            val displayName = if (suffix.isEmpty()) base else "$base  ·  $suffix"
            out +=
                AudioTrack(
                    group = group,
                    trackIndex = i,
                    language = lang,
                    label = label,
                    displayName = displayName,
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
    // Use setPreferredAudioLanguage when a language code is present — it
    // survives channel zaps (per-language, not per-track). Fall back to an
    // override when there's no language (e.g. single unlabeled track).
    val params = player.trackSelectionParameters.buildUpon()
    val lang = track.language
    if (!lang.isNullOrBlank()) {
        params.setPreferredAudioLanguage(lang)
        params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
    } else {
        params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        params.addOverride(
            androidx.media3.common.TrackSelectionOverride(
                track.group.mediaTrackGroup,
                track.trackIndex,
            ),
        )
    }
    player.trackSelectionParameters = params.build()
}

private fun languageDisplayName(code: String): String {
    return runCatching {
        val locale = Locale.forLanguageTag(code)
        locale.getDisplayLanguage(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }
    }.getOrElse { code.uppercase(Locale.ROOT) }
}

@Composable
private fun AudioTrackRow(
    track: AudioTrack,
    autoFocus: Boolean,
    focusRequester: FocusRequester?,
    onPick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) YancoPalette.BackgroundElevated else Color.Transparent,
            ).let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onPick() }
            .semantics { contentDescription = track.displayName + if (track.selected) ", selected" else "" }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (track.selected) "●" else "○",
            color = if (track.selected) YancoPalette.Accent else YancoPalette.TextMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = track.displayName,
            color = YancoPalette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OptionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (focused) YancoPalette.BackgroundElevated else Color.Transparent,
            ).let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .focusable(enabled = enabled, interactionSource = interaction)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) { onClick() }
            .semantics { contentDescription = "$label. $value" }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (enabled) YancoPalette.TextPrimary else YancoPalette.TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(12.dp))
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = value,
                color = if (enabled) YancoPalette.Accent else YancoPalette.TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}
