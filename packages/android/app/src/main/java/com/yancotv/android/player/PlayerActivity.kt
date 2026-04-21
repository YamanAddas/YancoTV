package com.yancotv.android.player

import android.content.Intent
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.yancotv.android.R
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Fullscreen player. Attaches the shared [PlaybackController.player]
 * ExoPlayer to the local [PlayerView] so the decoder handed off from the
 * mini preview keeps running without a rebuffer.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "YancoPlayerActivity"
    }

    private val controller: PlaybackController by inject()

    private lateinit var playerView: PlayerView
    private lateinit var titleOverlay: TextView
    private var listenerAttached = false

    private val playerListener = object : Player.Listener {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        playerView.useController = true
        playerView.controllerAutoShow = true
        playerView.controllerHideOnTouch = true
        playerView.setControllerShowTimeoutMs(4000)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.currentItem.collect { item ->
                    val title = item?.cleanTitle?.ifBlank { null } ?: item?.title
                    if (title.isNullOrBlank()) {
                        titleOverlay.visibility = View.GONE
                    } else {
                        titleOverlay.text = title
                        titleOverlay.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attachShared()
    }

    override fun onPause() {
        super.onPause()
        // Snapshot the VOD resume point every time the activity leaves the
        // foreground. Live streams are ignored inside persistResumePoint.
        controller.persistResumePoint()
    }

    override fun onStop() {
        super.onStop()
        // Hand the player back to the mini by detaching this view. The
        // MiniPlayer observes ON_RESUME and reclaims the surface on the
        // next composition, so the stream never pauses.
        playerView.player = null
    }

    override fun onDestroy() {
        if (listenerAttached) {
            controller.player.removeListener(playerListener)
            listenerAttached = false
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        attachShared()
    }

    private fun attachShared() {
        val target = controller.player
        if (!listenerAttached) {
            target.addListener(playerListener)
            listenerAttached = true
        }
        playerView.player = target
        playerView.requestFocus()
    }

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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (controller.previous()) return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (controller.next()) return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                val p = controller.player
                p.playWhenReady = !p.playWhenReady
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
