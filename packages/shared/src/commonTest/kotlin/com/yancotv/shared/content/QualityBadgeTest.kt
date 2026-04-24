package com.yancotv.shared.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityBadgeTest {
    @Test
    fun `extracts single tier`() {
        assertEquals(listOf(QualityBadge.UHD_4K), QualityBadge.parse("BBC News 4K"))
        assertEquals(listOf(QualityBadge.FHD), QualityBadge.parse("Sky Sports FHD"))
        assertEquals(listOf(QualityBadge.HD), QualityBadge.parse("CNN HD"))
    }

    @Test
    fun `normalizes UHD and ULTRA HD to 4K`() {
        assertEquals(listOf(QualityBadge.UHD_4K), QualityBadge.parse("Discovery UHD"))
        assertEquals(listOf(QualityBadge.UHD_4K), QualityBadge.parse("Discovery ULTRA HD"))
    }

    @Test
    fun `normalizes FULL HD to FHD`() {
        assertEquals(listOf(QualityBadge.FHD), QualityBadge.parse("Channel FULL HD"))
    }

    @Test
    fun `codec variants map to HEVC`() {
        assertEquals(listOf(QualityBadge.HEVC), QualityBadge.parse("Movie HEVC"))
        assertEquals(listOf(QualityBadge.HEVC), QualityBadge.parse("Movie H265"))
        assertEquals(listOf(QualityBadge.HEVC), QualityBadge.parse("Movie H.265"))
    }

    @Test
    fun `multiple tiers in order`() {
        assertEquals(
            listOf(QualityBadge.FHD, QualityBadge.HEVC),
            QualityBadge.parse("Title FHD HEVC"),
        )
    }

    @Test
    fun `deduplicates repeated tokens`() {
        assertEquals(
            listOf(QualityBadge.HD),
            QualityBadge.parse("Foo HD [HD]"),
        )
    }

    @Test
    fun `HDR10 maps to HDR`() {
        assertEquals(listOf(QualityBadge.HDR), QualityBadge.parse("Show HDR10"))
        assertEquals(listOf(QualityBadge.HDR), QualityBadge.parse("Show HDR"))
    }

    @Test
    fun `blank or unmatched returns empty`() {
        assertTrue(QualityBadge.parse("").isEmpty())
        assertTrue(QualityBadge.parse("Just a Title").isEmpty())
    }

    @Test
    fun `longer tokens win over shorter overlaps`() {
        // "ULTRA HD" must match before the trailing "HD" can be picked up alone.
        assertEquals(listOf(QualityBadge.UHD_4K), QualityBadge.parse("Channel ULTRA HD"))
    }
}
