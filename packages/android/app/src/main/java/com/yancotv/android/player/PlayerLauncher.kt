package com.yancotv.android.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import org.koin.core.context.GlobalContext

/**
 * In-process launcher for [PlayerActivity]. The shared [PlaybackController]
 * owns the queue + URL; this just opens the fullscreen surface on top.
 * Call `controller.play(list, index)` first, then invoke this.
 */
@UnstableApi
object PlayerLauncher {
    fun launch(ctx: Context) {
        // Detach the MiniPlayer's TextureView surface BEFORE starting the
        // fullscreen activity. Without this, PlayerActivity.onStart races
        // with the still-attached TextureView: ExoPlayerImpl.setVideoOutput
        // blocks the main thread waiting up to 5 s for the prior surface
        // ack, then raises ExoTimeoutException → ERROR_CODE_TIMEOUT and the
        // player drops to IDLE. Symptom: "video can't catch the stream"
        // plus ~120-frame Choreographer skips at the activity boundary.
        // Clearing here means PlayerActivity sees no prior output and the
        // surface swap is one-sided.
        runCatching {
            val controller = GlobalContext.get().get<PlaybackController>()
            controller.player.clearVideoSurface()
        }
        val intent = Intent(ctx, PlayerActivity::class.java).apply {
            if (ctx !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        ctx.startActivity(intent)
    }
}
