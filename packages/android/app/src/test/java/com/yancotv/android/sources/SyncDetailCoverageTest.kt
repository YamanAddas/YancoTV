package com.yancotv.android.sources

import com.yancotv.shared.sources.SyncDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MK.31.18 — guards the [SyncDetail] → resource mapping.
 *
 * `syncDetailText` itself needs a Context or a composition, so it is not
 * unit-testable here. What IS testable, and what actually breaks in practice, is
 * the failure mode of a sealed-type mapper: someone adds a `SyncDetail` case in
 * `packages/shared` and forgets the Android arm.
 *
 * Kotlin's exhaustiveness check catches that at compile time for the `when`
 * expressions in [SyncDetailText] — which is the real guard, and better than a
 * test. These tests cover what the compiler cannot: that the sealed hierarchy
 * still has the shape the mapper and the sync banner assume.
 */
class SyncDetailCoverageTest {
    /** Every case the mapper handles, kept in step with SyncDetailText by hand. */
    private val mapped: List<SyncDetail> =
        listOf(
            SyncDetail.Starting,
            SyncDetail.Connecting,
            SyncDetail.Authenticating,
            SyncDetail.FetchingCategories,
            SyncDetail.FetchingCatalog,
            SyncDetail.Finalizing,
            SyncDetail.WritingLive(1),
            SyncDetail.WritingMovies(2),
            SyncDetail.WritingSeries(3),
            SyncDetail.SourceNotFound("src-1"),
            SyncDetail.Failure("boom"),
        )

    @Test
    fun `every sealed subclass is represented in the mapped list`() {
        // If someone adds a case in packages/shared, this fails and points at the
        // Android mapper — the compiler already refuses to build SyncDetailText,
        // but this names the omission in a readable way.
        val declared = SyncDetail::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        val covered = mapped.map { it::class.simpleName }.toSet()
        assertEquals(
            declared,
            covered,
            "SyncDetail gained or lost a case — update SyncDetailText (both the @Composable " +
                "and the Context overload) and this list",
        )
    }

    @Test
    fun `the write cases carry their row count`() {
        // The banner formats these with a %1$d, so a case that dropped its count
        // would render "Live %1$d" literally.
        assertEquals(1, (mapped.filterIsInstance<SyncDetail.WritingLive>().single()).written)
        assertEquals(2, (mapped.filterIsInstance<SyncDetail.WritingMovies>().single()).written)
        assertEquals(3, (mapped.filterIsInstance<SyncDetail.WritingSeries>().single()).written)
    }

    @Test
    fun `Failure is the only case carrying free text`() {
        // The design rule from the SyncDetail doc: everything except Failure is a
        // closed set the code chooses, so everything except Failure can be a
        // resource. If a second String-carrying case appears, that rule needs
        // re-examining rather than silently extending.
        val freeText =
            mapped.filter { d ->
                d::class.members.any { m -> m.name == "text" }
            }
        assertEquals(
            listOf(SyncDetail.Failure("boom")),
            freeText,
            "a new SyncDetail case carries free text — confirm it genuinely cannot be a resource",
        )
    }

    @Test
    fun `SourceNotFound keeps the id for the message`() {
        val notFound = mapped.filterIsInstance<SyncDetail.SourceNotFound>().single()
        assertTrue(notFound.id.isNotBlank())
    }
}
