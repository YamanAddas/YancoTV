package com.yancotv.shared.content

/**
 * Quality tier extracted from a channel/VOD title. Mirrors the desktop
 * `group-parser.ts` `normalizeQuality` mapping so badges rendered on
 * Android match what the Electron app shows for the same title.
 */
enum class QualityBadge(val label: String) {
    UHD_8K("8K"),
    UHD_4K("4K"),
    FHD("FHD"),
    HD("HD"),
    SD("SD"),
    HEVC("HEVC"),
    H264("H.264"),
    HDR("HDR"),
    ;

    companion object {
        // Word-boundary match. The alternation order matters: longer / more
        // specific tokens (ULTRA HD, FULL HD, HDR10) come first so a title
        // like "Channel ULTRA HD" doesn't fall through to plain "HD".
        private val REGEX =
            Regex(
                """\b(8K|4K|UHD|ULTRA\s*HD|FHD|FULL\s*HD|HD|SD|HEVC|H\.?265|H\.?264|HDR10|HDR)\b""",
                RegexOption.IGNORE_CASE,
            )

        /** Extracts unique badges in the order they appear. */
        fun parse(title: String): List<QualityBadge> {
            if (title.isBlank()) return emptyList()
            val seen = LinkedHashSet<QualityBadge>()
            REGEX.findAll(title).forEach { match ->
                normalize(match.value)?.let(seen::add)
            }
            return seen.toList()
        }

        private fun normalize(raw: String): QualityBadge? {
            val u = raw.uppercase().replace(Regex("""\s+"""), "")
            return when {
                u == "8K" -> UHD_8K
                u == "4K" || u == "UHD" || u == "ULTRAHD" -> UHD_4K
                u == "FHD" || u == "FULLHD" -> FHD
                u == "HD" -> HD
                u == "SD" -> SD
                u == "HEVC" || u == "H265" || u == "H.265" -> HEVC
                u == "H264" || u == "H.264" -> H264
                u.startsWith("HDR") -> HDR
                else -> null
            }
        }
    }
}
