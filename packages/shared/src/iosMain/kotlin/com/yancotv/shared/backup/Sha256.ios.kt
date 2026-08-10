package com.yancotv.shared.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * iOS actual for [sha256Hex] — CommonCrypto's `CC_SHA256`, as the
 * commonMain KDoc anticipated. CryptoKit would be the more idiomatic
 * Apple choice but it is a Swift-only framework: Kotlin/Native can only
 * import Objective-C and C interfaces, so CommonCrypto is the reachable
 * primitive here.
 *
 * Byte-for-byte parity with [androidMain][sha256Hex]'s `MessageDigest`
 * path: same digest, same lowercase hex alphabet. Backup checksums are
 * compared across devices, so a divergence here would reject every
 * cross-platform restore.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(bytes: ByteArray): String = memScoped {
    val digest = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    // `Pinned.addressOf(0)` throws on an empty ByteArray, so the empty
    // input has to take the NULL-pointer path. CommonCrypto accepts
    // (NULL, 0) and still produces the empty-string digest
    // e3b0c442…b855 — which BackupImporter relies on when a backup
    // carries zero records.
    if (bytes.isEmpty()) {
        CC_SHA256(null, 0u, digest)
    } else {
        bytes.usePinned { pinned ->
            CC_SHA256(pinned.addressOf(0), bytes.size.toUInt(), digest)
        }
    }
    buildString(CC_SHA256_DIGEST_LENGTH * 2) {
        for (i in 0 until CC_SHA256_DIGEST_LENGTH) {
            val v = digest[i].toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }
}

private val HEX = "0123456789abcdef".toCharArray()
