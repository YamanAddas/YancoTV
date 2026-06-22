package com.yancotv.android

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.media3.exoplayer.ExoPlayer
import com.yancotv.android.handoff.HandoffReceiverService
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.sources.SourceSyncCoordinator
import com.yancotv.android.ui.focus.TvContextActionState
import com.yancotv.android.ui.shell.HomeScreen
import com.yancotv.android.ui.shell.SearchOverlayState
import com.yancotv.android.ui.theme.YancoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

// MK.26 testing — DEBUG-only sample source seeded on a fresh install. The
// iptv-org news category is publicly-available free-to-air broadcaster streams
// (legal, daily-validated); used only so the app is testable without a provider.
private const val SAMPLE_M3U_URL = "https://iptv-org.github.io/iptv/categories/news.m3u"
private const val SAMPLE_VOD_NAME = "Sample movies (bundled)"

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
    private val contentRepo: com.yancotv.shared.content.ContentRepository by inject()
    private val sourceRepo: com.yancotv.shared.sources.SourceRepository by inject()

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

    // MK.9.4 — track which ExoPlayer instance the keepAwake listener is
    // attached to. After a watchdog rebuild controller.player is a new
    // instance; this ref lets us remove from the released instance and
    // re-add to the replacement on the next attach.
    private var keepAwakeAttachedPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Audit catch — install AndroidX SplashScreen BEFORE super.onCreate
        // so the OS keeps the splash drawable visible through the activity
        // warmup window. Pre-fix: cold launch went launcher → black
        // frame → first Compose frame. Now: launcher → Yanco logo on
        // BackgroundDeep → Compose frame. Handoff back to Theme.YancoTV
        // happens automatically via postSplashScreenTheme in themes.xml.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val isTv = detectTv()
        requestNotificationsPermissionIfNeeded()

        // MK.26.A.1 — on a TV, run the LAN companion-handoff receiver so a
        // phone can "Play on TV". Phones are senders, not receivers, so this
        // is TV-only. Started from the foregrounded launcher activity to
        // satisfy Android 12+ foreground-service-start rules. Idempotent.
        if (isTv) {
            HandoffReceiverService.start(this)
        }

        setContent {
            YancoTheme(isTv = isTv) {
                HomeScreen(isTv = isTv)
            }
        }
        // MK.10.3 — voice search deep link. Google Assistant / Fire TV
        // voice remote dispatches Intent.ACTION_SEARCH (or the global
        // ACTION_VIEW with `query` extra) to the launcher activity that
        // declared the searchable metadata. We pull the recognised text
        // out and prime the global search overlay; SearchScreen consumes
        // it on next composition.
        handleSearchIntent(intent)

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
        // MK.9.4 — re-attach keepAwake to the new ExoPlayer after the
        // watchdog rebuilds. attachKeepAwake is idempotent (compares
        // against the tracked instance ref).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.playerRebuilt.collect {
                    attachKeepAwake()
                }
            }
        }

        // v9 → v10 — per-source "auto-sync on app start" toggle. Read
        // every source flagged on, kick a sync via the same coordinator
        // the manual Sync button uses (so the running-sync banner shows
        // up normally in Settings → Sources). Process-scoped one-shot:
        // a config-change-driven onCreate doesn't re-trigger.
        //
        // Sources are synced sequentially because [SourceSyncCoordinator]
        // refuses concurrent syncs (one shared Active state, one shared
        // banner). The for-loop awaits each completion via
        // `state.filter { it == null }.first()` before starting the next.
        if (!didStartAutoSync) {
            didStartAutoSync = true
            lifecycleScope.launch {
                // DEBUG-only: seed a free/legal sample source on a fresh install
                // so the app is testable without manually adding a provider.
                // Skipped once ANY source exists (never re-adds, never overrides
                // the user's own). Marked auto-sync so the loop below populates
                // it on this same launch.
                if (com.yancotv.android.BuildConfig.DEBUG) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val existing = sourceRepo.getAll()
                            // Fresh install only: seed the free live-news sample.
                            if (existing.isEmpty()) {
                                val news =
                                    sourceRepo.addSource(
                                        com.yancotv.shared.types.AddSourceInput(
                                            name = "iptv-org news (sample)",
                                            type = com.yancotv.shared.types.SourceType.M3U_URL,
                                            url = SAMPLE_M3U_URL,
                                        ),
                                    )
                                sourceRepo.setAutoSyncOnStart(news.id, true)
                            }
                            // Always ensure the bundled VOD sample (Blender
                            // open movies) is present so movie playback +
                            // casting are testable. M3U_FILE + android.resource://
                            // reads the bundled res/raw asset via ContentResolver
                            // — no network, no HttpClient. Added independently of
                            // the live seed so it shows up even when the user
                            // already has the news source.
                            if (existing.none { it.name == SAMPLE_VOD_NAME }) {
                                val vod =
                                    sourceRepo.addSource(
                                        com.yancotv.shared.types.AddSourceInput(
                                            name = SAMPLE_VOD_NAME,
                                            type = com.yancotv.shared.types.SourceType.M3U_FILE,
                                            filePath = "android.resource://$packageName/raw/sample_catalog",
                                        ),
                                    )
                                sourceRepo.setAutoSyncOnStart(vod.id, true)
                            }
                        }
                    }
                }
                val targets =
                    withContext(Dispatchers.IO) {
                        runCatching { sourceRepo.autoSyncOnStartList() }.getOrElse { emptyList() }
                    }
                for (source in targets) {
                    syncCoordinator.start(source.id, source.name)
                    // Wait for this sync to clear before kicking the next
                    // — otherwise start() refuses with "another sync is
                    // active" and the queue silently drops.
                    syncCoordinator.state
                        .filter { it == null }
                        .first()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        attachKeepAwake()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop launchMode: a SEARCH intent fired while the activity
        // is already alive lands here, not in onCreate.
        setIntent(intent)
        handleSearchIntent(intent)
    }

    private fun handleSearchIntent(intent: Intent?) {
        if (intent == null) return
        // MK.10.1 — Recommendations card deep-link. Cards carry the
        // content id in EXTRA_DEEP_LINK_ID; if the row still exists we
        // queue + launch playback. Missing row → fall through (user
        // ends up on home, which is the safe default).
        val deepLinkId =
            intent.getStringExtra(
                com.yancotv.android.recommendations.RecommendationsSync.EXTRA_DEEP_LINK_ID,
            )
        if (!deepLinkId.isNullOrBlank()) {
            // Clear the extra so a config-change recreate doesn't re-fire
            // the deep-link on every onCreate.
            intent.removeExtra(
                com.yancotv.android.recommendations.RecommendationsSync.EXTRA_DEEP_LINK_ID,
            )
            lifecycleScope.launch {
                val item: com.yancotv.shared.types.ContentItem? =
                    withContext(Dispatchers.IO) {
                        runCatching { contentRepo.findById(deepLinkId) }.getOrNull()
                    }
                if (item != null) {
                    if (controller.currentId != item.id) {
                        controller.play(listOf(item), 0)
                    }
                    com.yancotv.android.player.PlayerLauncher.launch(this@MainActivity)
                }
            }
            return
        }

        val query =
            when (intent.action) {
                Intent.ACTION_SEARCH -> intent.getStringExtra(android.app.SearchManager.QUERY)
                Intent.ACTION_VIEW -> intent.getStringExtra(android.app.SearchManager.QUERY)
                else -> null
            }
        if (!query.isNullOrBlank()) {
            SearchOverlayState.show(query.trim())
        }
    }

    override fun onStop() {
        super.onStop()
        keepAwakeAttachedPlayer?.removeListener(keepAwakeListener)
        keepAwakeAttachedPlayer = null
        setKeepScreenOn(false)
        // Mini-preview can host VOD (e.g. a movie the user dismissed back
        // to the shell). Pressing Home while that plays must persist the
        // resume point — PlayerActivity.onPause only covers the fullscreen
        // path. persistResumePoint is a no-op for live streams.
        controller.persistResumePoint()
    }

    /**
     * Attach [keepAwakeListener] to the current `controller.player`. Idempotent:
     * if the tracked instance already matches, only the seed-from-current-state
     * call runs. After a watchdog rebuild the instance differs, so the listener
     * is removed from the released player and added to the new one.
     */
    private fun attachKeepAwake() {
        val target = controller.player
        if (keepAwakeAttachedPlayer !== target) {
            keepAwakeAttachedPlayer?.removeListener(keepAwakeListener)
            target.addListener(keepAwakeListener)
            keepAwakeAttachedPlayer = target
        }
        // Seed from current state: if the mini-preview is already playing
        // when the shell returns to the foreground (e.g. back from
        // PlayerActivity), flip the flag on immediately.
        setKeepScreenOn(target.isPlaying)
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

    private fun isCenterKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

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

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
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

        // Process-scoped one-shot guard for the v9 → v10 auto-sync-on-app-
        // start flag. A configuration-change activity recreate (rotation,
        // font scale) calls onCreate again, but auto-sync should fire at
        // most once per process — i.e. once per cold app launch.
        @Volatile
        private var didStartAutoSync = false
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
