package com.yancotv.mobile.player

import android.content.Intent
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.Promise

/**
 * RN bridge — single method [launch]. Fires an Intent to
 * [PlayerActivity]; JS never touches ExoPlayer.
 */
class PlayerLauncherModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "PlayerLauncher"

    @ReactMethod
    fun launch(options: ReadableMap, promise: Promise) {
        val url = options.getString("url")
        if (url.isNullOrBlank()) {
            promise.reject("E_NO_URL", "url is required")
            return
        }
        val title = if (options.hasKey("title")) options.getString("title") else null
        val userAgent = if (options.hasKey("userAgent")) options.getString("userAgent") else null

        val ctx = getCurrentActivity() ?: reactApplicationContext
        val intent = Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            title?.let { putExtra(PlayerActivity.EXTRA_TITLE, it) }
            userAgent?.let { putExtra(PlayerActivity.EXTRA_USER_AGENT, it) }
            if (getCurrentActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
            promise.resolve(null)
        } catch (e: Throwable) {
            promise.reject("E_LAUNCH_FAILED", e.message, e)
        }
    }
}
