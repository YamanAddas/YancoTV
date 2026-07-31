package com.yancotv.android.ui.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.ui.focus.onEndwardKey
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoType
import com.yancotv.android.update.UpdateRepository

/**
 * Left navigation rail. Always visible — matches TiviMate's "three
 * panels side by side" shell so D-pad LEFT/RIGHT do nothing magic
 * except move focus between the rail, the groups list, and the
 * channel list.
 *
 * Focus return is driven by the explicit [activeRowFocus] requester
 * (bound by [bindActiveRowFocus] only to the current section's row).
 * When the user BACKs out of a section, the caller calls
 * `requestFocus()` and we land on the active row directly — no need
 * for [Modifier.focusRestorer] to remember last focus, because the
 * "last focus" target is identical to the active row by construction.
 * Stripping the restorer (MB-113) eliminates the race where
 * restorer-then-activeRowFocus could land out of order during the
 * sidebar's collapse→expand width animation.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppSidebar(
    current: AppSection,
    onSelect: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onMoveRight: () -> Unit = {},
    activeRowFocus: FocusRequester? = null,
    updateRepo: UpdateRepository = org.koin.compose.koinInject(),
) {
    // MK.30.4 — a pending update badges the Settings row, so the signal is
    // visible the moment the shell opens rather than only in a notification
    // the user may have swiped away (or never received, on a TV where the
    // shade is effectively invisible). Reactive off the same StateFlow that
    // Settings -> About renders, so installing the update clears it with no
    // extra plumbing.
    val pendingUpdate by updateRepo.info.collectAsState()
    // Cascade-collapse: when focus moves into the categories rail or content
    // panel, the sidebar shrinks to an icon strip so the user always knows
    // exactly one panel "owns" the screen. Width animates so the layout
    // doesn't snap; labels cross-fade so the transition reads as a graceful
    // collapse rather than a jump cut.
    val targetWidth = if (expanded) ShellDim.sidebarExpanded else ShellDim.sidebarCollapsed
    // MK.22.A.2 (MB-221): swapped from spring(0.85f, 320f) — that settled
    // in ~280-320 ms with an overshoot tail that read as a wobble at 10 ft
    // on a 168 dp delta (92 → 260). Width-only animations don't need
    // physics; tween(180, FastOutSlowIn) lands cleanly without overshoot
    // and matches the timing curve used elsewhere in Settings.
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "sidebar-width",
    )
    // MK.22.A.3 (MB-221): drive label visibility from the same width
    // animation as a single shared alpha — `(width - collapsed) /
    // (expanded - collapsed)` clamped to [0..1]. Replaces N per-row
    // `AnimatedVisibility(expandHorizontally / shrinkHorizontally)`
    // (~9 simultaneous layout-shifting animations on Fire TV). Reading
    // `width.value` here is fine — Compose snapshots it on each
    // recomposition the animation triggers.
    val expandSpan =
        (ShellDim.sidebarExpanded - ShellDim.sidebarCollapsed).value
            .takeIf { it > 0f } ?: 1f
    val labelAlpha =
        ((width - ShellDim.sidebarCollapsed).value / expandSpan)
            .coerceIn(0f, 1f)
    // Rail background: a softly lit vertical gradient against the
    // cinematic canvas so the three-column shell has real edge
    // definition without resorting to a hard divider line. Concept A
    // tints it greener so the rail visibly belongs to the emerald
    // palette, with a translucent floor so the cinematic backdrop
    // shows through — the rail frames the scene, it doesn't cover it.
    // Palette read is a composable op — hoist out of the `remember {}`
    // calculation lambda (MK.16.1). Brush re-derives only on palette
    // swap, so the MB-81 allocation concern still holds for steady state.
    val pal = LocalYancoPalette.current
    val brush =
        remember(pal) {
            Brush.verticalGradient(
                colors =
                listOf(
                    pal.BackgroundElevated.copy(alpha = 0.86f),
                    pal.BackgroundRaised.copy(alpha = 0.78f),
                    pal.BackgroundDeep.copy(alpha = 0.88f),
                ),
            )
        }
    Column(
        modifier =
        modifier
            .fillMaxHeight()
            .width(width)
            .background(brush)
            .border(1.dp, LocalYancoPalette.current.BorderSubtle.copy(alpha = 0.4f), RoundedCornerShape(0.dp))
            .padding(horizontal = Space.md, vertical = Space.md)
            // D-pad ENDWARD exits the sidebar — for browse sections HomeScreen
            // routes this into the categories rail; for non-browse sections
            // it lands inside the section's content. The rail is vertical
            // so the endward press has no in-rail meaning.
            //
            // MK.31.2: endward, not Key.DirectionRight. Arabic puts the
            // sidebar on the right, so "into the content" is a physical LEFT
            // press there — hardcoding RIGHT drove focus off the wrong edge.
            .onEndwardKey {
                onMoveRight()
                true
            }
            // MB-113: focusRestorer() removed. It races with the
            // explicit `activeRowFocus` binding (MB-106): on BACK / LEFT
            // from a section, the restorer fires first to land focus on
            // the *last-focused* descendant, then activeRowFocus fires
            // to target the *current-section* row. Usually they agree,
            // but during the COLLAPSED→EXPANDED width animation the
            // layout is in flux and the two requests can land out of
            // order — leaving the row focused but the interactionSource
            // lagging until the user nudges the D-pad ("detector won't
            // show until OK"). With explicit activeRowFocus we always
            // know which row to focus; the restorer is dead weight.
            .focusGroup(),
    ) {
        BrandMark(showWordmark = expanded)
        Spacer(Modifier.height(Space.md))
        // MB-99: take all remaining vertical space and scroll on overflow.
        // Logo stays at its full size (96.dp) per the user's ask; if the
        // viewport is short (phone landscape, scaled-down TVs), the
        // destinations list scrolls instead of clipping Settings off the
        // bottom. Focus traversal on TV brings the focused row into view
        // automatically through the scroll container.
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
            modifier =
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            AppSection.entries.forEach { section ->
                // MB-106: pass activeRowFocus *only* to the row matching
                // the current section. SidebarRow attaches it directly to
                // the inner Row that owns `.focusable(...)` — putting it
                // on the wrapper Box (v1) didn't reliably land focus on
                // the focusable descendant in Compose 1.7, so the
                // MutableInteractionSource never flipped and the focused
                // gradient + border didn't render until the user nudged
                // the D-pad. Binding rule extracted to
                // [bindActiveRowFocus] for unit-test coverage.
                SidebarRow(
                    section = section,
                    icon = iconFor(section),
                    selected = section == current,
                    showLabel = expanded,
                    labelAlpha = labelAlpha,
                    onClick = { onSelect(section) },
                    focusRequester = bindActiveRowFocus(section, current, activeRowFocus),
                    badged = badgeSection(section, pendingUpdate != null),
                )
            }
        }
    }
}

/**
 * Convert the sidebar accent bar's spring-driven [0..1] progress into the
 * vertical inset fraction used for padding. The spring animation spec has
 * damping < 1, so the raw progress can overshoot past 1 (or dip below 0 on
 * reversal). Compose's `padding` rejects negative Dp values with an
 * IllegalArgumentException — see regression test for the exact crash — so
 * we clamp here before the value reaches the layout node.
 *
 * Pulled out of the composable so it's unit-testable without spinning up
 * the Compose runtime.
 */
internal fun accentInsetFraction(springProgress: Float): Float = (1f - springProgress).coerceIn(0f, 1f)

/**
 * MB-106: which sidebar row, if any, should hold the [activeRowFocus]
 * requester for this composition.
 *
 * Rule: the requester binds to the row matching [current] — and only that
 * row. Returning the requester for non-current rows would let
 * `requestFocus()` land on whichever row Compose visited first, defeating
 * the whole point of the BACK-to-active-row UX. Returning `null` when no
 * requester was passed in keeps the SidebarRow caller path simple — there
 * is no "default" requester to fall back to.
 *
 * Pulled out of the composable so the binding contract is unit-testable
 * without spinning up the Compose runtime.
 */
/**
 * MK.30.4 — which sidebar row carries the pending-update dot.
 *
 * Only Settings, and only when an update is actually known. Settings is the
 * right host because it owns the About tab where the install lives, so the
 * badge points at the thing that resolves it — a dot on Home would tell the
 * user something is up without saying where to go.
 *
 * Pulled out of the composable so the rule is unit-testable without the
 * Compose runtime, matching [bindActiveRowFocus] next door.
 */
internal fun badgeSection(section: AppSection, updateAvailable: Boolean): Boolean = updateAvailable && section == AppSection.Settings

internal fun bindActiveRowFocus(section: AppSection, current: AppSection, activeRowFocus: FocusRequester?): FocusRequester? =
    if (section == current) activeRowFocus else null

private fun iconFor(section: AppSection): ImageVector = when (section) {
    AppSection.Home -> YancoIcons.Home
    AppSection.LiveTv -> YancoIcons.Live
    AppSection.Guide -> YancoIcons.Guide
    AppSection.Movies -> YancoIcons.Movies
    AppSection.Series -> YancoIcons.Series
    AppSection.Favorites -> YancoIcons.Favorites
    AppSection.Recordings -> YancoIcons.Recordings
    AppSection.Search -> YancoIcons.Search
    AppSection.Settings -> YancoIcons.Settings
}

@Composable
private fun BrandMark(showWordmark: Boolean) {
    // MK.29.5 — the asset follows the SHAPE OF THE SLOT, not just its size.
    //
    // Both states used to draw `ic_logo`, the 16:9 badge+wordmark lockup.
    // That is right when the sidebar is expanded (260dp — a wide slot), but
    // collapsed the slot is 92dp wide and near-square, and fitting a 16:9
    // strip into it scales the whole lockup down until the "Y" badge — the
    // only part still legible at that size — renders about 15dp across.
    // Measured on the Fire TV: the header box is 60x48dp and the badge
    // inside it was a smudge.
    //
    // Collapsed now draws `ic_logo_mark`: the badge alone, square, on real
    // alpha, so it fills the slot at ~56dp instead of a quarter of it.
    // Fit (never FillBounds) on both, so neither asset is stretched.
    // MK.31.11 — app_name, not a literal. Deliberately NOT translated (it is a
    // brand mark) but sourced from resources so there is one spelling of it.
    val brandName = stringResource(R.string.app_name)
    Image(
        painter = painterResource(
            id = if (showWordmark) R.drawable.ic_logo else R.drawable.ic_logo_mark,
        ),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
        Modifier
            .fillMaxWidth()
            .height(if (showWordmark) 96.dp else 56.dp)
            .padding(horizontal = Space.xs, vertical = Space.xs)
            .semantics { contentDescription = brandName },
    )
}

@Composable
private fun SidebarRow(
    section: AppSection,
    icon: ImageVector,
    selected: Boolean,
    showLabel: Boolean,
    labelAlpha: Float,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    badged: Boolean = false,
) {
    // MK.31.4 — resolved here because the semantics{} lambda below is not
    // composable, so stringResource cannot be called inside it.
    val sectionLabel = stringResource(section.labelRes)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val palette = LocalYancoPalette.current

    // Three visual states layered onto the hex-cut active row. The split
    // between "where am I" (focus) and "where I am rooted" (selected) has
    // to be loud — on Fire TV the user sits 3 metres away and a 0.12 alpha
    // delta on a green gradient is invisible. Rules locked here:
    //
    //   focused  → 2dp accent FRAME border. The frame is the *only* cue
    //              that's exclusive to focus. Same fill as `selected` so
    //              the row doesn't shift colour when focus arrives, only
    //              the outline appears. This matches the SettingsChip
    //              pattern (MB-110) and the SettingsScreen TabItem.
    //   selected → softer emerald wash + the persistent left accent rail
    //              bar (drawn by the outer Box). NO border — leaving the
    //              border slot exclusively for focus means BACK / LEFT
    //              into the sidebar always shows a visible frame on the
    //              active row, distinct from the wash that was already
    //              there. This is the MB-112 fix.
    //   idle     → transparent, muted foreground.
    val rowBrush =
        when {
            // Focused-and-selected and selected-but-not-focused share the
            // same fill so navigating onto the active row doesn't repaint
            // the gradient — only the focus frame (below) appears. This
            // is the explicit "selected wins on background, focus wins on
            // border" rule from SettingsChip.
            focused || selected ->
                Brush.horizontalGradient(
                    colors =
                    listOf(
                        palette.Accent.copy(alpha = 0.28f),
                        palette.Accent.copy(alpha = 0.10f),
                        Color.Transparent,
                    ),
                )
            else ->
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Transparent),
                )
        }
    // Focus FRAME — only painted while focused. Selected-without-focus
    // has no border at all; the left accent rail + fill carry the
    // breadcrumb cue. Selected-and-focused gets the focus frame on top
    // of the same fill, so it reads as "I'm rooted here AND my cursor is
    // here right now".
    val border = if (focused) palette.FocusRing else Color.Transparent
    val borderWidth = if (focused) 2.dp else 0.dp
    // MK.22.A.4 (MB-221): foreground colour was previously
    // `animateColorAsState` — at 10 ft on Fire TV a 200 ms tween between
    // TextSecondary → Accent → TextPrimary is invisible AND every row
    // holds its own animation instance, so when the sidebar collapses /
    // expands all N rows recompose with their animations restarting in
    // step. Hard switch keeps the snap legible and removes ~9 concurrent
    // colour animations from the expand path.
    val fg =
        when {
            focused -> palette.TextPrimary
            selected -> palette.Accent
            else -> palette.TextSecondary
        }
    // MK.22.A.4 (MB-221): accent rail inset was driven by an
    // `animateFloatAsState(spring 420f)` per row — same N-restart
    // problem. The bar's only on/off transition is on focus or
    // selection change which is a discrete user action, not a
    // continuous gesture; a hard switch is fine. `accentInsetFraction`
    // still caps at [0,1] so the inset math below is unchanged.
    val accentInsetFraction = accentInsetFraction(if (selected || focused) 1f else 0f)

    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        // Accent rail on the left edge marks the selected section even
        // when focus is elsewhere. Glow shadow makes the bar read as a
        // lit edge, not a flat stripe (Concept A's "you are here" cue).
        Box(
            modifier =
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .padding(vertical = Space.sm * accentInsetFraction)
                .shadow(
                    elevation = if (selected || focused) 12.dp else 0.dp,
                    shape = RoundedCornerShape(Radius.pill),
                    ambientColor = palette.Accent,
                    spotColor = palette.Accent,
                ).clip(RoundedCornerShape(Radius.pill))
                .background(
                    if (selected || focused) {
                        Brush.verticalGradient(
                            listOf(palette.AccentSoft, palette.Accent, palette.AccentDeep),
                        )
                    } else {
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                    },
                ),
        )
        // MB-113: shape stable across the sidebar's two width states.
        // CutCornerCardSmall uses an ABSOLUTE 16dp cut, which is 23 % of
        // the row width when the sidebar is collapsed (92dp) and only
        // 7 % when expanded (260dp) — same primitive, very different
        // visual character. The user observed "the main bar is two
        // shapes ... they show different selector". Replacing with a
        // 10dp rounded corner: same character at any aspect ratio,
        // focus FRAME reads identically in collapsed and expanded modes.
        // Cut-corner aesthetic is preserved everywhere else (chips,
        // cards, hero panels) — only the sidebar row, which morphs
        // width, opts out.
        val rowShape = remember { RoundedCornerShape(10.dp) }
        Row(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(start = Space.sm)
                .clip(rowShape)
                .background(rowBrush)
                .border(borderWidth, border, rowShape)
                .let { base ->
                    // Requester binds to the SAME node that's focusable,
                    // not a wrapper. requestFocus then lands on this
                    // node directly → interactionSource flips → focused
                    // gradient + ring render immediately (MB-106 v2).
                    if (focusRequester != null) base.focusRequester(focusRequester) else base
                }
                .focusable(interactionSource = interaction)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                )
                // MK.28.8 (MB-276) — announce selected state to TalkBack so
                // the current section is distinguishable from the rest.
                .semantics {
                    contentDescription = sectionLabel
                    this.selected = selected
                }
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            // MK.30.4 — pending-update dot. Overlaid on the icon rather than
            // placed after the label so it survives the collapsed sidebar,
            // where there is no label to sit beside. Box wraps only the icon
            // so the Row's spacedBy arrangement is unchanged for every
            // un-badged row (no 1px layout shift across the rail).
            Box {
                Icon(
                    imageVector = icon,
                    contentDescription = if (showLabel) null else sectionLabel,
                    tint = fg,
                    modifier = Modifier.size(22.dp),
                )
                if (badged) {
                    Box(
                        modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            // Nudged outside the glyph box so it reads as a
                            // badge on the icon, not part of the icon.
                            .offset(x = 3.dp, y = (-3).dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(palette.Premium)
                            // Ring against the row fill so the dot stays
                            // legible when the accent gradient sits behind it.
                            .border(1.dp, palette.BackgroundDeep, CircleShape),
                    )
                }
            }
            // MK.22.A.3 (MB-221): label cross-fade was previously
            // `AnimatedVisibility(expandHorizontally + fadeIn / shrinkHorizontally
            // + fadeOut)` PER ROW — that's ~9 simultaneous layout-shifting
            // animations during sidebar open. Each one re-measures its row
            // every frame, contending with the parent width tween. Replaced
            // with a single shared `labelAlpha` driven from the same width
            // animation in [AppSidebar] (parent), applied via `Modifier.alpha`.
            // The label widget is only emitted while showLabel-or-mid-collapse
            // (alpha > 0) so the collapsed state has no Text in the layout.
            if (showLabel || labelAlpha > 0f) {
                Text(
                    text = sectionLabel,
                    color = fg,
                    style = if (selected || focused) YancoType.LabelStrong else YancoType.Label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(labelAlpha),
                )
            }
        }
    }
}
