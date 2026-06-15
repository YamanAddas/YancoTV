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
import com.yancotv.shared.logger.Logger
import com.yancotv.shared.types.ContentItem
import com.yancotv.shared.types.ContentType

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
 * Reality check: the Default Media Receiver only plays H.264/AAC MP4 over
 * HTTPS+CORS — most IPTV VOD qualifies in container but not always in transport
 * (cleartext / no CORS), and raw-TS live does NOT play. Failures surface on the
 * Chromecast itself; a custom receiver + proxy (B.2/B.3) is the fix, out of
 * scope here.
 */
@UnstableApi
class CastController(private val appContext: Context, private val controller: PlaybackController, private val logger: Logger) {
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

            override fun onSessionEnding(session: CastSession) {}

            override fun onSessionEnded(session: CastSession, error: Int) {}

            override fun onSessionResuming(session: CastSession, sessionId: String) {}

            override fun onSessionResumeFailed(session: CastSession, error: Int) {}

            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }

    // Cast callbacks run on the main thread, so touching the main-thread-only
    // PlaybackController here is safe.
    private fun loadCurrent(session: CastSession) {
        val item = controller.currentItem.value ?: return
        val mediaInfo = buildMediaInfo(item) ?: return
        val request = MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).setAutoplay(true).build()
        runCatching {
            session.remoteMediaClient?.load(request)
            controller.player.pause()
            logger.info("Cast: loading ${item.id} on ${session.castDevice?.friendlyName}")
        }.onFailure { logger.warn("Cast: load failed — ${it.message}") }
    }

    private fun buildMediaInfo(item: ContentItem): MediaInfo? {
        val url = item.streamUrl.takeIf { it.isNotBlank() } ?: return null
        val metadata =
            MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, item.displayTitle)
                item.displayLogoUrl?.takeIf { it.isNotBlank() }?.let { addImage(WebImage(Uri.parse(it))) }
            }
        val streamType =
            if (item.type == ContentType.LIVE) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED
        return MediaInfo
            .Builder(url)
            .setStreamType(streamType)
            .setContentType(mimeFor(url))
            .setMetadata(metadata)
            .build()
    }

    private fun mimeFor(url: String): String = when {
        url.contains(".m3u8") -> "application/x-mpegURL"
        url.contains(".mpd") -> "application/dash+xml"
        else -> "video/mp4"
    }
}
