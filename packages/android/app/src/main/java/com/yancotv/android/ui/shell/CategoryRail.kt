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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.ui.focus.PlacedFocusAnchor
import com.yancotv.android.ui.focus.onEndwardKey
import com.yancotv.android.ui.focus.onStartwardKey
import com.yancotv.android.ui.focus.placedFocus
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Radius
import com.yancotv.android.ui.theme.ShellDim
import com.yancotv.android.ui.theme.Space
import com.yancotv.android.ui.theme.YancoIcons
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
    // MK.20.3 — when non-null, render hierarchical rows (Parent + Leaf)
    // instead of the flat [groups] list. Parents toggle expand on
    // click/CENTER via [onToggleExpand]; Leafs commit selection like the
    // flat path. The flat [groups] parameter is ignored when rows is set
    // — call sites pass empty for symmetry.
    rows: List<CategoryRailRow>? = null,
    onToggleExpand: ((String) -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    // Stable index math for the active pill — pinned Favorites + All sit
    // at the head of the visual list, then a divider (rendered as a
    // contentPadding gap, not an item), then the groups (flat or
    // hierarchical). For the hierarchical path the index is computed
    // against [rows] so collapsing a parent doesn't strand the scroll
    // position past the new tail.
    val effectiveGroupCount = rows?.size ?: groups.size
    val groupPos =
        remember(rows, groups, selected) {
            if (rows != null) {
                rows.indexOfFirst { it is CategoryRailRow.Leaf && it.groupName == selected }
            } else {
                groups.indexOf(selected)
            }
        }
    val selectedIndex =
        remember(groupPos, selected, showFavorites, effectiveGroupCount) {
            val offset = if (showFavorites) 1 else 0
            when (selected) {
                FAVORITES_GROUP -> if (showFavorites) 0 else -1
                ALL_GROUPS -> offset
                else -> if (groupPos < 0) -1 else offset + 1 + groupPos
            }
        }
    // Re-key on `groups`/`rows` so the scroll-bias gate doesn't carry a
    // stale selectedIndex across sections. Same reason as the flat path:
    // BrowseSection is wrapped in key(contentType) which already remounts
    // the rail, but this makes the intent explicit and survives any
    // future hoisting. Hierarchical: re-keys when expand state changes,
    // which is the right time to re-bias scroll.
    var prevScrolled by remember(groups, rows) { mutableStateOf(-1) }
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
            // Track whether ANY descendant pill has focus. Drives both
            // [BackHandler] (BACK on a pill pops to sidebar — without
            // this, hasFocus stayed false forever and BACK fell through
            // to the system handler, exiting the app) and the
            // onPanelFocusChanged callback that lets HomeScreen mark
            // panelFocus=Categories when focus enters by routes other
            // than the explicit RIGHT-from-sidebar path.
            .onFocusChanged { hasFocus = it.hasFocus }
            // D-pad LEFT pops to the sidebar from anywhere in the rail.
            // RIGHT is intentionally NOT handled here — it's owned by each
            // HexPillRow so the pill's `group` is captured in the click
            // closure and onSelect(group) commits atomically with
            // onEnterContent(). Handling RIGHT at the Column level lost the
            // pill identity, so onSelect relied on the LaunchedEffect-driven
            // onFocused having already fired — which it hadn't, after a
            // section switch (Live → Movies), so the previous section's
            // selectedGroup leaked into the new section's content panel.
            // MK.31.2: startward, not Key.DirectionLeft — the sidebar is on
            // the right in RTL, so backing out of the rail is a physical
            // RIGHT press there.
            .onStartwardKey {
                onExitToSidebar()
                true
            }.focusGroup(),
    ) {
        // Header label — gives the rail a "you are here" anchor; collapses
        // out of focus traversal because it isn't focusable.
        Text(
            text = stringResource(R.string.cat_kicker),
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
                        label = stringResource(R.string.cat_favorites),
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
                    label = stringResource(R.string.cat_all),
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
            if (rows != null) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is CategoryRailRow.Leaf -> {
                            val isSelected = selected == row.groupName
                            HexPillRow(
                                label = row.label,
                                leading = if (row.indented) YancoIcons.ChevronRight else null,
                                selected = isSelected,
                                anchor = if (isSelected) selectedAnchor else null,
                                onClick = {
                                    onSelect(row.groupName)
                                    onEnterContent()
                                },
                                onFocused = { onSelect(row.groupName) },
                                onCommitAndEnter = {
                                    onSelect(row.groupName)
                                    onEnterContent()
                                },
                                trailingText = null,
                                indented = row.indented,
                            )
                        }
                        is CategoryRailRow.Parent -> {
                            // Parents are visual buckets — committing them
                            // toggles expand/collapse, never changes the
                            // active filter. Glyph reflects state, count
                            // badge shows children. Focus stays on the
                            // parent across toggle so D-pad continues from
                            // a stable position.
                            HexPillRow(
                                label = row.label,
                                leading = if (row.expanded) YancoIcons.ChevronDown else YancoIcons.ChevronRight,
                                selected = false,
                                anchor = null,
                                onClick = { onToggleExpand?.invoke(row.label) },
                                onFocused = {},
                                onCommitAndEnter = { onToggleExpand?.invoke(row.label) },
                                trailingText = row.childCount.toString(),
                                indented = false,
                            )
                        }
                    }
                }
            } else {
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
                        trailingText = null,
                        indented = false,
                    )
                }
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
    trailingText: String? = null,
    indented: Boolean = false,
) {
    // MK.31.11 — resolved here; the semantics{} lambda below is not
    // composable, so stringResource cannot be called inside it.
    val pillDesc = stringResource(R.string.cat_desc, label)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // MK.22.B.5: 100 ms debounce so D-pad arrow-spam scrolling pills
    // doesn't fire onFocused (and thus onSelect → StateFlow + DB query)
    // for every pill the focus passes through. LaunchedEffect cancels
    // its block when `focused` changes, so a pill the user passes
    // through in <100 ms never commits.
    LaunchedEffect(focused) {
        if (focused) {
            kotlinx.coroutines.delay(100L)
            onFocused()
        }
    }

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
            // MK.31.2: endward, not Key.DirectionRight. See DirectionalNav.
            .onEndwardKey {
                onCommitAndEnter()
                true
            }.focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            // MK.28.8 (MB-276) — announce selected state to TalkBack, same
            // as the phone twin CategoryChipBar.
            .semantics(mergeDescendants = true) {
                contentDescription = pillDesc
                this.selected = selected
            }
            .padding(
                start = if (indented) Space.section else Space.lg,
                end = Space.lg,
                top = Space.xs,
                bottom = Space.xs,
            ),
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
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                color = fg.copy(alpha = 0.7f),
                style = YancoType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
