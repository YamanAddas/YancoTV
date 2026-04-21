package com.yancotv.android

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.yancotv.android.ui.shell.HomeScreen
import com.yancotv.android.ui.theme.YancoTheme

class MainActivity : ComponentActivity() {

    // We silently accept whatever the user chooses. Reminders still schedule
    // either way — a denied permission just means the notification drops on
    // the floor when AlarmManager fires. The Guide's "Set reminder" state
    // works regardless.
    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

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
