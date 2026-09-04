package com.yancotv.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MB-414 — a public declaration that nothing calls.
 *
 * ### The pattern this exists to break
 *
 * "Built and never connected" has now happened on all three platforms in this
 * project, and none of the usual gates catch it. A composable compiles, passes
 * lint, can even have its own unit test, and is called by nobody — the compiler
 * has no opinion about an uncalled public function, so it survives review
 * indefinitely and reads as a shipped feature to anyone browsing the tree.
 *
 * Confirmed instances:
 *  - MK.37.H.1  `SectionOverflowSheet` written and never rendered. `showOverflow`
 *               had been set since MK.37.B and nothing read it, so pressing More
 *               ran the indicator out and back and opened nothing.
 *  - MB-411     the desktop's timeshift service: a service, three IPC handlers,
 *               a push event and a preload namespace, with zero callers.
 *  - MB-407     the desktop's `Titlebar.tsx`, imported by nothing.
 *  - iOS        a commit titled, in as many words, "four features that were
 *               built and never connected".
 *
 * ### Why a source scan rather than a smarter tool
 *
 * The property is "no other file mentions this name", which is visible in the
 * text. Anything cleverer — a compiler plugin, a call-graph pass — costs more
 * than the bug does, and this catches the shape on the commit that introduces
 * it rather than months later.
 *
 * ### Deliberate exemptions
 *
 * Real reasons only; a name added here should be a decision, not a way to make
 * the test quiet. Each entry says why.
 */
class UnreferencedComposableTest {

    private val exempt = mapOf(
        "FocusTrap" to
            "A documented focus primitive. The native-android-mk checklist tells " +
            "authors to use it for wrapper-swallows-onClick cases, so it is part " +
            "of the guidance rather than dead code — deleting it would contradict " +
            "the rule that recommends it.",
        "SettingsNotificationsTab" to
            "Removed from the settings sidebar 2026-04-27 as a placeholder body. " +
            "The SettingsTab enum's comment records that the file stays in tree so " +
            "post-v1 work can wire it back in one line.",
        "SettingsStorageTab" to "Same as SettingsNotificationsTab.",
        "SettingsSubtitlesTab" to "Same as SettingsNotificationsTab.",
    )

    private fun sourceRoot(): File {
        // Tests run with the module directory as CWD.
        val direct = File("src/main/java")
        return if (direct.isDirectory) direct else File("app/src/main/java")
    }

    @Test
    fun `every public composable is referenced somewhere`() {
        val root = sourceRoot()
        assertTrue(
            "Kotlin source root not found from ${File(".").absolutePath} — this " +
                "test would otherwise scan nothing and pass vacuously",
            root.isDirectory,
        )

        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("expected to find Kotlin sources, found ${files.size}", files.size > 50)

        val sources = files.associateWith { it.readText() }

        // `@Composable` on its own line followed by `fun Name(`, which is how
        // every composable in this codebase is written.
        val declPattern = Regex("""@Composable\s*(?:@[A-Za-z]+(?:\([^)]*\))?\s*)*fun\s+([A-Z]\w*)\s*\(""")

        val declarations = mutableMapOf<String, MutableList<File>>()
        for ((file, text) in sources) {
            for (m in declPattern.findAll(text)) {
                declarations.getOrPut(m.groupValues[1]) { mutableListOf() }.add(file)
            }
        }

        val orphans = mutableListOf<String>()
        for ((name, declaredIn) in declarations) {
            // A name declared in more than one file is an overload or a private
            // helper repeated per screen; the text scan cannot tell those apart,
            // so it does not try.
            if (declaredIn.size != 1) continue
            if (name in exempt) continue

            val word = Regex("""\b${Regex.escape(name)}\b""")
            var uses = 0
            for ((file, text) in sources) {
                var hits = word.findAll(text).count()
                if (file == declaredIn[0]) hits -= 1 // its own declaration
                uses += hits
            }
            if (uses <= 0) {
                orphans += "$name (${declaredIn[0].path})"
            }
        }

        assertTrue(
            "Composables declared and referenced nowhere. Either wire them up, " +
                "delete them, or add them to `exempt` WITH a reason:\n  " +
                orphans.joinToString("\n  "),
            orphans.isEmpty(),
        )
    }

    @Test
    fun `every exemption still names something that exists`() {
        // An exemption for a deleted composable is a comment pretending to be a
        // rule. This keeps the list honest as the tree changes.
        val text = sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        val stale = exempt.keys.filterNot { name ->
            Regex("""fun\s+${Regex.escape(name)}\s*\(""").containsMatchIn(text)
        }
        assertTrue(
            "these names are exempted but no longer declared anywhere — remove " +
                "them from `exempt`: $stale",
            stale.isEmpty(),
        )
    }
}
