package com.yancotv.android.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType

/**
 * Left navigation rail. Always visible — matches TiviMate's "three
 * panels side by side" shell so D-pad LEFT/RIGHT do nothing magic
 * except move focus between the rail, the groups list, and the
 * channel list.
 *
 * [Modifier.focusRestorer] + [Modifier.focusGroup] together remember the
 * last focused row inside this rail, so when the user navigates away
 * and comes back, focus lands on whichever section they were on
 * instead of snapping to the first entry.
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
) {
    // Cascade-collapse: when focus moves into the categories rail or content
    // panel, the sidebar shrinks to an icon strip so the user always knows
    // exactly one panel "owns" the screen. Width animates so the layout
    // doesn't snap; labels cross-fade so the transition reads as a graceful
    // collapse rather than a jump cut.
    val targetWidth = if (expanded) ShellDim.sidebarExpanded else ShellDim.sidebarCollapsed
    val width by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 320f),
        label = "sidebar-width",
    )
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
                // D-pad RIGHT exits the sidebar — for browse sections HomeScreen
                // routes this into the categories rail; for non-browse sections
                // it lands inside the section's content. The rail is vertical
                // so RIGHT has no in-rail meaning.
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                        onMoveRight()
                        true
                    } else {
                        false
                    }
                }.focusRestorer()
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
                // the D-pad.
                SidebarRow(
                    section = section,
                    icon = iconFor(section),
                    selected = section == current,
                    showLabel = expanded,
                    onClick = { onSelect(section) },
                    focusRequester = if (section == current) activeRowFocus else null,
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

private fun iconFor(section: AppSection): ImageVector =
    when (section) {
        AppSection.Home -> YancoIcons.Home
        AppSection.LiveTv -> YancoIcons.Live
        AppSection.Guide -> YancoIcons.Guide
        AppSection.Movies -> YancoIcons.Movies
        AppSection.Series -> YancoIcons.Series
        AppSection.Favorites -> YancoIcons.Favorites
        AppSection.Search -> YancoIcons.Search
        AppSection.Settings -> YancoIcons.Settings
    }

@Composable
private fun BrandMark(showWordmark: Boolean) {
    // Shipped raster logo stretched to fill the sidebar width. Replaces the
    // old "Y tile + YancoTV / streaming suite" text block so the brand
    // reads as a crafted mark rather than a hand-wired monogram.
    //
    // When the sidebar collapses to icon-only mode the wordmark is too wide
    // to read so we shrink the logo strip to a square brand mark — Image
    // contentScale=Fit handles the rest. Height drops in lockstep so the
    // sidebar header doesn't leave a tall empty band above the rows.
    Image(
        painter = painterResource(id = R.drawable.ic_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(if (showWordmark) 96.dp else 56.dp)
                .padding(horizontal = Space.xs, vertical = Space.xs)
                .semantics { contentDescription = "YancoTV" },
    )
}

@Composable
private fun SidebarRow(
    section: AppSection,
    icon: ImageVector,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // Three visual states layered onto the hex-cut active row:
    //   focused  → emerald gradient + bright accent ring + lit edge
    //   selected → softer emerald wash, glow bar lit, no ring
    //   idle     → transparent, muted foreground
    val rowBrush =
        when {
            focused ->
                Brush.horizontalGradient(
                    colors =
                        listOf(
                            LocalYancoPalette.current.Accent.copy(alpha = 0.40f),
                            LocalYancoPalette.current.Accent.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                )
            selected ->
                Brush.horizontalGradient(
                    colors =
                        listOf(
                            LocalYancoPalette.current.Accent.copy(alpha = 0.28f),
                            LocalYancoPalette.current.Accent.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                )
            else ->
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Transparent),
                )
        }
    val border =
        when {
            focused -> LocalYancoPalette.current.FocusRing
            selected -> LocalYancoPalette.current.Accent.copy(alpha = 0.45f)
            else -> Color.Transparent
        }
    val fg by animateColorAsState(
        targetValue =
            when {
                focused -> LocalYancoPalette.current.TextPrimary
                selected -> LocalYancoPalette.current.Accent
                else -> LocalYancoPalette.current.TextSecondary
            },
        label = "sidebar-fg",
    )
    val accentBarHeight by animateFloatAsState(
        targetValue = if (selected || focused) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "sidebar-bar",
    )
    val accentInsetFraction = accentInsetFraction(accentBarHeight)

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
                        ambientColor = LocalYancoPalette.current.Accent,
                        spotColor = LocalYancoPalette.current.Accent,
                    ).clip(RoundedCornerShape(Radius.pill))
                    .background(
                        if (selected || focused) {
                            Brush.verticalGradient(
                                listOf(LocalYancoPalette.current.AccentSoft, LocalYancoPalette.current.Accent, LocalYancoPalette.current.AccentDeep),
                            )
                        } else {
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                        },
                    ),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = Space.sm)
                    .clip(YancoShapes.CutCornerCardSmall)
                    .background(rowBrush)
                    .border(1.dp, border, YancoShapes.CutCornerCardSmall)
                    .let { base ->
                        // Requester binds to the SAME node that's focusable,
                        // not a wrapper. requestFocus then lands on this
                        // node directly → interactionSource flips → focused
                        // gradient + ring render immediately (MB-106 v2).
                        if (focusRequester != null) base.focusRequester(focusRequester) else base
                    }
                    .focusable(interactionSource = interaction)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = if (showLabel) null else section.label,
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
            // Cross-fade the label so the collapsed icon-only state reads as
            // a graceful shrink, not a label suddenly disappearing on rebuild.
            // AnimatedVisibility keeps the icon stable while the label slides
            // in/out from the leading edge.
            AnimatedVisibility(
                visible = showLabel,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Text(
                    text = section.label,
                    color = fg,
                    style = if (selected || focused) YancoType.LabelStrong else YancoType.Label,
                    maxLines = 1,
                )
            }
        }
    }
}
