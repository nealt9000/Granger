package app.granger.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The A0 spike's transcripts (`spoken-titles/cases.tsv`: Neal's 60 replayed phrases and 25 live takes, text only)
 * against the book and pack names on his Pixel 6 (`names.txt`, public titles and authors only).
 */
class SpokenTitleMatcherTest {
    private data class Case(val id: String, val heard: String, val expect: String)

    private val names = resource("names.txt").lines().filter { it.isNotBlank() }
    private val cases = resource("cases.tsv").lines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
        val (id, heard, expect) = it.split('\t')
        Case(id, heard, expect)
    }

    private fun resource(name: String) = javaClass.getResource("/spoken-titles/$name")!!.readText()

    @Test
    fun everySpokenNameFindsItsTitle() {
        val misses = cases.filter { it.expect !in setOf("-", "*") }.mapNotNull { c ->
            val got = SpokenTitleMatcher.best(c.heard, names)
            val ok = when {
                got == null -> false
                c.expect.startsWith("~") -> got.name.contains(c.expect.drop(1), ignoreCase = true)
                else -> got.name in c.expect.split('|')
            }
            if (ok) null else "${c.id} '${c.heard}' → ${got?.name} (${got?.score}); wanted ${c.expect}"
        }
        assertThat(misses).isEmpty()
    }

    /** A question may name a real title (*Who wrote The Tale of Genji*); it must never be given one it doesn't name. */
    @Test
    fun noQuestionIsGivenATitleItDoesNotName() {
        val hits = cases.filter { it.expect == "-" }.mapNotNull { c ->
            SpokenTitleMatcher.best(c.heard, names)?.takeUnless { named(c.heard, it.name) }?.let { "${c.id} '${c.heard}' → ${it.name} (${it.score})" }
        }
        assertThat(hits).isEmpty()
    }

    private fun named(heard: String, name: String): Boolean {
        val words = SpokenTitleMatcher.normalize(heard).split(' ').toSet()
        return SpokenTitleMatcher.normalize(name).split(' ').filter { it !in setOf("the", "a", "of", "by") }.all { it in words }
    }

    @Test
    fun theTaskFiveMissesAreFixed() {
        assertThat(SpokenTitleMatcher.best("Open the Bible", names)!!.name).endsWith("Bible")
        assertThat(SpokenTitleMatcher.best("Open Wikipedia", names)!!.name).contains("Wikipedia")
        assertThat(SpokenTitleMatcher.best("Open Credo", names)!!.name).isEqualTo("Crito")
    }

    @Test
    fun accentsAndSpanishVerbsAreHandled() {
        val spanish = listOf("Cien años de soledad", "Don Quijote de la Mancha", "La Celestina")
        assertThat(SpokenTitleMatcher.best("abre cien anos de soledad", spanish)!!.name).isEqualTo("Cien años de soledad")
        assertThat(SpokenTitleMatcher.best("Lee el Quijote", spanish)!!.name).isEqualTo("Don Quijote de la Mancha")
        assertThat(SpokenTitleMatcher.normalize("Bhagavad Gītā: Song!")).isEqualTo("bhagavad gita song")
    }

    @Test
    fun emptyAndEdgeCases() {
        assertThat(SpokenTitleMatcher.best("   ", names)).isNull()
        assertThat(SpokenTitleMatcher.best("Open", emptyList())).isNull()
        assertThat(SpokenTitleMatcher.distance("kitten", "sitting")).isEqualTo(3)
        assertThat(SpokenTitleMatcher.best("Peter Pan", names)!!.score).isEqualTo(1.0)
    }
}
