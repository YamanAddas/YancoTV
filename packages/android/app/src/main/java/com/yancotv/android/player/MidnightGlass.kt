package com.yancotv.android.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * MK.34.2 — the "Midnight Lounge" surface language for the player chrome.
 *
 * One place for the tokens, the glass treatment and the hexagon, because the
 * brief asks for a design SYSTEM rather than values sprinkled across the dock,
 * the timeline and the options sheet. Three surfaces drifting apart is exactly
 * what produced the old chrome.
 *
 * **This is the Compose translation of a CSS brief.** The specification is
 * written in `clip-path`, `backdrop-filter` and custom properties; the intent is
 * followed exactly and the mechanism is Compose. Where the platform cannot do
 * the CSS thing at all, the divergence is documented at the point it happens —
 * see [glassSurface] for the one that matters.
 */
internal object MidnightGlass {
    // Brief tokens, verbatim. Named for the role rather than the hue so a later
    // retune does not leave "champagne" pointing at something blue.
    val BgPage = Color(0xFF111318)
    val BgSurface = Color(0xFF1A1E27)
    val BgInset = Color(0xFF151920)
    val Border = Color(0xFF2B3344)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFF9BA8BA)
    val TextDim = Color(0xFF6B7A8D)

    /** Current selection, focus rings, the hero control. */
    val Champagne = Color(0xFFE8B87A)

    /** Timeline played portion and navigation chevrons. */
    val Blue = Color(0xFF5CA9FF)

    /** Small active/status values ONLY — never a surface or a focus ring. */
    val Mint = Color(0xFF8BE0B4)

    /** Edge highlight that reads as a lit glass rim rather than a bevel. */
    val RimLight = Color(0xFFF5F7FA).copy(alpha = 0.16f)
    val InnerHighlight = Color.White.copy(alpha = 0.12f)
}

/**
 * The brief's hexagon, translated one-for-one from
 *
 *     clip-path: polygon(25% 0%, 75% 0%, 100% 50%, 75% 100%, 25% 100%, 0% 50%)
 *
 * Flat top and bottom, points at the left and right mid-edges. Deliberately NOT
 * `YancoShapes.PointyHex` (which is pointy-TOP) and not the cut-corner
 * `hexRowShape`: the brief names one silhouette for every control, and matching
 * it is the whole reason the dock reads as a set.
 *
 * Percentage-based, so one shape serves every size variant without a per-size
 * corner radius to keep in sync.
 */
internal val MidnightHex: Shape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    moveTo(size.width * 0.25f, 0f)
    lineTo(size.width * 0.75f, 0f)
    lineTo(size.width, size.height * 0.5f)
    lineTo(size.width * 0.75f, size.height)
    lineTo(size.width * 0.25f, size.height)
    lineTo(0f, size.height * 0.5f)
    close()
}

/**
 * The brief's glass treatment, minus the one property the platform cannot give.
 *
 * **There is no backdrop blur here, on any API level, and that is not an
 * oversight.** The brief asks for `backdrop-filter: blur(18px) saturate(130%)`.
 * Compose has no backdrop filter: `Modifier.blur` blurs a composable's OWN
 * content, not what is painted behind it, and it is API 31+ regardless. Blurring
 * what is actually behind these panels would mean blurring a `SurfaceView`
 * playing video from a separate ComposeView overlay — the video is not in this
 * view's draw pass at all, so there is nothing local to sample. Window-level
 * `setBackgroundBlurRadius` blurs behind a WINDOW, which the dock is not, and a
 * cached snapshot would smear a stale frame behind live motion.
 *
 * The Fire TV compounds it (API 28, and minSdk is 24), but the honest statement
 * is that this is an architecture limit, not a version gate — so no version
 * branch is pretended here.
 *
 * What IS implemented is everything else the brief specifies, and those are the
 * properties actually carrying "smoked midnight glass": the 145-degree two-stop
 * translucent gradient, the lit rim, and low enough opacity that the film stays
 * perceptible through the surface. What is lost is only the softening of the
 * frame behind it — edges behind the panel stay sharp instead of melting.
 *
 * @param alpha scales the treatment for surfaces meant to sit lighter on the
 *   frame (the timeline ribbon) than the dock does.
 */
@Composable
internal fun Modifier.glassSurface(shape: Shape, alpha: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        // CSS `linear-gradient(145deg, …)`. 145deg in CSS runs clockwise from
        // "to top", landing as a down-and-right diagonal — the offsets below
        // reproduce that direction rather than a plain vertical, so the lit
        // edge sits top-left where the rim highlight is.
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF232834).copy(alpha = 0.68f * alpha),
                Color(0xFF111318).copy(alpha = 0.55f * alpha),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
    )
    .border(1.dp, MidnightGlass.RimLight, shape)

/** Size + emphasis tiers. The brief is explicit that these must NOT be equal. */
internal enum class HexVariant {
    /** Play/pause. The largest thing in the dock, and the only champagne fill. */
    HERO,

    /** -10 / +10. Clearly smaller than the hero, clearly larger than secondary. */
    TRANSPORT,

    /** CC, AUDIO, SPEED, FIT, favourite, menu. */
    SECONDARY,

    /** The icon inside an options-sheet row. Smallest. */
    MENU_ICON,
}

/**
 * Control sizes for [variant] at the current screen width.
 *
 * The Compose analogue of the brief's `clamp()`: the reference proportions are
 * quoted at 1920x1080, and rather than hard-code that one resolution the tier is
 * scaled by how wide the window actually is and then clamped. A 1280x720 TV and
 * a phone in landscape both land somewhere sensible instead of overflowing.
 */
@Immutable
internal data class HexMetrics(val size: Dp, val borderWidth: Dp)

@Composable
internal fun hexMetrics(variant: HexVariant): HexMetrics {
    val widthDp = LocalConfiguration.current.screenWidthDp
    // 960dp is a 1920px screen at the 2.0 density Android TV reports; the ratio
    // is what makes this resolution-independent rather than the absolute value.
    val scale = (widthDp / 960f).coerceIn(0.72f, 1.15f)
    val base = when (variant) {
        HexVariant.HERO -> 84.dp
        HexVariant.TRANSPORT -> 56.dp
        HexVariant.SECONDARY -> 52.dp
        HexVariant.MENU_ICON -> 34.dp
    }
    val floor = when (variant) {
        HexVariant.HERO -> 60.dp
        HexVariant.TRANSPORT -> 42.dp
        HexVariant.SECONDARY -> 40.dp
        HexVariant.MENU_ICON -> 28.dp
    }
    val scaled = (base * scale).coerceAtLeast(floor)
    return HexMetrics(size = scaled, borderWidth = if (variant == HexVariant.HERO) 2.dp else 1.dp)
}

/**
 * The one hexagonal control every player surface uses.
 *
 * States are layered rather than exclusive so a focused hero still reads as the
 * hero: [selected] sets the fill, [focused] sets the ring, [enabled] drops both
 * to a dimmed treatment. The brief asks for a champagne outline "visible from a
 * couch" and explicitly warns off glow, bevels and 3D-game styling — so focus is
 * a 2dp champagne ring plus a small lift in fill opacity, and nothing else.
 *
 * @param contentDescription the accessible label. Required, not optional: these
 *   are custom Boxes, which are silent to TalkBack and the TV reader by default.
 */
@Composable
internal fun HexControl(
    variant: HexVariant,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable (contentColor: Color) -> Unit,
) {
    val metrics = hexMetrics(variant)
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }

    val fill: Brush = when {
        !enabled -> Brush.linearGradient(
            listOf(MidnightGlass.BgInset.copy(alpha = 0.45f), MidnightGlass.BgInset.copy(alpha = 0.3f)),
        )
        // Hero and selected controls take translucent champagne glass with a
        // restrained warm interior — translucent, so the frame still shows
        // through, which is what keeps it from reading as a solid button.
        variant == HexVariant.HERO || selected -> Brush.linearGradient(
            listOf(
                MidnightGlass.Champagne.copy(alpha = if (isFocused) 0.42f else 0.32f),
                MidnightGlass.Champagne.copy(alpha = 0.14f),
            ),
        )
        // Everything else is smoked navy with a thin edge highlight.
        else -> Brush.linearGradient(
            listOf(
                Color(0xFF232834).copy(alpha = if (isFocused) 0.85f else 0.68f),
                Color(0xFF111318).copy(alpha = 0.55f),
            ),
        )
    }

    val ring = when {
        !enabled -> MidnightGlass.Border.copy(alpha = 0.4f)
        isFocused -> MidnightGlass.Champagne
        selected || variant == HexVariant.HERO -> MidnightGlass.Champagne.copy(alpha = 0.55f)
        else -> MidnightGlass.RimLight
    }

    val contentColor = when {
        !enabled -> MidnightGlass.TextDim
        variant == HexVariant.HERO || selected -> MidnightGlass.TextPrimary
        isFocused -> MidnightGlass.Champagne
        else -> MidnightGlass.TextSecondary
    }

    Box(
        modifier = modifier
            .size(metrics.size)
            .clip(MidnightHex)
            .background(fill)
            .border(if (isFocused) 2.dp else metrics.borderWidth, ring, MidnightHex)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .then(
                if (enabled) {
                    // indication = null because the hexagon draws its own focus
                    // ring; the default ripple is rectangular and would bleed
                    // outside a non-rectangular clip. Same call shape as the
                    // dock's existing TransportButton, so focus behaviour on the
                    // leanback tree is unchanged.
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        content(contentColor)
    }
}
