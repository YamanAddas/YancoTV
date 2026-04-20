package com.yancotv.mobile.player

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.yancotv.mobile.R
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Native TiviMate-style player. Hosts ExoPlayer + PlayerView directly —
 * no React Native bridge in the playback path. Launched from JS via
 * [PlayerLauncherModule]; finishes on BACK.
 *
 * This is the single source of truth for playback on Android. RN owns
 * only the library/browse UI.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "YancoPlayerActivity"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_USER_AGENT = "extra_user_agent"

        private const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleOverlay: TextView

    private var startUrl: String? = null
    private var startTitle: String? = null
    private var userAgent: String = DEFAULT_USER_AGENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive fullscreen — hide status + nav bars, keep screen on.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.player_view)
        titleOverlay = findViewById(R.id.title_overlay)

        readIntent(intent)
        startTitle?.let {
            titleOverlay.text = it
            titleOverlay.visibility = View.VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        // Fresh URL mid-session: rebuild the player so HLS/DASH manifests reload cleanly.
        releasePlayer()
        startUrl?.let { startPlayer(it) }
        startTitle?.let {
            titleOverlay.text = it
            titleOverlay.visibility = View.VISIBLE
        }
    }

    private fun readIntent(intent: Intent?) {
        if (intent == null) return
        startUrl = intent.getStringExtra(EXTRA_URL)
        startTitle = intent.getStringExtra(EXTRA_TITLE)
        intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }?.let {
            userAgent = it
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) startPlayerIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23) startPlayerIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT <= 23) releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) releasePlayer()
    }

    private fun startPlayerIfNeeded() {
        val url = startUrl ?: run {
            Log.e(TAG, "No URL provided — finishing.")
            finish()
            return
        }
        if (player == null) startPlayer(url)
    }

    private fun startPlayer(url: String) {
        Log.i(TAG, "startPlayer url=${redact(url)}")

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(userAgent)

        val mediaSourceFactory: MediaSource.Factory =
            DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                addListener(listener)
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                prepare()
            }

        playerView.player = exo
        playerView.useController = true
        playerView.controllerAutoShow = true
        playerView.controllerHideOnTouch = true
        playerView.setControllerShowTimeoutMs(4000)
        playerView.requestFocus()
        player = exo
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        playerView.player = null
    }

    // D-pad BACK closes the player. MENU re-shows controls.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                playerView.showController()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError ${error.errorCodeName}", error)
        }

        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            Log.i(TAG, "onPlaybackStateChanged=$name")
        }
    }

    private fun redact(url: String): String =
        url.replace(Regex("^(https?://[^/]+)/[^/]+/[^/]+/(.+)$"), "$1/***/***/$2")
}
