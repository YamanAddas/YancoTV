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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import com.yancotv.android.recording.RecordingService
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.player.ExternalPlayer
import com.yancotv.shared.epg.EpgRepository
import com.yancotv.shared.favorites.FavoritesRepository
import com.yancotv.shared.recording.RecordInput
import com.yancotv.shared.recording.RecordingFormat
import com.yancotv.shared.recording.RecordingStatus
import com.yancotv.shared.recording.RecordingsRepository
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
                        SleepPanel(
                            controller = controller,
                            onBack = onDismiss,
                            onEscapeUp = escapeToTabs,
                        )
                    SheetMode.RECORD ->
                        RecordPanel(
                            controller = controller,
                            onBack = onDismiss,
                            onEscapeUp = escapeToTabs,
                        )
                    SheetMode.FAV ->
                        FavoritesPanel(
                            controller = controller,
                            onBack = onDismiss,
                            onEscapeUp = escapeToTabs,
                        )
                    SheetMode.EXT ->
                        ExternalPanel(
                            controller = controller,
                            onBack = onDismiss,
                            onEscapeUp = escapeToTabs,
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

/**
 * Hex-orb tab / chrome chip. Aligns with the player dock's transport
 * orb language so the whole player surface reads as one visual family:
 *
 *   - focused → solid Accent → AccentDeep gradient + 16dp accent glow +
 *               accent border + black foreground (the cursor pulses)
 *   - active  → soft accent wash + accent-tinted border + 6dp glow +
 *               accent foreground (the selection sits)
 *   - idle    → translucent BackgroundDeep, hairline border, muted text
 *               (the rest fade into the sheet)
 *
 * Silhouette is now [YancoShapes.HexCapsule] — true horizontal hex with
 * angled side caps, matching CategoryRail pills + the player dock's
 * SecondaryChip. Glow uses the canonical .shadow(ambient/spot=Accent)
 * pattern applied BEFORE clip so it radiates outside the hex outline.
 *
 * Single focus target — `.clickable` already makes the node focusable
 * AND emits FocusInteraction events through the shared interaction
 * source, so `collectIsFocusedAsState()` still works. Stacking
 * `.focusable(interactionSource = …)` on top creates two siblings; the
 * outer wins focus, so CENTER never reaches the clickable that owns
 * `onClick` (the bug fixed in `da159cc`).
 */
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
    val shape = YancoShapes.HexCapsule
    val bgBrush =
        when {
            focused -> Brush.verticalGradient(listOf(pal.Accent, pal.AccentDeep))
            active ->
                Brush.verticalGradient(
                    listOf(
                        pal.Accent.copy(alpha = 0.22f),
                        pal.AccentDeep.copy(alpha = 0.14f),
                    ),
                )
            else -> SolidColor(pal.BackgroundDeep.copy(alpha = 0.55f))
        }
    val fg =
        when {
            focused -> Color(0xFF04130C)
            active -> pal.Accent
            else -> pal.TextMuted
        }
    val borderColor =
        when {
            focused -> pal.Accent
            active -> pal.Accent.copy(alpha = 0.55f)
            else -> pal.BorderSubtle
        }
    val glowElevation =
        when {
            focused -> 16.dp
            active -> 6.dp
            else -> 0.dp
        }
    Box(
        modifier =
            modifier
                .height(34.dp)
                .shadow(
                    elevation = glowElevation,
                    shape = shape,
                    ambientColor = pal.Accent,
                    spotColor = pal.Accent,
                )
                .clip(shape)
                .background(bgBrush)
                .border(if (focused) 2.dp else 1.dp, borderColor, shape)
                .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                .let { m ->
                    if (onClick != null) {
                        m.clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onClick() }
                    } else {
                        m
                    }
                }
                .semantics { contentDescription = label + if (active) ", selected" else "" }
                .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 9.sp,
            letterSpacing = 1.4.sp,
            fontWeight = if (active || focused) FontWeight.Black else FontWeight.Bold,
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
    val scope = rememberCoroutineScope()

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
            // Mini PointyHex orb — same silhouette as the dock transport
            // buttons, scaled down to a 38dp leading badge. Matches the
            // YancoShapes.PointyHex check chip on the right side of the
            // row, so the row reads as "orb · text · orb".
            val pal = LocalYancoPalette.current
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .clip(YancoShapes.PointyHex)
                        .background(
                            if (track.selected) {
                                Brush.verticalGradient(listOf(pal.Accent, pal.AccentDeep))
                            } else {
                                Brush.verticalGradient(
                                    listOf(pal.BackgroundElevated, pal.BackgroundDeep),
                                )
                            },
                        )
                        .border(
                            1.dp,
                            if (track.selected) pal.Accent else pal.BorderSubtle,
                            YancoShapes.PointyHex,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = track.language?.uppercase(Locale.ROOT)?.take(2) ?: "—",
                    color = if (track.selected) Color(0xFF04130C) else pal.TextSecondary,
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
    val scope = rememberCoroutineScope()

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
    val scope = rememberCoroutineScope()
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
    val scope = rememberCoroutineScope()
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

// ───── Sleep panel (MK.12b.1) ─────

/**
 * Sleep-timer picker. Off + four fixed durations (15 / 30 / 45 / 60 min)
 * + End-of-program. The fixed presets persist across channel zap; the
 * End-of-program option is only offered when the current item has a
 * `tvgId` and [EpgRepository.getNowProgramme] returns a programme — and
 * the timer is auto-cancelled by [PlaybackController.loadCurrent] when
 * the user zaps to a different channel (the program-end deadline only
 * makes sense for the channel it was set on).
 *
 * The active-timer callout above the rows mirrors [CurrentSpeedCallout]
 * — large numerals, accent gradient — so SLEEP feels visually parallel
 * to SPEED. Remaining time recomputes once per second from the
 * controller's deadline; no per-tick controller writes.
 */
@UnstableApi
@Composable
private fun SleepPanel(
    controller: PlaybackController,
    onBack: () -> Unit,
    onEscapeUp: () -> Unit,
    epg: EpgRepository = koinInject(),
) {
    val firstRowFocus = remember { FocusRequester() }
    val sleepState by controller.sleepTimer.collectAsState()
    val currentItem by controller.currentItem.collectAsState()
    val ioScope = rememberCoroutineScope()

    // End-of-program is only meaningful for live channels with a tvgId
    // AND a programme that covers "now". Look it up off the main thread
    // (SQLDelight blocks). null `endOfProgramMs` means the row is hidden.
    var endOfProgramMs by remember { mutableStateOf<Long?>(null) }
    val tvgId = currentItem?.tvgId?.takeIf { it.isNotBlank() }
    LaunchedEffect(tvgId) {
        endOfProgramMs =
            if (tvgId == null) {
                null
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        // EPG endTime is Unix seconds; convert to ms before subtracting.
                        epg.getNowProgramme(tvgId)?.let { (it.endTime * 1000L) - now }?.takeIf { it > 0L }
                    }
                }.getOrNull()
            }
    }

    // Panel initial focus lands on Off (the first row). DPAD UP escapes
    // to the tab strip — same pattern as Audio/Subs/Speed/Aspect.
    LaunchedEffect(Unit) {
        runCatching { firstRowFocus.requestFocus() }
    }

    SleepActiveCallout(state = sleepState)

    SectionKicker(text = "DURATION")
    HexOptionRow(
        leading = null,
        label = "Off",
        sub = "No sleep timer",
        selected = sleepState is SleepTimerState.Off,
        focusRequester = firstRowFocus,
        onEscapeUp = onEscapeUp,
        onPick = {
            controller.cancelSleepTimer()
            onBack()
        },
    )

    val presets =
        listOf(
            SleepTimerOption.MIN_15 to ("15 minutes" to "Pause after 15 min"),
            SleepTimerOption.MIN_30 to ("30 minutes" to "Pause after 30 min"),
            SleepTimerOption.MIN_45 to ("45 minutes" to "Pause after 45 min"),
            SleepTimerOption.MIN_60 to ("1 hour" to "Pause after 60 min"),
        )
    presets.forEach { (option, labels) ->
        val active = sleepState
        val isSelected = active is SleepTimerState.Active && active.option == option
        HexOptionRow(
            leading = null,
            label = labels.first,
            sub = labels.second,
            selected = isSelected,
            focusRequester = null,
            onPick = {
                controller.setSleepTimer(option)
                onBack()
            },
        )
    }

    val eopMs = endOfProgramMs
    if (eopMs != null) {
        val active = sleepState
        val isSelected =
            active is SleepTimerState.Active && active.option == SleepTimerOption.END_OF_PROGRAM
        HexOptionRow(
            leading = null,
            label = "End of programme",
            sub = "Pause when this show ends · ${formatSleepDuration(eopMs)} remaining",
            selected = isSelected,
            focusRequester = null,
            onPick = {
                // Re-read at pick time — the LaunchedEffect-cached value
                // can be a few seconds stale; freshness matters when the
                // programme is about to end.
                ioScope.launch {
                    val freshMs =
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                epg.getNowProgramme(tvgId ?: return@withContext null)
                                    ?.let { (it.endTime * 1000L) - now }
                                    ?.takeIf { it > 0L }
                            }
                        }.getOrNull()
                    if (freshMs != null) {
                        controller.setSleepTimer(SleepTimerOption.END_OF_PROGRAM, freshMs)
                    }
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun SleepActiveCallout(state: SleepTimerState) {
    val pal = LocalYancoPalette.current
    if (state !is SleepTimerState.Active) return

    // Re-tick once per second so the countdown stays live without touching
    // the controller. State recomputes locally from the (immutable) deadline.
    var remainingMs by remember(state.deadlineMs) {
        mutableStateOf((state.deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L))
    }
    LaunchedEffect(state.deadlineMs) {
        while (true) {
            remainingMs = (state.deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remainingMs <= 0L) break
            delay(1_000L)
        }
    }
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
                text = "TIMER · ${sleepOptionLabel(state.option)}",
                color = pal.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 1.8.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatSleepCountdown(remainingMs),
                color = pal.Accent,
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1.8).sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "PLAYBACK PAUSES WHEN THIS HITS ZERO",
                color = pal.TextMuted,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

private fun sleepOptionLabel(option: SleepTimerOption): String =
    when (option) {
        SleepTimerOption.MIN_15 -> "15 MIN"
        SleepTimerOption.MIN_30 -> "30 MIN"
        SleepTimerOption.MIN_45 -> "45 MIN"
        SleepTimerOption.MIN_60 -> "1 HOUR"
        SleepTimerOption.END_OF_PROGRAM -> "END OF PROGRAMME"
    }

/** Compact "X min" / "Y h Z min" style for an option's remaining duration. */
private fun formatSleepDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0L)
    if (totalMinutes < 60L) return "$totalMinutes min"
    val hours = totalMinutes / 60L
    val mins = totalMinutes % 60L
    return if (mins == 0L) "$hours h" else "$hours h $mins min"
}

/** mm:ss / h:mm:ss countdown for the active-timer callout. */
private fun formatSleepCountdown(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

// ───── Favorites panel (MK.13.1) ─────

/**
 * Add-to-favorites parity inside the player options sheet. The Home/Browse
 * star buttons already toggle via [FavoritesRepository.toggle]; this panel
 * exposes the same affordance from inside the player so the user doesn't
 * have to leave fullscreen to bookmark a title.
 *
 * Episode-aware: when the controller is playing a [Playable.Episode] the
 * synthesized "view" item's id is the episode id, which would FK-violate
 * `favorites.content_id` (FK'd to `content(id)`; episodes live in their
 * own table). The panel toggles the *series* id in that case — exactly
 * what the user means when they say "favourite this".
 *
 * Reactive — `isFavoriteFlow(id)` emits a fresh value every time any
 * write to the favorites table fires the SQLDelight notifier, so a
 * toggle from Browse/Home or this panel reflects everywhere immediately.
 */

// ───── Record panel (MK.14.2) ─────

/**
 * One-tap "Record now" / "Stop recording" surface for the channel
 * currently in the player. Wires the player options sheet's RECORD
 * tab into the Stage 3.1 recording engine.
 *
 * Behaviour:
 *   - If [PlaybackController.currentItem] is null → empty-state row.
 *   - If a recording for this channel is already in flight (status =
 *     RECORDING in the recordings table) → show "Stop recording";
 *     tapping fires [RecordingService.stop].
 *   - Otherwise → show "Start recording" with the detected format
 *     (HLS if the URL ends in `.m3u8`, MPEG-TS for everything else
 *     including Xtream catch-up `.ts`); tapping fires
 *     [RecordingService.start] and dismisses the sheet.
 *
 * The "browse past recordings" link belongs in [SheetMode.RECORD]'s
 * companion full-screen RecordingsScreen (MK.14.5) — out of scope
 * here.
 */
@UnstableApi
@Composable
private fun RecordPanel(
    controller: PlaybackController,
    onBack: () -> Unit,
    onEscapeUp: () -> Unit,
    recordings: RecordingsRepository = koinInject(),
) {
    val pal = LocalYancoPalette.current
    val firstRowFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val currentItem by controller.currentItem.collectAsState()
    // Reactive list of in-flight recordings — flips this panel's
    // start-vs-stop state when another surface starts / stops a
    // recording for the same channel.
    val inflight by remember { recordings.allFlow() }
        .collectAsState(initial = emptyList())
    val activeForChannel =
        inflight.firstOrNull { it.contentId == currentItem?.id && it.status == RecordingStatus.RECORDING }

    LaunchedEffect(currentItem?.id) {
        if (currentItem != null) runCatching { firstRowFocus.requestFocus() }
    }

    if (currentItem == null) {
        EmptyPanelLine("Nothing playing — start a stream to record it.")
        return
    }
    val item = currentItem!!
    val displayTitle =
        item.cleanTitle?.takeIf { it.isNotBlank() } ?: item.title

    SectionKicker(text = "CURRENT TITLE")
    Text(
        text = displayTitle,
        color = pal.TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    val typeHint =
        when (item.type) {
            ContentType.LIVE -> "Live channel"
            ContentType.MOVIE -> "Movie"
            ContentType.SERIES -> "Series"
        }
    Text(
        text = typeHint.uppercase(Locale.ROOT),
        color = pal.TextMuted,
        fontSize = 10.sp,
        letterSpacing = 1.6.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    if (activeForChannel != null) {
        SectionKicker(text = "IN PROGRESS")
        HexOptionRow(
            leading = null,
            label = "Stop recording",
            // Honest copy — there's no "library" yet (MK.14.5 lands the
            // sidebar Recordings destination). The file is on the device
            // at the path managed by Settings → Recordings.
            sub = "Saving to your device · Tap to finish",
            selected = true,
            focusRequester = firstRowFocus,
            onEscapeUp = onEscapeUp,
            onPick = {
                RecordingService.stop(context, activeForChannel.id)
                onBack()
            },
        )
        return
    }

    val format = detectRecordingFormat(item.streamUrl)
    val formatLabel =
        when (format) {
            RecordingFormat.HLS -> "HLS · auto-detected from .m3u8"
            RecordingFormat.MPEG_TS -> "MPEG-TS · direct stream"
        }

    SectionKicker(text = "START")
    HexOptionRow(
        leading = null,
        label = "Record this channel",
        sub = formatLabel + " · keep watching while it records",
        selected = false,
        focusRequester = firstRowFocus,
        onEscapeUp = onEscapeUp,
        onPick = {
            // **MK.14.8 (2026-04-26 pivot).** RecordingService routes this
            // to its live-tee path because the URL matches the channel
            // currently in PlaybackController — `RecordingDataSink.begin()`
            // taps the bytes ExoPlayer is already pulling, so the player
            // keeps playing while bytes stream to disk. The previous
            // architecture (`controller.stop()` + `activity.finish()` +
            // grace delay) opened a second HTTP GET to the same URL,
            // which 1-stream IPTV providers refused.
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
                "Recording started · keep watching or open Recordings.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            onBack()
        },
    )
}

/** Heuristic format detection for record-now from the player. HLS
 *  manifests end in `.m3u8` (sometimes with query string); everything
 *  else (raw `.ts`, Xtream catch-up paths, plain `.mp4`) is treated
 *  as MPEG-TS for the recorder's purposes — the MpegTsRecorder reads
 *  a continuous body, which works for both. DASH / encrypted formats
 *  go down the disabled-with-tooltip path in MK.14.7; v1.0 doesn't
 *  surface them in this panel. */
private fun detectRecordingFormat(streamUrl: String): RecordingFormat {
    val withoutQuery = streamUrl.substringBefore('?').substringBefore('#')
    return if (withoutQuery.endsWith(".m3u8", ignoreCase = true)) {
        RecordingFormat.HLS
    } else {
        RecordingFormat.MPEG_TS
    }
}

@Composable
private fun FavoritesPanel(
    controller: PlaybackController,
    onBack: () -> Unit,
    onEscapeUp: () -> Unit,
    favorites: FavoritesRepository = koinInject(),
) {
    val pal = LocalYancoPalette.current
    val firstRowFocus = remember { FocusRequester() }
    val currentItem by controller.currentItem.collectAsState()
    val currentEpisode by controller.currentEpisode.collectAsState()
    val ioScope = rememberCoroutineScope()

    // Pick the FK-safe content id for the current playback context:
    //   - Episode play → the *series* id (episodes have no content row)
    //   - Live channel / Movie → the item's own id
    //   - Nothing playing → null (panel renders empty state)
    val favoriteId =
        currentEpisode?.seriesId
            ?: currentItem?.id

    val displayTitle =
        currentEpisode?.let { ep ->
            // For episodes, prefer "<Series> · <Episode>" if the series
            // title is on the synthesized item. Fall back to the episode
            // title alone if not.
            val ep_title = ep.title
            currentItem?.cleanTitle?.takeIf { it.isNotBlank() }?.let { "$it · $ep_title" } ?: ep_title
        } ?: currentItem?.cleanTitle?.takeIf { it.isNotBlank() }
            ?: currentItem?.title

    // Pull `isFavorite` reactively so toggles from this panel OR from any
    // other surface (Home star button, Browse hover toggle) refresh the
    // displayed state without a focus round-trip. SQLDelight's `asFlow`
    // dispatches the underlying read to IO inside the repo.
    val isFav by remember(favoriteId) {
        if (favoriteId != null) {
            favorites.isFavoriteFlow(favoriteId)
        } else {
            kotlinx.coroutines.flow.flowOf(false)
        }
    }.collectAsState(initial = false)

    LaunchedEffect(favoriteId) {
        if (favoriteId != null) runCatching { firstRowFocus.requestFocus() }
    }

    if (favoriteId == null) {
        EmptyPanelLine("Nothing playing — start a stream to add it to favourites.")
        return
    }

    SectionKicker(text = "CURRENT TITLE")
    Text(
        text = displayTitle ?: "Unknown",
        color = pal.TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    val typeHint =
        when (currentItem?.type) {
            ContentType.LIVE -> "Live channel"
            ContentType.MOVIE -> if (currentEpisode != null) "Series" else "Movie"
            ContentType.SERIES -> "Series"
            null -> ""
        }
    if (typeHint.isNotBlank()) {
        Text(
            text = typeHint.uppercase(Locale.ROOT),
            color = pal.TextMuted,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }

    SectionKicker(text = if (isFav) "IN YOUR FAVOURITES" else "ADD TO FAVOURITES")
    HexOptionRow(
        leading = null,
        label = if (isFav) "Remove from favourites" else "Add to favourites",
        sub =
            if (isFav) {
                "Tap to remove · also in the Favourites rail"
            } else {
                "Pinned to the Favourites rail on Home"
            },
        selected = isFav,
        focusRequester = firstRowFocus,
        onEscapeUp = onEscapeUp,
        onPick = {
            // SQLDelight write — must dispatch to IO. The reactive
            // isFavoriteFlow above re-emits the new state, so this
            // composable re-renders without a manual refresh.
            ioScope.launch(Dispatchers.IO) {
                runCatching { favorites.toggle(favoriteId) }
            }
            onBack()
        },
    )
}

// ───── External player panel (MK.18.1) ─────

/**
 * "Open in external" — hands the current stream URL off to a third-party
 * video player via `Intent.ACTION_VIEW`. See [ExternalPlayer] for the
 * curated set (VLC / MX Player / Just Player) plus a chooser fallback.
 *
 * For VOD content the local position (ms) is passed as the `position`
 * extra — VLC and MX Player honour it for resume. Live streams pass null
 * (no offset to honour).
 *
 * The internal player is paused on hand-off so two streams aren't
 * consuming the network at once; the user returns to a paused player
 * which they can resume or stop. [onBack] dismisses the sheet after
 * launching.
 */
@UnstableApi
@Composable
private fun ExternalPanel(
    controller: PlaybackController,
    onBack: () -> Unit,
    onEscapeUp: () -> Unit,
) {
    val context = LocalContext.current
    val firstRowFocus = remember { FocusRequester() }
    val currentItem by controller.currentItem.collectAsState()
    // Re-query installed apps each time the sheet opens — the user could
    // have side-loaded VLC since the last time. `remember(currentItem?.id)`
    // would be too aggressive (the list doesn't depend on the title), but
    // a one-shot per panel-open via remember(Unit) is the right cadence.
    val installed = remember { ExternalPlayer.installed(context) }
    val streamUrl = currentItem?.streamUrl?.takeIf { it.isNotBlank() }

    LaunchedEffect(streamUrl) {
        if (streamUrl != null) runCatching { firstRowFocus.requestFocus() }
    }

    if (streamUrl == null) {
        EmptyPanelLine("Nothing playing — start a stream to hand it off to another player.")
        return
    }

    val isLive = currentItem?.type == ContentType.LIVE
    val positionMs =
        if (isLive) {
            null
        } else {
            controller.player.currentPosition.takeIf { it > 0L }
        }

    SectionKicker(
        text =
            if (installed.isEmpty()) {
                "NO KNOWN PLAYERS DETECTED"
            } else {
                "INSTALLED · ${installed.size} OPTION${if (installed.size == 1) "" else "S"}"
            },
    )

    installed.forEachIndexed { idx, app ->
        HexOptionRow(
            leading = null,
            label = app.displayName,
            sub = app.sub,
            selected = false,
            focusRequester = if (idx == 0) firstRowFocus else null,
            onEscapeUp = if (idx == 0) onEscapeUp else null,
            onPick = {
                // Pause the local stream before the external player gets
                // network priority — saves bandwidth and lets the user
                // come back to a stopped point.
                controller.player.pause()
                ExternalPlayer.launch(
                    context = context,
                    streamUrl = streamUrl,
                    positionMs = positionMs,
                    app = app,
                )
                onBack()
            },
        )
    }

    HexOptionRow(
        leading = null,
        label = "Choose another player…",
        sub = "Pick from any video app installed on this device",
        selected = false,
        focusRequester = if (installed.isEmpty()) firstRowFocus else null,
        onEscapeUp = if (installed.isEmpty()) onEscapeUp else null,
        onPick = {
            controller.player.pause()
            ExternalPlayer.launch(
                context = context,
                streamUrl = streamUrl,
                positionMs = positionMs,
                app = null,
            )
            onBack()
        },
    )
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
 * Hex-orb option row used across every wired panel. Layout:
 * `[leading?]  [label + sub]  <check?>`.
 *
 * Visual model parallels the dock's TransportButton + the tab strip's
 * HexCapsule so the whole player feels like one orb family:
 *
 *   - focused          → bright Accent → AccentDeep gradient + 18dp glow
 *                        + accent border + black foreground
 *   - selected (idle)  → soft accent wash + accent-tinted border +
 *                        6dp glow (the persistent "this is set" signal)
 *   - idle             → BackgroundElevated → BackgroundHover gradient,
 *                        hairline BorderSubtle, no glow
 *
 * Silhouette is [YancoShapes.HexCapsule] — angled side caps on a
 * full-width hex pill. The selected check chip is a [YancoShapes.PointyHex]
 * mini-orb so the badge inside the row reads as "the same family,
 * smaller scale" rather than a distinct glyph.
 *
 * Single focus target — see HexCapsule for the rationale.
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
    val shape = YancoShapes.HexCapsule
    val bgBrush =
        when {
            focused -> Brush.verticalGradient(listOf(pal.Accent, pal.AccentDeep))
            selected ->
                Brush.verticalGradient(
                    listOf(
                        pal.Accent.copy(alpha = 0.22f),
                        pal.AccentDeep.copy(alpha = 0.14f),
                    ),
                )
            else -> Brush.verticalGradient(listOf(pal.BackgroundElevated, pal.BackgroundHover))
        }
    val borderColor =
        when {
            focused -> pal.Accent
            selected -> pal.Accent.copy(alpha = 0.55f)
            else -> pal.BorderSubtle
        }
    val labelColor = if (focused) Color(0xFF04130C) else pal.TextPrimary
    val subColor = if (focused) Color(0xFF04130C).copy(alpha = 0.72f) else pal.TextMuted
    val glowElevation =
        when {
            focused -> 18.dp
            selected -> 6.dp
            else -> 0.dp
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = glowElevation,
                    shape = shape,
                    ambientColor = pal.Accent,
                    spotColor = pal.Accent,
                )
                .clip(shape)
                .background(bgBrush)
                .border(if (focused) 2.dp else 1.dp, borderColor, shape)
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
                .clickable(interactionSource = interaction, indication = null) { onPick() }
                .semantics { contentDescription = label + if (selected) ", selected" else "" }
                .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.1).sp,
            )
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    color = subColor,
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
                        .size(28.dp)
                        .clip(YancoShapes.PointyHex)
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
    Spacer(Modifier.height(8.dp))
}

private fun languageDisplayName(code: String): String {
    return runCatching {
        val locale = Locale.forLanguageTag(code)
        locale.getDisplayLanguage(Locale.getDefault()).ifBlank { code.uppercase(Locale.ROOT) }
    }.getOrElse { code.uppercase(Locale.ROOT) }
}
