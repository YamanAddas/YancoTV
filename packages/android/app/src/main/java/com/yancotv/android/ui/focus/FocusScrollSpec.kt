package com.yancotv.android.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * MK.30.1 (MB-307) — focus-driven scroll positioning for D-pad surfaces.
 *
 * Compose's default [BringIntoViewSpec] scrolls the *minimum* distance that
 * makes the focused element visible. On a TV settings pane that reads as a
 * bug: D-pad DOWN through a tab then back UP parks the first row flush with
 * the viewport top, which leaves the [com.yancotv.android.ui.settings.SettingsSection]
 * header ("Video", "Continuity", …) clipped above it. Because nothing above
 * that row is focusable, there is no way to scroll further up — the header
 * stays cut off until the tab remounts.
 *
 * The previous per-screen spec in `SettingsScreen` tried to fix this with a
 * 32dp margin, but a section header is title (19sp) + 6dp + subtitle + 16dp
 * ≈ 80dp tall, so 32dp of headroom still cut it. It also carried three
 * further defects, all fixed here:
 *
 *   1. `if (size >= containerSize) return 0f` meant a focusable taller than
 *      the viewport never scrolled into view *at all*.
 *   2. The backward branch was gated on `trailingEdge < containerSize` and
 *      the forward branch on `offset > safetyPx`; a row tall enough to fail
 *      both fell through to "no scroll" and stayed clipped.
 *   3. No epsilon, so sub-pixel residue could re-trigger a scroll.
 *
 * ### Why asymmetric margins
 *
 * [DEFAULT_HEADROOM] is deliberately much larger than [DEFAULT_FOOTROOM].
 * Headroom is what reveals the section header when travelling *up*, so it is
 * sized to clear one. Footroom only has to keep the last row off the panel
 * border. This mirrors leanback's window-alignment offset, where the focus
 * "cursor" rides a fixed distance from the leading edge rather than at it.
 *
 * ### Over-requesting is safe and intentional
 *
 * At the very top of the content the requested backward distance exceeds
 * what the container can give. The scroll clamps at 0 — which is exactly
 * the desired outcome (header *and* the container's top padding revealed) —
 * and Compose's bring-into-view animation cancels itself once a step stops
 * being consumed, so an unsatisfiable request terminates instead of looping.
 */
object FocusScrollDefaults {
    /**
     * Distance kept between the focused element's leading edge and the
     * viewport top. Sized to clear a `SettingsSection` header (~80dp) so
     * travelling up never leaves one clipped.
     */
    val DEFAULT_HEADROOM: Dp = 96.dp

    /**
     * Distance kept between the focused element's trailing edge and the
     * viewport bottom. Only needs to keep the last row off the panel
     * border — the per-tab `bottom` padding lives inside the scroll content
     * and bring-into-view never scrolls padding into view on its own.
     */
    val DEFAULT_FOOTROOM: Dp = 32.dp

    /** Scroll requests smaller than this are treated as "already correct". */
    const val EPSILON_PX: Float = 0.5f

    /**
     * Hard ceiling on headroom as a fraction of the viewport, applied on top
     * of [DEFAULT_HEADROOM].
     *
     * MK.30.2 — without this, one global headroom cannot serve surfaces of
     * wildly different heights. 96dp is right for a Settings pane (~400dp
     * tall, ~80dp section headers) but absurd inside the player options menu,
     * which is capped at a couple of hundred dp: the focused row would be
     * shoved a quarter of the way down a list that barely scrolls. Capping at
     * a fraction of the viewport makes the same spec safe everywhere — small
     * containers get proportionally small margins for free.
     */
    const val HEADROOM_MAX_FRACTION: Float = 0.25f
}

/**
 * Pure scroll-distance math behind [rememberFocusScrollSpec]. Extracted so
 * the behaviour is unit-testable without a Compose harness — see
 * `FocusScrollSpecTest`.
 *
 * All values are pixels in the scroll container's coordinate space:
 *  - [offset] leading edge of the focused element relative to the viewport
 *    top (negative = clipped above).
 *  - [size] the focused element's extent along the scroll axis.
 *  - [containerSize] the viewport's extent along the scroll axis.
 *
 * Returns the signed distance to scroll: negative = backward (toward the
 * start of the content), positive = forward. Zero = leave it alone.
 *
 * Guarantees, all covered by tests:
 *  - **Stable.** Applying the returned distance always yields a state where
 *    the next call returns 0, so there is no oscillation.
 *  - **Never clips.** If the element can fit in the viewport it ends up
 *    fully visible.
 *  - **Degrades.** When the element is too tall to honour both margins the
 *    margins shrink (headroom wins over footroom) rather than the element
 *    being left half off-screen.
 */
fun focusScrollDistance(offset: Float, size: Float, containerSize: Float, headroomPx: Float, footroomPx: Float): Float {
    if (containerSize <= 0f) return 0f
    val leading = offset
    val trailing = offset + size

    // Element taller than the viewport — margins are meaningless, so just
    // make sure *an* edge is anchored. If it already spans the viewport
    // (the About tab's dpadVerticalScroll Column-as-focusable), leave it
    // alone; returning a delta on every focus event there produced a
    // feedback loop with animateScrollBy that read as flicker.
    if (size >= containerSize) {
        val raw =
            when {
                leading > 0f -> leading
                trailing < containerSize -> trailing - containerSize
                else -> 0f
            }
        return raw.takeIf { abs(it) >= FocusScrollDefaults.EPSILON_PX } ?: 0f
    }

    // Slack available for margins. When the element is tall enough that the
    // requested headroom + footroom don't both fit, headroom is honoured
    // first (seeing the section header you're arriving at matters more than
    // a gap under the row) and footroom absorbs the remainder.
    //
    // Headroom is additionally capped at a fraction of the viewport so one
    // spec scales from a full-height Settings pane down to the player's
    // options menu — see [FocusScrollDefaults.HEADROOM_MAX_FRACTION].
    val slack = containerSize - size
    val head = minOf(headroomPx, slack, containerSize * FocusScrollDefaults.HEADROOM_MAX_FRACTION)
    val foot = minOf(footroomPx, slack - head)

    val raw =
        when {
            // Clipped above, or sitting inside the headroom band.
            leading < head -> leading - head
            // Clipped below, or sitting inside the footroom band.
            trailing > containerSize - foot -> trailing - (containerSize - foot)
            // Fully visible with both margins clear.
            else -> 0f
        }
    return raw.takeIf { abs(it) >= FocusScrollDefaults.EPSILON_PX } ?: 0f
}

/**
 * [BringIntoViewSpec] wrapping [focusScrollDistance]. Provide it with
 * [ProvideFocusScrollSpec] rather than wiring it per scroll container.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun rememberFocusScrollSpec(headroom: Dp = FocusScrollDefaults.DEFAULT_HEADROOM, footroom: Dp = FocusScrollDefaults.DEFAULT_FOOTROOM): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density, headroom, footroom) {
        val headroomPx = with(density) { headroom.toPx() }
        val footroomPx = with(density) { footroom.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = focusScrollDistance(
                offset = offset,
                size = size,
                containerSize = containerSize,
                headroomPx = headroomPx,
                footroomPx = footroomPx,
            )
        }
    }
}

/**
 * Installs the YancoTV focus-scroll spec for every scroll container in
 * [content]. Wrap a *screen* (or a pane) in this rather than threading a
 * spec through each `verticalScroll` / `LazyColumn` — the spec travels down
 * the composition, so nested and future scroll containers inherit it and
 * cannot regress back to Compose's flush-to-edge default.
 */
/**
 * MK.30.6 (MB-316) — how close to the content start a focus-driven scroll has
 * to settle before [snapToTopNearStart] finishes the job and goes to 0.
 *
 * Sized to clear a `SettingsSection` header plus the container's top padding
 * (~80dp + 24dp), with slack.
 */
/**
 * MB-336 — raised from 140dp after the bug-hunt geometry audit: the Backup
 * tab's first focusable sits ~246–276dp deep at the 125% font-scale preset, so
 * its up-travel residue (~151–181dp) missed the old window and the header
 * stayed cut. 200dp covers every audited tab at every shipped preset.
 *
 * Raising it is safe ONLY because the predicate is now direction-aware: with
 * the old any-settle rule a 200dp window would have re-yanked downward travel
 * through the top fifth of every tab. The safety invariant for the focused row
 * staying visible after a snap is `threshold + headroom + rowHeight <=
 * viewport` (200 + 95 + ~60 <= 381dp on the TV pane) — the row the user was on
 * never leaves the screen.
 */
val DEFAULT_TOP_SNAP: Dp = 200.dp

/**
 * True when a settled scroll position should snap to 0.
 *
 * Three gates, all unit-tested:
 *  - `scrollValue >= 1`: already at 0 means snapping is a pointless animation.
 *  - `scrollValue <= thresholdPx`: genuinely mid-list — yanking the user to
 *    the top there would be hostile.
 *  - `scrollValue < previousSettledValue` (MB-336): the settle must be the end
 *    of net-UPWARD travel. The old any-direction rule fired on downward travel
 *    too: on a tab whose total overflow is inside the window, every DOWN press
 *    settled inside 1..threshold, bounced back to 0, and pushed the focused
 *    row below the fold — the "sometimes it scrolls weird" half of the report.
 *    Downward settles (value > previous) never snap.
 *
 * Pure so the rule is unit-testable without a scroll container.
 */
fun shouldSnapToTop(scrollValue: Int, previousSettledValue: Int, thresholdPx: Int): Boolean =
    scrollValue in 1..thresholdPx && scrollValue < previousSettledValue

/**
 * Finishes an almost-complete scroll back to the start.
 *
 * ### Why [ProvideFocusScrollSpec] isn't enough on its own
 *
 * Found on-device (Chromecast with Google TV, 2026-07-31) after MK.30.1
 * shipped: the Playback tab still opened with its "Video" header clipped.
 *
 * A [BringIntoViewSpec] only ever sees the *focused element's* rect. Leading
 * headroom therefore measures from the focused element — but the thing the user
 * needs on screen is the section header, and the focusable is a chip sitting
 * ~290px below its section's top (title, subtitle, row label, row hint all come
 * first). No sane headroom reaches that far: 96dp capped at a quarter of the
 * viewport is ~190px, so travelling up unwound the scroll to ~163px and stopped
 * — header still off-screen, and nothing focusable above it to scroll further.
 *
 * The scroll container knows the one thing the spec cannot: how much scroll is
 * actually left. When that remainder is smaller than a section header, there is
 * no reason to keep it — so this closes the gap.
 *
 * Mid-list behaviour is completely unchanged: the remainder there is far past
 * [DEFAULT_TOP_SNAP], so the predicate never fires and focus positioning stays
 * exactly as [focusScrollDistance] left it.
 *
 * Usage — the ScrollState has to be hoisted so both can see it:
 * ```
 * val scroll = rememberScrollState()
 * Column(Modifier.verticalScroll(scroll).snapToTopNearStart(scroll).padding(…))
 * ```
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.snapToTopNearStart(scrollState: ScrollState, threshold: Dp = DEFAULT_TOP_SNAP): Modifier {
    val thresholdPx = with(LocalDensity.current) { threshold.roundToPx() }
    // MB-336 — never snap while the keyboard is up. The IME resize re-settles
    // the scroll, and with the old rule that settle could land inside the snap
    // window and yank the pane to the tab top WHILE THE USER WAS TYPING — the
    // field they pressed vanishing under the header was one confirmed mechanism
    // behind "the screen shows a different thing than the box I pressed".
    // rememberUpdatedState so the running collector sees the CURRENT value
    // without restarting the effect (an IME toggle must not reset [lastSettled]).
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    LaunchedEffect(scrollState, thresholdPx) {
        // MB-336 — direction reference. Seeded from the current value, not 0,
        // so a screen that restores a saved offset (SourceDetailScreen re-opens
        // mid-scroll) still snaps on the user's first upward travel, while a
        // fresh screen's first DOWNWARD settle can never read as "upward".
        var lastSettled = scrollState.value
        snapshotFlow { scrollState.value to scrollState.isScrollInProgress }
            // Only act once motion has stopped. Firing mid-scroll would fight
            // both the bring-into-view animation and a touch drag.
            .collect { (value, inProgress) ->
                if (inProgress) return@collect
                val fire = shouldSnapToTop(value, lastSettled, thresholdPx) && !imeVisible.value
                lastSettled = value
                if (fire) {
                    // MB-336 — the snap used to die permanently here. Both this
                    // animation and every focus bring-into-view drive the same
                    // ScrollState mutex, so a D-pad press landing mid-snap
                    // cancels animateScrollTo with a CancellationException.
                    // Uncaught, it killed the collect loop — and the effect —
                    // for the rest of the composition: the snap worked until
                    // the first interrupted animation and then never again,
                    // which is exactly the "sometimes" in the user's report.
                    // A mutex takeover is NOT our own cancellation, so swallow
                    // it and keep collecting; rethrow only when this coroutine
                    // itself is being torn down.
                    try {
                        scrollState.animateScrollTo(0)
                        lastSettled = 0
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                        // Interrupted by a competing scroll: leave lastSettled
                        // at the pre-snap value so the NEXT settle below it
                        // still reads as upward travel and retries the snap.
                    }
                }
            }
    }
    return this
}

/**
 * Opts [content] out of the ambient headroom/footroom and back to
 * minimum-distance scrolling — which is what [focusScrollDistance] reduces to
 * when both margins are zero.
 *
 * MK.30.2 — needed because [LocalBringIntoViewSpec] is **axis-agnostic**: one
 * spec serves both the vertical scroll it was tuned for and any horizontal
 * rail nested inside it. Leading headroom is what reveals a section header
 * when travelling up a vertical list; applied to a coverflow or chip rail it
 * instead shoves the focused card away from the leading edge and changes the
 * rail's feel. Wrap shared horizontal rails in this so hosting them inside a
 * [ProvideFocusScrollSpec] screen can't alter their scroll behaviour.
 */
@Composable
fun ProvideDefaultFocusScroll(content: @Composable () -> Unit) {
    ProvideFocusScrollSpec(headroom = 0.dp, footroom = 0.dp, content = content)
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ProvideFocusScrollSpec(
    headroom: Dp = FocusScrollDefaults.DEFAULT_HEADROOM,
    footroom: Dp = FocusScrollDefaults.DEFAULT_FOOTROOM,
    content: @Composable () -> Unit,
) {
    val spec = rememberFocusScrollSpec(headroom = headroom, footroom = footroom)
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
