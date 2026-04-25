package com.yancotv.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import org.koin.core.context.GlobalContext

/**
 * Debug-only verification entry point for the MK.9.4 FFmpeg watchdog. Calls
 * [PlaybackController.debugForceRebuildForVerification], which exercises
 * the same release → snapshot → rebuild → restore → emit-signal path that
 * fires on a real FFmpeg-package error.
 *
 * What you should see in `adb logcat`:
 *
 *   ExoPlayerImpl: Init <new id> [AndroidXMedia3/...]
 *     ↑ new player instance constructed
 *
 * What you should NOT see in logcat:
 *
 *   DefaultRenderersFactory: Loaded FfmpegAudioRenderer.
 *     ↑ the rebuilt player uses EXTENSION_RENDERER_MODE_OFF, so the FFmpeg
 *       extension is intentionally not loaded on the rebuilt instance
 *
 * What you should see on screen (if a stream was playing when triggered):
 *   - A brief buffer (the new player re-prepares the same MediaItem at the
 *     captured position)
 *   - Playback continues, picture and audio resume
 *   - For LIVE streams: re-syncs to the live edge automatically
 *
 * Trigger:
 *   adb shell am broadcast -a com.yancotv.android.debug.WATCHDOG_SMOKE_TEST
 *
 * Production builds never see this receiver.
 */
@UnstableApi
class WatchdogSmokeTestReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "YancoWatchdogSmoke"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // PlaybackController calls into ExoPlayer, which is main-thread-only.
        // BroadcastReceivers run on main by default but this is defensive —
        // posting to the main looper makes the threading guarantee explicit.
        Handler(Looper.getMainLooper()).post {
            try {
                val controller: PlaybackController = GlobalContext.get().get()
                Log.i(TAG, "Forcing watchdog rebuild for verification…")
                controller.debugForceRebuildForVerification()
                Log.i(TAG, "Watchdog rebuild dispatched — check ExoPlayerImpl: Init log line")
            } catch (t: Throwable) {
                Log.e(TAG, "Watchdog smoke test failed", t)
            }
        }
    }
}
