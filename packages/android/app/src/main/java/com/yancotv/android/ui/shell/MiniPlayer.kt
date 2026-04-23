package com.yancotv.android.ui.shell

import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
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
        // Hide the whole AndroidView wrapper from Compose's focus search.
        // Without this the embedded TextureView subtree participates in
        // focus traversal, and after the hero flips from idle → playing
        // (OK on a rail card) Compose lands focus *inside* the interop
        // subtree — which has no focus visual — so the chip bar and rail
        // look like they've lost their selector until the user blindly
        // wakes focus with an arrow key. See BrowseShell MiniPlayer focus
        // leak regression, 2026-04-22 fix.
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .focusProperties { canFocus = false },
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                isFocusable = false
                isFocusableInTouchMode = false
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
        onDispose {
            lifecycle.removeObserver(observer)
            // Detach the surface so ExoPlayer stops rendering to this view
            // after it leaves composition. Without this the player keeps a
            // dangling reference, continues playing audio in the background,
            // and crashes when the next MiniPlayer calls setVideoTextureView.
            viewRef[0]?.let { controller.player.clearVideoTextureView(it) }
        }
    }
}
