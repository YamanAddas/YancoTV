package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CategoryFilterPanel(
    groups: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showFavorites: Boolean = true,
    smartGrouping: Boolean = false,
) {
    val bucketized = remember(groups, smartGrouping) {
        if (smartGrouping) bucketize(groups) else emptyMap()
    }
    val listState = rememberLazyListState()
    // Keep the selected row visible in the viewport — a 400-group provider
    // list otherwise leaves the user scrolling blind when they come back
    // from a deep pick. Fires whenever the selection moves, not on every
    // recomposition, so focus isn't yanked mid-navigation.
    val selectedIndex = remember(groups, selected, smartGrouping, showFavorites) {
        indexOfSelected(groups, bucketized, selected, smartGrouping, showFavorites)
    }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            runCatching { listState.scrollToItem(maxOf(0, selectedIndex - 3)) }
        }
    }

    // Fallback requester for `focusRestorer`. Without it, when the category
    // panel's focus group receives focus for the first time (e.g. DPAD RIGHT
    // from the sidebar on cold start), Compose's default enter can't cascade
    // through the nested LazyColumn to any `GroupRow`; focus stalls on the
    // focusGroup wrapper and the user sees no selector until pressing OK.
    // Attaching this requester to the currently-selected GroupRow and passing
    // it to `focusRestorer { firstItemFocus }` routes first-arrival focus to
    // the selected row. Saved-state restoration still wins on return visits.
    val firstItemFocus = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(240.dp)
            .background(YancoPalette.BackgroundDeep)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .focusRestorer { firstItemFocus }
            .focusGroup(),
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
                    val isSelected = selected == FAVORITES_GROUP
                    GroupRow(
                        label = "\u2606  Favorites",
                        trailing = null,
                        selected = isSelected,
                        depth = 0,
                        focusRequester = if (isSelected) firstItemFocus else null,
                        onClick = { onSelect(FAVORITES_GROUP) },
                    )
                }
            }
            item(key = "__all__") {
                val isSelected = selected == ALL_GROUPS
                GroupRow(
                    label = "All",
                    trailing = if (groups.isNotEmpty()) groups.size.toString() else null,
                    selected = isSelected,
                    depth = 0,
                    focusRequester = if (isSelected) firstItemFocus else null,
                    onClick = { onSelect(ALL_GROUPS) },
                )
            }
            if (smartGrouping) {
                bucketized.forEach { (bucket, members) ->
                    item(key = "bucket:${bucket.name}") {
                        BucketHeader(bucket = bucket, count = members.size)
                    }
                    items(members, key = { "${bucket.name}:$it" }) { group ->
                        val isSelected = selected == group
                        GroupRow(
                            label = group,
                            trailing = null,
                            selected = isSelected,
                            depth = 1,
                            focusRequester = if (isSelected) firstItemFocus else null,
                            onClick = { onSelect(group) },
                        )
                    }
                }
            } else {
                items(groups, key = { it }) { group ->
                    val isSelected = selected == group
                    GroupRow(
                        label = group,
                        trailing = null,
                        selected = isSelected,
                        depth = 0,
                        focusRequester = if (isSelected) firstItemFocus else null,
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
    focusRequester: FocusRequester? = null,
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            // focusRequester (if any) is the fallback target for the parent
            // Column's `focusRestorer { firstItemFocus }` — only one GroupRow
            // holds it per composition (the currently-selected one), so first-
            // arrival focus lands on that visible row instead of dying on the
            // focusGroup wrapper.
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
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

internal fun indexOfSelected(
    groups: List<String>,
    bucketized: Map<GroupBucket, List<String>>,
    selected: String,
    smartGrouping: Boolean,
    showFavorites: Boolean,
): Int {
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
