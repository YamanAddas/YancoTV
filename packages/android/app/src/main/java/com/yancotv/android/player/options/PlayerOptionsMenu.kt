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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.R
import com.yancotv.android.player.MidnightHex
import com.yancotv.android.player.PlayerChromeMetrics
import com.yancotv.android.player.glassSurface
import com.yancotv.android.ui.focus.ProvideFocusScrollSpec
import com.yancotv.android.ui.focus.endwardKey
import com.yancotv.android.ui.focus.startwardKey
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoIcons

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
fun PlayerOptionsMenu(state: PlayerOptionsState, rows: List<PlayerOptionsRow>, onDismiss: () -> Unit) {
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
        // Hide the popup while a panel is active. RCA: when both the
        // popup and the panel composed in the same ComposeView, the
        // popup's focused row (the one the user OK'd) stayed in the
        // focus tree and competed with the panel's `awaitAndRequest`.
        // On single-row panels (RECORD / FAVORITES / EXTERNAL) focus
        // reliably stuck on the popup row, so CENTER re-fired
        // `openPanel(SAME)` — a no-op — and Stop / Add-to-favorites /
        // External-launch never reached their onPick. Removing the
        // popup from the tree while a panel is up forces focus onto
        // the panel's row. Return-to-popup focus is handled by the
        // LaunchedEffect above (keyed on activePanel flipping back to
        // null).
        AnimatedVisibility(
            visible = visible && activePanel == null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        ) {
            ProvideFocusScrollSpec {
                val cfg = LocalConfiguration.current
                val sheetWidth = PlayerChromeMetrics.sheetWidthDp(cfg.screenWidthDp.toFloat()).dp
                val sheetMaxHeight = PlayerChromeMetrics.sheetMaxHeightDp(cfg.screenHeightDp.toFloat()).dp
                Column(
                    modifier =
                    Modifier
                        .padding(end = 20.dp, bottom = 16.dp)
                        .width(sheetWidth)
                        .heightIn(max = sheetMaxHeight)
                        // Glass, not the old 0xEE near-opaque fill: the brief
                        // requires the film to stay perceptible through the
                        // sheet, and 93% opacity is a wall with a tint.
                        .glassSurface(RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    PlayerOptionsHeader()
                    rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            // Hairline separators instead of a card per row —
                            // the brief calls out not boxing every row.
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(palette.BorderSubtle.copy(alpha = 0.5f)),
                            )
                        }
                        PlayerOptionsRowItem(
                            row = row,
                            focusRequester = rowFocus[row.category],
                        )
                    }
                }
            }
        }
    }
}

/**
 * MK.34.7 — sheet header: a small accent-outlined hex carrying a sliders mark,
 * then OPTIONS in near-white.
 *
 * The old header read "OPTIONS  ·  ◂ ▸ to switch". The brief asks for that
 * trailing instruction to go, and it should: it described a gesture that only
 * works on SOME rows (the ones with onCyclePrev/onCycleNext), so it was
 * advertising a capability the row under the user's cursor might not have.
 */
@Composable
private fun PlayerOptionsHeader() {
    val palette = LocalYancoPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .clip(MidnightHex)
                .border(1.dp, palette.Accent.copy(alpha = 0.6f), MidnightHex),
        ) {
            Icon(
                imageVector = YancoIcons.Sliders,
                contentDescription = null,
                tint = palette.Accent,
                modifier = Modifier.size(11.dp),
            )
        }
        Text(
            text = stringResource(R.string.po_header),
            color = palette.TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
        )
    }
}

@Composable
private fun PlayerOptionsRowItem(row: PlayerOptionsRow, focusRequester: FocusRequester?) {
    // MK.31.2 — prev/next follow reading order, so they are logical.
    val cyclePrevKey = startwardKey()
    val cycleNextKey = endwardKey()
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Selection treatment, per the brief: a subtle translucent accent strip and
    // a thin accent outline — NOT the old filled BackgroundHover rectangle,
    // which read as a large glowing block at TV distance.
    val bg = if (focused) palette.Accent.copy(alpha = 0.14f) else Color.Transparent
    val border = if (focused) palette.Accent.copy(alpha = 0.75f) else Color.Transparent

    Row(
        modifier =
        Modifier
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            // onPreviewKeyEvent must wrap focusable so it sees the
            // event before the focusable's default arrow-key focus
            // search runs. Returns true on a successful cycle so the
            // activity's swallow guard doesn't double-handle the key.
            // MK.31.2 — prev/next follow reading order, so they are logical:
            // in RTL a physical LEFT press advances. Unlike the seek bar in
            // VodPlayerDock (which stays physical on purpose), there is no
            // timeline here — just an ordered option list.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    cyclePrevKey -> {
                        val handler = row.onCyclePrev ?: return@onPreviewKeyEvent false
                        handler()
                        true
                    }
                    cycleNextKey -> {
                        val handler = row.onCycleNext ?: return@onPreviewKeyEvent false
                        handler()
                        true
                    }
                    else -> false
                }
            }.focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) { row.onPick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Small outlined hex icon. Solid accent fill when this row is the
        // current selection, which is the brief's "small solid hex selection
        // marker" — carried by the row's own icon rather than added as a second
        // mark competing with it.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .clip(MidnightHex)
                .background(if (focused) palette.Accent else Color.Transparent)
                .border(1.dp, if (focused) palette.Accent else palette.BorderSubtle, MidnightHex),
        ) {
            Icon(
                imageVector = row.category.icon(),
                contentDescription = null,
                tint = if (focused) palette.BackgroundDeep else palette.TextSecondary,
                modifier = Modifier.size(11.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                color = palette.TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.currentValue,
                color = if (row.canCycle) palette.AccentSoft else palette.TextMuted,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Chevron at the opposite edge, replacing the "◂ ▸" hint. It points at
        // the panel this row opens, which every row does; the old glyph pair
        // implied left/right cycling that only some rows support.
        Icon(
            imageVector = YancoIcons.ChevronRight,
            contentDescription = null,
            tint = palette.AccentSoft.copy(alpha = 0.8f),
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Row icon, per the brief's per-row suggestions. */
private fun PlayerOptionCategory.icon() = when (this) {
    PlayerOptionCategory.AUDIO -> YancoIcons.Speaker
    PlayerOptionCategory.SUBTITLES, PlayerOptionCategory.SUBTITLE_SEARCH -> YancoIcons.Subtitles
    PlayerOptionCategory.ASPECT -> YancoIcons.Aspect
    PlayerOptionCategory.SPEED -> YancoIcons.Speedometer
    PlayerOptionCategory.SLEEP -> YancoIcons.Moon
    PlayerOptionCategory.FAVORITES -> YancoIcons.Favorites
    PlayerOptionCategory.EXTERNAL -> YancoIcons.ExternalPlay
    else -> YancoIcons.Settings
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

/** Cap so the popup never exceeds usable screen real estate. 560 dp
 *  fits all 8 rows + the kicker comfortably on a 720dp landscape
 *  layout; if the device is shorter, verticalScroll picks up the
 *  remainder. */
private const val MENU_MAX_HEIGHT = 560
