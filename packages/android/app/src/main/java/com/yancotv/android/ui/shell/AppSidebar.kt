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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.theme.YancoPalette

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
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(200.dp)
            .background(YancoPalette.BackgroundRaised)
            .padding(vertical = 16.dp, horizontal = 12.dp)
            .focusRestorer()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppSection.entries.forEach { section ->
            SidebarRow(
                label = section.label,
                selected = section == current,
                onClick = { onSelect(section) },
            )
        }
    }
}

@Composable
private fun SidebarRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val bg = when {
        focused -> YancoPalette.BackgroundHover
        selected -> YancoPalette.Accent.copy(alpha = 0.22f)
        else -> Color.Transparent
    }
    val border = if (focused) YancoPalette.FocusRing else Color.Transparent

    androidx.compose.material3.Text(
        text = label,
        color = if (selected) YancoPalette.Accent else YancoPalette.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
