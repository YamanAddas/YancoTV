package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
) {
    // Local filter — a compact search for 400+ providers where scrolling
    // through the full list is untenable. Stored in rememberSaveable so
    // rotations / process death don't wipe the typed query mid-browse.
    var query by rememberSaveable { mutableStateOf("") }
    val filteredGroups = remember(groups, query) {
        if (query.isBlank()) groups
        else groups.filter { it.contains(query, ignoreCase = true) }
    }
    // Bucketize lazily — the hash-and-loop is cheap (<5 ms for 1000 groups)
    // but with 400 row recompositions it would still be wasted work to do
    // inside a LazyColumn item.
    val bucketized = remember(filteredGroups, smartGrouping) {
        if (smartGrouping) bucketize(filteredGroups) else emptyMap()
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(YancoPalette.BackgroundDeep)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterBar(
            query = query,
            onQueryChange = { query = it },
        )
        LazyColumn(
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (showFavorites) {
                // Pinned at the top so a user scanning for saved items never
                // has to scroll past a provider's noisy group list. Hollow
                // star glyph because selecting it doesn't favorite anything —
                // it filters to what's already starred.
                item {
                    GroupRow(
                        label = "\u2606  Favorites",
                        trailing = null,
                        selected = selected == FAVORITES_GROUP,
                        depth = 0,
                        onClick = { onSelect(FAVORITES_GROUP) },
                    )
                }
            }
            item {
                GroupRow(
                    label = "All",
                    trailing = if (groups.isNotEmpty()) groups.size.toString() else null,
                    selected = selected == ALL_GROUPS,
                    depth = 0,
                    onClick = { onSelect(ALL_GROUPS) },
                )
            }
            if (smartGrouping && query.isBlank()) {
                // Buckets render as collapsed section headers. Tapping a
                // bucket header just scrolls it into view / expands it —
                // selecting a member still passes the exact group name so
                // the content query doesn't need a new IN-clause variant.
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
                            onClick = { onSelect(group) },
                        )
                    }
                }
            } else {
                items(filteredGroups, key = { it }) { group ->
                    GroupRow(
                        label = group,
                        trailing = null,
                        selected = selected == group,
                        depth = 0,
                        onClick = { onSelect(group) },
                    )
                }
                if (filteredGroups.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            text = "No groups match \"$query\"",
                            color = YancoPalette.TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(query: String, onQueryChange: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = if (focused) YancoPalette.FocusRing else YancoPalette.BorderSubtle
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(YancoPalette.BackgroundRaised)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) {
            Text(
                text = "Filter groups…",
                color = YancoPalette.TextMuted,
                fontSize = 12.sp,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = YancoPalette.TextPrimary, fontSize = 12.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(YancoPalette.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BucketHeader(bucket: GroupBucket, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val bg = when {
        selected -> YancoPalette.Accent.copy(alpha = 0.15f)
        focused -> YancoPalette.BackgroundHover
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
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = leftPad, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
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
