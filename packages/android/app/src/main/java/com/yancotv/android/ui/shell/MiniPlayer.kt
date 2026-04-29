package com.yancotv.android.ui.shell

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import org.koin.compose.koinInject

/**
 * In-shell preview. Hosts an [AndroidExternalSurface] (Compose 1.6+) —
 * SurfaceView-backed but properly composited within the Compose tree.
 *
 * **MB-118 fix (2026-04-27):** the previous TextureView implementation
 * showed black on channels whose hardware decoder only outputs to a
 * SurfaceView (some HEVC profiles on Fire TV-class hardware). The
 * obvious "switch to SurfaceView" fix had its own problem under the
 * old API — SurfaceView inside `AndroidView` rendered on its own
 * window layer, leaving a black rectangle in the Compose tree.
 * `AndroidExternalSurface` is the modern Compose primitive that
 * resolves both: it punches a Surface into the Compose tree with
 * proper z-ordering, so the same hardware path that the fullscreen
 * `PlayerActivity` uses works here too.
 *
 * The fullscreen [com.yancotv.android.player.PlayerActivity] uses
 * SurfaceView via its XML layout — same underlying surface type, so
 * channels that play in fullscreen now also play in the preview.
 *
 * When the fullscreen activity resigns the surface on its `onStop`,
 * this preview reclaims it on the next `ON_RESUME` by re-binding the
 * Surface to the player.
 */
@UnstableApi
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, controller: PlaybackController = koinInject()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Cache the live Surface so the lifecycle observer + watchdog effect
    // can re-bind without waiting for the next onSurface emission. Null
    // when the SurfaceView hasn't created its surface yet (very brief
    // window during composition) or after the surface is destroyed.
    val surfaceRef = remember { mutableStateOf<android.view.Surface?>(null) }

    AndroidExternalSurface(
        // Hide from Compose focus search — same constraint as the
        // previous TextureView path. Without this, after the hero flips
        // from idle → playing, focus could land inside the interop
        // subtree (which has no focus visual) and the chips/rail look
        // like they lost their selector. See BrowseShell MiniPlayer
        // focus leak regression, 2026-04-22 fix.
        //
        // Sizing is delegated to the caller; modifier comes through
        // unmodified except for the focus block.
        modifier = modifier.focusProperties { canFocus = false },
        onInit = {
            onSurface { surface, _, _ ->
                surfaceRef.value = surface
                controller.player.setVideoSurface(surface)
                surface.onDestroyed {
                    if (surfaceRef.value === this) {
                        surfaceRef.value = null
                        // Symmetric detach. Without it, the player keeps
                        // a dangling Surface reference once SurfaceView
                        // tears down — the next attach can deadlock on
                        // ExoTimeoutException ("Detaching surface timed
                        // out"). See PlayerActivity.onStop's matching
                        // clearVideoSurface() call (MB-119 fix).
                        controller.player.clearVideoSurface(this)
                    }
                }
            }
        },
    )

    DisposableEffect(lifecycle, controller) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    surfaceRef.value?.let { s ->
                        // Fullscreen activity may have taken the surface
                        // when it ran. Clear-then-attach keeps the swap
                        // one-sided so the next setVideoSurface doesn't
                        // block the main thread waiting for the prior
                        // ack (PlayerLauncher comment + ExoTimeoutException
                        // "Detaching surface timed out").
                        controller.player.clearVideoSurface()
                        controller.player.setVideoSurface(s)
                    }
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // MK.9.4 — re-bind the Surface after a watchdog rebuild. The old
    // ExoPlayer was released; controller.player now points at the
    // platform-only replacement, which has no surface attached.
    // Without this the mini-preview goes black after a recovered crash.
    LaunchedEffect(controller) {
        controller.playerRebuilt.collect {
            surfaceRef.value?.let { s ->
                controller.player.clearVideoSurface()
                controller.player.setVideoSurface(s)
            }
        }
    }
}

@Suppress("UnusedReceiverParameter")
private fun AndroidExternalSurfaceScope.unused() {
    // Keeps the import path stable; the [AndroidExternalSurfaceScope] type
    // is referenced via the `onInit` lambda receiver but the IDE may not
    // realize it's used.
}
