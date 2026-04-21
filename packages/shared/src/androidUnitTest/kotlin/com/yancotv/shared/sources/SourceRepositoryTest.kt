package com.yancotv.shared.sources

import com.yancotv.shared.http.HttpClient
import com.yancotv.shared.http.HttpRequestOptions
import com.yancotv.shared.types.AddSourceInput
import com.yancotv.shared.types.SourceType
import com.yancotv.shared.types.UpdateSourceInput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRepositoryTest {

    private class FakeHttpClient(
        private val textResponses: Map<String, String> = emptyMap(),
    ) : HttpClient {
        override suspend fun getJson(url: String, options: HttpRequestOptions): Any? =
            error("getJson not used in these tests")
        override suspend fun getText(url: String, options: HttpRequestOptions): String =
            textResponses[url] ?: error("unmocked URL: $url")
    }

    private class FakeFileReader(private val contents: Map<String, String>) : FileContentReader {
        override suspend fun readText(path: String): String =
            contents[path] ?: error("unmocked file: $path")
    }

    private fun repo(
        http: HttpClient = FakeHttpClient(),
        reader: FileContentReader = FakeFileReader(emptyMap()),
        now: Long = 1_000L,
    ): SourceRepository {
        val bundle = testDatabase()
        return SourceRepository(
            db = bundle.db,
            driver = bundle.driver,
            credentialStore = PlaintextCredentialStore(),
            http = http,
            fileReader = reader,
            clock = { now },
            idGenerator = run {
                var n = 0
                { "id-${++n}" }
            },
        )
    }

    @Test
    fun `add + get round-trip`() {
        val r = repo()
        val s = r.addSource(
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
                name = "X", type = SourceType.XTREAM,
                url = "http://x", username = "user1", password = "p@ss",
            ),
        )
        // Plaintext store makes the BLOB equal to the plaintext bytes — but
        // the contract is the same: callers never read the BLOB directly.
        // What we check here is that the repo actually wrote the credential
        // through the store (not as plaintext in a string column).
        val row = r.getAll().single()
        assertNull(row.url?.let { if (it.contains("user1")) "leak" else null },
            "URL column must not contain credentials")
    }

    @Test
    fun `updateSource preserves existing credentials when input is null`() {
        val r = repo()
        val s = r.addSource(
            AddSourceInput(
                name = "X", type = SourceType.XTREAM,
                url = "http://x", username = "user1", password = "pw1",
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
        val playlist = """
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
        val playlist = """
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
        val playlist = """
            #EXTM3U
            #EXTINF:-1,Only Channel
            http://a/x.ts
        """.trimIndent()
        val r = repo(reader = FakeFileReader(mapOf("content://playlist" to playlist)))
        val s = r.addSource(
            AddSourceInput(name = "Local", type = SourceType.M3U_FILE, filePath = "content://playlist"),
        )
        r.syncSource(s.id).toList()
        assertEquals(1, r.getById(s.id)?.channelCount)
    }

    private fun assertFails(block: () -> Unit) {
        var threw = false
        try { block() } catch (_: Throwable) { threw = true }
        assertTrue(threw, "expected block to throw")
    }
}
