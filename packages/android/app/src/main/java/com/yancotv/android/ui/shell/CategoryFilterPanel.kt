package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.YancoPalette

const val ALL_GROUPS = "__all__"

/**
 * Synthetic group id for the pinned "Favorites" row at the top of the
 * category rail (MK.8.3 spec). Selecting it swaps the content list's data
 * source from the paged `content` query to `FavoritesRepository.allForType`.
 */
const val FAVORITES_GROUP = "__favorites__"

@Composable
fun CategoryFilterPanel(
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showFavorites: Boolean = true,
    smartGrouping: Boolean = false,
    /**
     * When the shell reveals this panel, it calls `requestFocus()` on this
     * requester to move D-pad focus here. We wire it to the currently-
     * selected row so landing never dumps the user on "All" or the first
     * provider category — they land on whatever they last picked.
     *
     * Previous versions attached this to the panel's outer Column, which
     * meant focus fell on the first focusable descendant (the filter
     * text field), auto-popping the software keyboard. Removing the
     * text field + targeting the selected row fixes both bugs at once.
     */
    selectedRowFocus: FocusRequester? = null,
) {
    val bucketized = remember(groups, smartGrouping) {
        if (smartGrouping) bucketize(groups) else emptyMap()
    }

    // Auto-scroll the selected row into view so after LEFT-revealing the
    // panel, the user sees where focus landed. Without this, a long group
    // list might scroll to top while focus is 40 rows down — disorienting.
    val listState = rememberLazyListState()
    val selectedIndex = remember(groups, selected, smartGrouping) {
        indexOfSelected(groups, bucketized, selected, smartGrouping, showFavorites)
    }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            // scroll near the middle — feels less edge-pinned than 0.
            runCatching { listState.scrollToItem(maxOf(0, selectedIndex - 3)) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(240.dp)
            .background(YancoPalette.BackgroundDeep)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Categories",
            color = YancoPalette.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (showFavorites) {
                item(key = "__favorites__") {
                    GroupRow(
                        label = "\u2606  Favorites",
                        trailing = null,
                        selected = selected == FAVORITES_GROUP,
                        depth = 0,
                        focusRequester = selectedRowFocus.takeIf { selected == FAVORITES_GROUP },
                        onClick = { onSelect(FAVORITES_GROUP) },
                    )
                }
            }
            item(key = "__all__") {
                GroupRow(
                    label = "All",
                    trailing = if (groups.isNotEmpty()) groups.size.toString() else null,
                    selected = selected == ALL_GROUPS,
                    depth = 0,
                    focusRequester = selectedRowFocus.takeIf { selected == ALL_GROUPS },
                    onClick = { onSelect(ALL_GROUPS) },
                )
            }
            if (smartGrouping) {
                bucketized.forEach { (bucket, members) ->
                    item(key = "bucket:${bucket.name}") {
                        BucketHeader(bucket = bucket, count = members.size)
                    }
                    items(members, key = { "${bucket.name}:$it" }) { group ->
                        GroupRow(
                            label = group,
                            trailing = null,
                            selected = selected == group,
                            depth = 1,
                            focusRequester = selectedRowFocus.takeIf { selected == group },
                            onClick = { onSelect(group) },
                        )
                    }
                }
            } else {
                items(groups, key = { it }) { group ->
                    GroupRow(
                        label = group,
                        trailing = null,
                        selected = selected == group,
                        depth = 0,
                        focusRequester = selectedRowFocus.takeIf { selected == group },
                        onClick = { onSelect(group) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BucketHeader(bucket: GroupBucket, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = bucket.icon,
            fontSize = 13.sp,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = bucket.display.uppercase(),
            color = YancoPalette.Accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = count.toString(),
            color = YancoPalette.TextMuted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun GroupRow(
    label: String,
    trailing: String?,
    selected: Boolean,
    depth: Int,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val bg = when {
        focused -> YancoPalette.BackgroundHover
        selected -> YancoPalette.Accent.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val border = if (focused) YancoPalette.FocusRing else Color.Transparent
    val leftPad = (12 + depth * 10).dp

    val baseModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .background(bg)
        .border(1.dp, border, RoundedCornerShape(6.dp))
    val withFocus = if (focusRequester != null) {
        baseModifier.focusRequester(focusRequester)
    } else {
        baseModifier
    }
    Row(
        modifier = withFocus
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = leftPad, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) YancoPalette.Accent else YancoPalette.TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        trailing?.let {
            Text(
                text = it,
                color = YancoPalette.TextMuted,
                fontSize = 10.sp,
            )
        }
    }
}

/**
 * Best-effort index of the selected row within the current LazyColumn
 * layout so the list can scroll near it on panel reveal. Keep the formula
 * close to the LazyColumn rendering logic in [CategoryFilterPanel] — if
 * they drift, the auto-scroll will land on the wrong row, not hard-fail.
 */
private fun indexOfSelected(
    groups: List<String>,
    bucketized: Map<GroupBucket, List<String>>,
    selected: String,
    smartGrouping: Boolean,
    showFavorites: Boolean,
): Int {
    // Favorites row + All row at the top of every layout.
    val prefix = (if (showFavorites) 1 else 0) + 1
    if (selected == FAVORITES_GROUP) return 0
    if (selected == ALL_GROUPS) return prefix - 1
    return if (smartGrouping) {
        var idx = prefix
        for ((_, members) in bucketized) {
            idx++ // bucket header
            val local = members.indexOf(selected)
            if (local >= 0) return idx + local
            idx += members.size
        }
        -1
    } else {
        val local = groups.indexOf(selected)
        if (local < 0) -1 else prefix + local
    }
}
