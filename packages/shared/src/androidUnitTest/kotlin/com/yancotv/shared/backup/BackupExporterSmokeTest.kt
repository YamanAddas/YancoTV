package com.yancotv.shared.backup

import com.yancotv.shared.sources.PlaintextCredentialStore
import com.yancotv.shared.sources.testDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * MK.19.8.1 — DoD smoke: exporter runs against a fixture DB without
 * crashing and produces a stable checksum across calls. Full round-trip
 * + schema-guard tests live in MK.19.8.6 alongside the importer.
 */
class BackupExporterSmokeTest {
    @Test fun emptyDb_exportSucceedsWithStableChecksum() {
        val db = testDb()
        val exporter = BackupExporter(db, PlaintextCredentialStore())

        val first = exporter.export(appVersion = "0.1.0", dbSchemaVersion = 8, nowMs = 1000L)
        val second = exporter.export(appVersion = "0.1.0", dbSchemaVersion = 8, nowMs = 2000L)

        // Empty DB → all record buckets empty.
        assertEquals(0, first.records.sources.size)
        assertEquals(0, first.records.favorites.size)
        // Default favorite_lists row is seeded by FavoriteLists.sq, so 1 list expected.
        assertEquals(1, first.records.favoriteLists.size)
        assertEquals("default", first.records.favoriteLists.single().id)

        // No password → no encryption header.
        assertNull(first.encryption)

        // Checksum is over `records`; same DB state → same checksum across calls
        // (createdAt differs but only `records` feeds the checksum).
        assertEquals(first.checksum, second.checksum)

        // recordCounts mirrors actual list sizes.
        assertEquals(0, first.recordCounts["sources"])
        assertEquals(1, first.recordCounts["favoriteLists"])
    }

    @Test fun encryptedExport_emitsEncryptionHeader() {
        val db = testDb()
        val exporter = BackupExporter(db, PlaintextCredentialStore())

        val withPassword = exporter.export("0.1.0", 8, 1000L, password = "hunter2")

        assertNotNull(withPassword.encryption)
        assertEquals("pbkdf2-sha256", withPassword.encryption!!.kdf)
        assertEquals(BACKUP_PBKDF2_ITERATIONS, withPassword.encryption!!.iterations)
        assertEquals(32, withPassword.encryption!!.saltHex.length) // 16 bytes hex = 32 chars
    }
}
