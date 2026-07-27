package com.yancotv.android.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.YancoIcons

/**
 * Carries the active Settings tab's [FocusRequester] down through the
 * tab body so every [SettingsRow] / [SettingsChipRow] / [SettingsSlider]
 * can register a LEFT-exit that lands on the inner sidebar's active
 * tab — the user's "back to the previous menu" target.
 *
 * Provided by `SettingsScreen.ContentPane`. Default is
 * [FocusRequester.Default] so a row used outside Settings doesn't
 * crash; it just falls back to Compose's natural directional search.
 */
internal val LocalActiveSettingsTabFocus = compositionLocalOf { FocusRequester.Default }

/**
 * Modifier helper: turns its receiver into a focus group that redirects
 * a LEFT-exit to the supplied [FocusRequester]. Used by the row-level
 * primitives so each [SettingsRow] / chip strip / slider preset row has
 * its OWN exit boundary — Compose's directional search would otherwise
 * find a leftward-and-upward focusable in a sibling row above (e.g.
 * 'System default' in User-Agent presets when LEFT is pressed from
 * '5s' in Connect-timeout presets — same content pane, different row).
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.leftExitsTo(target: FocusRequester): Modifier = this
    .focusGroup()
    .focusProperties {
        exit = { direction ->
            if (direction == FocusDirection.Left) target else FocusRequester.Default
        }
    }

/**
 * Shared submenu primitives. Maps the Claude-Design "Frosted Emerald"
 * settings spec (`design_handoff_yancotv/designs/settings.html`) into
 * Compose so every tab gets the same vertical rhythm, headers and row
 * shape — no more per-tab Column-with-spacing piles.
 *
 * Three primitives ship here:
 *   - [SettingsSection]  : title + gradient hairline + optional sub-prose + right-slot.
 *   - [SettingsRow]      : single setting card (kicker + label + hint + right-slot + body).
 *   - [SettingsSlider]   : focus-aware slider with preset chips and LEFT/RIGHT step.
 *   - [SettingsKicker]   : the 10sp uppercase mono caption used for "TV ONLY" / "DESTRUCTIVE" tags.
 *   - [SettingsSelect]   : pill-shaped value display with a chevron — replaces Material3
 *                          DropdownMenu surfaces that don't focus correctly on TV.
 *
 * Existing component-tier primitives ([SettingsChip], [SettingsToggleRow],
 * [SettingsClickToEditField], [SettingsButton] family) compose INSIDE
 * these — `SettingsRow` provides the framing, the existing controls
 * provide the input.
 */

@Composable
internal fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    right: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalYancoPalette.current
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = if (sub != null) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                color = palette.TextPrimary,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.2).sp,
            )
            // Gradient hairline divides the title from the right-slot. Keeps the
            // section header reading as one continuous strip — same trick as
            // §`Section` in the HTML mock.
            Box(
                modifier =
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors =
                            listOf(
                                palette.Accent.copy(alpha = 0.20f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            right?.invoke()
        }
        if (sub != null) {
            Text(
                text = sub,
                color = palette.TextMuted,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth(),
            )
        }
        content()
    }
}

/**
 * One settings row. Three modes:
 *   - [onClick] non-null: row is the focus target and a tap commits.
 *     Used for navigational rows (e.g. 'Open hidden list', 'Browse
 *     folder', source list rows).
 *   - [onClick] null + [readOnlyFocusable] true: row is focusable but
 *     CENTER does nothing. Used for read-only info rows that should
 *     still be reachable by D-pad so the screen scrolls smoothly
 *     through them — without this, D-pad jumps over the row and the
 *     content never scrolls into view.
 *   - [onClick] null + [readOnlyFocusable] false (default): row is
 *     pure static framing for child focusables (chip rows, sliders,
 *     toggles in [content] / [right]).
 *
 * Use [right] for inline controls (Toggle, Select, button) and
 * [content] for full-width body controls (Slider, ChipRow). Both can
 * be present on the same row.
 */
@Composable
internal fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    kicker: String? = null,
    right: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    readOnlyFocusable: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val activeTabFocus = LocalActiveSettingsTabFocus.current
    val isFocusTarget = onClick != null || readOnlyFocusable

    val border =
        when {
            isFocusTarget && focused -> palette.FocusRing
            else -> palette.BorderSubtle
        }
    val borderWidth = if (isFocusTarget && focused) 1.5.dp else 1.dp

    val rowModifier =
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.BackgroundRaised.copy(alpha = if (focused) 0.65f else 0.5f))
            .border(borderWidth, border, shape)
            // Each row owns its LEFT-exit boundary. Without this, Compose's
            // spatial focus search for D-pad LEFT would find a focusable in
            // a *different* row above and move focus there. Wrapping the
            // row in its own focusGroup with exit-to-activeTabFocus means
            // in-row LEFT navigation works (chip 2 → chip 1) but the moment
            // LEFT crosses the row boundary, focus lands on the active tab
            // in the inner sidebar — the user's "previous menu".
            .leftExitsTo(activeTabFocus)
            .let {
                when {
                    onClick != null ->
                        it
                            .focusable(interactionSource = interaction)
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                                role = Role.Button,
                                onClick = onClick,
                            )
                    readOnlyFocusable ->
                        it.focusable(interactionSource = interaction)
                    else -> it
                }
            }
            .padding(horizontal = 22.dp, vertical = 16.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (kicker != null) {
                SettingsKicker(text = kicker, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(
                text = label,
                color = palette.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    color = palette.TextMuted,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (content != null) {
                Box(modifier = Modifier.padding(top = 12.dp)) { content() }
            }
        }
        // MK.29.1 — Drill-in chevron. Universal "this row opens a picker"
        // affordance: when the row itself is clickable (onClick != null)
        // AND there's no other right-slot widget (a toggle / select / button
        // that already telegraphs the action), paint a ›-style chevron
        // on the right edge. Brightens to Accent on focus so the user can
        // see WHERE the press will land before pressing OK. Pure visual —
        // no focus target of its own (the row owns input).
        if (right != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                right()
            }
        } else if (onClick != null) {
            Icon(
                imageVector = YancoIcons.ChevronRight,
                contentDescription = null,
                tint = if (focused) palette.Accent else palette.TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Uppercase mono caption — "TV ONLY", "DESTRUCTIVE", "EPG", "DVR" etc.
 * Mirrors `kicker-d` from the HTML mock: 10sp / 700 / wide tracking,
 * accent or muted color depending on context.
 */
@Composable
internal fun SettingsKicker(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val palette = LocalYancoPalette.current
    Text(
        text = text.uppercase(),
        color = if (accent) palette.Accent else palette.TextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier,
    )
}

/**
 * Focus-aware slider. The whole bar is one focus target. While focused,
 * D-pad LEFT / RIGHT step the value by [step] (or to the nearest preset
 * if [presets] are provided). The HTML reference relies on the browser's
 * native `<input type=range>` which is keyboard-focusable; on Android TV
 * we have to build the affordance ourselves.
 *
 * Renders: 4dp track + accent gradient fill + 24dp circular knob +
 * value readout to the right + optional preset chip row underneath.
 */
@Composable
internal fun SettingsSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    unit: String = "",
    presets: List<Int>? = null,
) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(2.dp)

    val clampedValue = value.coerceIn(range.first, range.last)
    val progress =
        if (range.last == range.first) {
            0f
        } else {
            (clampedValue - range.first).toFloat() / (range.last - range.first).toFloat()
        }

    val barHeight = 28.dp
    val knobSize = 22.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // MK.28.6 (MB-271) — map a track x-coordinate to a stepped value.
            // Captured by the tap + drag handlers below; state-backed so the
            // pointerInput lambda (which never restarts) sees current values.
            val currentValue by rememberUpdatedState(clampedValue)
            val currentOnChange by rememberUpdatedState(onValueChange)
            BoxWithConstraints(
                modifier =
                Modifier
                    .weight(1f)
                    .height(barHeight)
                    .focusable(interactionSource = interaction)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                val next = (clampedValue - step).coerceAtLeast(range.first)
                                if (next != clampedValue) onValueChange(next)
                                true
                            }
                            Key.DirectionRight -> {
                                val next = (clampedValue + step).coerceAtMost(range.last)
                                if (next != clampedValue) onValueChange(next)
                                true
                            }
                            else -> false
                        }
                    }
                    // MB-271 — touch input: tap the track to jump, drag to
                    // scrub. Pre-fix the knob was a dead visual on phone and
                    // any value between the preset chips was unreachable by
                    // touch. Key-driven path above is untouched (TV).
                    .pointerInput(range, step) {
                        fun valueForX(x: Float): Int {
                            val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            val raw = range.first + fraction * (range.last - range.first)
                            val stepped = (Math.round(raw / step) * step)
                            return stepped.coerceIn(range.first, range.last)
                        }
                        detectTapGestures { offset ->
                            val next = valueForX(offset.x)
                            if (next != currentValue) currentOnChange(next)
                        }
                    }
                    .pointerInput(range, step) {
                        fun valueForX(x: Float): Int {
                            val fraction = (x / size.width.toFloat()).coerceIn(0f, 1f)
                            val raw = range.first + fraction * (range.last - range.first)
                            val stepped = (Math.round(raw / step) * step)
                            return stepped.coerceIn(range.first, range.last)
                        }
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val next = valueForX(change.position.x)
                            if (next != currentValue) currentOnChange(next)
                        }
                    }
                    // MK.28.8 (MB-278) — expose the slider to TalkBack: name
                    // it with the current value + unit, publish the range so
                    // it announces as an adjustable progress control, and
                    // provide setProgress so AT can change the value (the
                    // key/touch paths above are invisible to accessibility
                    // services).
                    .semantics {
                        contentDescription = "$clampedValue$unit"
                        progressBarRangeInfo =
                            ProgressBarRangeInfo(
                                current = clampedValue.toFloat(),
                                range = range.first.toFloat()..range.last.toFloat(),
                            )
                        setProgress { target ->
                            val stepped =
                                (Math.round(target / step) * step)
                                    .coerceIn(range.first, range.last)
                            if (stepped != clampedValue) onValueChange(stepped)
                            true
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Empty track
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(shape)
                        .background(Color.White.copy(alpha = 0.08f)),
                )
                // Accent fill
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0.001f))
                        .height(4.dp)
                        .clip(shape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(palette.AccentDeep, palette.Accent),
                            ),
                        ),
                )
                // Knob — translated to the progress point. The .offset() variant
                // would clip on the right edge; using an absolute-position Box
                // inside a BoxWithConstraints is cleaner and lets us keep the
                // knob centered on the point regardless of width.
                // coerceAtLeast(0.dp): during the first/transient measure pass
                // BoxWithConstraints can report maxWidth < knobSize (or 0),
                // making maxOffset negative → a negative knobX → `.padding`
                // throws "Padding must be non-negative" and crashes the whole
                // tab. Clamp so the knob just pins to the start until the real
                // width resolves. (Bit the phone's narrower Settings layout.)
                val maxOffset = (maxWidth - knobSize).coerceAtLeast(0.dp)
                val knobX =
                    (maxOffset.value * progress.coerceIn(0f, 1f))
                        .dp
                val ringColor =
                    if (focused) palette.FocusRing else palette.Accent
                Box(
                    modifier =
                    Modifier
                        .padding(start = knobX)
                        .size(knobSize)
                        .clip(RoundedCornerShape(11.dp))
                        .background(palette.Accent)
                        .border(
                            width = if (focused) 2.dp else 1.dp,
                            color = ringColor,
                            shape = RoundedCornerShape(11.dp),
                        ),
                )
            }
            Text(
                text = "$clampedValue$unit",
                color = palette.Accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                // MB-300 — was a hard `.width(70.dp)`. Five monospace glyphs
                // at 16sp need 60.1dp at a 125% font scale, so 70 is fine at
                // the presets we ship but has no slack for a 4-digit value
                // plus unit. Nothing depends on it being exactly 70, so let
                // it grow rather than clip.
                modifier = Modifier.widthIn(min = 78.dp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!presets.isNullOrEmpty()) {
            // MB-300 — the preset chips are laid out in a plain Row with no
            // scroll. Measured on the read-timeout row, the four chips need
            // 122.3dp of a 124dp budget at 1.0x; at the shipped 125% font
            // scale (or once a chip goes SemiBold on selection) the fourth
            // chip is pushed past the edge and becomes unreachable by D-pad,
            // because an unplaced child is not focusable. One modifier is the
            // entire difference.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                presets.forEach { preset ->
                    SettingsChip(
                        label = "$preset$unit",
                        selected = preset == clampedValue,
                        onClick = { onValueChange(preset.coerceIn(range.first, range.last)) },
                    )
                }
            }
        }
    }
}

/**
 * Pill-shaped, focus-aware value display. Used wherever the design's
 * `Select` appears — it shows the current option + a hint that there
 * are more, and tapping it should open a picker (the picker UI itself
 * stays per-tab so we don't constrain the option list shape).
 *
 * This intentionally REPLACES Material3 `DropdownMenu` for TV surfaces:
 * the Material dropdown anchors a popup that doesn't honour leanback
 * focus — RIGHT past the trigger lands somewhere unrelated.
 */
@Composable
internal fun SettingsSelect(value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalYancoPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(20.dp)
    val targetScale = if (focused) 1.02f else 1f
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 180),
        label = "selectScale",
    )

    Row(
        modifier =
        modifier
            .height(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(palette.BackgroundElevated)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) palette.FocusRing else palette.BorderSubtle,
                shape = shape,
            )
            .focusable(interactionSource = interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.DropdownList,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = value,
            color = palette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        // Chevron-down hint — single triangle drawn with two short text
        // glyphs would be inconsistent across font fallbacks. Use a simple
        // 6×4 down-arrow Box instead.
        Box(
            modifier =
            Modifier
                .size(width = 8.dp, height = 5.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(palette.TextSecondary, palette.TextMuted),
                    ),
                ),
        )
    }
}

/**
 * Horizontal chip row helper. Used for "small enum" pickers like
 * Buffer-preset / Resize / Channel-number-format where the design's
 * `ChipRow` shows N options on one line.
 */
@Composable
internal fun SettingsChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            SettingsChip(
                label = option,
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

/** Convenience overload — caller passes any enum-like list whose stringification
 *  matches what should display. */
@Composable
internal fun <T> SettingsChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            SettingsChip(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

/** Same vertical container the existing tabs use — extracted so each tab
 *  body can drop the boilerplate Column.fillMaxSize().verticalScroll().padding(24).
 *  Provides Section spacing internally; nest [SettingsSection]s inside. */
@Composable
internal fun SettingsTabBody(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        content()
    }
}

/** Helper: render a single settings text label outside a Row — used when
 *  a tab needs a freeform paragraph between sections (e.g. About hero). */
@Composable
internal fun SettingsBodyText(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 12.sp, @Suppress("UNUSED_PARAMETER") accent: Boolean = false) {
    val palette = LocalYancoPalette.current
    Text(
        text = text,
        color = palette.TextMuted,
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.5f).sp,
        modifier = modifier,
    )
}

/** Spacer used between sibling rows inside a section. Keeps the rhythm
 *  consistent — same gap the design's `marginBottom:10` on `.y-card` rows. */
@Composable
internal fun SettingsRowSpacer() {
    Spacer(modifier = Modifier.height(10.dp))
}

/**
 * Read-only switch indicator — visual mirror of [VerdantSwitch] but
 * sized for inline use inside a [SettingsRow] right-slot or any list
 * row that owns input upstream. Does NOT register a focus target.
 *
 * Pulled out of [SettingsToggleRow] so screens like Groups (a long
 * list where the row itself is the focus target) get the same emerald
 * pill instead of a Material3 `Switch` whose unchecked thumb fades
 * into the dark BackgroundRaised on Fire TV.
 */
@Composable
internal fun SettingsInlineSwitch(checked: Boolean) {
    val palette = LocalYancoPalette.current
    val knobOffset by animateFloatAsState(
        targetValue = if (checked) 22f else 3f,
        animationSpec = tween(durationMillis = 200),
        label = "inlineSwitchKnob",
    )
    val trackBrush =
        if (checked) {
            Brush.verticalGradient(listOf(palette.AccentDeep, palette.Accent))
        } else {
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f)),
            )
        }
    val borderColor =
        if (checked) palette.Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f)

    Row(
        modifier =
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(trackBrush)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(knobOffset.dp))
        Box(
            modifier =
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(palette.TextPrimary, palette.TextSecondary),
                    ),
                ),
        )
    }
}
