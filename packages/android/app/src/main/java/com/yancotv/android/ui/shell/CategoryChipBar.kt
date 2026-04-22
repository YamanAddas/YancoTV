package com.yancotv.android.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.YancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType

/**
 * Horizontal category filter that replaces the full-height CategoryFilterPanel.
 * Sits above the hero as a compact chip strip — categories inform the rail
 * below, they don't dominate the browse canvas.
 *
 * Focus behavior:
 *   - The selected chip owns the caller's [FocusRequester], so section
 *     entry and BACK-from-rail land on a real focusable leaf
 *   - The selected chip auto-scrolls into view so a 40-group catalogue
 *     remains usable
 *   - Specialized first chips — Favorites (heart) + All (grid) — stay
 *     pinned so the most-used filters are always one focus hop away.
 */
@Composable
fun CategoryChipBar(
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showFavorites: Boolean = true,
    externalSelectedFocus: FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    // `externalSelectedFocus` (when provided) lets the caller request focus
    // onto whichever chip is currently selected — used by BrowseShell to
    // snap the selector onto "All" when the user BACKs out of a filtered
    // group. When no external requester is supplied we fall back to a
    // local one so the selected chip can still be requested directly.
    val internalFirstFocus = remember { FocusRequester() }
    val firstItemFocus = externalSelectedFocus ?: internalFirstFocus

    val selectedIndex = remember(groups, selected, showFavorites) {
        selectedChipIndex(groups = groups, selected = selected, showFavorites = showFavorites)
    }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            runCatching { listState.scrollToItem(maxOf(0, selectedIndex - 1)) }
        }
    }

    LazyRow(
        state = listState,
        modifier = modifier
            .focusGroup(),
        contentPadding = PaddingValues(
            horizontal = Space.page,
            vertical = Space.xs,
        ),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showFavorites) {
            item(key = "__fav__") {
                val isSelected = selected == FAVORITES_GROUP
                Chip(
                    label = "Favorites",
                    selected = isSelected,
                    leading = YancoIcons.StarFilled,
                    focusRequester = if (isSelected) firstItemFocus else null,
                    onClick = { onSelect(FAVORITES_GROUP) },
                )
            }
        }
        item(key = "__all__") {
            val isSelected = selected == ALL_GROUPS
            Chip(
                label = "All",
                selected = isSelected,
                leading = YancoIcons.Guide,
                focusRequester = if (isSelected) firstItemFocus else null,
                onClick = { onSelect(ALL_GROUPS) },
            )
        }
        item(key = "__sep__") {
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .size(width = 1.dp, height = 20.dp)
                    .background(YancoPalette.BorderSubtle),
            )
        }
        items(groups, key = { it }) { group ->
            val isSelected = selected == group
            Chip(
                label = group,
                selected = isSelected,
                leading = null,
                focusRequester = if (isSelected) firstItemFocus else null,
                onClick = { onSelect(group) },
            )
        }
    }
}

internal fun selectedChipIndex(
    groups: List<String>,
    selected: String,
    showFavorites: Boolean,
): Int {
    val favoriteCount = if (showFavorites) 1 else 0
    return when (selected) {
        FAVORITES_GROUP -> if (showFavorites) 0 else -1
        ALL_GROUPS -> favoriteCount
        else -> {
            val g = groups.indexOf(selected)
            if (g < 0) -1 else favoriteCount + 2 + g
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    leading: androidx.compose.ui.graphics.vector.ImageVector?,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg = when {
        // Focused chip = colored selector — accent-tinted translucent wash
        // so the hero/backdrop still bleeds through and the ring + fill
        // read as "picked" at 10 ft (2026-04-22).
        focused -> YancoPalette.Accent.copy(alpha = 0.28f)
        selected -> YancoPalette.Accent.copy(alpha = 0.18f)
        else -> YancoPalette.BackgroundDeep.copy(alpha = 0.45f)
    }
    val border = when {
        focused -> YancoPalette.FocusRing
        selected -> YancoPalette.Accent.copy(alpha = 0.55f)
        else -> YancoPalette.BorderSubtle
    }
    val fg by animateColorAsState(
        targetValue = when {
            focused -> YancoPalette.TextPrimary
            selected -> YancoPalette.Accent
            else -> YancoPalette.TextSecondary
        },
        label = "chip-fg",
    )
    // Hex-inspired chip — leading angular bevel + rounded trailing cap so
    // each chip reads as part of the shell's angular family. Selected chip
    // picks up a 2dp accent rim and an LED-style dot on the leading edge.
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(YancoShapes.ChipBevel)
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, border, YancoShapes.ChipBevel)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = Space.lg, end = Space.lg, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (leading != null) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        } else if (selected) {
            // Small accent pip instead of an icon. Reinforces "picked" with
            // a touch of colour without a generic leading glyph.
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(Radius.pill))
                    .background(YancoPalette.Accent),
            )
        }
        Text(
            text = label,
            color = fg,
            style = if (selected) YancoType.LabelStrong else YancoType.Label,
            maxLines = 1,
        )
    }
}
