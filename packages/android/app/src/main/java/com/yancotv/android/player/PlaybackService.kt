package com.yancotv.android.player

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Media3 [MediaSessionService] that owns the single app-wide [ExoPlayer].
 *
 * Every playback surface in the app — the mini preview in the shell, the
 * fullscreen [PlayerActivity], and system media buttons (BT headsets,
 * Android Auto, the lock-screen notification on phone) — routes through a
 * [androidx.media3.session.MediaController] bound to the session this
 * service exposes.
 *
 * Why this sits in a Service (not the controller singleton we had in MK.0):
 *
 *   * Background playback. When the user backgrounds the shell or locks
 *     the phone, the service keeps decoding. A Koin singleton does not —
 *     the Application process is eligible for kill once no visible
 *     component references it.
 *   * Media-style notification. [MediaSessionService]'s default
 *     [androidx.media3.session.DefaultMediaNotificationProvider] renders
 *     a platform media notification with metadata (title, artwork, group)
 *     and transport controls that route to the player without any custom
 *     glue.
 *   * Foreground state machine. Media3 handles `startForeground` +
 *     `stopForeground` automatically based on [Player.playWhenReady] and
 *     playback state, which keeps us inside the Android 14 foreground
 *     service rules without hand-rolling lifecycle code.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(DEFAULT_USER_AGENT)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory),
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * If the user swipes the app away from recents AND nothing is actively
     * playing, stop the service. If audio is still playing (phone user
     * listening to something), keep going — that's the entire point of a
     * media session service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        // VLC UA is the de-facto default IPTV providers whitelist; matches
        // what the shell's OkHttp source clients send and the fullscreen
        // player sent before the service refactor. Keeping it identical
        // avoids provider-side UA checks flipping streams to audio-only.
        private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
        private const val CONNECT_TIMEOUT_SEC = 15L
        private const val READ_TIMEOUT_SEC = 30L
    }
}
