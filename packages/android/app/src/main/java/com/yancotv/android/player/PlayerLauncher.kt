package com.yancotv.android.player

import android.content.Context
import android.content.Intent

/**
 * In-process launcher for [PlayerActivity]. The native app calls this
 * directly from Compose / ViewModel — there is no RN bridge in the
 * native Android build.
 */
object PlayerLauncher {
    fun launch(
        ctx: Context,
        url: String,
        title: String? = null,
        userAgent: String? = null,
    ) {
        val intent = Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            title?.let { putExtra(PlayerActivity.EXTRA_TITLE, it) }
            userAgent?.let { putExtra(PlayerActivity.EXTRA_USER_AGENT, it) }
            if (ctx !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        ctx.startActivity(intent)
    }
}
