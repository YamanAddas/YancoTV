package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * Concept A port of the VOD player chrome. This file owns the non-controller
 * overlay states that sit on top of [androidx.media3.ui.PlayerView] inside
 * `activity_player.xml` via a `ComposeView` ViewStub.
 *
 * Scope for MK.16.player.vod.chrome: BUFFERING + ERROR only. The full
 * 5-state port (metadata / controls / scrub / nextup / end) lands in
 * follow-up slices — see `PRODUCTION_PLAN_NATIVE.md`.
 *
 * Theme note: per the MK.16.1 precedent, this overlay does not wrap itself
 * in `YancoTheme`. The `staticCompositionLocalOf { FrostedEmerald }` default
 * on `LocalYancoPalette` gives us the palette downstream; runtime theme
 * switching for overlays lands with MK.16.2.
 */
enum class VodChromeState {
    NONE,
    BUFFERING,
    ERROR,
}

/**
 * Diagnostic payload rendered by the buffering overlay's tile row. All
 * fields are pre-formatted strings — the composable does no math.
 */
data class VodChromeBuffering(
    val bitrate: String = "—",
    val bufferFill: String = "—",
    val latency: String = "—",
    val resolution: String = "—",
    val progressLabel: String = "BUFFERING",
)

/**
 * Error payload rendered by the error overlay. All fields pre-formatted.
 * [codeName] is the short machine identifier (e.g. `E_MEDIA_STALLED`) and
 * [codeNumeric] is the raw Media3 error code. Diagnostic block fields
 * are optional — blank strings render as "—".
 */
data class VodChromeError(
    val codeName: String = "E_PLAYBACK",
    val codeNumeric: String = "",
    val title: String = "Couldn't open this stream",
    val description: String = "",
    val sourceName: String = "",
    val streamPath: String = "",
    val remote: String = "",
    val attempt: String = "",
)

/**
 * Top-level dispatch. Stage-1 skeleton: renders nothing for any state —
 * later stages add the actual overlays. Kept as a no-op so the activity
 * wiring can land without the overlay bodies being finished.
 */
@Composable
fun VodPlayerChrome(
    state: VodChromeState,
    buffering: VodChromeBuffering,
    error: VodChromeError,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onPlaybackOptions: () -> Unit,
    onSwitchQuality: () -> Unit,
    onTrySource: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        VodChromeState.NONE -> Unit
        VodChromeState.BUFFERING -> BufferingOverlay(
            data = buffering,
            onRetry = onRetry,
            onPlaybackOptions = onPlaybackOptions,
            onBack = onBack,
            modifier = modifier,
        )
        VodChromeState.ERROR -> ErrorOverlay(
            data = error,
            onRetry = onRetry,
            onSwitchQuality = onSwitchQuality,
            onTrySource = onTrySource,
            onReport = onReport,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

// ----------------------------------------------------------------------
// Local shape + building-block helpers. Duplicated (not shared) with
// `PlayerOptionsSheet.kt` on purpose — the 12-line GenericShape is small
// enough that keeping it per-file beats hoisting to a util module and
// matches the isolation we want for overlay composables.
// ----------------------------------------------------------------------

/**
 * Hex-cut rectangle shape: trims top-left and bottom-right corners by
 * [corner] px to produce the diagonal "hex rail" silhouette used across
 * Concept A. Must be a @Composable helper because it reads density.
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

/**
 * Compact hex-clipped button. Primary variant fills with accent; ghost
 * variant uses a subtle border over the raised surface. `onClick` is
 * wired to the overlay's action callbacks.
 */
@Composable
private fun HexBtn(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYancoPalette.current
    val shape = hexRowShape(10.dp)
    // Audit-pass-7 (Bug D): focus chrome via shared interactionSource.
    // Previously `.clickable(onClick)` had no focus indicator, so D-pad
    // navigation between BUFFERING / ERROR overlay actions left no
    // visible cursor.
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        when {
            focused && primary -> palette.AccentSoft
            focused -> palette.BackgroundHover
            primary -> palette.Accent
            else -> palette.BackgroundRaised
        }
    val borderColor = if (focused) palette.FocusRing else palette.BorderSubtle
    val borderWidth = if (focused) 2.dp else 1.dp
    val fg = if (primary) Color.Black else palette.TextPrimary
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(bg)
            .border(borderWidth, borderColor, shape)
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.4.sp,
        )
    }
}

/**
 * Small hex chip used for the "BUFFERING · 38%" / "ERR · E_MEDIA_STALLED"
 * kicker pills. [tone] picks the foreground colour: accent / error /
 * muted. Background is always the deep raised surface.
 */
@Composable
private fun HexChip(
    label: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYancoPalette.current
    val shape = hexRowShape(6.dp)
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(shape)
            .background(palette.BackgroundRaised)
            .border(1.dp, palette.BorderSubtle, shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tone,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        )
    }
}

/**
 * One tile in the buffering diagnostic row. Hex-clipped, shows a small
 * uppercase label and a larger value. [accent] tints the value when
 * true (used for the live bitrate tile).
 */
@Composable
private fun DiagnosticTile(
    label: String,
    value: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYancoPalette.current
    val shape = hexRowShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(palette.BackgroundRaised)
            .border(1.dp, palette.BorderSubtle, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            color = palette.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.8.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            color = if (accent) palette.Accent else palette.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ----------------------------------------------------------------------
// BufferingOverlay — shown while the player reports STATE_BUFFERING.
// Matches the "Tuning the stream" state from the Concept A VOD handoff:
// kicker + headline + description + 4 diagnostic tiles + action row.
// The loader ring + inner Y hex from the design are approximated with
// a static hex badge for this first slice; animated variant can land
// in a follow-up once the static layout is verified on Fire TV.
// ----------------------------------------------------------------------

@Composable
private fun BufferingOverlay(
    data: VodChromeBuffering,
    onRetry: () -> Unit,
    onPlaybackOptions: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYancoPalette.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.BackgroundDeep.copy(alpha = 0.82f))
            .padding(horizontal = 56.dp, vertical = 48.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hex badge where the design has a rotating loader ring. Static
            // for now; rotation animation lands with the controls slice.
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(hexRowShape(20.dp))
                    .background(palette.BackgroundRaised)
                    .border(2.dp, palette.Accent, hexRowShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(hexRowShape(12.dp))
                        .background(palette.Accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Y",
                        color = Color.Black,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            HexChip(label = data.progressLabel, tone = palette.Accent)
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Tuning the stream",
                color = palette.TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Negotiating the best bitrate for your connection. This usually takes a moment.",
                color = palette.TextSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiagnosticTile(
                    label = "BITRATE",
                    value = data.bitrate,
                    accent = true,
                    modifier = Modifier.width(148.dp),
                )
                DiagnosticTile(
                    label = "BUFFER",
                    value = data.bufferFill,
                    accent = false,
                    modifier = Modifier.width(148.dp),
                )
                DiagnosticTile(
                    label = "LATENCY",
                    value = data.latency,
                    accent = false,
                    modifier = Modifier.width(148.dp),
                )
                DiagnosticTile(
                    label = "RES.",
                    value = data.resolution,
                    accent = false,
                    modifier = Modifier.width(148.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HexBtn(label = "RETRY", primary = true, onClick = onRetry)
                HexBtn(label = "PLAYBACK OPTIONS", primary = false, onClick = onPlaybackOptions)
                HexBtn(label = "CANCEL", primary = false, onClick = onBack)
            }
        }
    }
}

// ----------------------------------------------------------------------
// ErrorOverlay — shown when the player reports a playback exception.
// Concept A "Couldn't open this stream" state: hex icon + kicker +
// headline + description + monospace diagnostic block + action row.
// ----------------------------------------------------------------------

@Composable
private fun ErrorOverlay(
    data: VodChromeError,
    onRetry: () -> Unit,
    onSwitchQuality: () -> Unit,
    onTrySource: () -> Unit,
    onReport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalYancoPalette.current
    val kicker = buildString {
        append("ERR · ")
        append(data.codeName)
        if (data.codeNumeric.isNotBlank()) {
            append(" · ")
            append(data.codeNumeric)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.BackgroundDeep.copy(alpha = 0.9f))
            .padding(horizontal = 56.dp, vertical = 48.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hex icon with an X mark. Static layout; the animated error
            // pulse from the design lands in a follow-up.
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(hexRowShape(30.dp))
                    .background(palette.BackgroundRaised)
                    .border(2.dp, palette.Error.copy(alpha = 0.6f), hexRowShape(30.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "×",
                    color = palette.Error,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(24.dp))
            HexChip(label = kicker, tone = palette.Error)
            Spacer(Modifier.height(18.dp))
            Text(
                text = data.title,
                color = palette.TextPrimary,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
            )
            if (data.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = data.description,
                    color = palette.TextSecondary,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
            DiagnosticBlock(data = data)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HexBtn(label = "RETRY", primary = true, onClick = onRetry)
                HexBtn(label = "SWITCH TO 1080P", primary = false, onClick = onSwitchQuality)
                HexBtn(label = "TRY ANOTHER SOURCE", primary = false, onClick = onTrySource)
                HexBtn(label = "REPORT ISSUE", primary = false, onClick = onReport)
            }
            Spacer(Modifier.height(12.dp))
            HexBtn(label = "BACK", primary = false, onClick = onBack)
        }
    }
}

/**
 * Monospace diagnostic block rendered under the error headline. Each row
 * is a `label: value` pair so the user has something concrete to share
 * when filing a report. Missing fields render as "—".
 */
@Composable
private fun DiagnosticBlock(data: VodChromeError) {
    val palette = LocalYancoPalette.current
    val shape = hexRowShape(10.dp)
    val rows = listOf(
        "source" to data.sourceName.ifBlank { "—" },
        "stream" to data.streamPath.ifBlank { "—" },
        "remote" to data.remote.ifBlank { "—" },
        "attempt" to data.attempt.ifBlank { "—" },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.BackgroundRaised)
            .border(1.dp, palette.BorderSubtle, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        rows.forEachIndexed { idx, (label, value) ->
            if (idx > 0) Spacer(Modifier.height(4.dp))
            Row {
                Text(
                    text = "$label:",
                    color = palette.TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    text = value,
                    color = palette.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
