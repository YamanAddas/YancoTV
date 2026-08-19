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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.LocalYancoPalette

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
@Immutable
internal data class GlassTokens(
    val textPrimary: Color,
    val textSecondary: Color,
    val textDim: Color,
    /** Selection, focus rings, the hero fill. The theme's primary accent. */
    val accent: Color,
    /** Timeline played fill and the scrubber — related to [accent], not equal. */
    val accentSoft: Color,
    /** Lit glass rim. */
    val rim: Color,
    val surfaceTop: Color,
    val surfaceBottom: Color,
    val inset: Color,
    val border: Color,
)

/**
 * Resolve the glass tokens from the ACTIVE THEME.
 *
 * The brief specifies literal hexes — champagne #E8B87A, blue #5CA9FF, a fixed
 * #1A1E27 surface — and the first version of this file hardcoded them. That was
 * wrong for this app: palettes are user-switchable through ThemeController, so a
 * hardcoded set makes the player the one surface that ignores the user's choice,
 * and it drifts the moment any other palette is edited (user instruction,
 * 2026-08-19).
 *
 * What the brief actually specifies is a set of ROLES — one colour for
 * selection, a related-but-distinct one for the timeline, a three-step text
 * ramp, a lit rim over a translucent two-stop surface. Those roles map cleanly
 * onto YancoPalette, so the design survives intact and follows the theme:
 *
 *   champagne (selection / focus / hero) -> Accent
 *   blue (timeline played + scrubber)    -> AccentSoft
 *   text ramp                            -> TextPrimary / TextSecondary / TextMuted
 *   rim                                  -> PanelBorder, already a white alpha
 *   glass surface                        -> BackgroundElevated over BackgroundDeep
 *
 * The accent/accentSoft split is load-bearing rather than decorative: the brief
 * uses two hues so a focused control never competes with the track for the same
 * colour. Collapsing both to `Accent` would put an emerald scrubber on an
 * emerald track and lose the handle. AccentSoft is lighter than Accent in every
 * shipped palette, so the separation survives a theme swap.
 */
@Composable
internal fun glassTokens(): GlassTokens {
    val p = LocalYancoPalette.current
    return GlassTokens(
        textPrimary = p.TextPrimary,
        textSecondary = p.TextSecondary,
        textDim = p.TextMuted,
        accent = p.Accent,
        accentSoft = p.AccentSoft,
        rim = p.PanelBorder,
        surfaceTop = p.BackgroundElevated,
        surfaceBottom = p.BackgroundDeep,
        inset = p.BackgroundRaised,
        border = p.BorderSubtle,
    )
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
internal fun Modifier.glassSurface(shape: Shape, alpha: Float = 1f): Modifier {
    val t = glassTokens()
    return this
        .clip(shape)
        .background(
            // CSS `linear-gradient(145deg, …)`. 145deg in CSS runs clockwise from
            // "to top", landing as a down-and-right diagonal — the offsets below
            // reproduce that direction rather than a plain vertical, so the lit
            // edge sits top-left where the rim highlight is.
            Brush.linearGradient(
                colors = listOf(
                    t.surfaceTop.copy(alpha = 0.72f * alpha),
                    t.surfaceBottom.copy(alpha = 0.58f * alpha),
                ),
                start = Offset.Zero,
                end = Offset.Infinite,
            ),
        )
        .border(1.dp, t.rim, shape)
}

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
 * Control sizes for [variant], as a FRACTION OF SCREEN WIDTH.
 *
 * **The brief's numbers are physical pixels at 1920x1080, not dp**, and the
 * first version of this file read them as dp. On a Fire TV, which reports
 * density 2.0, that made every control exactly twice the specified size: the
 * hero measured 168px against a spec of 78-88px. It also overflowed the dock row
 * — the last control, the three-dot menu, was crushed from 104px to 48px — and
 * pushed the whole overlay to 57% of screen height against a 28% cap. One
 * mis-read unit, three symptoms.
 *
 * Sizing off screen WIDTH rather than dp fixes it at every density, which a
 * simple halving would not have. The ratios below are the brief's midpoints over
 * 1920, so a device reporting 960dp at density 2.0 and one reporting 1920dp at
 * density 1.0 both render the same PHYSICAL size — which is the only thing a
 * viewer three metres away actually perceives.
 *
 * The dp floors are an accessibility backstop for small windows, not part of the
 * design: they stop a phone in landscape shrinking a focus target below what a
 * D-pad user can see.
 */
@Immutable
internal data class HexMetrics(val size: Dp, val borderWidth: Dp)

@Composable
internal fun hexMetrics(variant: HexVariant): HexMetrics {
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    // Brief midpoints over the 1920px reference: hero 83, transport 56,
    // secondary 52, menu icon 30.
    val ratio = when (variant) {
        HexVariant.HERO -> 0.0432f
        HexVariant.TRANSPORT -> 0.0292f
        HexVariant.SECONDARY -> 0.0271f
        HexVariant.MENU_ICON -> 0.0156f
    }
    val floor = when (variant) {
        HexVariant.HERO -> 40.dp
        HexVariant.TRANSPORT -> 27.dp
        HexVariant.SECONDARY -> 25.dp
        HexVariant.MENU_ICON -> 18.dp
    }
    val size = (widthDp * ratio).dp.coerceAtLeast(floor)
    return HexMetrics(size = size, borderWidth = if (variant == HexVariant.HERO) 2.dp else 1.dp)
}

/**
 * Dock spacing, on the same fraction-of-width basis as [hexMetrics] and for the
 * same reason — the brief quotes 24-32px padding and 12-18px gaps at 1920.
 */
@Immutable
internal data class DockMetrics(val horizontalPadding: Dp, val gap: Dp, val verticalPadding: Dp)

@Composable
internal fun dockMetrics(): DockMetrics {
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    return DockMetrics(
        horizontalPadding = (widthDp * 0.0146f).dp.coerceAtLeast(10.dp),
        gap = (widthDp * 0.0078f).dp.coerceAtLeast(5.dp),
        verticalPadding = (widthDp * 0.0057f).dp.coerceAtLeast(4.dp),
    )
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
    /**
     * Override the width, keeping the tier's height. Null means square.
     *
     * The reference renders CC / AUDIO / SPEED / FIT as ELONGATED hexagons, and
     * it has to: the flat top of a regular hexagon spans only the middle 50% of
     * its width, so "AUDIO" at a legible size does not fit inside a 52dp one. A
     * stretched hexagon is the same silhouette, so the set still reads as one
     * family — [MidnightHex] is percentage-based precisely so it can take a
     * non-square box without a second shape to keep in sync.
     */
    width: Dp? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable (contentColor: Color) -> Unit,
) {
    val metrics = hexMetrics(variant)
    val t = glassTokens()
    val interaction = remember { MutableInteractionSource() }
    var isFocused by remember { mutableStateOf(false) }

    val fill: Brush = when {
        !enabled -> Brush.linearGradient(
            listOf(t.inset.copy(alpha = 0.45f), t.inset.copy(alpha = 0.3f)),
        )
        // Hero and selected controls take translucent champagne glass with a
        // restrained warm interior — translucent, so the frame still shows
        // through, which is what keeps it from reading as a solid button.
        variant == HexVariant.HERO || selected -> Brush.linearGradient(
            listOf(
                t.accent.copy(alpha = if (isFocused) 0.42f else 0.32f),
                t.accent.copy(alpha = 0.14f),
            ),
        )
        // Everything else is smoked navy with a thin edge highlight.
        else -> Brush.linearGradient(
            listOf(
                t.surfaceTop.copy(alpha = if (isFocused) 0.9f else 0.72f),
                t.surfaceBottom.copy(alpha = 0.58f),
            ),
        )
    }

    val ring = when {
        !enabled -> t.border.copy(alpha = 0.4f)
        isFocused -> t.accent
        selected || variant == HexVariant.HERO -> t.accent.copy(alpha = 0.55f)
        else -> t.rim
    }

    val contentColor = when {
        !enabled -> t.textDim
        variant == HexVariant.HERO || selected -> t.textPrimary
        isFocused -> t.accent
        else -> t.textSecondary
    }

    Box(
        modifier = modifier
            .size(width = width ?: metrics.size, height = metrics.size)
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
            // mergeDescendants — the control must expose ONE node, not a
            // described container wrapping an undescribed child. Without it the
            // icon-bearing controls published a second, unlabelled node: the
            // favourite control reported 67x96 bounds overlapping its neighbour
            // (every text-bearing control measured exactly right), and
            // uiautomator's own childNafCheck NPE'd walking the tree. A screen
            // reader would have hit the same shape — a button, then an anonymous
            // graphic inside it.
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                this.role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // clearAndSetSemantics on the CONTENT, not just mergeDescendants on the
        // parent. mergeDescendants alone still let an Icon child drag in
        // Compose's 48dp minimum-interactive-size expansion: the favourite
        // control reported 67x96 bounds — 96px is exactly 48dp — overlapping its
        // neighbour, while every text-bearing control measured 52x52 as drawn.
        // The drawn hexagon was always correct; the published node was not, and
        // a TV reader navigating by node would have found a target that did not
        // match what was on screen.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.clearAndSetSemantics {}) {
            content(contentColor)
        }
    }
}
