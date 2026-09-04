package com.yancotv.android.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yancotv.android.R
import com.yancotv.android.ui.components.CinematicBackground
import com.yancotv.android.ui.theme.LocalShellMetrics
import com.yancotv.android.ui.theme.LocalYancoPalette
import com.yancotv.android.ui.theme.Space

/**
 * The first screen of our own that a viewer sees.
 *
 * ### Why this exists rather than a richer system splash
 *
 * The platform splash draws exactly one thing — an icon, masked to a circle —
 * and nothing else. It cannot show a wordmark beside a progress indicator, and
 * a wide lockup pushed into a round mask is what MB-350 spent its time undoing.
 * So the system splash keeps the square badge, which a circle suits, and the
 * wordmark moves here, where it can be set as artwork at a size that reads.
 *
 * ### Why it is gated on real work, not a timer
 *
 * A splash held open by `delay()` is a lie that costs the viewer time on every
 * launch. This one is shown while the source list is being read and disappears
 * the moment that returns — so on a warm launch it is a single frame, and on a
 * cold one it covers exactly the wait that was already happening. The gate can
 * never stick: the read either returns or throws, and both release it.
 */
@Composable
fun BrandSplash(modifier: Modifier = Modifier) {
    val palette = LocalYancoPalette.current
    val metrics = LocalShellMetrics.current
    val brandName = stringResource(R.string.app_name)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The same backdrop the shell paints, so the hand-off to Home is a
        // change of content rather than a change of scene.
        CinematicBackground(modifier = Modifier.fillMaxSize())

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.section),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier =
                Modifier
                    // Proportional, like everything else in the shell: the
                    // wordmark should be the same share of a phone and of a
                    // television rather than a constant that suits one of them.
                    .width((metrics.lane * 0.62f).coerceIn(200.dp, 420.dp))
                    .semantics { contentDescription = brandName },
            )
            CircularProgressIndicator(
                color = palette.Accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
