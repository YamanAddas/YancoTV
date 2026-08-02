package com.yancotv.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yancotv.android.R
import com.yancotv.android.ui.theme.LocalYancoPalette
import kotlinx.coroutines.flow.StateFlow

/**
 * MB-343 (plan W4 / MK.25.B.3) — payload for [UpNextOverlay].
 *
 * Non-nullable with a [visible] flag rather than a nullable card, matching
 * [SeekFlashOverlay]'s `seconds != 0` shape. A nullable payload would blank the
 * text the instant the card is dismissed and leave the exit animation drawing an
 * empty box, which would otherwise need retained state outside composition.
 *
 * @param label the next episode, already rendered — `S1E3 · Title`, or bare
 *   `S1E3` when the provider gave no usable title. Built by the activity from
 *   [episodeKicker] so the card and the dock's kicker agree on the code format.
 * @param remainingMs time until the current episode ends, clamped at zero.
 * @param showCountdown whether to render the "NEXT IN m:ss" line at all.
 *
 * False when the user's `autoPlayNext` preference is off — which is the SHIPPED
 * DEFAULT. Nothing advances at the end of the episode in that configuration, so
 * a countdown would be a promise with nothing behind it: the number would reach
 * zero and the player would simply park on the last frame. The card is still
 * worth showing there — naming what is queued is exactly the information the
 * user needs to decide whether to reach for the dock's NEXT button — it just
 * must not claim a transition is coming.
 */
internal data class UpNextCardData(val visible: Boolean = false, val label: String = "", val remainingMs: Long = 0L, val showCountdown: Boolean = false)

/**
 * MB-343 — the up-next card.
 *
 * What the user sees in the last 20 seconds of an episode: which episode is
 * queued, and how long until it starts. That is the *warning* half of W4;
 * autoplay's timing is unchanged.
 *
 * **This overlay is deliberately inert.** Like [SeekFlashOverlay] it does not
 * pull focus, owns no key binding, advances nothing, and is absent from the
 * `noOverlay` expression in `PlayerActivity.dispatchKeyEvent`. Every one of
 * those is a decision, not an omission:
 *
 *  - A focus target here would have to be reachable, escapable, and ordered
 *    against the dock's own initial-focus push — and a card that grabs focus
 *    mid-seek steals the key stream from a gesture already in progress.
 *  - A key binding would silently reassign a key for the last 20 s of every
 *    episode, with no way for the user to know it had changed.
 *  - Adding it to `noOverlay` would switch off the CENTER long-press arm and
 *    both LIVE pre-empt blocks for that window, which is MB-247's shape.
 *
 * The *jump early* half of W4 is served by the dock's existing NEXT transport
 * button, which was already focusable, RTL-correct and one CENTER press away —
 * it was simply never wired for episodes, because `play(episode)` synthesises a
 * one-item queue and the button gated on `queue.size > 1`. Fixing that is a
 * smaller and better-behaved change than inventing a second activation surface.
 *
 * Consequently the card is suppressed whenever the dock is up: the dock already
 * shows time remaining and the NEXT button, and two things in the same corner is
 * worse than either alone. [UpNextDecision.shouldShow] takes that as an input so
 * it is a tested rule rather than a rendering accident.
 */
@Composable
internal fun UpNextOverlay(flow: StateFlow<UpNextCardData>) {
    val data by flow.collectAsState()
    val palette = LocalYancoPalette.current
    AnimatedVisibility(
        visible = data.visible,
        enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 3 }),
        exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 3 }),
    ) {
        Column(
            modifier =
            Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xE60A1410))
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.vd_up_next),
                color = palette.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = data.label,
                color = palette.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (data.showCountdown) {
                Spacer(Modifier.height(4.dp))
                Text(
                    // Reuses DockTimeFormatter so the countdown, the dock's
                    // remaining time and the seek badge all agree on formatting
                    // and on locale-aware digits.
                    text = stringResource(R.string.vd_next_in, DockTimeFormatter.formatClock(data.remainingMs)),
                    color = palette.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
