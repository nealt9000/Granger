package app.granger.voiceinput

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TitleMatcherTest {
    private val matcher = TitleMatcher(
        listOf("The Dhammapada", "Crito", "Ready.gov", "Hector Berlioz", "Toned Ear", "Wikivoyage", "Medical Library"),
    )

    @Test
    fun misheardNamesFromTheReplayFindTheirTitles() {
        assertThat(matcher.best("Open the domapada")!!.title).isEqualTo("The Dhammapada")
        assertThat(matcher.best("Open Hector berleos")!!.title).isEqualTo("Hector Berlioz")
        assertThat(matcher.best("Open tone deer")!!.title).isEqualTo("Toned Ear")
        assertThat(matcher.best("Open wiki voyage")!!.title).isEqualTo("Wikivoyage")
        val crito = matcher.best("Open Credo")!!
        assertThat(crito.title).isEqualTo("Crito")
        assertThat(crito.score).isAtLeast(TitleMatcher.THRESHOLD)
    }

    @Test
    fun commandsScoreBelowTheThreshold() {
        assertThat(matcher.best("Next chapter")!!.score).isLessThan(TitleMatcher.THRESHOLD)
        assertThat(matcher.best("Bookmark this page")!!.score).isLessThan(TitleMatcher.THRESHOLD)
    }

    @Test
    fun normalizesAndStripsTheVerb() {
        assertThat(TitleMatcher.stripVerb(TitleMatcher.normalize("Search for: Ready.gov!"))).isEqualTo("ready gov")
        assertThat(TitleMatcher.distance("kitten", "sitting")).isEqualTo(3)
        assertThat(matcher.best("   ")).isNull()
    }
}
