package com.yancotv.android.ui.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.focus.PlacedFocusAnchor
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoShapes
import com.yancotv.android.ui.theme.YancoType

/**
 * Vertical category rail. Sits between [AppSidebar] (left) and the content
 * panel (right) in Live TV / Movies / Series. Every category renders as a
 * [YancoShapes.HexPill] — a horizontal hex with long flat top + bottom edges
 * that fits a line of label text comfortably.
 *
 * Cascade-collapse contract:
 *   - This panel is mounted by [HomeScreen] only when `panelFocus !=
 *     PanelFocus.Content`. When the user enters the content area the panel
 *     unmounts entirely; the content panel slides left to fill the gap.
 *   - When focus enters any pill, the rail's [onPanelFocusChanged] callback
 *     fires so HomeScreen can collapse the sidebar to icon-only.
 *
 * Focus discipline:
 *   - Selection follows focus: the focused pill is the selected one. UP/DOWN
 *     scrolls + re-filters the content area.
 *   - CENTER on a pill commits the selection (same as focus) AND calls
 *     [onEnterContent] so a single press takes the user from picking to
 *     watching.
 *   - BACK on any pill calls [onExitToSidebar].
 *   - On mount the selected pill grabs focus via its [FocusRequester], so
 *     BACK from content lands on the pill the channels were filtered by.
 */
@Composable
fun CategoryRail(
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onEnterContent: () -> Unit,
    onExitToSidebar: () -> Unit,
    onPanelFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showFavorites: Boolean = true,
    selectedAnchor: PlacedFocusAnchor? = null,
) {
    val listState = rememberLazyListState()

    // Stable index math for the active pill — pinned Favorites + All sit
    // at the head of the visual list, then a divider (rendered as a
    // contentPadding gap, not an item), then the prioritised groups.
    val groupPos = remember(groups, selected) { groups.indexOf(selected) }
    val selectedIndex =
        remember(groupPos, selected, showFavorites) {
            val offset = if (showFavorites) 1 else 0
            when (selected) {
                FAVORITES_GROUP -> if (showFavorites) 0 else -1
                ALL_GROUPS -> offset
                else -> if (groupPos < 0) -1 else offset + 1 + groupPos
            }
        }
    // Re-key on `groups` so the scroll-bias gate doesn't carry a stale
    // selectedIndex across sections — without this, switching from a Live
    // section where Sports was at index 5 to a Movies section where the
    // initial "All" is at index 1 would still fire scroll for any new
    // index != 5, but never actually clear the fact that we already
    // "scrolled" once. Belt-and-braces: BrowseSection is wrapped in
    // key(contentType) which already remounts the rail, but this makes
    // the intent explicit and survives any future hoisting.
    var prevScrolled by remember(groups) { mutableStateOf(-1) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0 && selectedIndex != prevScrolled) {
            prevScrolled = selectedIndex
            // Bias scroll so the pill sits a row higher than dead-center —
            // matches LazyColumn's natural rest position for a focused item.
            runCatching { listState.animateScrollToItem(maxOf(0, selectedIndex - 2)) }
        }
    }

    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) { onPanelFocusChanged(hasFocus) }

    BackHandler(enabled = hasFocus) { onExitToSidebar() }

    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .width(ShellDim.categoriesPanelWidth)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            LocalYancoPalette.current.BackgroundElevated.copy(alpha = 0.78f),
                            LocalYancoPalette.current.BackgroundRaised.copy(alpha = 0.70f),
                            LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.82f),
                        ),
                    ),
                ).border(
                    width = 1.dp,
                    color = LocalYancoPalette.current.BorderSubtle.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(0.dp),
                ).padding(top = Space.md)
                // D-pad LEFT pops to the sidebar from anywhere in the rail.
                // RIGHT is intentionally NOT handled here — it's owned by each
                // HexPillRow so the pill's `group` is captured in the click
                // closure and onSelect(group) commits atomically with
                // onEnterContent(). Handling RIGHT at the Column level lost the
                // pill identity, so onSelect relied on the LaunchedEffect-driven
                // onFocused having already fired — which it hadn't, after a
                // section switch (Live → Movies), so the previous section's
                // selectedGroup leaked into the new section's content panel.
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                        onExitToSidebar()
                        true
                    } else {
                        false
                    }
                }.focusGroup(),
    ) {
        // Header label — gives the rail a "you are here" anchor; collapses
        // out of focus traversal because it isn't focusable.
        Text(
            text = "CATEGORIES",
            color = LocalYancoPalette.current.Accent,
            style = YancoType.Overline,
            modifier = Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.sm),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight(),
            contentPadding =
                PaddingValues(
                    start = Space.md,
                    end = Space.md,
                    top = Space.xs,
                    bottom = Space.section,
                ),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (showFavorites) {
                item(key = "__fav__") {
                    val isSelected = selected == FAVORITES_GROUP
                    HexPillRow(
                        label = "Favorites",
                        leading = YancoIcons.StarFilled,
                        selected = isSelected,
                        anchor = if (isSelected) selectedAnchor else null,
                        onClick = {
                            onSelect(FAVORITES_GROUP)
                            onEnterContent()
                        },
                        onFocused = { onSelect(FAVORITES_GROUP) },
                        onCommitAndEnter = {
                            onSelect(FAVORITES_GROUP)
                            onEnterContent()
                        },
                    )
                }
            }
            item(key = "__all__") {
                val isSelected = selected == ALL_GROUPS
                HexPillRow(
                    label = "All",
                    leading = YancoIcons.Guide,
                    selected = isSelected,
                    anchor = if (isSelected) selectedAnchor else null,
                    onClick = {
                        onSelect(ALL_GROUPS)
                        onEnterContent()
                    },
                    onFocused = { onSelect(ALL_GROUPS) },
                    onCommitAndEnter = {
                        onSelect(ALL_GROUPS)
                        onEnterContent()
                    },
                )
            }
            items(groups, key = { it }) { group ->
                val isSelected = selected == group
                HexPillRow(
                    label = group,
                    leading = null,
                    selected = isSelected,
                    anchor = if (isSelected) selectedAnchor else null,
                    onClick = {
                        onSelect(group)
                        onEnterContent()
                    },
                    onFocused = { onSelect(group) },
                    onCommitAndEnter = {
                        onSelect(group)
                        onEnterContent()
                    },
                )
            }
        }
    }
}

/**
 * Single pill in the rail. The hex outline is permanent; fill + glow react
 * to focus + selection. We deliberately don't separate "selected but not
 * focused" from "focused" the way the chip bar did, because in the rail
 * focus IS selection — moving focus already changed the active filter.
 */
@Composable
private fun HexPillRow(
    label: String,
    leading: ImageVector?,
    selected: Boolean,
    anchor: PlacedFocusAnchor?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onCommitAndEnter: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }

    val bg: Brush =
        when {
            focused ->
                Brush.verticalGradient(
                    listOf(LocalYancoPalette.current.Accent, LocalYancoPalette.current.AccentDeep),
                )
            selected ->
                Brush.verticalGradient(
                    listOf(
                        LocalYancoPalette.current.Accent.copy(alpha = 0.22f),
                        LocalYancoPalette.current.AccentDeep.copy(alpha = 0.14f),
                    ),
                )
            else -> SolidColor(LocalYancoPalette.current.BackgroundDeep.copy(alpha = 0.55f))
        }
    val border =
        when {
            focused -> LocalYancoPalette.current.FocusRing
            selected -> LocalYancoPalette.current.Accent.copy(alpha = 0.55f)
            else -> LocalYancoPalette.current.BorderSubtle
        }
    val fg by animateColorAsState(
        targetValue =
            when {
                focused -> Color.Black
                selected -> LocalYancoPalette.current.Accent
                else -> LocalYancoPalette.current.TextSecondary
            },
        label = "rail-pill-fg",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .shadow(
                    elevation = if (focused) 16.dp else 0.dp,
                    shape = YancoShapes.HexPill,
                    ambientColor = LocalYancoPalette.current.Accent,
                    spotColor = LocalYancoPalette.current.Accent,
                ).clip(YancoShapes.HexPill)
                .background(bg)
                .border(if (focused) 2.dp else 1.dp, border, YancoShapes.HexPill)
                // PlacedFocusAnchor (per native-android-mk skill rule): waits for
                // the pill's onPlaced callback before requestFocus(). Plain
                // FocusRequester races a freshly-mounted rail and silently fails
                // — exactly the bug the user hit when LEFT from the CTA opened
                // the rail visually but left focus stuck on the CTA, so DOWN
                // walked into the coverflow instead of the next pill.
                .then(anchor?.let { Modifier.placedFocus(it) } ?: Modifier)
                // RIGHT on a pill commits THIS pill's group + enters content.
                // Owning RIGHT here (not on the rail's outer Column) ensures the
                // pill identity is captured in scope — relying on the focused
                // pill's onFocused → onSelect having already fired loses the
                // race after a section switch (Live → Movies), where the rail
                // re-mounts with the previous section's selectedGroup until the
                // user navigates pills. Now RIGHT and CENTER share one path.
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) {
                        onCommitAndEnter()
                        true
                    } else {
                        false
                    }
                }.focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "Category: $label" }
                .padding(horizontal = Space.lg, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (leading != null) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        } else if (selected) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(if (focused) Color.Black else LocalYancoPalette.current.Accent),
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
