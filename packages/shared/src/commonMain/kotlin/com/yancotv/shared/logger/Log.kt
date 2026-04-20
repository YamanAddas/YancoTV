package com.yancotv.shared.logger

import co.touchlab.kermit.Logger

/**
 * Shared logger. Platforms get platform-default sinks from kermit
 * (Logcat on Android, NSLog on iOS). Call as Log.l.i { "..." }.
 */
object Log {
    val l: Logger = Logger.withTag("yanco")
}
