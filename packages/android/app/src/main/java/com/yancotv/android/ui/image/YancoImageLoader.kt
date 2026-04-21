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
 *  * Memory cache sized to 25% of app heap — logos re-render on every scroll
 *    row so memory caching dominates the cost.
 *  * Disk LRU at 250MB in `cacheDir/yanco-images`; big enough for thousands
 *    of logos at typical 5–40KB each without blowing up the profile.
 *  * OkHttp network fetcher so requests share the same TLS/redirect stack
 *    the rest of the app uses.
 *  * Crossfade (120ms) so hot-cached frames don't flash on rapid focus
 *    changes.
 */
fun buildYancoImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .memoryCache {
        MemoryCache.Builder()
            .maxSizePercent(context, 0.25)
            .build()
    }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve("yanco-images"))
            .maxSizeBytes(250L * 1024 * 1024)
            .build()
    }
    .components {
        add(OkHttpNetworkFetcherFactory(OkHttpClient()))
    }
    .crossfade(120)
    .build()
