package app.granger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan L2: every string and plural resolves under Spanish with sample args, so a format string the Spanish broke
 * (a %d given a word, a stray %) fails here rather than on the phone. The names come from the English files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "es")
class SpanishResolveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun theAppIsInSpanish() {
        assertThat(context.getString(R.string.common_cancel)).isEqualTo("Cancelar")
        assertThat(context.resources.getQuantityString(R.plurals.common_books, 1, 1)).isEqualTo("1 libro")
        assertThat(context.resources.getQuantityString(R.plurals.common_books, 3, 3)).isEqualTo("3 libros")
    }

    @Test fun everyStringResolvesWithSampleArgs() {
        val res = context.resources
        val problems = mutableListOf<String>()
        var resolved = 0
        SpanishStringsTest.englishFiles().forEach { file ->
            SpanishStringsTest.parse(file).values.forEach { entry ->
                val type = if (entry.plural) "plurals" else "string"
                val id = res.getIdentifier(entry.name, type, context.packageName)
                if (id == 0) {
                    problems += "${entry.name}: no $type resource"
                    return@forEach
                }
                val args = sampleArgs(entry.placeholders())
                try {
                    val texts = if (entry.plural) {
                        listOf(1, 2, 1_000_000).map { res.getQuantityString(id, it, *args) }
                    } else {
                        listOf(if (args.isEmpty()) res.getString(id) else res.getString(id, *args))
                    }
                    texts.filter { it.isBlank() }.forEach { _ -> problems += "${entry.name}: blank" }
                    resolved++
                } catch (e: Exception) {
                    problems += "${entry.name}: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        assertWithMessage(problems.joinToString("\n")).that(problems).isEmpty()
        assertThat(resolved).isGreaterThan(800)
    }

    /** One arg per placeholder, of the type its conversion wants: 7 for %d, 1.5 for %f, a word for %s. */
    private fun sampleArgs(placeholders: Set<String>): Array<Any> {
        fun sample(spec: String): Any = when (spec.last()) {
            'd' -> 7
            'f' -> 1.5
            else -> "x"
        }
        val indexed = placeholders.mapNotNull { p -> Regex("""^%(\d+)\$""").find(p)?.let { it.groupValues[1].toInt() to p } }.toMap()
        if (indexed.isNotEmpty()) return Array((indexed.keys.max())) { i -> indexed[i + 1]?.let(::sample) ?: "x" }
        return placeholders.map(::sample).toTypedArray()
    }
}
