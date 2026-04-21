package com.yancotv.android.logger

import android.util.Log
import com.yancotv.shared.logger.Logger

/**
 * Routes shared-module log calls to Android logcat. Single tag so `adb logcat
 * -s Yanco:*` shows every shared-module breadcrumb — the thing that mattered
 * while chasing the Save-hang on MK.6: without this wire-up every `logger.*`
 * call in shared/ was a no-op, making Keystore and SQL steps invisible.
 */
class AndroidLogger(private val tag: String = "Yanco") : Logger {
    override fun info(msg: String) { Log.i(tag, msg) }
    override fun warn(msg: String) { Log.w(tag, msg) }
    override fun error(msg: String) { Log.e(tag, msg) }
}
