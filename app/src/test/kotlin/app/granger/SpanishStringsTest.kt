package app.granger

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Plan L2: every English strings file has a values-es twin that says the same things. A missing or extra name, a
 * placeholder that's lost or invented, or a plural without `one` and `other` fails here rather than on the phone.
 */
class SpanishStringsTest {
    @Test fun everyStringsFileHasASpanishTwin() {
        val missing = englishFiles().filterNot { spanishTwin(it).isFile }.map { it.relativeTo(repoRoot()).path }
        assertWithMessage("English strings files with no values-es twin").that(missing).isEmpty()
    }

    @Test fun theSpanishHasEveryNameAndNoOthers() {
        val problems = englishFiles().filter { spanishTwin(it).isFile }.flatMap { en ->
            val english = parse(en)
            val spanish = parse(spanishTwin(en))
            val path = en.relativeTo(repoRoot()).path
            (english.keys - spanish.keys).map { "$path: $it has no Spanish" } +
                (spanish.keys - english.keys).map { "$path: $it is in Spanish but not in English" }
        }
        assertWithMessage(problems.joinToString("\n")).that(problems).isEmpty()
    }

    @Test fun placeholdersMatch() {
        val problems = twins().mapNotNull { (path, en, es) ->
            val want = en.placeholders()
            val got = es.placeholders()
            if (want == got) null else "$path: ${en.name} has $got in Spanish, $want in English"
        }
        assertWithMessage(problems.joinToString("\n")).that(problems).isEmpty()
    }

    @Test fun pluralsGiveOneAndOther() {
        val problems = twins().filter { (_, en, _) -> en.plural }.mapNotNull { (path, en, es) ->
            val lacking = listOf("one", "other").filterNot { it in es.forms }
            if (lacking.isEmpty()) null else "$path: ${en.name} lacks $lacking"
        }
        assertWithMessage(problems.joinToString("\n")).that(problems).isEmpty()
    }

    @Test fun thePlaceholderPatternReadsAndroidFormats() {
        assertThat(placeholdersIn("%1\$s of %2\$d · %3\$.1f×")).containsExactly("%1\$s", "%2\$d", "%3\$.1f")
        assertThat(placeholdersIn("%d songs")).containsExactly("%d")
        assertThat(placeholdersIn("%1\$d%% done")).containsExactly("%1\$d")
        assertThat(placeholdersIn("100%% offline")).isEmpty()
    }

    /** A `<string>` or a `<plurals>`: its forms by quantity, or one form keyed "" for a string. */
    data class Entry(val name: String, val plural: Boolean, val forms: Map<String, String>) {
        fun placeholders(): Set<String> = forms.values.flatMap(::placeholdersIn).toSet()
    }

    private data class Twin(val path: String, val en: Entry, val es: Entry)

    private fun twins(): List<Twin> = englishFiles().filter { spanishTwin(it).isFile }.flatMap { en ->
        val spanish = parse(spanishTwin(en))
        parse(en).values.mapNotNull { e -> spanish[e.name]?.let { Twin(en.relativeTo(repoRoot()).path, e, it) } }
    }

    companion object {
        private val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")

        fun placeholdersIn(text: String): List<String> = PLACEHOLDER.findAll(text.replace("%%", "")).map { it.value }.toList()

        fun repoRoot(): File = HardcodedStringsTest.repoRoot()

        /** Every English strings file: the app's strings_<package>.xml and each core module's strings.xml. */
        fun englishFiles(): List<File> {
            val root = repoRoot()
            val app = File(root, "app/src/main/res/values").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().toList()
            val core = File(root, "core").listFiles().orEmpty().map { File(it, "src/main/res/values/strings.xml") }.filter { it.isFile }
            return (app + core).sortedBy { it.path }
        }

        fun spanishTwin(english: File): File = File(english.parentFile.parentFile, "values-es/${english.name}")

        /** The translatable entries of a strings file, by name. */
        fun parse(file: File): Map<String, Entry> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val children = doc.documentElement.childNodes
            return (0 until children.length).map { children.item(it) }.filterIsInstance<Element>()
                .filter { it.getAttribute("translatable") != "false" }
                .mapNotNull { e ->
                    when (e.tagName) {
                        "string" -> Entry(e.getAttribute("name"), false, mapOf("" to e.textContent))
                        "plurals" -> {
                            val items = e.getElementsByTagName("item")
                            Entry(e.getAttribute("name"), true, (0 until items.length).map { items.item(it) as Element }
                                .associate { it.getAttribute("quantity") to it.textContent })
                        }
                        else -> null
                    }
                }
                .associateBy { it.name }
        }
    }
}
