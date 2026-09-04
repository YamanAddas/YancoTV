package com.yancotv.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MB-416 — a component that renders mirrored and decides physical.
 *
 * ### The shape
 *
 * Compose mirrors the picture under RTL for free: `Row`, `Alignment.*Start`,
 * `Modifier.offset`, `fillMaxWidth(fraction)` in a default-aligned `Box`. It
 * mirrors nothing about input — `position.x` is a distance from the physical
 * left edge and `KEYCODE_DPAD_LEFT` is the physical left key, in every locale.
 *
 * So a component can get a mirrored render and unmirrored decisions and
 * disagree with itself **only in Arabic**, which is exactly where nobody looks.
 * It has happened twice, a milestone apart:
 *
 *  - MK.31.2  `SettingsSlider` — fill mirrored, keys physical. "Left" raised
 *             the value while the fill shrank rightward.
 *  - MB-416   `SectionFlowBar` — Row and indicator mirrored, tap divided a raw
 *             `position.x`. Every tab activated its mirror: المزيد at the left
 *             end opened Home, الرئيسية at the right opened the More sheet.
 *
 * Neither was caught by the compiler, by lint, by a unit test or by a device
 * pass in English. Both needed someone to hold an Arabic phone.
 *
 * ### What this asserts
 *
 * A file that makes a horizontal decision from a raw pointer x, or from a
 * physical arrow key, must **say something about layout direction** — pin
 * itself LTR, read `LocalLayoutDirection`, or use the app's logical key
 * helpers. Mentioning direction is not proof of correctness; it is proof that
 * someone thought about it, which is the part that was missing both times.
 *
 * Deliberately a source scan: the property is "did the author consider this",
 * which lives in the text. Anything cleverer costs more than the bug does.
 */
class LayoutDirectionInputTest {

    /**
     * Files that make a physical horizontal decision **on purpose**. A name here
     * is a decision, not a way to make the test quiet.
     */
    private val exempt = mapOf(
        "PlayerActivity.kt" to
            "The seek keys are deliberately physical. MK.31.2 settled it and " +
            "VodDockProgressRow repeats it: a media timeline does not mirror — " +
            "platform playback UI and every mainstream video app keep the " +
            "scrubber left-to-right, and LEFT = rewind is muscle memory " +
            "independent of reading direction. Mirroring these would make " +
            "Arabic viewers seek backwards when they meant forwards.",
    )

    private val pointerX = Regex(
        """(position\.x|offset\.x|centroid\.x)\s*[/*+\-]""",
    )
    private val rawArrow = Regex("""KEYCODE_DPAD_(LEFT|RIGHT)|Key\.Direction(Left|Right)""")
    private val directionAware = Regex(
        """LocalLayoutDirection|LayoutDirection\.(Rtl|Ltr)|startwardKey|endwardKey|""" +
            """absoluteOffset|flowBarSlotAt|flowBarDragTarget""",
    )

    private fun sourceRoot(): File {
        val direct = File("src/main/java")
        return if (direct.isDirectory) direct else File("app/src/main/java")
    }

    private fun sourceLines(text: String): List<String> {
        // Comments do not decide anything, and this file's own KDoc names the
        // very symbols it scans for — without this the test would flag itself.
        return text.split("\n").map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") }
    }

    @Test
    fun `a physical horizontal decision comes with a direction decision`() {
        val root = sourceRoot()
        assertTrue(
            "Kotlin source root not found from ${File(".").absolutePath} — this " +
                "test would otherwise scan nothing and pass vacuously",
            root.isDirectory,
        )
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find Kotlin sources, found ${files.size}", files.size > 50)

        val offenders = mutableListOf<String>()
        for (file in files) {
            if (file.name in exempt) continue
            val text = file.readText()
            if (directionAware.containsMatchIn(text)) continue
            val body = sourceLines(text)
            val reasons = buildList {
                if (body.any { pointerX.containsMatchIn(it) }) add("pointer x")
                if (body.any { rawArrow.containsMatchIn(it) }) add("physical arrow key")
            }
            if (reasons.isNotEmpty()) {
                offenders += "${file.name} (${reasons.joinToString(" + ")})"
            }
        }

        assertTrue(
            "These decide horizontally from a physical input and never mention " +
                "layout direction. Under RTL the render mirrors and the decision " +
                "does not, so they disagree with themselves in Arabic only. Pin " +
                "the component LTR, make the input logical, or add it to " +
                "`exempt` WITH a reason:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `every exemption still names a file that exists and still needs it`() {
        // An exemption for a file that no longer makes a physical decision is a
        // comment pretending to be a rule.
        val files = sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val stale = exempt.keys.filterNot { name ->
            val file = files.firstOrNull { it.name == name } ?: return@filterNot false
            val body = sourceLines(file.readText())
            body.any { pointerX.containsMatchIn(it) } || body.any { rawArrow.containsMatchIn(it) }
        }
        assertTrue(
            "these files are exempted but no longer make a physical horizontal " +
                "decision — remove them from `exempt`: $stale",
            stale.isEmpty(),
        )
    }
}
