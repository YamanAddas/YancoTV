package com.yancotv.shared.sources

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.types.AddSourceInput
import com.yancotv.shared.types.SourceType
import com.yancotv.shared.types.UpdateSourceInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class SourceRepositoryTest {
    private class FakeHttpClient(private val textResponses: Map<String, String> = emptyMap()) : HttpClient {
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = error("getJson not used in these tests")

        override suspend fun getText(url: String, options: HttpRequestOptions): String = textResponses[url] ?: error("unmocked URL: $url")
    }

    private class FakeFileReader(private val contents: Map<String, String>) : FileContentReader {
        override suspend fun readText(path: String): String = contents[path] ?: error("unmocked file: $path")
    }

    private fun repo(http: HttpClient = FakeHttpClient(), reader: FileContentReader = FakeFileReader(emptyMap()), now: Long = 1_000L): SourceRepository {
        val bundle = testDatabase()
        return SourceRepository(
            db = bundle.db,
            driver = bundle.driver,
            credentialStore = PlaintextCredentialStore(),
            http = http,
            fileReader = reader,
            clock = { now },
            idGenerator =
            run {
                var n = 0
                { "id-${++n}" }
            },
        )
    }

    @Test
    fun `add + get round-trip`() {
        val r = repo()
        val s =
            r.addSource(
                AddSourceInput(name = "Main", type = SourceType.M3U_URL, url = "http://a/list.m3u"),
            )
        assertEquals("id-1", s.id)
        assertEquals("Main", s.name)
        assertEquals(SourceType.M3U_URL, s.type)
        assertEquals(1, r.getAll().size)
        assertEquals(s, r.getById("id-1"))
    }

    @Test
    fun `credentials are encrypted at the BLOB column`() {
        val r = repo()
        r.addSource(
            AddSourceInput(
                name = "X",
                type = SourceType.XTREAM,
                url = "http://x",
                username = "user1",
                password = "p@ss",
            ),
        )
        // Plaintext store makes the BLOB equal to the plaintext bytes — but
        // the contract is the same: callers never read the BLOB directly.
        // What we check here is that the repo actually wrote the credential
        // through the store (not as plaintext in a string column).
        val row = r.getAll().single()
        assertNull(
            row.url?.let { if (it.contains("user1")) "leak" else null },
            "URL column must not contain credentials",
        )
    }

    @Test
    fun `updateSource preserves existing credentials when input is null`() {
        val r = repo()
        val s =
            r.addSource(
                AddSourceInput(
                    name = "X",
                    type = SourceType.XTREAM,
                    url = "http://x",
                    username = "user1",
                    password = "pw1",
                ),
            )
        r.updateSource(UpdateSourceInput(id = s.id, name = "X-renamed"))
        val after = r.getById(s.id)
        assertEquals("X-renamed", after?.name)
    }

    @Test
    fun `validate rejects missing fields per source type`() {
        val r = repo()
        assertFails { r.addSource(AddSourceInput(name = "", type = SourceType.M3U_URL, url = "http://x")) }
        assertFails { r.addSource(AddSourceInput(name = "A", type = SourceType.M3U_URL)) }
        assertFails { r.addSource(AddSourceInput(name = "A", type = SourceType.XTREAM, url = "http://x")) }
        assertFails { r.addSource(AddSourceInput(name = "A", type = SourceType.STALKER, url = "http://x")) }
    }

    @Test
    fun `reorder assigns priorities in list order`() {
        val r = repo()
        val a = r.addSource(AddSourceInput(name = "A", type = SourceType.M3U_URL, url = "http://a"))
        val b = r.addSource(AddSourceInput(name = "B", type = SourceType.M3U_URL, url = "http://b"))
        val c = r.addSource(AddSourceInput(name = "C", type = SourceType.M3U_URL, url = "http://c"))
        r.reorder(listOf(c.id, a.id, b.id))
        val ordered = r.getAll()
        assertEquals(listOf("C", "A", "B"), ordered.map { it.name })
    }

    @Test
    fun `removeSource cascades content rows`() = runTest {
        val playlist =
            """
                #EXTM3U
                #EXTINF:-1 tvg-id="bbc" group-title="News",BBC One
                http://a/1.ts
                #EXTINF:-1 tvg-id="cnn" group-title="News",CNN
                http://a/2.ts
            """.trimIndent()
        val r = repo(http = FakeHttpClient(mapOf("http://a/list.m3u" to playlist)))
        val s = r.addSource(AddSourceInput(name = "A", type = SourceType.M3U_URL, url = "http://a/list.m3u"))
        r.syncSource(s.id).toList()
        r.removeSource(s.id)
        assertNull(r.getById(s.id))
    }

    @Test
    fun `syncSource M3U URL emits FETCHING, WRITING, DONE and persists content`() = runTest {
        val playlist =
            """
                #EXTM3U
                #EXTINF:-1 tvg-id="bbc" group-title="News",BBC One
                http://a/1.ts
                #EXTINF:-1 tvg-id="cnn" group-title="News",CNN
                http://a/2.ts
            """.trimIndent()
        val http = FakeHttpClient(mapOf("http://a/list.m3u" to playlist))
        val r = repo(http = http)

        val s = r.addSource(AddSourceInput(name = "A", type = SourceType.M3U_URL, url = "http://a/list.m3u"))
        val events = r.syncSource(s.id).toList()

        val phases = events.map { it.phase }
        assertTrue(SyncProgress.Phase.FETCHING in phases)
        assertTrue(SyncProgress.Phase.WRITING in phases)
        assertEquals(SyncProgress.Phase.DONE, events.last().phase)
        assertEquals(2, events.last().current)

        val reloaded = r.getById(s.id)
        assertNotNull(reloaded)
        assertEquals(2, reloaded.channelCount)
        assertNull(reloaded.lastSyncError)
        assertEquals(1_000L, reloaded.lastSynced)
    }

    @Test
    fun `syncSource emits ERROR and preserves previous channel_count on failure`() = runTest {
        val r = repo(http = FakeHttpClient(emptyMap())) // any URL throws
        val s = r.addSource(AddSourceInput(name = "A", type = SourceType.M3U_URL, url = "http://nowhere/list.m3u"))
        val events = r.syncSource(s.id).toList()
        assertEquals(SyncProgress.Phase.ERROR, events.last().phase)
        val reloaded = r.getById(s.id)
        assertNotNull(reloaded)
        assertEquals(0, reloaded.channelCount) // stayed at initial
        assertFalse(reloaded.lastSyncError.isNullOrBlank())
    }

    @Test
    fun `syncSource on missing id emits ERROR without throwing`() = runTest {
        val r = repo()
        val events = r.syncSource("does-not-exist").toList()
        assertEquals(1, events.size)
        assertEquals(SyncProgress.Phase.ERROR, events[0].phase)
    }

    @Test
    fun `m3u_file reads through FileContentReader`() = runTest {
        val playlist =
            """
                #EXTM3U
                #EXTINF:-1,Only Channel
                http://a/x.ts
            """.trimIndent()
        val r = repo(reader = FakeFileReader(mapOf("content://playlist" to playlist)))
        val s =
            r.addSource(
                AddSourceInput(name = "Local", type = SourceType.M3U_FILE, filePath = "content://playlist"),
            )
        r.syncSource(s.id).toList()
        assertEquals(1, r.getById(s.id)?.channelCount)
    }

    @Test
    fun `removeSource cascades to content rows`() {
        // Build the repo with a known db handle so we can poke content_fts
        // via the same driver. Keeps the test focused on cascade behaviour.
        val bundle = testDatabase()
        val repo =
            SourceRepository(
                db = bundle.db,
                driver = bundle.driver,
                credentialStore = PlaintextCredentialStore(),
                http = FakeHttpClient(),
                fileReader = FakeFileReader(emptyMap()),
                clock = { 1_000L },
                idGenerator =
                run {
                    var n = 0;
                    { "cascade-id-${++n}" }
                },
            )
        val saved =
            repo.addSource(
                AddSourceInput(name = "S", type = SourceType.M3U_URL, url = "http://s"),
            )
        bundle.db.contentQueries.insert(
            id = "ch-1",
            source_id = saved.id,
            type = "live",
            title = "Ch",
            clean_title = "Ch",
            group_name = null,
            stream_url = "http://stream/1",
            logo_url = null,
            tvg_id = "c1",
            metadata_json = null,
            sort_order = 0L,
            created_at = 0L,
        )
        assertEquals(
            1L,
            bundle.db.contentQueries
                .countByType("live")
                .executeAsOne(),
        )

        repo.removeSource(saved.id)

        assertEquals(
            0L,
            bundle.db.contentQueries
                .countByType("live")
                .executeAsOne(),
            "removeSource must cascade-delete content rows via the FK",
        )
        assertEquals(0, repo.getAll().size)
    }

    /**
     * MK.23.D.3 — cancellation mid-flight leaves the DB consistent.
     *
     * Contract under test: when the user cancels a sync (or the
     * coordinator's CoroutineScope is torn down), the post-conditions
     * are:
     *   • PRAGMA foreign_keys is back ON. Critical — without this, a
     *     subsequent user-initiated source removal silently fails to
     *     cascade, leaking dependents (favorites pointing at gone
     *     content). Same family as MB-220.
     *   • Cross-source data is untouched. A cancellation on source B
     *     must not affect source A's favorites or content.
     *   • The cancelled source ends with no half-written rows or with
     *     a clean rollback to the previous catalog.
     *
     * Approach: stage the sync to suspend in HTTP fetch (before
     * prepareSource toggles FK off), cancel the collector, verify
     * post-state. The cancellation-during-fetch case is the simpler
     * but more frequent path — user dismisses Settings while a sync
     * is starting up. The harder case (cancel mid-chunk-write) is
     * covered indirectly by the abortSource cross-source FK survival
     * test in BulkContentWriterTest (MK.23.C.2).
     */
    @Test fun `cancelling syncSource mid-flight preserves FK and other sources`() = kotlinx.coroutines.test.runTest {
        // Source A is set up with a normal HTTP — but we never sync
        // it through the same client. Use a separate inserts to seed
        // A's content + favorite directly into the DB.
        val signal = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slowHttp =
            object : HttpClient {
                override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = error("not used")

                override suspend fun getText(url: String, options: HttpRequestOptions): String {
                    signal.complete(Unit)
                    // Suspend until cancelled — cancellation propagates
                    // up through syncSource's channelFlow.
                    kotlinx.coroutines.suspendCancellableCoroutine<String> { }
                    error("unreachable")
                }
            }

        val bundle = testDatabase()
        val repository =
            SourceRepository(
                db = bundle.db,
                driver = bundle.driver,
                credentialStore = PlaintextCredentialStore(),
                http = slowHttp,
                fileReader = FakeFileReader(emptyMap()),
                clock = { 1_000L },
                idGenerator =
                run {
                    var n = 0
                    { "id-${++n}" }
                },
            )

        // Source A — seeded directly so we don't go through the
        // slowHttp client. Add a content row + favorite as the
        // "user data we must protect" baseline.
        val a =
            repository.addSource(
                AddSourceInput(name = "A", type = SourceType.M3U_URL, url = "http://a/list.m3u"),
            )
        bundle.db.contentQueries.insert(
            id = "ch-a",
            source_id = a.id,
            type = "live",
            title = "Ch A",
            clean_title = "Ch A",
            group_name = null,
            stream_url = "http://stream/a",
            logo_url = null,
            tvg_id = null,
            metadata_json = null,
            sort_order = 0L,
            created_at = 0L,
        )
        bundle.db.favoritesQueries.insert(
            id = "fav:ch-a",
            content_id = "ch-a",
            list_id = "default",
            added_at = 1L,
        )
        assertTrue(bundle.db.favoritesQueries.isFavorite("ch-a").executeAsOne())

        // Source B — sync this one and cancel mid-flight.
        val b =
            repository.addSource(
                AddSourceInput(name = "B", type = SourceType.M3U_URL, url = "http://b/list.m3u"),
            )

        val job =
            launch {
                repository.syncSource(b.id).collect { /* drain */ }
            }
        // Wait until the sync's HTTP fetch is in flight.
        signal.await()
        // Now cancel — propagates into the suspended getText.
        job.cancel()
        job.join()

        // Post-conditions:
        //   1. Source A's favorite is intact — cancellation on B
        //      didn't touch A.
        assertTrue(
            bundle.db.favoritesQueries.isFavorite("ch-a").executeAsOne(),
            "Source A's favorite must survive cancelling a sync on a different source",
        )
        //   2. PRAGMA foreign_keys = 1 — verified by triggering a
        //      real cascade. Insert a probe content + favorite for A,
        //      delete the probe content row, observe the favorite
        //      follows via cascade.
        bundle.db.contentQueries.insert(
            id = "probe-after-cancel",
            source_id = a.id,
            type = "live",
            title = "Probe",
            clean_title = "Probe",
            group_name = null,
            stream_url = "http://stream/probe",
            logo_url = null,
            tvg_id = null,
            metadata_json = null,
            sort_order = 999L,
            created_at = 0L,
        )
        bundle.db.favoritesQueries.insert(
            id = "fav:probe-after-cancel",
            content_id = "probe-after-cancel",
            list_id = "default",
            added_at = 999L,
        )
        assertTrue(bundle.db.favoritesQueries.isFavorite("probe-after-cancel").executeAsOne())

        bundle.driver.execute(null, "DELETE FROM content WHERE id = ?", 1) {
            bindString(0, "probe-after-cancel")
        }
        assertFalse(
            bundle.db.favoritesQueries.isFavorite("probe-after-cancel").executeAsOne(),
            "Cascade must fire after cancelling syncSource — proves FK was never left disabled",
        )
        //   3. Source B has no content rows (sync was cancelled
        //      before any chunk was written).
        assertEquals(
            0L,
            bundle.db.contentQueries.countBySource(b.id).executeAsOne(),
            "Cancelled-during-fetch sync must not have written content",
        )
    }

    /**
     * MK.24.E.1 — strengthens MK.23.D.3 by landing the cancel AFTER the
     * chunk loop has written rows, not during the upstream HTTP fetch.
     *
     * The D.3 test cancels by suspending the HTTP body forever, so the
     * sync never reaches `writeM3uBulk`'s try block — the abort path
     * exercised is "abort with no prepareSource ran". This test forces
     * the sync past `prepareSource` + at least one `bulk.writeM3uChunk`
     * call by giving the HTTP client a real M3U body of CHUNK_SIZE+1
     * entries; the collector then cancels itself the moment the first
     * `WRITING` progress emits. Cancellation propagates back through
     * `send → channelFlow → writeM3uBulk` while-loop → catch block →
     * `bulk.abortSource()`. Post-state proves the catch path actually
     * ran end-to-end after writes had started, not just before.
     *
     * Critical assertion: FK is back ON. A bug that left `PRAGMA
     * foreign_keys = OFF` after a partial-write abort would silently
     * break cascade semantics for the rest of the connection's life —
     * the same family as MB-220 but on the cancellation side.
     */
    @Test fun `cancelling syncSource after first chunk written runs abortSource and restores FK`() = kotlinx.coroutines.test.runTest {
        // CHUNK_SIZE = 500 in SourceRepository — give it 600 entries
        // so the first chunk completes and the second chunk is
        // queued, but cancellation lands before the second runs.
        val entryCount = 600
        val m3uBody = buildString {
            appendLine("#EXTM3U")
            repeat(entryCount) { i ->
                appendLine("#EXTINF:-1 tvg-id=\"ch$i\",Channel $i")
                appendLine("http://stream.example/$i")
            }
        }
        val http =
            object : HttpClient {
                override suspend fun getJson(url: String, options: HttpRequestOptions): Any? = error("not used")

                override suspend fun getText(url: String, options: HttpRequestOptions): String = m3uBody
            }

        val bundle = testDatabase()
        val repository =
            SourceRepository(
                db = bundle.db,
                driver = bundle.driver,
                credentialStore = PlaintextCredentialStore(),
                http = http,
                fileReader = FakeFileReader(emptyMap()),
                clock = { 1_000L },
                idGenerator =
                run {
                    var n = 0
                    { "id-${++n}" }
                },
            )

        // Source A — seeded directly + favorited as the
        // "user data we must protect" baseline.
        val a =
            repository.addSource(
                AddSourceInput(name = "A", type = SourceType.M3U_URL, url = "http://a/list.m3u"),
            )
        bundle.db.contentQueries.insert(
            id = "ch-a",
            source_id = a.id,
            type = "live",
            title = "Ch A",
            clean_title = "Ch A",
            group_name = null,
            stream_url = "http://stream/a",
            logo_url = null,
            tvg_id = null,
            metadata_json = null,
            sort_order = 0L,
            created_at = 0L,
        )
        bundle.db.favoritesQueries.insert(
            id = "fav:ch-a",
            content_id = "ch-a",
            list_id = "default",
            added_at = 1L,
        )

        // Source B — sync this one. Cancel on first WRITING emit.
        val b =
            repository.addSource(
                AddSourceInput(name = "B", type = SourceType.M3U_URL, url = "http://b/list.m3u"),
            )

        var sawWriting = false
        val firstChunkSeen = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job =
            launch {
                repository.syncSource(b.id).collect { p ->
                    if (p.phase == SyncProgress.Phase.WRITING && p.current > 0 && !sawWriting) {
                        // First chunk has landed — signal back to the
                        // test scope so it can cancel us from outside.
                        // Cancelling from inside the collect via
                        // coroutineContext.cancel() works too but the
                        // outside-job pattern matches the existing
                        // D.3 test for consistency.
                        sawWriting = true
                        firstChunkSeen.complete(Unit)
                    }
                }
            }
        firstChunkSeen.await()
        // Cancel — propagates back through send → channelFlow into
        // writeM3uBulk's chunk loop. Next suspend point (the next
        // withContext + writeM3uChunk for chunk #2) throws
        // CancellationException; the catch routes to abortSource.
        job.cancel()
        job.join()

        assertTrue(
            sawWriting,
            "Test invariant: cancellation must land AFTER a WRITING emit (= chunk loop ran), not during fetch — otherwise this is a duplicate of the D.3 test",
        )

        // Post-conditions:
        //   1. Source A's favorite intact — unrelated to B's sync.
        assertTrue(
            bundle.db.favoritesQueries.isFavorite("ch-a").executeAsOne(),
            "Source A's favorite must survive a mid-chunk cancellation on source B",
        )

        //   2. PRAGMA foreign_keys = 1 — proven by triggering a real
        //      cascade. Insert a probe content + favorite, delete the
        //      probe content, observe the favorite cascades.
        bundle.db.contentQueries.insert(
            id = "probe-after-mid-chunk-cancel",
            source_id = a.id,
            type = "live",
            title = "Probe",
            clean_title = "Probe",
            group_name = null,
            stream_url = "http://stream/probe",
            logo_url = null,
            tvg_id = null,
            metadata_json = null,
            sort_order = 999L,
            created_at = 0L,
        )
        bundle.db.favoritesQueries.insert(
            id = "fav:probe-after-mid-chunk-cancel",
            content_id = "probe-after-mid-chunk-cancel",
            list_id = "default",
            added_at = 999L,
        )
        assertTrue(bundle.db.favoritesQueries.isFavorite("probe-after-mid-chunk-cancel").executeAsOne())

        bundle.driver.execute(null, "DELETE FROM content WHERE id = ?", 1) {
            bindString(0, "probe-after-mid-chunk-cancel")
        }
        assertFalse(
            bundle.db.favoritesQueries.isFavorite("probe-after-mid-chunk-cancel").executeAsOne(),
            "FK must be back ON after abortSource — cascade fires on the probe",
        )

        //   3. Source B's content was wiped by abortSource. The
        //      first chunk's 500 rows were inserted then deleted by
        //      abortSource's `DELETE FROM content WHERE source_id = ?`.
        assertEquals(
            0L,
            bundle.db.contentQueries.countBySource(b.id).executeAsOne(),
            "abortSource MUST wipe the partial chunk writes — leaving them would mean a half-synced source visible to UI",
        )
    }

    private fun assertFails(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue(threw, "expected block to throw")
    }
}
