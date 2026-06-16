package com.yancotv.android.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import com.yancotv.android.player.PlaybackController
import com.yancotv.android.prefs.AppPreferences
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.sources.SourceRepository
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MK.26 Track B — Google Cast (Chromecast) controller. Isolated from Media3:
 * the Chromecast is its own REMOTE player driven by `RemoteMediaClient`, so the
 * local ExoPlayer is untouched (just paused while casting).
 *
 * Everything is gated behind [isAvailable] (Google Play Services), so on Fire OS
 * (no Play Services) this is inert — [ensureContext] returns null and no Cast
 * code runs. When the user connects to a Chromecast, [sessionListener] loads
 * whatever is currently playing.
 *
 * MK.26 B.2 Phase 1: casts route through the on-device [CastProxy] (ffmpeg
 * remux/transcode -> HLS over the LAN with provider headers injected), so raw-TS
 * / AC-3 / cleartext / gated IPTV plays on the Default Receiver. HEVC video is
 * copied (fails on Cast until the Phase 2 hardware transcode). Runtime is only
 * verifiable on a real Chromecast.
 */
@UnstableApi
class CastController(
    private val appContext: Context,
    private val controller: PlaybackController,
    private val proxy: CastProxy,
    private val sources: SourceRepository,
    private val prefs: AppPreferences,
    private val logger: Logger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var castContext: CastContext? = null
    private var registered = false

    private val _sessionState = MutableStateFlow<CastSessionState>(CastSessionState.Idle)

    /**
     * Active while a Cast session is connected. The phone player observes this
     * and shows a "Casting to <device>" overlay (with a Stop control) instead of
     * the frozen, locally-paused video frame. Stays [CastSessionState.Idle]
     * forever where Cast is unavailable (no Play Services), so the overlay never
     * appears there.
     */
    val sessionState: StateFlow<CastSessionState> = _sessionState.asStateFlow()

    // Last receiver position (ms), tracked continuously while casting so a remote
    // BACK — which stops the receiver WITHOUT ending the Cast session — can still
    // resume local playback at the right spot (by the time the user taps Stop, the
    // receiver has dropped its position).
    @Volatile private var lastCastPositionMs = 0L

    private fun currentClient(): RemoteMediaClient? = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    private val progressListener =
        RemoteMediaClient.ProgressListener { positionMs, _ -> if (positionMs > 0L) lastCastPositionMs = positionMs }

    // The receiver stopped (remote BACK / finished) but the session is still
    // connected — tear the overlay down + resume local instead of leaving the phone
    // stuck on "Casting…" until the user manually taps Stop.
    private val mediaCallback =
        object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val status = currentClient()?.mediaStatus
                val state = status?.playerState ?: MediaStatus.PLAYER_STATE_UNKNOWN
                // Media finished or the receiver went idle → auto-clear + resume.
                // (NOTE: the TV remote's BACK button sends NO event at all — confirmed
                // via logcat — so it can't be auto-cleared; use the phone's Stop.)
                // Only act while we believe we're casting, so a stray pre-load /
                // transition update can't tear down a healthy session.
                val stopped = status == null || state == MediaStatus.PLAYER_STATE_IDLE
                if (stopped && _sessionState.value is CastSessionState.Active) {
                    endCast()
                }
            }
        }

    // Fires when the receiver APPLICATION goes away — including dismissing the
    // Default Receiver with the TV's own BACK button, which (confirmed via logcat)
    // sends NO media/session event. This is the only signal for that case.
    private val castListener =
        object : Cast.Listener() {
            override fun onApplicationDisconnected(statusCode: Int) {
                logger.info("Cast: application disconnected status=$statusCode")
                endCast()
            }
        }

    /** True only where Google Play Services is present (never on Fire OS). */
    fun isAvailable(): Boolean = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS

    /**
     * Lazily initialise [CastContext], gated by [isAvailable] so it never throws
     * on a Play-Services-less device. Registers the session listener once.
     */
    fun ensureContext(): CastContext? {
        if (!isAvailable()) return null
        if (castContext == null) {
            castContext = runCatching { CastContext.getSharedInstance(appContext) }.getOrNull()
            if (castContext != null && !registered) {
                castContext?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
                registered = true
            }
        }
        return castContext
    }

    /** Show the system device picker. Caller passes an AppCompat Activity context. */
    fun showDevicePicker(activityContext: Context) {
        val selector = ensureContext()?.mergedSelector ?: return
        runCatching {
            MediaRouteChooserDialog(activityContext).apply { routeSelector = selector }.show()
        }.onFailure { logger.warn("Cast: device picker failed — ${it.message}") }
    }

    private val sessionListener =
        object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = startCast(session)

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = startCast(session)

            override fun onSessionStarting(session: CastSession) {}

            override fun onSessionStartFailed(session: CastSession, error: Int) = endCast()

            override fun onSessionEnding(session: CastSession) = endCast()

            override fun onSessionEnded(session: CastSession, error: Int) = endCast()

            override fun onSessionResuming(session: CastSession, sessionId: String) {}

            override fun onSessionResumeFailed(session: CastSession, error: Int) = endCast()

            override fun onSessionSuspended(session: CastSession, reason: Int) {
                logger.info("Cast: session suspended reason=$reason")
            }
        }

    // All SessionManagerListener callbacks fire on the main thread.

    /** Session connected: surface the casting state, then load the current item. */
    private fun startCast(session: CastSession) {
        // getCastDevice() is main-thread-only and we're on the SDK's main callback
        // here — capture the name now and thread it through the IO coroutine so
        // nothing touches the CastSession off-main (that IllegalStateException
        // crash only surfaced once the proxy started succeeding).
        val deviceName = session.castDevice?.friendlyName ?: "your TV"
        runCatching { session.addCastListener(castListener) }
        _sessionState.value = CastSessionState.Active(deviceName)
        loadCurrent(session, deviceName)
    }

    /**
     * Session ended / failed: stop the proxy, clear the casting state so the
     * overlay hides, and resume the local player — CONTINUING where the cast left
     * off, not where it started. Reads the receiver's last position (best-effort:
     * the session may be tearing down) and seeks the local player there so playback
     * is continuous both ways. All idempotent (safe from ending+ended / start-fail).
     */
    private fun endCast() {
        // Prefer the continuously-tracked position (it survives a receiver-side stop
        // where a live read would already be gone); fall back to a live read.
        val resumeMs =
            lastCastPositionMs.takeIf { it > 0L }
                ?: runCatching { currentClient()?.approximateStreamPosition ?: 0L }.getOrDefault(0L)
        lastCastPositionMs = 0L
        runCatching {
            currentClient()?.let {
                it.removeProgressListener(progressListener)
                it.unregisterCallback(mediaCallback)
            }
        }
        runCatching { castContext?.sessionManager?.currentCastSession?.removeCastListener(castListener) }
        proxy.stop()
        _sessionState.value = CastSessionState.Idle
        runCatching {
            if (resumeMs > 0L) controller.player.seekTo(resumeMs)
            controller.player.play()
        }
    }

    /** Stop button on the casting overlay: end the session (→ [endCast]). */
    fun stopCasting() {
        runCatching { castContext?.sessionManager?.endCurrentSession(true) }
            .onFailure { logger.warn("Cast: stop failed — ${it.message}") }
    }

    // onSessionStarted fires on the main thread; pause the local player there,
    // then do the header lookup + proxy spin-up off-main and load on main.
    private fun loadCurrent(session: CastSession, deviceName: String) {
        val item = controller.currentItem.value ?: return
        val url = item.streamUrl.takeIf { it.isNotBlank() } ?: return
        // Resume position (ms), captured on the MAIN thread (ExoPlayer is main-only).
        // Applied as a receiver-side seek via setCurrentTime — now that the proxy
        // serves a complete VOD playlist (MK.26.B.3) the receiver actually honors it.
        val resumeMs = if (item.type == ContentType.LIVE) 0L else controller.player.currentPosition.coerceAtLeast(0L)
        controller.player.pause()
        scope.launch {
            // Resolve the provider headers the same way the local player does.
            val src = runCatching { sources.getById(item.sourceId) }.getOrNull()
            val ua =
                src?.userAgent?.takeIf { it.isNotBlank() }
                    ?: prefs.networkFlow.value.userAgentOverride?.takeIf { it.isNotBlank() }
            val referer = src?.referer?.takeIf { it.isNotBlank() }
            val isLive = item.type == ContentType.LIVE
            // ffmpeg remux/transcode -> HLS the Default Receiver can play.
            val proxyUrl =
                when (val outcome = proxy.start(url, ua, referer, isLive)) {
                    is CastProxyOutcome.Ready -> outcome.url
                    CastProxyOutcome.NoNetwork -> {
                        logger.warn("Cast: no Wi-Fi address to serve ${item.id}")
                        withContext(Dispatchers.Main) {
                            failCast("Couldn't cast — make sure the phone is on the same Wi-Fi as the TV.")
                        }
                        return@launch
                    }
                    CastProxyOutcome.NotReady -> {
                        logger.warn("Cast: proxy couldn't prepare ${item.id} for casting")
                        withContext(Dispatchers.Main) {
                            failCast("Couldn't prepare this video for casting — it may be an unsupported format (e.g. HEVC).")
                        }
                        return@launch
                    }
                }
            val request =
                MediaLoadRequestData.Builder()
                    .setMediaInfo(buildMediaInfo(item, proxyUrl))
                    .setAutoplay(true)
                    .apply { if (resumeMs > 0L) setCurrentTime(resumeMs) }
                    .build()
            withContext(Dispatchers.Main) {
                val client = session.remoteMediaClient
                if (client == null) {
                    logger.warn("Cast: no RemoteMediaClient on session")
                    failCast(castFailedMessage(deviceName))
                    return@withContext
                }
                runCatching {
                    // load() returns a PendingResult: a receiver-side rejection
                    // (the Default Receiver can't decode the proxied stream — e.g.
                    // HEVC until Phase 2) resolves ASYNC here and does NOT throw.
                    // Cover both the sync throw (onFailure) and the async reject.
                    client.load(request).setResultCallback { result ->
                        if (result.status.isSuccess) {
                            // Track the receiver position + watch for a receiver-side
                            // stop (remote BACK) so the phone overlay + local resume
                            // stay in sync with the TV.
                            runCatching {
                                client.registerCallback(mediaCallback)
                                client.addProgressListener(progressListener, 1_000L)
                            }
                        } else {
                            logger.warn("Cast: receiver rejected media — status ${result.status.statusCode}")
                            failCast(castFailedMessage(deviceName))
                        }
                    }
                }.onFailure {
                    logger.warn("Cast: load failed — ${it.message}")
                    failCast(castFailedMessage(deviceName))
                }
            }
            logger.info("Cast: proxying ${item.id} to $deviceName")
        }
    }

    private fun castFailedMessage(deviceName: String): String = "Couldn't cast this stream to $deviceName."

    /**
     * Main-thread only. A cast attempt didn't take — tell the user AND resume the
     * local player we paused in [loadCurrent]. Without the resume the phone is
     * left on a frozen frame with no way back but a manual tap (audit CAST-1/2).
     * Safe on every failure path: nothing is playing on the receiver (the load
     * never succeeded), so there is no double-playback.
     */
    private fun failCast(message: String) {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG).show()
        // Tear the proxy down: a receiver-reject reaches here WITHOUT an
        // onSessionEnded, so without this the ffmpeg transcode + Ktor server leak
        // (audit CAST-SEC-7). Then drop the casting overlay and hand the user back
        // their local playback rather than a "Casting…" screen with nothing on TV.
        runCatching { proxy.stop() }
        _sessionState.value = CastSessionState.Idle
        runCatching { controller.player.play() }
            .onFailure { logger.warn("Cast: couldn't resume local playback — ${it.message}") }
    }

    private fun buildMediaInfo(item: ContentItem, contentUrl: String): MediaInfo {
        val metadata =
            MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, item.displayTitle)
                item.displayLogoUrl?.takeIf { it.isNotBlank() }?.let { addImage(WebImage(Uri.parse(it))) }
            }
        val streamType =
            if (item.type == ContentType.LIVE) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED
        return MediaInfo
            .Builder(contentUrl)
            .setStreamType(streamType)
            // The proxy always serves HLS.
            .setContentType("application/x-mpegurl")
            .setMetadata(metadata)
            .build()
    }
}

/** Phone-side cast UI state — drives the player's "Casting to <device>" overlay. */
sealed interface CastSessionState {
    data object Idle : CastSessionState

    data class Active(val deviceName: String) : CastSessionState
}
