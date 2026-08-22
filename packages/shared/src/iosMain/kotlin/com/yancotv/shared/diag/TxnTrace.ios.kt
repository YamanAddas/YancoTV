package com.yancotv.shared.diag

import platform.Foundation.NSThread

/**
 * iOS has no thread-local SQLite session to leak the way Android's does, so this
 * exists to keep `commonMain` compiling rather than to diagnose anything.
 */
actual fun currentThreadName(): String = if (NSThread.isMainThread()) {
    "main"
} else {
    NSThread.currentThread.description ?: "unknown"
}
