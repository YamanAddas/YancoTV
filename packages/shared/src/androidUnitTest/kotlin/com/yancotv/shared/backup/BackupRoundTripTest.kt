package com.yancotv.shared.backup

import com.yancotv.shared.db.YancoDb
import com.yancotv.shared.sources.PlaintextCredentialStore
import com.yancotv.shared.sources.testDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MK.19.8.6 — DoD-pinning tests for the export/import engine.
 *
 *  - **Round-trip**: export populated DB → import into fresh DB → state
 *    equivalent (within the documented v1 scope).
 *  - **Schema guard**: import refuses backups with newer dbSchemaVersion.
 *  - **Checksum guard**: tampered records fail import.
 *  - **Credential modes**: plaintext + password-encrypted both round-trip.
 *  - **Re-link buffer**: favorites pointing at content not yet present
 *    are buffered, then resolved by retryPendingLinks() after the
 *    content row is inserted.
 */
class BackupRoundTripTest {
    private fun seedSource(
        db: YancoDb,
        id: String = "src-A",
        username: String = "user@host",
        password: String = "p@ssw0rd",
    ) {
        val store = PlaintextCredentialStore()
        db.sourcesQueries.insert(
            id = id,
            name = "Main",
            type = "xtream",
            url = "http://provider/list",
            file_path = null,
            username_encrypted = store.encrypt(username),
            password_encrypted = store.encrypt(password),
            mac_address_encrypted = null,
            epg_url = null,
            user_agent = null,
            referer = null,
            last_synced = null,
            last_sync_error = null,
            is_active = true,
            priority = 0,
            channel_count = 0,
            auto_sync_interval = 0,
            epg_priority = 0,
            created_at = 1L,
            updated_at = 1L,
        )
    }

    private fun seedContent(
        db: YancoDb,
        id: String,
        sourceId: String = "src-A",
        streamUrl: String = "http://stream/$id",
        title: String = "Channel $id",
    ) {
        db.contentQueries.insert(
            id = id,
            source_id = sourceId,
            type = "live",
            title = title,
            clean_title = title,
            group_name = null,
            stream_url = streamUrl,
            logo_url = null,
            tvg_id = null,
            metadata_json = null,
            sort_order = 0L,
            created_at = 0L,
        )
    }

    @Test fun roundTrip_plaintextMode_preservesUserCuratedState() {
        // Seed: source + 2 channels + 1 favorite + 1 setting + 1 group pref.
        val src = testDb()
        seedSource(src)
        seedContent(src, "ch-1")
        seedContent(src, "ch-2")
        src.favoritesQueries.insert(id = "fav-1", content_id = "ch-1", list_id = "default", added_at = 100L)
        src.settingsQueries.upsert("pref_audio_lang", "en")
        src.groupPreferencesQueries.upsert(
            id = "gp-1",
            content_type = "live",
            group_key = "Sports",
            sort_order = 0,
            is_hidden = false,
            is_pinned = true,
            custom_name = null,
            created_at = 1L,
        )

        val exporter = BackupExporter(src, PlaintextCredentialStore())
        val file = exporter.export("0.1.0", 8, 1000L)

        // Fresh destination DB. Import lands the source first; favorite
        // buffers because the content row hasn't been re-fetched yet.
        // Then we simulate a source-sync (seed the content) and drain
        // the pending buffer.
        val dst = testDb()
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        val firstReport = importer.import(file, currentSchemaVersion = 8)
        assertEquals(1, firstReport.restored["sources"])
        assertEquals(1, firstReport.restored["groupPreferences"])
        assertEquals(1, firstReport.restored["settings"])
        // Favorite buffered — content row not present yet.
        assertEquals(1, firstReport.unlinked["favorites"])

        // Simulate source-sync completion: content row reappears.
        seedContent(dst, "ch-1")
        val secondReport = importer.retryPendingLinks()
        assertEquals(1, secondReport.restored["favorites"])

        // Verify the source's encrypted credentials decrypt to the
        // originals on the destination DB (we use the same Plaintext
        // store on both ends — the round-trip preserves the cleartext).
        val restoredSrc = dst.sourcesQueries.selectById("src-A").executeAsOne()
        val store = PlaintextCredentialStore()
        assertEquals("user@host", store.decrypt(restoredSrc.username_encrypted!!))
        assertEquals("p@ssw0rd", store.decrypt(restoredSrc.password_encrypted!!))

        // Favorite was resolved against ch-1 (which we pre-seeded).
        // selectAll JOINs content so the row's `id` is the content's id.
        val favs = dst.favoritesQueries.selectAll().executeAsList()
        assertEquals(1, favs.size)
        assertEquals("ch-1", favs.single().id)

        // No pending links (content row was already there).
        assertTrue(importer.pendingLinks.value.isEmpty())
    }

    @Test fun roundTrip_passwordMode_credsDecryptAfterImport() {
        val src = testDb()
        seedSource(src, username = "secret-user", password = "secret-pass")

        val exporter = BackupExporter(src, PlaintextCredentialStore())
        val file = exporter.export("0.1.0", 8, 1000L, password = "hunter2")

        // Encryption header present; credential strings in the file are
        // ciphertext (not the originals).
        val srcRecord = file.records.sources.single()
        assertTrue(srcRecord.username != "secret-user", "expected ciphertext, got plaintext: ${srcRecord.username}")

        val dst = testDb()
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        val report = importer.import(file, password = "hunter2", currentSchemaVersion = 8)

        assertEquals(1, report.restored["sources"])
        val store = PlaintextCredentialStore()
        val restored = dst.sourcesQueries.selectById("src-A").executeAsOne()
        assertEquals("secret-user", store.decrypt(restored.username_encrypted!!))
        assertEquals("secret-pass", store.decrypt(restored.password_encrypted!!))
    }

    @Test fun import_passwordModeWithoutPassword_throws() {
        val src = testDb()
        seedSource(src)
        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1000L, password = "x")

        val dst = testDb()
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        assertFailsWith<BackupDecryptException> {
            importer.import(file, password = null, currentSchemaVersion = 8)
        }
    }

    @Test fun import_schemaTooNew_throws() {
        val src = testDb()
        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", dbSchemaVersion = 99, nowMs = 1L)

        val dst = testDb()
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        assertFailsWith<BackupSchemaTooNewException> {
            importer.import(file, currentSchemaVersion = 8)
        }
    }

    @Test fun import_tamperedRecords_throwsChecksumMismatch() {
        val src = testDb()
        seedSource(src)
        val original = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1L)
        // Mutate one source's name AFTER checksum was sealed.
        val tampered =
            original.copy(
                records = original.records.copy(
                    sources = original.records.sources.map { it.copy(name = "TAMPERED") },
                ),
            )

        val dst = testDb()
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        assertFailsWith<BackupChecksumMismatchException> {
            importer.import(tampered, currentSchemaVersion = 8)
        }
    }

    @Test fun relink_favoritesBufferedThenResolvedAfterContentSeed() {
        // Source-side: source + content + favorite. Export then import to
        // a fresh DB WITHOUT pre-seeding content. Favorite must buffer.
        val src = testDb()
        seedSource(src)
        seedContent(src, "ch-1")
        src.favoritesQueries.insert(id = "fav-1", content_id = "ch-1", list_id = "default", added_at = 100L)
        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1L)

        val dst = testDb()
        // No content seeded yet — simulating a freshly-imported backup
        // before the source-sync runs.
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        val firstReport = importer.import(file, currentSchemaVersion = 8)

        // Source landed but favorite is buffered (couldn't link).
        assertEquals(1, firstReport.restored["sources"])
        assertEquals(0, firstReport.restored["favorites"])
        assertEquals(1, firstReport.unlinked["favorites"])
        assertEquals(0, dst.favoritesQueries.selectAll().executeAsList().size)

        // Now simulate the source-sync completing: insert the content row.
        seedContent(dst, "ch-1")

        // Drain the pending buffer.
        val secondReport = importer.retryPendingLinks()
        assertEquals(1, secondReport.restored["favorites"])
        assertEquals(1, dst.favoritesQueries.selectAll().executeAsList().size)
        assertTrue(importer.pendingLinks.value.isEmpty())
    }

    @Test fun import_settingsAreUpsertedNotSkipped() {
        val src = testDb()
        src.settingsQueries.upsert("pref_audio_lang", "ar")
        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1L)

        val dst = testDb()
        // dst has a different value already — merge mode overwrites.
        dst.settingsQueries.upsert("pref_audio_lang", "en")
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        importer.import(file, currentSchemaVersion = 8)

        assertEquals("ar", dst.settingsQueries.get("pref_audio_lang").executeAsOne())
    }

    @Test fun import_existingSourceById_skipsNotOverwrites() {
        val src = testDb()
        seedSource(src, id = "src-A", username = "from-export", password = "x")
        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1L)

        val dst = testDb()
        seedSource(dst, id = "src-A", username = "already-here", password = "y")
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        val report = importer.import(file, currentSchemaVersion = 8)

        assertEquals(0, report.restored["sources"])
        assertEquals(1, report.skipped["sources"])
        // Original credentials untouched.
        val store = PlaintextCredentialStore()
        val row = dst.sourcesQueries.selectById("src-A").executeAsOne()
        assertEquals("already-here", store.decrypt(row.username_encrypted!!))
    }
}
