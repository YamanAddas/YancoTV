package com.yancotv.android.player

import android.content.Context
import android.content.Intent

/**
 * In-process launcher for [PlayerActivity]. The shared [PlaybackController]
 * owns the queue + URL; this just opens the fullscreen surface on top.
 * Call `controller.play(list, index)` first, then invoke this.
 */
object PlayerLauncher {
    fun launch(ctx: Context) {
        val intent = Intent(ctx, PlayerActivity::class.java).apply {
            if (ctx !is android.app.Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        ctx.startActivity(intent)
    }
}
