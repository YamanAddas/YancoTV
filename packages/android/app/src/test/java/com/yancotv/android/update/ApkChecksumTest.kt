package com.yancotv.android.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MB-369 — the digest gate in front of the self-update installer. */
class ApkChecksumTest {
    private fun tempFile(bytes: ByteArray): File = File.createTempFile("apk", ".bin").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    @Test fun knownVectorMatches() {
        // sha256("abc") — FIPS 180-2 test vector, same one BackupCipherParityTest pins
        val f = tempFile("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ApkChecksum.sha256Hex(f))
    }

    @Test fun caseAndWhitespaceInTheManifestValueAreForgiven() {
        val f = tempFile("abc".toByteArray())
        assertTrue(ApkChecksum.matches(f, " BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD "))
    }

    @Test fun tamperedFileFailsTheGate() {
        val f = tempFile("abd".toByteArray())
        assertFalse(
            ApkChecksum.matches(f, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            "a single flipped byte must fail",
        )
    }

    @Test fun absentDigestPassesForOldManifests() {
        // the published 1.5.3 update.json has no sha256; installed devices
        // must keep updating
        val f = tempFile("anything".toByteArray())
        assertTrue(ApkChecksum.matches(f, null))
        assertTrue(ApkChecksum.matches(f, ""))
    }
}
