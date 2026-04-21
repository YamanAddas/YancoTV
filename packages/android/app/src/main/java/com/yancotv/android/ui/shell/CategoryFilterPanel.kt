package com.yancotv.android.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(260.dp)
            .background(YancoPalette.BackgroundDeep)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Categories",
            color = YancoPalette.TextMuted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showFavorites) {
                // Pinned at the top so a user scanning for saved items never
                // has to scroll past a provider's noisy group list. Hollow
                // star glyph because selecting it doesn't favorite anything —
                // it filters to what's already starred.
                item {
                    GroupRow(
                        label = "\u2606 Favorites",
                        selected = selected == FAVORITES_GROUP,
                        onClick = { onSelect(FAVORITES_GROUP) },
                    )
                }
            }
            item {
                GroupRow(
                    label = "All",
                    selected = selected == ALL_GROUPS,
                    onClick = { onSelect(ALL_GROUPS) },
                )
            }
            items(groups) { group ->
                GroupRow(
                    label = group,
                    selected = selected == group,
                    onClick = { onSelect(group) },
                )
            }
        }
    }
}

@Composable
private fun GroupRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val bg = when {
        selected -> YancoPalette.Accent.copy(alpha = 0.15f)
        focused -> YancoPalette.BackgroundHover
        else -> Color.Transparent
    }
    val border = if (focused) YancoPalette.FocusRing else Color.Transparent

    Text(
        text = label,
        color = if (selected) YancoPalette.Accent else YancoPalette.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
