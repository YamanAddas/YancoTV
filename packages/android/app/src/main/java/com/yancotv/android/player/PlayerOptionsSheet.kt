package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.ResizeMode
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.compose.koinInject
import java.util.Locale

/**
 * Player-options side sheet — Concept A port (MK.16.sheet, 2026-04-24).
 *
 * Restructured from the MK.12a.1 bottom sheet into a right-anchored 720dp
 * side sheet that matches `docs/design/design_handoff_yancotv/designs/options-sheet.html`.
 * Opened via KEYCODE_MENU on the remote; dismissed by BACK or scrim-tap.
 * Playback is not paused while the sheet is up — the stream underneath
 * keeps running.
 *
 * The 10 tabs (Audio / Subs / Speed / Aspect / Sleep / Record / Fav / Ext /
 * Cast / Appearance) live at the top of the sheet head as hex-capsule
 * chips. BACK dismisses the sheet directly (no "return to root options
 * list" hop — there is no root list any more; the sheet opens directly on
 * whichever tab [PlayerActivity] last set).
 *
 * Wired panels (from MK.12a.2–.5):
 *   - [SheetMode.AUDIO] — track picker, persists language
 *   - [SheetMode.SUBS]  — track picker + external-file loader
 *   - [SheetMode.SPEED] — 6 preset rates, persists for VOD only
 *   - [SheetMode.ASPECT] — 4 fit modes (Fit / Fill / Zoom / Stretch)
 *
 * Stub panels (placeholder cards, shipping in later milestones):
 *   - [SheetMode.SLEEP]  — MK.12b.1 (sleep timer)
 *   - [SheetMode.RECORD] — MK.14    (DVR)
 *   - [SheetMode.FAV]    — MK.13.1 parity inside the sheet
 *   - [SheetMode.EXT]    — MK.18.1  (external player)
 *   - [SheetMode.CAST]   — MK.18.3  (cast / AirPlay)
 *   - [SheetMode.LOOK]   — MK.16.2  (theme + HDR target picker)
 */

/** Which panel the options sheet is currently showing. Top-level so
 *  [PlayerActivity] can hoist the state, persist the last-picked tab, and
 *  (pre-MK.16.sheet) route BACK. BACK handling now dismisses directly
 *  from any panel — no root-list hop. */
enum class SheetMode(val ordinal2: String, val tabLabel: String, val kicker: String, val title: String, val sub: String) {
    AUDIO(
        "01",
        "AUDIO",
        "01 · PLAYER · AUDIO",
        "Audio tracks",
        "Choose language, mix and described audio. Applies to current stream.",
    ),
    SUBS(
        "02",
        "SUBS",
        "02 · PLAYER · SUBTITLES",
        "Subtitles",
        "Enable captions, match a language, or load an external .srt.",
    ),
    SPEED(
        "03",
        "SPEED",
        "03 · PLAYER · PLAYBACK RATE",
        "Playback speed",
        "VOD speed persists across sessions. Live channels reset to 1.0× on zap.",
    ),
    ASPECT(
        "04",
        "ASPECT",
        "04 · PLAYER · ASPECT & ZOOM",
        "Aspect ratio",
        "Match the source or force a ratio.",
    ),
    SLEEP(
        "05",
        "SLEEP",
        "05 · PLAYER · SLEEP TIMER",
        "Sleep timer",
        "Stop playback and return to home after a chosen duration.",
    ),
    RECORD(
        "06",
        "RECORD",
        "06 · PLAYER · DVR",
        "Record programme",
        "Save this stream to cloud storage or local drive.",
    ),
    FAV(
        "07",
        "FAV",
        "07 · PLAYER · FAVORITES",
        "Add to favorites",
        "Save this title to one or more playlists.",
    ),
    EXT(
        "08",
        "EXTERNAL",
        "08 · PLAYER · EXTERNAL",
        "Open in external player",
        "Hand off the current stream URL to VLC, MX Player, Kodi, or a custom intent.",
    ),
    CAST(
        "09",
        "CAST",
        "09 · PLAYER · CAST",
        "Cast to another device",
        "Send the stream to a nearby Chromecast / AirPlay / DLNA receiver.",
    ),
    LOOK(
        "10",
        "APPEARANCE",
        "10 · PLAYER · APPEARANCE",
        "Player appearance",
        "Tune brightness, HDR target and on-screen information density.",
    ),
}

/** Width of the right-aligned sheet surface. Matches the 720px design
 *  artboard 1:1 — 720dp reads identical on 1080p TV and is still narrow
 *  enough on a 6" phone that the video peeks through on the left. */
private val SheetWidth = 720.dp

/** Hex-cut corner depth used across the sheet for all "card" surfaces —
 *  preview strips, callout cards, and grid chips. Matches the design's
 *  22dp diagonal. */
private val HexCardCornerDp = 22.dp

/** Smaller hex-cut used on `opt-row` list items — 14dp per the design
 *  CSS (`clip-path: polygon(14px 0, ...)`). */
private val HexRowCornerDp = 14.dp

@UnstableApi
@Composable
fun PlayerOptionsSheet(
    mode: SheetMode,
    onModeChange: (SheetMode) -> Unit,
    onDismiss: () -> Unit,
    onPickSubtitleFile: () -> Unit,
    controller: PlaybackController = koinInject(),
    prefs: AppPreferences = koinInject(),
) {
    val pal = LocalYancoPalette.current
    // Sheet-level FocusRequester attached to the currently active tab in
    // the head's TabStrip. Used by each panel's *first row* to route DPAD
    // UP back to the tab strip — that's the escape hatch users need to
    // switch tabs after the panel auto-focus has grabbed initial focus.
    //
    // We deliberately do NOT auto-request focus here on open. Each panel's
    // LaunchedEffect focuses its first row so users can pick immediately
    // without any extra key press. UP from the first row comes back here.
    // That split keeps "open → click first option" a single action AND
    // keeps the tab strip reachable (press UP once from any panel's first
    // row; then LEFT/RIGHT to switch tabs; DOWN to re-enter the panel via
    // Compose's natural focus search).
    val activeTabFocus = remember { FocusRequester() }
    val escapeToTabs: () -> Unit = { runCatching { activeTabFocus.requestFocus() } }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Scrim darkens the left 2/3 of the screen; sheet occupies the
                // right 720dp. Tap anywhere in the scrim (outside the sheet)
                // to dismiss.
                .background(Color.Black.copy(alpha = 0.55f))
                // Scrim dismiss must NOT pollute the focus tree — `.clickable`
                // creates a focusable target on TV that can swallow CENTER
                // before the sheet's tabs/rows ever see it. `pointerInput`
                // gives us touch dismiss without registering a focus target.
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .width(SheetWidth)
                    // Approximation of the design's frosted-glass: a very
                    // dark green base with slight translucency. True
                    // backdrop blur costs too much on Fire TV stick-class
                    // hardware — opaque-ish is the pragmatic fallback.
                    .background(Color(0xEE0A1410))
                    .border(1.dp, pal.BorderSubtle)
                    // Swallow scrim-level taps that originate inside the
                    // sheet — same focus-tree caveat as the scrim above:
                    // `.clickable` here would register a focus target that
                    // wins over the inner tabs/rows on TV.
                    .pointerInput(Unit) { detectTapGestures { /* consume — block scrim dismiss */ } },
        ) {
            SheetHead(
                mode = mode,
                onModeChange = onModeChange,
                pal = pal,
                activeTabFocus = activeTabFocus,
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when (mode) {
                    SheetMode.AUDIO ->
                        AudioPanel(
                            controller = controller,
                            prefs = prefs,
                            onBack = onDismiss,
                            onEscapeUp = escapeToTabs,
                        )
                    SheetMode.SUBS ->
                        SubtitlesPanel(
                            controller = controller,
                            prefs = prefs,
                            onBack = onDismiss,
                            onPickExternal = onPickSubtitleFile,
                            onEscapeUp = escapeToTabs,
                        )
                    SheetMode.SPEED ->
                        SpeedPanel(
                            controller = controller,
                            prefs = prefs,
                            onBack = onDismiss,
                            onEscapeUp = escapeToTabs,
                        )
                    SheetMode.ASPECT ->
                        AspectPanel(prefs = prefs, onBack = onDismiss, onEscapeUp = escapeToTabs)
                    SheetMode.SLEEP ->
                        StubPanel(
                            heading = "Sleep timer",
                            body = "Pick a duration and playback stops, returning to home. Cloud + local backups of partial progress.",
                            milestone = "MK.12b.1",
                        )
                    SheetMode.RECORD ->
                        StubPanel(
                            heading = "Record programme",
                            body = "Save this stream to Nextcloud / S3 / local drive. Scheduled recordings continue in the background.",
                            milestone = "MK.14",
                        )
                    SheetMode.FAV ->
                        StubPanel(
                            heading = "Add to favorites",
                            body = "Add this title to one or more playlists. Top-level shortcut from Home.",
                            milestone = "MK.13.1",
                        )
                    SheetMode.EXT ->
                        StubPanel(
                            heading = "Open in external",
                            body = "Hand off the URL to VLC, MX Player, Kodi, or a custom intent. Position is passed along; return picks up where you left off.",
                            milestone = "MK.18.1",
                        )
                    SheetMode.CAST ->
                        StubPanel(
                            heading = "Cast",
                            body = "Send the stream to a nearby Chromecast or AirPlay receiver. Playback stays in sync.",
                            milestone = "MK.18.3",
                        )
                    SheetMode.LOOK ->
                        StubPanel(
                            heading = "Player appearance",
                            body = "Brightness ceiling, HDR target (SDR / HDR10 / HDR10+ / Dolby Vision), accent swap, and overlay-info toggles.",
                            milestone = "MK.16.2",
                        )
                }
            }
            SheetFoot(pal = pal)
        }
    }
}

// ───── Sheet chrome ─────

@Composable
private fun SheetHead(
    mode: SheetMode,
    onModeChange: (SheetMode) -> Unit,
    pal: YancoPalette,
    activeTabFocus: FocusRequester,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HexCapsule(
                label = "PLAYER OPTIONS",
                active = true,
                focusRequester = null,
                onClick = null,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "◀  BACK",
                color = pal.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 1.4.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = mode.kicker,
            color = pal.Accent,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = mode.title,
            color = pal.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.6).sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = mode.sub,
            color = pal.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        TabStrip(active = mode, onSelect = onModeChange, activeTabFocus = activeTabFocus)
        Spacer(Modifier.height(2.dp))
    }
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(pal.BorderSubtle),
    )
}

@Composable
private fun TabStrip(
    active: SheetMode,
    onSelect: (SheetMode) -> Unit,
    activeTabFocus: FocusRequester,
) {
    // Two-row wrap — 10 tabs split 5+5 for legibility on a 720dp sheet.
    // Manually partitioned instead of using FlowRow to avoid pulling in
    // the `material3-window-size-class` artifact.
    val all = SheetMode.values().toList()
    val first = all.take(5)
    val second = all.drop(5)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(first, second).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { m ->
                    // Active tab receives the hoisted FocusRequester so
                    // [PlayerOptionsSheet]'s LaunchedEffect can land initial
                    // focus on it. As the user switches tabs with D-pad
                    // LEFT/RIGHT the requester re-attaches to the newly
                    // active tab — focus naturally follows the user.
                    HexCapsule(
                        label = m.tabLabel,
                        active = m == active,
                        focusRequester = if (m == active) activeTabFocus else null,
                        onClick = { onSelect(m) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HexCapsule(
    label: String,
    active: Boolean,
    focusRequester: FocusRequester?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val pal = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bgBrush =
        when {
            active -> Brush.verticalGradient(listOf(pal.Accent, pal.AccentDeep))
            focused -> Brush.verticalGradient(listOf(pal.BackgroundElevated, pal.BackgroundHover))
            else -> Brush.verticalGradient(listOf(pal.BackgroundRaised, pal.BackgroundRaised))
        }
    val fg =
        when {
            active -> Color(0xFF04130C)
            else -> if (focused) pal.TextPrimary else pal.TextMuted
        }
    val borderColor = if (active || focused) pal.Accent else pal.BorderSubtle
    Box(
        modifier =
            modifier
                .height(34.dp)
                .clip(hexRowShape(HexRowCornerDp))
                .background(bgBrush)
                .border(1.dp, borderColor, hexRowShape(HexRowCornerDp))
                .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                .let { m ->
                    if (onClick != null) {
                        // Single focus target only — `.clickable` already
                        // makes the node focusable AND emits FocusInteraction
                        // events through the shared `interaction` source, so
                        // `collectIsFocusedAsState()` still works. Stacking an
                        // explicit `.focusable(interactionSource = …)` on top
                        // creates two siblings; the outer wins focus, so
                        // CENTER never reaches the clickable that owns
                        // `onClick`. That was the symptom: tabs visibly took
                        // focus but CENTER did nothing.
                        m.clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onClick() }
                    } else {
                        m
                    }
                }
                .semantics { contentDescription = label + if (active) ", selected" else "" }
                .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
        )
    }
}

@Composable
private fun SheetFoot(pal: YancoPalette) {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(pal.BorderSubtle),
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "▲▼ CHOOSE  ·  ● SET  ·  ◀ BACK",
            color = pal.TextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.8.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        HexCapsule(
            label = "APPLIES TO THIS STREAM",
            active = false,
            focusRequester = null,
            onClick = null,
        )
    }
}

// ───── Hex shape helper ─────

/**
 * Hex-cut-corner `Shape` — trims the top-left and bottom-right corners
 * by `corner`dp, per the Concept A `opt-row` CSS. Top-right and
 * bottom-left stay square so focus rings read as axis-aligned rectangles
 * with a bevel, not a hexagon.
 *
 * `@Composable` because `GenericShape`'s callback only gets `(Size,
 * LayoutDirection)` — density has to be captured here and closed over.
 * `remember`ed on (corner, density) so the same Shape instance is
 * reused across recompositions.
 */
@Composable
private fun hexRowShape(corner: Dp): Shape {
    val density = LocalDensity.current
    return remember(corner, density) {
        val c = with(density) { corner.toPx() }
        GenericShape { size, _ ->
            moveTo(c, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - c)
            lineTo(size.width - c, size.height)
            lineTo(0f, size.height)
            lineTo(0f, c)
            close()
        }
    }
}

// ───── Audio panel ─────

@UnstableApi
@Composable
private fun AudioPanel(
    controller: PlaybackController,
    prefs: AppPreferences,
    onBack: () -> Unit,
    onEscapeUp: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    val audioTracks = rememberAudioTracks(controller.player)
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // Panel initial focus lands on the first row so the user can pick
    // immediately without extra key presses. The sheet-level
    // [PlayerOptionsSheet] no longer auto-requests the active tab on open
    // (which raced with this request); instead the first row consumes
    // DPAD UP via `onEscapeUp` to route focus back to the tab strip when
    // the user wants to switch tabs. See the focus model note in the
    // sheet composable.
    LaunchedEffect(audioTracks) {
        if (audioTracks.isNotEmpty()) runCatching { firstRowFocus.requestFocus() }
    }
    SectionKicker(text = "TRACKS · ${audioTracks.size} AVAILABLE")
    if (audioTracks.isEmpty()) {
        EmptyPanelLine("No audio tracks reported yet. Try again once playback starts.")
    } else {
        audioTracks.forEachIndexed { idx, track ->
            AudioTrackRow(
                track = track,
                focusRequester = if (idx == 0) firstRowFocus else null,
                onEscapeUp = if (idx == 0) onEscapeUp else null,
                onPick = {
                    applyAudioTrack(controller.player, track)
                    val lang = track.language
                    if (!lang.isNullOrBlank()) {
                        scope.launch { prefs.setAudioLanguage(lang) }
                    }
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun AudioTrackRow(
    track: AudioTrack,
    focusRequester: FocusRequester?,
    onEscapeUp: (() -> Unit)?,
    onPick: () -> Unit,
) {
    HexOptionRow(
        leading = {
            val pal = LocalYancoPalette.current
            Box(
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(hexRowShape(HexRowCornerDp))
                        .background(
                            if (track.selected) pal.Accent.copy(alpha = 0.22f) else pal.BackgroundElevated,
                        )
                        .border(
                            1.dp,
                            if (track.selected) pal.Accent else pal.BorderSubtle,
                            hexRowShape(HexRowCornerDp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = track.language?.uppercase(Locale.ROOT)?.take(2) ?: "—",
                    color = if (track.selected) pal.Accent else pal.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                )
            }
        },
        label = track.displayName.substringBefore("  ·  "),
        sub = track.displayName.substringAfter("  ·  ", missingDelimiterValue = ""),
        selected = track.selected,
        focusRequester = focusRequester,
        onEscapeUp = onEscapeUp,
        onPick = onPick,
    )
}

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

// ───── Subtitles panel ─────

/**
 * Subtitle track picker. Off / embedded tracks / "Load external file…".
 * Behaviour preserved from MK.12a.3: Off clears overrides + disables the
 * text type; picking a track with a language sets preferredTextLanguage
 * (so same lang auto-selects next zap); unlabeled tracks use an override.
 * External subs delegate to [onPickExternal] — PlayerActivity owns the
 * SAF launcher.
 */
@UnstableApi
@Composable
private fun SubtitlesPanel(
    controller: PlaybackController,
    prefs: AppPreferences,
    onBack: () -> Unit,
    onPickExternal: () -> Unit,
    onEscapeUp: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    val textTracks = rememberTextTracks(controller.player)
    val textDisabled = rememberTextDisabled(controller.player)
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // Panel initial focus lands on the first row ("Off"). See AudioPanel
    // for the model note — DPAD UP on the first row escapes to the tab
    // strip.
    LaunchedEffect(Unit) {
        runCatching { firstRowFocus.requestFocus() }
    }
    SectionKicker(text = "LANGUAGE · ${textTracks.size} EMBEDDED")

    val offSelected = textDisabled || textTracks.none { it.selected }
    HexOptionRow(
        leading = null,
        label = "Off",
        sub = "No subtitles",
        selected = offSelected,
        focusRequester = firstRowFocus,
        onEscapeUp = onEscapeUp,
        onPick = {
            applyTextSelection(controller.player, null)
            scope.launch { prefs.setSubtitleLanguage("") }
            onBack()
        },
    )

    textTracks.forEach { track ->
        HexOptionRow(
            leading = null,
            label = track.displayName.substringBefore("  ·  "),
            sub = track.displayName.substringAfter("  ·  ", missingDelimiterValue = ""),
            selected = !textDisabled && track.selected,
            focusRequester = null,
            onPick = {
                applyTextSelection(controller.player, track)
                val lang = track.language
                if (!lang.isNullOrBlank()) {
                    scope.launch { prefs.setSubtitleLanguage(lang) }
                }
                onBack()
            },
        )
    }

    HexOptionRow(
        leading = null,
        label = "Load external file…",
        sub = "USB · Network share · URL",
        selected = false,
        focusRequester = null,
        onPick = { onPickExternal() },
    )
}

private data class TextTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val language: String?,
    val label: String?,
    val displayName: String,
    val selected: Boolean,
)

@UnstableApi
@Composable
private fun rememberTextTracks(player: Player): List<TextTrack> {
    var tracks by remember { mutableStateOf(readTextTracks(player)) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onTracksChanged(t: Tracks) {
                    tracks = readTextTracks(player)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return tracks
}

@UnstableApi
@Composable
private fun rememberTextDisabled(player: Player): Boolean {
    var disabled by remember { mutableStateOf(readTextDisabled(player)) }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onTrackSelectionParametersChanged(
                    params: androidx.media3.common.TrackSelectionParameters,
                ) {
                    disabled = params.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                }
            }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return disabled
}

@UnstableApi
private fun readTextDisabled(player: Player): Boolean =
    player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

@UnstableApi
private fun readTextTracks(player: Player): List<TextTrack> {
    val groups = player.currentTracks.groups
    val out = mutableListOf<TextTrack>()
    for (group in groups) {
        if (group.type != C.TRACK_TYPE_TEXT) continue
        for (i in 0 until group.length) {
            // Deliberately don't gate on Tracks.Group.isTrackSupported(i) —
            // same reason as pre-refactor: the strict variant hides PGS /
            // VobSub / DVB text tracks that the renderer can actually
            // render.
            val fmt = group.getTrackFormat(i)
            val lang = fmt.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            val label = fmt.label?.takeIf { it.isNotBlank() }
            val langName = lang?.let { languageDisplayName(it) }
            val base = label ?: langName ?: "Track ${out.size + 1}"
            val suffix =
                buildList {
                    if (label != null && langName != null && langName != label) add(langName)
                }.joinToString(" · ")
            val displayName = if (suffix.isEmpty()) base else "$base  ·  $suffix"
            out +=
                TextTrack(
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
private fun applyTextSelection(
    player: Player,
    track: TextTrack?,
) {
    val params = player.trackSelectionParameters.buildUpon()
    if (track == null) {
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
    } else {
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val lang = track.language
        if (!lang.isNullOrBlank()) {
            params.setPreferredTextLanguage(lang)
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            params.addOverride(
                androidx.media3.common.TrackSelectionOverride(
                    track.group.mediaTrackGroup,
                    track.trackIndex,
                ),
            )
        }
    }
    player.trackSelectionParameters = params.build()
}

// ───── Speed panel ─────

@UnstableApi
@Composable
private fun SpeedPanel(
    controller: PlaybackController,
    prefs: AppPreferences,
    onBack: () -> Unit,
    onEscapeUp: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
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
    // Panel initial focus lands on the first preset row. See AudioPanel
    // for the model note — DPAD UP on the first row escapes to the tab
    // strip. The CurrentSpeedCallout above it is display-only (no
    // `focusable`) so focus search skips it cleanly.
    LaunchedEffect(Unit) {
        runCatching { firstRowFocus.requestFocus() }
    }

    // Current-speed callout card (hex-cut, gradient accent tint).
    CurrentSpeedCallout(current = currentSpeed)

    SectionKicker(text = "PRESETS")
    SPEED_PRESETS.forEachIndexed { idx, speed ->
        HexOptionRow(
            leading = null,
            label = formatSpeed(speed),
            sub =
                when (speed) {
                    1.0f -> "Normal"
                    else -> "Pitch-corrected"
                },
            selected = kotlin.math.abs(currentSpeed - speed) < 0.01f,
            focusRequester = if (idx == 0) firstRowFocus else null,
            onEscapeUp = if (idx == 0) onEscapeUp else null,
            onPick = {
                controller.player.setPlaybackSpeed(speed)
                val item = controller.currentItem.value
                if (item != null && item.type != com.yancotv.shared.types.ContentType.LIVE) {
                    scope.launch { prefs.setSpeed(speed) }
                }
                onBack()
            },
        )
    }
}

@Composable
private fun CurrentSpeedCallout(current: Float) {
    val pal = LocalYancoPalette.current
    val calloutBrush =
        remember(pal) {
            Brush.linearGradient(
                colors =
                    listOf(
                        pal.Accent.copy(alpha = 0.12f),
                        pal.Accent.copy(alpha = 0.02f),
                    ),
            )
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(hexRowShape(HexCardCornerDp))
                .background(calloutBrush)
                .border(1.dp, pal.Accent.copy(alpha = 0.3f), hexRowShape(HexCardCornerDp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CURRENT",
                color = pal.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 1.8.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatSpeed(current),
                color = pal.Accent,
                fontSize = 68.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-2).sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "PITCH-CORRECTED · NO ARTIFACTS",
                color = pal.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

private val SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

@UnstableApi
private fun currentPlayerSpeed(player: Player): Float = player.playbackParameters.speed

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100f).toInt() / 100f
    return if (rounded == rounded.toInt().toFloat()) {
        "${rounded.toInt()}×"
    } else {
        val s = String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
        "$s×"
    }
}

// ───── Aspect panel ─────

@Composable
private fun AspectPanel(
    prefs: AppPreferences,
    onBack: () -> Unit,
    onEscapeUp: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    val playback by prefs.playbackFlow.collectAsState()
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    // Panel initial focus lands on the first fit-mode row. See AudioPanel
    // for the model note — DPAD UP on the first row escapes to the tab
    // strip.
    LaunchedEffect(Unit) {
        runCatching { firstRowFocus.requestFocus() }
    }

    SectionKicker(text = "FIT MODES")
    ResizeMode.values().forEachIndexed { idx, rm ->
        HexOptionRow(
            leading = null,
            label = rm.displayName,
            sub = aspectSublabel(rm),
            selected = playback.resizeMode == rm,
            focusRequester = if (idx == 0) firstRowFocus else null,
            onEscapeUp = if (idx == 0) onEscapeUp else null,
            onPick = {
                scope.launch { prefs.setResizeMode(rm) }
                onBack()
            },
        )
    }
}

private fun aspectSublabel(mode: ResizeMode): String =
    when (mode.displayName.uppercase(Locale.ROOT)) {
        "FIT" -> "Letterbox — preserves source"
        "FILL" -> "Crop — edges may clip"
        "ZOOM" -> "Fill vertical — crop sides"
        "STRETCH" -> "Distort — not recommended"
        else -> ""
    }

// ───── Stub panel (placeholder for future-milestone tabs) ─────

@Composable
private fun StubPanel(
    heading: String,
    body: String,
    milestone: String,
) {
    val pal = LocalYancoPalette.current
    val calloutBrush =
        remember(pal) {
            Brush.linearGradient(
                colors =
                    listOf(
                        pal.Accent.copy(alpha = 0.10f),
                        pal.Accent.copy(alpha = 0.02f),
                    ),
            )
        }
    Spacer(Modifier.height(20.dp))
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(hexRowShape(HexCardCornerDp))
                .background(calloutBrush)
                .border(1.dp, pal.Accent.copy(alpha = 0.3f), hexRowShape(HexCardCornerDp))
                .padding(horizontal = 28.dp, vertical = 28.dp),
    ) {
        Column {
            Text(
                text = "COMING IN $milestone",
                color = pal.Accent,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = heading,
                color = pal.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.4).sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = body,
                color = pal.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

// ───── Shared primitives ─────

@Composable
private fun SectionKicker(text: String) {
    val pal = LocalYancoPalette.current
    Text(
        text = text,
        color = pal.TextMuted,
        fontSize = 10.sp,
        letterSpacing = 1.8.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun EmptyPanelLine(text: String) {
    Text(
        text = text,
        color = LocalYancoPalette.current.TextMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

/**
 * Canonical hex-cut list row used across every wired panel. Layout:
 * `[leading?]  [label + sub]  <check>`. Focus visual = accent border +
 * accent-tinted gradient fill. Selected visual = steady accent border
 * and a filled check chip. Both can co-exist (focused on the currently
 * selected item).
 */
@Composable
private fun HexOptionRow(
    leading: (@Composable () -> Unit)?,
    label: String,
    sub: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onPick: () -> Unit,
    onEscapeUp: (() -> Unit)? = null,
) {
    val pal = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = hexRowShape(HexRowCornerDp)
    val bgBrush =
        when {
            focused ->
                Brush.horizontalGradient(
                    listOf(
                        pal.Accent.copy(alpha = 0.22f),
                        pal.Accent.copy(alpha = 0.04f),
                    ),
                )
            else -> Brush.verticalGradient(listOf(pal.BackgroundElevated, pal.BackgroundHover))
        }
    val borderColor =
        when {
            focused -> pal.Accent
            selected -> pal.Accent.copy(alpha = 0.8f)
            else -> pal.BorderSubtle
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bgBrush)
                .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
                .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                // DPAD UP escape: first-row rows (focusRequester != null)
                // route UP back to the active tab. Without this, Compose's
                // natural focus search from the top row of a panel has no
                // up-sibling and the user is stranded in the panel body.
                // Non-first rows leave `onEscapeUp` null so UP travels to
                // the row above via normal focus traversal.
                .let { m ->
                    if (onEscapeUp != null) {
                        m.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                onEscapeUp()
                                true
                            } else {
                                false
                            }
                        }
                    } else {
                        m
                    }
                }
                // Single focus target — see HexCapsule for the rationale.
                // `.clickable` is already focusable; double-stacking with
                // `.focusable(interactionSource = …)` made CENTER no-op
                // because the outer `.focusable` won focus and never
                // routed activation to the inner clickable.
                .clickable(interactionSource = interaction, indication = null) { onPick() }
                .semantics { contentDescription = label + if (selected) ", selected" else "" }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = pal.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.1).sp,
            )
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    color = pal.TextMuted,
                    fontSize = 11.sp,
                    letterSpacing = 1.0.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Box(
                modifier =
                    Modifier
                        .size(26.dp)
                        .clip(hexRowShape(8.dp))
                        .background(
                            Brush.verticalGradient(listOf(pal.Accent, pal.AccentDeep)),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    color = Color(0xFF04130C),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

private fun languageDisplayName(code: String): String {
    return runCatching {
        val locale = Locale.forLanguageTag(code)
        locale.getDisplayLanguage(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }
    }.getOrElse { code.uppercase(Locale.ROOT) }
}
