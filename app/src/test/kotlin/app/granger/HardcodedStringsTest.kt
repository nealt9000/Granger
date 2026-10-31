package app.granger

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * No English text written straight into the code (Plan L1 Task 9): UI text comes from string resources, or [UiText]
 * from code without Compose. A line that has to keep a literal says why with `// l10n-ignore: <why>`. Files whose name
 * holds `Spike` or `Debug` are debug-only and skipped.
 */
class HardcodedStringsTest {
    @Test fun noFileHasHardcodedUiText() {
        val hits = scan(repoRoot()).map { (path, n) -> "$path: $n hardcoded strings" }
        assertWithMessage("Use string resources (or UiText) for UI text, or mark the line `// l10n-ignore: <why>`; see Plan L1.\n" + hits.joinToString("\n"))
            .that(hits).isEmpty()
    }

    @Test fun thePatternsCatchUiTextAndLeaveIdsAlone() {
        val hits = listOf(
            """Text("Cancel")""",
            """Text("3 items")""",
            """Text("${'$'}{pack.title} will be deleted")""",
            """Icon(Icons.Filled.Search, contentDescription = "Search")""",
            """AlertDialog(title = "Delete collection?")""",
            """Modifier.clickable(onClickLabel = "Open chapter")""",
            """ConfirmDialog(confirm = "Rename")""",
            """Toast.makeText(context, "Saved", Toast.LENGTH_SHORT)""",
            """snackbar.showSnackbar("Removed")""",
            """.setContentTitle("Downloading")""",
        )
        val misses = listOf(
            """Text("${'$'}it%")""",
            """Text(title)""",
            """Icon(Icons.Filled.Search, contentDescription = null)""",
            """Icon(Icons.Filled.Search, contentDescription = "")""",
            """composable(route = "settings")""",
            """Log.d(TAG, "Opened ${'$'}id")""",
            """val title = "wikipedia_en_all"""",
            """private const val label = "Granger"""",
            """Text("Cancel") // l10n-ignore: a test""",
            """ConfirmDialog(confirm = onConfirm)""",
            """// Text("Cancel")""",
        )
        hits.forEach { assertWithMessage(it).that(hitsIn(it)).isEqualTo(1) }
        misses.forEach { assertWithMessage(it).that(hitsIn(it)).isEqualTo(0) }
    }

    companion object {
        private val patterns = listOf(
            // Text("Cancel"), Text("3 items"), and templates with words in them: Text("${pack.title} will be deleted").
            Regex("""\bText\(\s*"([^"]*)""""),
            Regex("""\bcontentDescription\s*=\s*"[^"]"""),
            // As a named argument, not a declaration: `title = "…"` but not `val title = "…"`.
            Regex("""(?<!\b(val|var)\s)\b(\w*[lL]abel|title|text|message|placeholder|body|supportingText|description|stateDescription|confirm|dismiss)\s*=\s*"[A-Z]"""),
            Regex("""\bToast\.makeText\([^")]*""""),
            Regex("""\bshowSnackbar\(\s*""""),
            Regex("""\bsetContent(Title|Text)\(\s*""""),
        )

        /** A Text literal counts when it starts with a capital or digit, or has a word outside its templates. */
        private fun isUiText(literal: String) = literal.firstOrNull()?.let { it.isUpperCase() || it.isDigit() } == true ||
            literal.replace(Regex("""\$\{[^}"]*}?|\$\w+"""), "").contains(Regex("[A-Za-z]{2,}"))

        fun hitsIn(line: String): Int {
            val code = line.trimStart()
            if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || "// l10n-ignore:" in line) return 0
            return patterns.sumOf { p ->
                p.findAll(line).count { m -> m.groups.size < 2 || p !== patterns[0] || isUiText(m.groupValues[1]) }
            }
        }

        /** Hits per file in the main Kotlin of app and each core module, keyed by path from the repo root, sorted. */
        fun scan(root: File): Map<String, Int> {
            val dirs = listOf(File(root, "app/src/main/kotlin")) +
                File(root, "core").listFiles().orEmpty().map { File(it, "src/main/kotlin") }
            return dirs.filter { it.isDirectory }
                .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" }.toList() }
                .filterNot { "Spike" in it.name || "Debug" in it.name }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readLines().sumOf(::hitsIn) }
                .filterValues { it > 0 }
                .toSortedMap()
        }

        /** Gradle runs unit tests from the module directory; walk up to the directory with settings.gradle.kts. */
        fun repoRoot(): File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
    }
}
