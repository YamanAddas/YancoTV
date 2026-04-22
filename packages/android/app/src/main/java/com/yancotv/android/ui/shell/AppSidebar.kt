package com.yancotv.android.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.yancotv.android.ui.theme.YancoPalette
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
) {
    // Rail background: a softly lit vertical gradient against the
    // cinematic canvas so the three-column shell has real edge
    // definition without resorting to a hard divider line. Alpha so
    // the hero preview / cinematic backdrop shows through — the rail
    // frames the scene, it doesn't cover it (2026-04-22 translucent pass).
    val brush = remember {
        Brush.verticalGradient(
            colors = listOf(
                YancoPalette.BackgroundRaised.copy(alpha = 0.72f),
                YancoPalette.BackgroundDeep.copy(alpha = 0.82f),
            ),
        )
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(ShellDim.sidebarExpanded)
            .background(brush)
            .border(1.dp, YancoPalette.BorderSubtle.copy(alpha = 0.4f), RoundedCornerShape(0.dp))
            .padding(horizontal = Space.md, vertical = Space.xl)
            .focusRestorer()
            .focusGroup(),
    ) {
        BrandMark()
        Spacer(Modifier.height(Space.xxl))
        Column(
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            AppSection.entries.forEach { section ->
                SidebarRow(
                    section = section,
                    icon = iconFor(section),
                    selected = section == current,
                    onClick = { onSelect(section) },
                )
            }
        }
        // Push a subtle footer tag to the bottom — reinforces this is a
        // branded product shell, not a debug menu.
        Spacer(Modifier.fillMaxSize().weight(1f))
        Text(
            text = "YANCOTV+",
            color = YancoPalette.TextFaint,
            style = YancoType.Overline,
            modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
        )
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
internal fun accentInsetFraction(springProgress: Float): Float =
    (1f - springProgress).coerceIn(0f, 1f)

private fun iconFor(section: AppSection): ImageVector = when (section) {
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
private fun BrandMark() {
    // Shipped raster logo stretched to fill the sidebar width. Replaces the
    // old "Y tile + YancoTV / streaming suite" text block so the brand
    // reads as a crafted mark rather than a hand-wired monogram.
    Image(
        painter = painterResource(id = R.drawable.tv_banner),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = Space.xs, vertical = Space.xs)
            .semantics { contentDescription = "YancoTV" },
    )
}

@Composable
private fun SidebarRow(
    section: AppSection,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // Three visual states stack left-to-right in priority:
    //   focused  → accent-tinted translucent fill + bright ring + scale lift
    //   selected → accent pill background (low alpha), no ring
    //   idle     → transparent, muted foreground
    val bg = when {
        focused -> YancoPalette.Accent.copy(alpha = 0.22f)
        selected -> YancoPalette.Accent.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val border = when {
        focused -> YancoPalette.FocusRing
        else -> Color.Transparent
    }
    val fg by animateColorAsState(
        targetValue = when {
            focused -> YancoPalette.TextPrimary
            selected -> YancoPalette.Accent
            else -> YancoPalette.TextSecondary
        },
        label = "sidebar-fg",
    )
    val accentBarHeight by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "sidebar-bar",
    )
    val accentInsetFraction = accentInsetFraction(accentBarHeight)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        // Accent rail on the left edge marks the selected section even
        // when focus is elsewhere — TiviMate-style "you are here".
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = Space.sm * accentInsetFraction)
                .clip(RoundedCornerShape(Radius.pill))
                .background(if (selected) YancoPalette.Accent else Color.Transparent),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = Space.sm)
                .clip(RoundedCornerShape(Radius.control))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(Radius.control))
                .focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = section.label,
                color = fg,
                style = if (selected || focused) YancoType.LabelStrong else YancoType.Label,
            )
        }
    }
}
