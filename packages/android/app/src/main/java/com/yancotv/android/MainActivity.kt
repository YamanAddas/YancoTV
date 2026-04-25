package com.yancotv.android

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.ui.focus.TvContextActionState
import com.yancotv.android.ui.shell.HomeScreen
import com.yancotv.android.ui.shell.SearchOverlayState
import com.yancotv.android.ui.theme.YancoTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

@UnstableApi
class MainActivity : ComponentActivity() {
    // We silently accept whatever the user chooses. Reminders still schedule
    // either way — a denied permission just means the notification drops on
    // the floor when AlarmManager fires. The Guide's "Set reminder" state
    // works regardless.
    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val controller: PlaybackController by inject()
    private val syncCoordinator: SourceSyncCoordinator by inject()

    // Keep the shell's window awake only while the shared ExoPlayer is
    // actually playing — covers the mini-preview case where MainActivity
    // hosts playback. Without this the TV's inactivity timer puts the
    // screen to sleep mid-channel. Cleared on pause so the device can
    // sleep normally when nothing is on.
    private val keepAwakeListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                setKeepScreenOn(isPlaying)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isTv = detectTv()
        requestNotificationsPermissionIfNeeded()

        setContent {
            YancoTheme(isTv = isTv) {
                HomeScreen(isTv = isTv)
            }
        }

        // Subscribe to the sync coordinator's error bus so bad-credential
        // and unreachable-host failures reach the user even when they've
        // navigated away from the Sources screen. repeatOnLifecycle keeps
        // the collector scoped to STARTED so we don't pop toasts while
        // the shell is backgrounded.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                syncCoordinator.errors.collect { message ->
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.player.addListener(keepAwakeListener)
        // Seed from current state: if the mini-preview is already playing
        // when the shell returns to the foreground (e.g. back from
        // PlayerActivity), flip the flag on immediately.
        setKeepScreenOn(controller.player.isPlaying)
    }

    override fun onStop() {
        super.onStop()
        controller.player.removeListener(keepAwakeListener)
        setKeepScreenOn(false)
        // Mini-preview can host VOD (e.g. a movie the user dismissed back
        // to the shell). Pressing Home while that plays must persist the
        // resume point — PlayerActivity.onPause only covers the fullscreen
        // path. persistResumePoint is a no-op for live streams.
        controller.persistResumePoint()
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // MB-98 — D-pad CENTER long-press tracking. We deliberately don't use
    // KeyEvent.startTracking() / onKeyLongPress: that path requires
    // returning true from onKeyDown to start tracking, which would prevent
    // Compose's combinedClickable from seeing the DOWN and would break the
    // short-press onClick. Instead we run a manual 500ms timer scoped to
    // the held key. Cancelled on UP (short-press, falls through to onClick)
    // or fires the focused-card action and swallows the eventual UP
    // (long-press; consumed so combinedClickable.onClick doesn't also run).
    private var longPressJob: Job? = null
    private var longPressFired = false

    private fun isCenterKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        // Global search hotkeys — work from anywhere in the shell without
        // first navigating to the Search sidebar destination. TV remotes
        // send KEYCODE_SEARCH; phone / bluetooth keyboards send Ctrl-K.
        // Short-circuit before super so the key doesn't also trigger a
        // device-level global search handler.
        val isSearchKey = keyCode == KeyEvent.KEYCODE_SEARCH
        val isCtrlK = keyCode == KeyEvent.KEYCODE_K && (event?.isCtrlPressed == true)
        if (isSearchKey || isCtrlK) {
            SearchOverlayState.show()
            return true
        }

        // MB-98 — KEYCODE_MENU is the canonical Android TV context-action
        // key (Fire TV voice remote ≡, Shield ⋮). Single-press; fires
        // whichever card currently holds focus. If no card is registered
        // (e.g. focus is on the sidebar), fall through to default handling.
        if (keyCode == KeyEvent.KEYCODE_MENU && event?.repeatCount == 0) {
            if (TvContextActionState.fire()) return true
        }

        // MB-98 — start the long-press timer on the FIRST CENTER/ENTER DOWN
        // only. Repeats arrive while held; we ignore those (the timer is
        // already running). Don't consume — Compose still needs the DOWN
        // for its combinedClickable press state and for the eventual short
        // onClick on UP.
        if (event?.repeatCount == 0 && isCenterKey(keyCode)) {
            longPressJob?.cancel()
            longPressFired = false
            longPressJob =
                lifecycleScope.launch {
                    delay(LONG_PRESS_TIMEOUT_MS)
                    if (TvContextActionState.fire()) {
                        longPressFired = true
                    }
                }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent?,
    ): Boolean {
        // MB-98 — short-press path: cancel pending timer, let combinedClickable
        // see UP and fire onClick normally.
        // Long-press path: timer already fired and opened the menu, so swallow
        // this UP to keep onClick from also running on release.
        if (isCenterKey(keyCode)) {
            longPressJob?.cancel()
            longPressJob = null
            if (longPressFired) {
                longPressFired = false
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        // Standard Android long-press threshold. Matches ViewConfiguration's
        // default and what users have been trained to expect from the
        // platform (settings dialogs, launcher icon edits, etc.).
        private const val LONG_PRESS_TIMEOUT_MS = 500L
    }

    private fun requestNotificationsPermissionIfNeeded() {
        // POST_NOTIFICATIONS is a runtime permission on API 33+. Fire TV on
        // API 32 and below auto-grants it from the manifest declaration.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun detectTv(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
