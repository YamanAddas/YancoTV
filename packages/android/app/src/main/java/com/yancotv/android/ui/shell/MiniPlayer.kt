package com.yancotv.android.ui.shell

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.yancotv.android.player.PlaybackController
import org.koin.compose.koinInject

/**
 * In-shell preview. Hosts a [PlayerView] bound to the shared
 * [PlaybackController.player] ([androidx.media3.session.MediaController]
 * proxy for [com.yancotv.android.player.PlaybackService]'s ExoPlayer).
 *
 * The controller is null until [PlaybackController.connect] resolves the
 * bind; we collect [PlaybackController.connected] so the view is
 * reassigned the moment the proxy is available.
 *
 * When the fullscreen [com.yancotv.android.player.PlayerActivity] resigns
 * the surface on its `onStop`, this view reclaims it on the next
 * `ON_RESUME` so the stream continues without a rebuffer.
 */
@UnstableApi
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    controller: PlaybackController = koinInject(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val viewRef = remember { arrayOfNulls<PlayerView>(1) }
    val connected by controller.connected.collectAsState()

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                useController = false
                player = controller.player
                viewRef[0] = this
            }
        },
        update = { view ->
            viewRef[0] = view
            // `connected` is read so update runs when the controller flips
            // from null to bound; the flag itself isn't used, the recompose
            // is what matters.
            val ignored = connected
            if (view.player !== controller.player) view.player = controller.player
        },
    )

    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewRef[0]?.let { v ->
                    val target = controller.player
                    if (target != null && v.player !== target) {
                        // Fullscreen activity may have taken the surface;
                        // reassigning forces the PlayerView to reparent.
                        v.player = null
                        v.player = target
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
