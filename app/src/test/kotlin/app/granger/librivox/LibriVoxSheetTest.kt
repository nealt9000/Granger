package app.granger.librivox

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibriVoxSheetTest {
    @Test fun descriptionEntitiesAreDecoded() {
        // LibriVox descriptions are HTML: Task 4 strips the tags but leaves the entities.
        assertThat(decodeEntities("Tom &amp; Jerry &quot;meet&quot; &lt;the&gt; &#39;Time&#39; Traveller&#x2014;at last&nbsp;&hellip;"))
            .isEqualTo("Tom & Jerry \"meet\" <the> 'Time' Traveller—at last …")
        assertThat(decodeEntities("&rsquo;s &ldquo;x&rdquo; &mdash; &ndash; &eacute;t&eacute; &apos;")).isEqualTo("’s “x” — – été '")
        // Decoded once: "&amp;lt;" is the text "&lt;", not "<".
        assertThat(decodeEntities("&amp;lt;")).isEqualTo("&lt;")
        // Unknown names, bare ampersands and bad numbers stay as written.
        assertThat(decodeEntities("AT&T &bogus; &#xZZ; &#99999999; & done")).isEqualTo("AT&T &bogus; &#xZZ; &#99999999; & done")
    }

    @Test fun readersShowTheFirstThreeThenHowManyMore() {
        assertThat(readersLabel(emptyList())).isNull()
        assertThat(readersLabel(listOf("Aaron Elliott"))).isEqualTo("Read by Aaron Elliott")
        assertThat(readersLabel(listOf("A", "B", "C"))).isEqualTo("Read by A, B, C")
        assertThat(readersLabel(listOf("A", "B", "C", "D", "E"))).isEqualTo("Read by A, B, C and 2 more")
    }

    @Test fun durationsReadAsHoursAndMinutes() {
        assertThat(durationLabel(14_969_000L)).isEqualTo("4 h 9 min")
        assertThat(durationLabel(3_600_000L)).isEqualTo("1 h")
        assertThat(durationLabel(59 * 60_000L)).isEqualTo("59 min")
        assertThat(durationLabel(20_000L)).isEqualTo("1 min")
        // A book not yet on archive.org has no total time.
        assertThat(durationLabel(0L)).isNull()
    }
}
