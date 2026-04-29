package com.yancotv.android.ui.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient

/**
 * Global Coil 3 [ImageLoader] for logos + posters.
 *
 *  * Memory cache hard-capped at 32 MB — predictable budget on Fire TV Stick
 *    (320 MB heap = ~10%). The previous 25%-of-heap default ballooned to
 *    ~80 MB on Fire TV and competed with the player's video memory pool
 *    during long sessions. Logos + posters cache-hit fine at 32 MB.
 *  * Disk LRU at 250MB in `cacheDir/yanco-images`; big enough for thousands
 *    of logos at typical 5–40KB each without blowing up the profile.
 *  * OkHttp network fetcher uses the **shared** app-level [OkHttpClient]
 *    (Koin singleton) so we share the same connection pool + dispatcher +
 *    DNS cache + TLS sessions as `AndroidEpgImporter`. MK.24.I.7 / MB-230 —
 *    pre-fix this constructed `OkHttpClient()` fresh, doubling the
 *    connection-pool / thread-executor / DNS / TLS infrastructure overhead.
 *  * Crossfade (120ms) so hot-cached frames don't flash on rapid focus
 *    changes.
 *  * Memory cache responds to system pressure: see `YancoApp.onTrimMemory`
 *    which clears or trims this cache on `RUNNING_LOW`/`RUNNING_CRITICAL`/
 *    `BACKGROUND`/`COMPLETE` levels.
 */
fun buildYancoImageLoader(context: Context, sharedHttp: OkHttpClient): ImageLoader = ImageLoader
    .Builder(context)
    .memoryCache {
        MemoryCache
            .Builder()
            .maxSizeBytes(32L * 1024 * 1024)
            .build()
    }.diskCache {
        DiskCache
            .Builder()
            .directory(context.cacheDir.resolve("yanco-images"))
            .maxSizeBytes(250L * 1024 * 1024)
            .build()
    }.components {
        add(OkHttpNetworkFetcherFactory(sharedHttp))
    }.crossfade(120)
    .build()
