package com.yancotv.android

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.ui.shell.HomeScreen
import com.yancotv.android.ui.shell.SearchOverlayState
import com.yancotv.android.ui.theme.YancoTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isTv = detectTv()
        requestNotificationsPermissionIfNeeded()

        setContent {
            YancoTheme(isTv = isTv) {
                HomeScreen(isTv = isTv)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Mini-preview can host VOD (e.g. a movie the user dismissed back
        // to the shell). Pressing Home while that plays must persist the
        // resume point — PlayerActivity.onPause only covers the fullscreen
        // path. persistResumePoint is a no-op for live streams.
        controller.persistResumePoint()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
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
        return super.onKeyDown(keyCode, event)
    }

    private fun requestNotificationsPermissionIfNeeded() {
        // POST_NOTIFICATIONS is a runtime permission on API 33+. Fire TV on
        // API 32 and below auto-grants it from the manifest declaration.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
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
