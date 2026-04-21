package com.yancotv.android.ui.shell

import android.view.TextureView
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
import com.yancotv.android.player.PlaybackController
import org.koin.compose.koinInject

/**
 * In-shell preview. Hosts a [TextureView] bound to the shared
 * [PlaybackController.player] ExoPlayer via `setVideoTextureView`.
 *
 * TextureView (not SurfaceView) because SurfaceView inside a Compose
 * `AndroidView` renders on its own window layer, not in the Compose
 * drawing tree, which shows through as a black rectangle on Fire TV.
 * TextureView composites normally at the cost of a bit of GPU overhead,
 * which is fine for a 320x180 preview.
 *
 * The fullscreen [com.yancotv.android.player.PlayerActivity] uses
 * SurfaceView (via its XML layout) — higher perf, and that activity isn't
 * hosted inside Compose so the compositing issue doesn't apply.
 *
 * When the fullscreen activity resigns the surface on its `onStop`, this
 * view reclaims it on the next `ON_RESUME` by re-binding through the
 * TextureView API.
 */
@UnstableApi
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    controller: PlaybackController = koinInject(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val viewRef = remember { arrayOfNulls<TextureView>(1) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                viewRef[0] = this
                controller.player.setVideoTextureView(this)
            }
        },
        update = { view ->
            viewRef[0] = view
            controller.player.setVideoTextureView(view)
        },
    )

    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewRef[0]?.let { v ->
                    // Fullscreen activity may have taken the surface — reclaim it.
                    controller.player.setVideoTextureView(v)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
