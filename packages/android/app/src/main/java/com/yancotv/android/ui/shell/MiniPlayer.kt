package com.yancotv.android.ui.shell

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
 * [PlaybackController.player]. When the fullscreen
 * [com.yancotv.android.player.PlayerActivity] resigns the surface on its
 * `onStop`, this view reclaims it on the next `ON_RESUME` so the stream
 * continues without a rebuffer.
 */
@OptIn(UnstableApi::class)
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    controller: PlaybackController = koinInject(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val viewRef = remember { arrayOfNulls<PlayerView>(1) }

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
            if (view.player !== controller.player) view.player = controller.player
        },
    )

    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewRef[0]?.let { v ->
                    if (v.player !== controller.player) v.player = controller.player
                    else {
                        // Surface may have been claimed by fullscreen; reassign to reclaim.
                        v.player = null
                        v.player = controller.player
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
