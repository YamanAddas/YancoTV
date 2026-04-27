package com.yancotv.android.player

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.android.prefs.DefaultExternalPlayer
import com.yancotv.shared.types.ContentType
import org.koin.core.context.GlobalContext

/**
 * In-process launcher for [PlayerActivity]. The shared [PlaybackController]
 * owns the queue + URL; this just opens the fullscreen surface on top.
 * Call `controller.play(list, index)` first, then invoke this.
 */
@UnstableApi
object PlayerLauncher {
    fun launch(ctx: Context) {
        val koin = runCatching { GlobalContext.get() }.getOrNull()
        val controller = runCatching { koin?.get<PlaybackController>() }.getOrNull()
        val prefs = runCatching { koin?.get<AppPreferences>() }.getOrNull()

        // MK.18.2 — short-circuit to the configured external player when
        // the current item's content bucket has one set AND the chosen app
        // is installed. INTERNAL (default) and an uninstalled pick both
        // fall through to the normal PlayerActivity launch — playback
        // never strands because the user uninstalled VLC.
        val current = controller?.currentItem?.value
        val streamUrl = current?.streamUrl?.takeIf { it.isNotBlank() }
        val choice = prefs?.externalPlayerFlow?.value?.forContentType(current?.type ?: ContentType.MOVIE)
        val app = choice?.app
        if (current != null && streamUrl != null && choice != DefaultExternalPlayer.INTERNAL && app != null) {
            val installed = ExternalPlayer.installed(ctx).contains(app)
            if (installed) {
                val isLive = current.type == ContentType.LIVE
                val positionMs = if (isLive) null else controller.player.currentPosition.takeIf { it > 0L }
                // Pause the internal player so audio doesn't stack while
                // the external player loads. clearVideoSurface() also
                // prevents the surface-handoff race documented below.
                runCatching { controller.player.pause() }
                runCatching { controller.player.clearVideoSurface() }
                val ok =
                    ExternalPlayer.launch(
                        context = ctx,
                        streamUrl = streamUrl,
                        positionMs = positionMs,
                        app = app,
                    )
                if (ok) return
                // External launch failed (rare — package vanished between
                // the installed check and startActivity). Fall through to
                // the internal player.
            }
        }

        // Detach the MiniPlayer's TextureView surface BEFORE starting the
        // fullscreen activity. Without this, PlayerActivity.onStart races
        // with the still-attached TextureView: ExoPlayerImpl.setVideoOutput
        // blocks the main thread waiting up to 5 s for the prior surface
        // ack, then raises ExoTimeoutException → ERROR_CODE_TIMEOUT and the
        // player drops to IDLE. Symptom: "video can't catch the stream"
        // plus ~120-frame Choreographer skips at the activity boundary.
        // Clearing here means PlayerActivity sees no prior output and the
        // surface swap is one-sided.
        runCatching { controller?.player?.clearVideoSurface() }
        val intent =
            Intent(ctx, PlayerActivity::class.java).apply {
                if (ctx !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        ctx.startActivity(intent)
    }
}
