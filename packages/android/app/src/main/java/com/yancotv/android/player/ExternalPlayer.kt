package com.yancotv.android.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.yancotv.android.R

/**
 * MK.18.1 — known external video player apps. The `packageName` must
 * also be listed in the manifest's `<queries><package>` block for
 * Android 11+ visibility, otherwise [PackageManager.getPackageInfo]
 * always reports "not installed".
 */
// MK.31.23 — `displayName` stays a literal: VLC / MX Player / Just Player are
// product names, not copy. The "Open in X" line around it is localized, so
// `sub` became a helper rather than a stored string.
enum class ExternalPlayerApp(val packageName: String, val displayName: String) {
    VLC("org.videolan.vlc", "VLC"),
    MX_PRO("com.mxtech.videoplayer.pro", "MX Player Pro"),
    MX_FREE("com.mxtech.videoplayer.ad", "MX Player"),
    JUST_PLAYER("com.brouken.player", "Just Player"),
    ;

    fun sub(ctx: Context): String = ctx.getString(R.string.ep_open_in, displayName)
}

/**
 * MK.18.1 — "Open in external player" support.
 *
 * Hands the current stream URL off to a third-party video player via
 * `Intent.ACTION_VIEW`. The known set is VLC, MX Player (Free + Pro),
 * and Just Player; any other player that registers for the video MIME
 * type wildcard is reachable via the chooser fallback.
 *
 * Why a curated set:
 *   - VLC and MX Player are the de-facto choices on Android TV / Fire TV
 *     for IPTV streams that the internal player struggles with (audio
 *     sync, exotic codecs, .ts containers).
 *   - Just Player is the modern open-source alternative built on Media3.
 *
 * Why also a chooser fallback:
 *   - Power users may have Kodi, nPlayer, or a custom Media3 build —
 *     letting Android show its system chooser is the lowest-friction
 *     "I'll pick whatever's installed" path.
 */
object ExternalPlayer {
    /** Apps from [ExternalPlayerApp] that are actually installed on the device. */
    fun installed(context: Context): List<ExternalPlayerApp> {
        val pm = context.packageManager
        return ExternalPlayerApp.values().filter { isPackageInstalled(pm, it.packageName) }
    }

    /**
     * Build an `ACTION_VIEW` intent for [streamUrl] targeting [app].
     * Sets the video wildcard MIME type — providers that serve `.m3u8` /
     * `.ts` / `.mp4` over HTTP rarely advertise the right Content-Type, so
     * leaving the MIME explicit is the difference between "VLC plays it"
     * and "VLC pops a 'no app can handle this' dialog".
     *
     * Position handoff: VLC and MX Player both honour an `Intent` extra
     * named `position` (milliseconds) for resume; Just Player follows the
     * same convention. Live streams pass null because the offset is
     * meaningless for live.
     */
    fun buildIntent(streamUrl: String, positionMs: Long?, app: ExternalPlayerApp?): Intent {
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(streamUrl), "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (positionMs != null && positionMs > 0L) {
                    // VLC + MX Player both read this extra. Just Player
                    // follows suit.
                    putExtra("position", positionMs)
                }
            }
        if (app != null) intent.setPackage(app.packageName)
        return intent
    }

    /**
     * Convenience launch — builds the intent for [app] (or null for the
     * system chooser) and starts it. Returns false if the launch failed
     * (no resolver, or the user cancelled the chooser).
     */
    fun launch(context: Context, streamUrl: String, positionMs: Long?, app: ExternalPlayerApp?): Boolean = runCatching {
        val intent = buildIntent(streamUrl, positionMs, app)
        val toStart =
            if (app == null) {
                Intent.createChooser(intent, context.getString(R.string.ep_open_with)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                intent
            }
        context.startActivity(toStart)
        true
    }.getOrDefault(false)

    private fun isPackageInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        // GET_ACTIVITIES = 0 — we only need existence, not the
        // package details. The query is cheap and returns null /
        // throws NameNotFoundException when the package isn't
        // installed; runCatching collapses both to false.
        pm.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)
}
