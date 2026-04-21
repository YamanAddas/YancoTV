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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.yancotv.android.R
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Fullscreen player. Attaches the shared [PlaybackController.player] to the
 * local [PlayerView] via [PlayerView.switchTargetView] so the surface handed
 * off from the mini-preview keeps decoding without a rebuffer.
 *
 * The controller owns the queue; this activity only drives D-pad zap and
 * re-renders the title overlay when [PlaybackController.currentItem] ticks.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "YancoPlayerActivity"
    }

    private val controller: PlaybackController by inject()

    private lateinit var playerView: PlayerView
    private lateinit var titleOverlay: TextView

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

        controller.player.addListener(listener)

        lifecycleScope.launch {
            controller.currentItem.collect { item ->
                val title = item?.cleanTitle ?: item?.title
                if (title.isNullOrBlank()) {
                    titleOverlay.visibility = View.GONE
                } else {
                    titleOverlay.text = title
                    titleOverlay.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attachShared()
    }

    override fun onStop() {
        super.onStop()
        // Hand the player back by detaching this view; the mini PlayerView
        // re-attaches on the next composition, so the stream never pauses.
        if (playerView.player === controller.player) {
            playerView.player = null
        }
    }

    override fun onDestroy() {
        controller.player.removeListener(listener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        attachShared()
    }

    private fun attachShared() {
        // Assigning the shared player to this view is enough — Media3
        // re-parents the surface on set. The mini PlayerView in the shell
        // re-attaches on the next composition when we come back.
        playerView.player = controller.player
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
                controller.player.playWhenReady = !controller.player.playWhenReady
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
}
