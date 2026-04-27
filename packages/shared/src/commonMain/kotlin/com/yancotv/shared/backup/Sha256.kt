package com.yancotv.shared.backup

/**
 * MK.19.8 — KMP-portable SHA-256. No `kotlinx-datetime`-style helper
 * exists in commonMain (no `okio.HashingSink` either, since the project
 * doesn't depend on Okio in shared). expect/actual is the simplest path:
 *  - androidMain → java.security.MessageDigest("SHA-256")
 *  - iosMain (when iOS lands) → CommonCrypto's CC_SHA256
 *
 * Pure data in / hex out. No streaming API needed — backup payloads are
 * a few hundred KB at the realistic upper bound (catalogues with 50k
 * channels have ~50KB of catalogued user state once content rows are
 * stripped).
 */
expect fun sha256Hex(bytes: ByteArray): String
