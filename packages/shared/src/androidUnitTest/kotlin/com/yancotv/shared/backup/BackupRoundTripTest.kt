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
            auto_sync_on_start = false,
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

    @Test fun import_passwordModeWithWrongPassword_landsEmptyCredsWithWarning() {
        // Option C fallback path — the file is password-encrypted, the
        // user supplies a non-null but wrong password, PBKDF2 happily
        // derives a key from it (it doesn't validate), the AES/GCM
        // auth-tag check then fails per credential blob. The importer
        // surfaces this via report.warnings and lands the row with
        // empty credentials rather than aborting the whole restore —
        // sources/url/etc. survive; the user re-enters creds.
        //
        // Distinct from import_passwordModeWithoutPassword_throws —
        // null password aborts before key derivation. Wrong-but-present
        // password is the case the importer absorbs.
        val src = testDb()
        seedSource(src, username = "secret-user", password = "secret-pass")
        val file =
            BackupExporter(src, PlaintextCredentialStore())
                .export("0.1.0", 8, 1L, password = "right-password")

        val dst = testDb()
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        val report = importer.import(file, password = "wrong-password", currentSchemaVersion = 8)

        assertEquals(1, report.restored["sources"])
        assertTrue(
            report.warnings.any { it.contains("credential decrypt failed") },
            "expected a credential-decrypt warning in: ${report.warnings}",
        )
        val store = PlaintextCredentialStore()
        val row = dst.sourcesQueries.selectById("src-A").executeAsOne()
        assertEquals("", store.decrypt(row.username_encrypted!!))
        assertEquals("", store.decrypt(row.password_encrypted!!))
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

    @Test fun relink_overridesAndParentalLockedHidden_resolveAfterContentSeed() {
        // Audit fix #4 — coverage parity for the four content-id-keyed
        // record types beyond favorites. Same buffer-then-drain contract
        // as the favorites relink test: import → all four types buffer →
        // seed content → retry → all four resolve.
        val src = testDb()
        seedSource(src)
        seedContent(src, "ch-1", streamUrl = "http://stream/ch-1", title = "Ch 1")
        // content override (rename + custom logo).
        src.contentQueries.setOverrides(
            nameOverride = "My Channel",
            logoOverride = "http://logo/custom.png",
            id = "ch-1",
        )
        // channel override (custom number, group reassignment).
        src.parentalQueries.upsertOverride(
            content_id = "ch-1",
            custom_name = null,
            custom_logo_url = null,
            custom_number = 42L,
            custom_group = "MyFavs",
            updated_at = 7L,
        )
        src.parentalQueries.lockChannel("ch-1", 8L)
        src.parentalQueries.hideChannel("ch-1", 9L)

        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1L)

        val dst = testDb()
        val importer = BackupImporter(dst, PlaintextCredentialStore())
        val first = importer.import(file, currentSchemaVersion = 8)

        // All four buffered (no content yet).
        assertEquals(1, first.unlinked["contentOverrides"])
        assertEquals(1, first.unlinked["channelOverrides"])
        assertEquals(1, first.unlinked["lockedChannels"])
        assertEquals(1, first.unlinked["hiddenChannels"])

        // Source-sync simulation.
        seedContent(dst, "ch-1", streamUrl = "http://stream/ch-1", title = "Ch 1")
        val second = importer.retryPendingLinks()

        assertEquals(1, second.restored["contentOverrides"])
        assertEquals(1, second.restored["channelOverrides"])
        assertEquals(1, second.restored["lockedChannels"])
        assertEquals(1, second.restored["hiddenChannels"])

        // Verify they actually landed.
        val ch = dst.contentQueries.selectById("ch-1").executeAsOne()
        assertEquals("My Channel", ch.name_override)
        assertEquals("http://logo/custom.png", ch.logo_override)
        val override = dst.parentalQueries.selectOverride("ch-1").executeAsOne()
        assertEquals(42L, override.custom_number)
        assertEquals("MyFavs", override.custom_group)
        assertTrue(dst.parentalQueries.isLocked("ch-1").executeAsOne())
        assertTrue(dst.parentalQueries.isHidden("ch-1").executeAsOne())
        assertTrue(importer.pendingLinks.value.isEmpty())
    }

    @Test fun importRecordings_missingFileUri_landsAsFailed() {
        // MB-217 — completed recording whose file URI doesn't resolve
        // on the target device should import as FAILED("file_not_found_post_restore"),
        // not COMPLETED with an unplayable file. Recordings that were
        // already FAILED / CANCELLED at export skip the check.
        val src = testDb()
        seedSource(src)
        seedContent(src, "ch-1", streamUrl = "http://stream/movie")
        // Two recordings: one COMPLETED (will be flagged), one FAILED
        // (already terminal-non-playable, doesn't need the check).
        src.recordingsQueries.insert(
            id = "rec-good",
            content_id = "ch-1",
            title = "Good Movie",
            stream_url = "http://stream/movie",
            file_path = "content://exists/1",
            status = "completed",
            started_at = 1L,
            ended_at = 100L,
            duration_seconds = 99L,
            file_size_bytes = 1024L,
            error = null,
            format = "mpeg_ts",
        )
        src.recordingsQueries.insert(
            id = "rec-orphan",
            content_id = "ch-1",
            title = "Orphan Movie",
            stream_url = "http://stream/movie",
            file_path = "content://gone/2",
            status = "completed",
            started_at = 2L,
            ended_at = 200L,
            duration_seconds = 198L,
            file_size_bytes = 2048L,
            error = null,
            format = "mpeg_ts",
        )
        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1L)

        // Inject a fileExists fake: only "content://exists/1" reports true.
        val dst = testDb()
        val importer =
            BackupImporter(
                dst,
                PlaintextCredentialStore(),
                recordingFileExists = { uri -> uri == "content://exists/1" },
            )
        importer.import(file, currentSchemaVersion = 8)

        val good = dst.recordingsQueries.selectById("rec-good").executeAsOne()
        assertEquals("completed", good.status)
        assertNull(good.error)

        val orphan = dst.recordingsQueries.selectById("rec-orphan").executeAsOne()
        assertEquals("failed", orphan.status)
        assertEquals("file_not_found_post_restore", orphan.error)
    }

    @Test fun relink_recordingContentIdResolvesByStreamUrl() {
        // Audit fix #1 — recording rows on import re-resolve content_id
        // via stream_url (no source_id was exported). Pre-seed content
        // in dst so the lookup succeeds during the synchronous phase.
        val src = testDb()
        seedSource(src)
        seedContent(src, "ch-1", streamUrl = "http://stream/movie")
        src.recordingsQueries.insert(
            id = "rec-1",
            content_id = "ch-1",
            title = "My Movie",
            stream_url = "http://stream/movie",
            file_path = "content://recording/1",
            status = "completed",
            started_at = 1L,
            ended_at = 100L,
            duration_seconds = 99L,
            file_size_bytes = 1024L,
            error = null,
            format = "mpeg_ts",
        )
        val file = BackupExporter(src, PlaintextCredentialStore()).export("0.1.0", 8, 1L)

        val dst = testDb()
        seedSource(dst)
        seedContent(dst, "ch-1", streamUrl = "http://stream/movie")
        BackupImporter(dst, PlaintextCredentialStore()).import(file, currentSchemaVersion = 8)

        val restored = dst.recordingsQueries.selectById("rec-1").executeAsOne()
        assertEquals("ch-1", restored.content_id)
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
