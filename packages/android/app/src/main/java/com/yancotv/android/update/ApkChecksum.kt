package com.yancotv.android.update

import java.io.File
import java.security.MessageDigest

/**
 * MB-369 — SHA-256 helpers for the self-update download.
 *
 * Pure and file-in/hex-out so the verification logic is unit-testable
 * without an OkHttp stack or an installer session. The digest is computed
 * once more over the finished file rather than trusting a rolling hash from
 * the copy loop: the file on disk is what the installer will hand to
 * Android, so the file on disk is what must be verified.
 */
internal object ApkChecksum {
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * True when [file] matches [expectedHex], or when no digest was
     * expected at all — pre-MB-369 manifests carry none, and a fleet of
     * installed devices depends on those staying installable.
     */
    fun matches(file: File, expectedHex: String?): Boolean {
        if (expectedHex.isNullOrBlank()) return true
        return sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true)
    }
}
