package com.yancotv.android.player.options

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.ui.theme.LocalYancoPalette

/**
 * MK.options.redesign — bottom-right popup that lists the player option
 * categories with their current value. Replaces the old tabbed sheet's
 * entry point.
 *
 * Per-row contract:
 *   - **OK / CENTER** — opens that category's floating panel.
 *   - **D-pad LEFT / RIGHT** — cycles the value in place when the
 *     category supports it (slice 1: Aspect only). Other categories
 *     ignore the gesture; user opens the panel for full controls.
 *   - **Current value** is rendered inline so users who only want to
 *     check state don't need to drill in.
 *
 * Focus model: the first row auto-focuses on open; UP/DOWN walks the
 * list. BACK closes the popup. Outside-tap also closes (touch path
 * via `pointerInput`; doesn't introduce a focusable scrim that would
 * trap CENTER on TV).
 */
@UnstableApi
@Composable
fun PlayerOptionsMenu(
    state: PlayerOptionsState,
    rows: List<PlayerOptionsRow>,
    onDismiss: () -> Unit,
) {
    val visible by state.menuVisible.collectAsState()
    val activePanel by state.activePanel.collectAsState()
    val palette = LocalYancoPalette.current
    // One requester per row so the popup can restore focus to the
    // exact row whose panel was just closed. A single-first-row
    // requester collapsed the return-from-panel case onto the first
    // row, which lost the user's place.
    val rowFocus =
        remember(rows.map { it.category }) {
            rows.associate { it.category to FocusRequester() }
        }
    // Track the last-opened category so we can focus its row when the
    // panel exits. `previousPanel` only updates after we react to the
    // transition; without this delay we'd see active==null before we
    // remember which panel had been open.
    val previousPanel = remember { mutableStateOf<PlayerOptionCategory?>(null) }

    LaunchedEffect(visible, activePanel) {
        if (!visible) {
            previousPanel.value = null
            return@LaunchedEffect
        }
        if (activePanel == null) {
            // Popup is up, no panel. Focus either:
            //   - the row whose panel was just closed (return path), or
            //   - the first row (initial open / no prior panel).
            val target =
                previousPanel.value
                    ?: rows.firstOrNull()?.category
            target?.let { cat -> runCatching { rowFocus[cat]?.requestFocus() } }
        }
        // Keep `previousPanel` up to date for the next transition.
        previousPanel.value = activePanel
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Touch-only outside dismiss. Don't add a clickable scrim;
                // it would steal CENTER from the rows on TV.
                .pointerInput(visible) {
                    if (visible) detectTapGestures { onDismiss() }
                },
        contentAlignment = Alignment.BottomEnd,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(end = 32.dp, bottom = 96.dp)
                        .width(MENU_WIDTH.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xEE0A1410))
                        .border(1.dp, palette.BorderSubtle, RoundedCornerShape(12.dp))
                        .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "OPTIONS  ·  ◂ ▸ to switch",
                    color = palette.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                rows.forEach { row ->
                    PlayerOptionsRowItem(
                        row = row,
                        focusRequester = rowFocus[row.category],
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerOptionsRowItem(
    row: PlayerOptionsRow,
    focusRequester: FocusRequester?,
) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val bg =
        if (focused) palette.BackgroundHover else Color.Transparent
    val border =
        if (focused) palette.Accent else Color.Transparent

    Row(
        modifier =
            Modifier
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(6.dp))
                .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                // onPreviewKeyEvent must wrap focusable so it sees the
                // event before the focusable's default arrow-key focus
                // search runs. Earlier order (after focusable + clickable)
                // didn't fire because Compose had already moved focus or
                // the modifier chain didn't expose preview events on the
                // focusable's children-side. Returns true on a successful
                // cycle so the activity's swallow guard doesn't double-
                // handle the key.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            val handler = row.onCyclePrev ?: return@onPreviewKeyEvent false
                            handler()
                            true
                        }
                        Key.DirectionRight -> {
                            val handler = row.onCycleNext ?: return@onPreviewKeyEvent false
                            handler()
                            true
                        }
                        else -> false
                    }
                }.focusable(interactionSource = interaction)
                .clickable(interactionSource = interaction, indication = null) { row.onPick() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                color = palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = row.currentValue,
                color = if (row.canCycle) palette.Accent else palette.TextMuted,
                fontSize = 12.sp,
            )
        }
        if (row.canCycle) {
            Text(
                text = "◂ ▸",
                color = palette.TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * One row in the popup. Categories that don't support in-place
 * cycling pass null for `onCyclePrev` / `onCycleNext`; the row's
 * `◂ ▸` hint is suppressed and LEFT/RIGHT keys fall through.
 */
data class PlayerOptionsRow(
    val category: PlayerOptionCategory,
    val label: String,
    val currentValue: String,
    val onPick: () -> Unit,
    val onCyclePrev: (() -> Unit)? = null,
    val onCycleNext: (() -> Unit)? = null,
) {
    val canCycle: Boolean get() = onCyclePrev != null || onCycleNext != null
}

private const val MENU_WIDTH = 320
