package com.yancotv.android.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
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
            override fun onSessionStarted(session: CastSession, sessionId: String) = loadCurrent(session)

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = loadCurrent(session)

            override fun onSessionStarting(session: CastSession) {}

            override fun onSessionStartFailed(session: CastSession, error: Int) {}

            override fun onSessionEnding(session: CastSession) = proxy.stop()

            override fun onSessionEnded(session: CastSession, error: Int) = proxy.stop()

            override fun onSessionResuming(session: CastSession, sessionId: String) {}

            override fun onSessionResumeFailed(session: CastSession, error: Int) {}

            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }

    // onSessionStarted fires on the main thread; pause the local player there,
    // then do the header lookup + proxy spin-up off-main and load on main.
    private fun loadCurrent(session: CastSession) {
        val item = controller.currentItem.value ?: return
        val url = item.streamUrl.takeIf { it.isNotBlank() } ?: return
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
            val proxyUrl = proxy.start(url, ua, referer, isLive)
            if (proxyUrl == null) {
                logger.warn("Cast: proxy unavailable — cannot cast ${item.id}")
                return@launch
            }
            val request =
                MediaLoadRequestData.Builder().setMediaInfo(buildMediaInfo(item, proxyUrl)).setAutoplay(true).build()
            withContext(Dispatchers.Main) {
                runCatching { session.remoteMediaClient?.load(request) }
                    .onFailure { logger.warn("Cast: load failed — ${it.message}") }
            }
            logger.info("Cast: proxying ${item.id} to ${session.castDevice?.friendlyName}")
        }
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
