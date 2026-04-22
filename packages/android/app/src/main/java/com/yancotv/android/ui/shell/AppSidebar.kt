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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yancotv.android.ui.nav.AppSection
import com.yancotv.android.ui.theme.YancoPalette

/**
 * Left rail. D-pad navigates vertically; horizontal Right exits to the
 * content column. Width is deliberately narrow on TV so the rail doesn't
 * dominate a 1080p surface; phone gets the same layout inside a drawer
 * ([com.yancotv.android.ui.shell.HomeScreen] handles the containment).
 *
 * [currentRowFocus] is requested by the shell when the sidebar is
 * revealed, so focus lands on whichever section is currently selected
 * rather than always falling to "Home".
 */
@Composable
fun AppSidebar(
    current: AppSection,
    onSelect: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
    currentRowFocus: FocusRequester? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(200.dp)
            .background(YancoPalette.BackgroundRaised)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppSection.entries.forEach { section ->
            SidebarRow(
                label = section.label,
                selected = section == current,
                focusRequester = currentRowFocus.takeIf { section == current },
                onClick = { onSelect(section) },
            )
        }
    }
}

@Composable
private fun SidebarRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
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

    val base = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(bg)
        .border(1.dp, border, RoundedCornerShape(8.dp))
    val withFocus = if (focusRequester != null) base.focusRequester(focusRequester) else base

    androidx.compose.material3.Text(
        text = label,
        color = if (selected) YancoPalette.Accent else YancoPalette.TextPrimary,
        modifier = withFocus
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
